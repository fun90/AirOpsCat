package store

import (
	"context"
	"database/sql"
	"errors"
	"math"
	"math/big"
	"strconv"
	"strings"
	"time"
)

// TrafficUsage 的配额单位为 GB，0 表示不限量；周期为上海时间的左闭右开区间。
type TrafficUsage struct {
	UsedBytes              int64
	QuotaGB                int64
	PeriodStart, PeriodEnd time.Time
}

// TrafficPeriod 是账户一个计费周期的累计流量；QuotaGB 为周期快照，缺省取账户当前配额。
type TrafficPeriod struct {
	PeriodStart, PeriodEnd              time.Time
	UploadBytes, DownloadBytes, QuotaGB int64
}

// AccountTrafficHistory 返回账户已保留的周期（含当前），按开始时间倒序。
func AccountTrafficHistory(ctx context.Context, db *sql.DB, accountID int64) ([]TrafficPeriod, error) {
	rows, err := db.QueryContext(ctx, `SELECT s.period_start,s.period_end,s.upload_bytes,s.download_bytes,coalesce(s.bandwidth_quota,a.bandwidth,0)
 FROM account_traffic_stats s JOIN account a ON a.id=s.account_id WHERE s.account_id=? ORDER BY s.period_start DESC,s.id DESC`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []TrafficPeriod
	for rows.Next() {
		var p TrafficPeriod
		var s, e string
		if err = rows.Scan(&s, &e, &p.UploadBytes, &p.DownloadBytes, &p.QuotaGB); err != nil {
			return nil, err
		}
		if p.PeriodStart, err = ParseTime(s); err != nil {
			return nil, err
		}
		if p.PeriodEnd, err = ParseTime(e); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

func trafficPeriod(anchor, at time.Time, period string) (time.Time, time.Time, error) {
	anchor, at = anchor.In(Shanghai), at.In(Shanghai)
	boundary := func(year int, month time.Month) time.Time {
		first := time.Date(year, month, 1, anchor.Hour(), anchor.Minute(), anchor.Second(), 0, Shanghai)
		day := min(anchor.Day(), time.Date(first.Year(), first.Month()+1, 0, 0, 0, 0, 0, Shanghai).Day())
		return first.AddDate(0, 0, day-1)
	}
	switch period {
	case PeriodMonthly:
		year, month := at.Year(), at.Month()
		if at.Before(boundary(year, month)) {
			month--
		}
		return boundary(year, month), boundary(year, month+1), nil
	case PeriodYearly:
		year := at.Year()
		if at.Before(boundary(year, anchor.Month())) {
			year--
		}
		return boundary(year, anchor.Month()), boundary(year+1, anchor.Month()), nil
	default:
		return time.Time{}, time.Time{}, invalid("周期类型不合法")
	}
}

func accountUsage(ctx context.Context, q dbtx, id int64, at time.Time) (*TrafficUsage, error) {
	var anchor, period string
	var quota sql.NullInt64
	if err := q.QueryRowContext(ctx, `SELECT traffic_anchor, period_type, bandwidth FROM account WHERE id=?`, id).Scan(&anchor, &period, &quota); err != nil {
		return nil, err
	}
	a, err := ParseTime(anchor)
	if err != nil {
		return nil, err
	}
	start, end, err := trafficPeriod(a, at, period)
	if err != nil {
		return nil, err
	}
	u := &TrafficUsage{QuotaGB: quota.Int64, PeriodStart: start, PeriodEnd: end}
	// 导入的现存周期优先，避免续期或迁移重新划分已计费流量。
	var s, e string
	var up, down int64
	var snapshot sql.NullInt64
	err = q.QueryRowContext(ctx, `SELECT period_start,period_end,upload_bytes,download_bytes,bandwidth_quota FROM account_traffic_stats
 WHERE account_id=? AND period_start<=? AND period_end>? ORDER BY period_start DESC,id DESC LIMIT 1`, id, FormatTime(at), FormatTime(at)).Scan(&s, &e, &up, &down, &snapshot)
	if errors.Is(err, sql.ErrNoRows) {
		return u, nil
	}
	if err != nil {
		return nil, err
	}
	if up < 0 || down < 0 || up > math.MaxInt64-down {
		return nil, invalid("账户流量无效或溢出")
	}
	u.UsedBytes = up + down
	if snapshot.Valid {
		u.QuotaGB = snapshot.Int64
	}
	if u.PeriodStart, err = ParseTime(s); err != nil {
		return nil, err
	}
	if u.PeriodEnd, err = ParseTime(e); err != nil {
		return nil, err
	}
	return u, nil
}

// AccountUsage 即时计算当前周期（没有统计行时用量为 0）；账户不存在返回 sql.ErrNoRows。
func AccountUsage(ctx context.Context, db *sql.DB, accountID int64, at time.Time) (*TrafficUsage, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	u, err := accountUsage(ctx, tx, accountID, at)
	if err != nil {
		return nil, err
	}
	return u, tx.Commit()
}

// MbpsLimits 是配置生成时的账户限速，0 表示不输出该方向字段。
type MbpsLimits struct {
	DownloadMbps, UploadMbps int
	OverQuota                bool
	QuotaLimited             bool
}

// EffectiveLimits 每次按当前周期和配置重新判定；关闭全局开关仍保留账户原生限速。
func EffectiveLimits(ctx context.Context, db *sql.DB, accountID int64, at time.Time) (*MbpsLimits, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	u, err := accountUsage(ctx, tx, accountID, at)
	if err != nil {
		return nil, err
	}
	l := &MbpsLimits{}
	if err = tx.QueryRowContext(ctx, `SELECT coalesce(download_mbps,0),coalesce(upload_mbps,0) FROM account WHERE id=?`, accountID).Scan(&l.DownloadMbps, &l.UploadMbps); err != nil {
		return nil, err
	}
	config := func(key string) (string, error) {
		var v sql.NullString
		err := tx.QueryRowContext(ctx, `SELECT config_value FROM system_config WHERE config_key=?`, key).Scan(&v)
		if errors.Is(err, sql.ErrNoRows) {
			return "", nil
		}
		return strings.TrimSpace(v.String), err
	}
	enabled, err := config("airopscat.ratelimit.enabled")
	if err != nil {
		return nil, err
	}
	// 除法比较避免 GB 转 bytes 溢出。
	l.OverQuota = u.QuotaGB > 0 && u.UsedBytes/(1<<30) >= u.QuotaGB
	if strings.EqualFold(enabled, "true") && l.OverQuota {
		l.QuotaLimited = true
		for _, c := range []struct {
			key string
			dst *int
		}{{"download", &l.DownloadMbps}, {"upload", &l.UploadMbps}} {
			raw, err := config("airopscat.account.traffic-over-quota." + c.key + "-mbps")
			if err != nil {
				return nil, err
			}
			n, err := strconv.Atoi(raw)
			if err != nil {
				n = 1
			}
			*c.dst = max(1, n)
		}
	}
	return l, tx.Commit()
}

// EffectiveLimitOverrides 在调用方已有事务内批量读取活跃账户限速，配置和账户各查一次。
// 返回值按 accountNo 索引，包含原生限速；OverQuota 表示用量状态，开关关闭时不覆盖速度。
// CaptureDeployment 持有事务时应传入该事务，不应调用另开事务的 LoadEffectiveLimits。
func EffectiveLimitOverrides(ctx context.Context, q dbtx, at time.Time) (map[string]MbpsLimits, error) {
	config := map[string]string{}
	rows, err := q.QueryContext(ctx, `SELECT config_key,config_value FROM system_config WHERE config_key IN
 ('airopscat.ratelimit.enabled','airopscat.account.traffic-over-quota.download-mbps','airopscat.account.traffic-over-quota.upload-mbps')`)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var key string
		var value sql.NullString
		if err = rows.Scan(&key, &value); err != nil {
			rows.Close()
			return nil, err
		}
		config[strings.ToLower(key)] = strings.TrimSpace(value.String)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	speed := func(direction string) int {
		n, err := strconv.Atoi(config["airopscat.account.traffic-over-quota."+direction+"-mbps"])
		if err != nil {
			return 1
		}
		return max(1, n)
	}
	enabled := strings.EqualFold(config["airopscat.ratelimit.enabled"], "true")
	stamp := FormatTime(at)
	rows, err = q.QueryContext(ctx, `SELECT a.account_no,coalesce(a.download_mbps,0),coalesce(a.upload_mbps,0),
 coalesce(s.upload_bytes,0),coalesce(s.download_bytes,0),coalesce(s.bandwidth_quota,a.bandwidth,0)
 FROM account a LEFT JOIN account_traffic_stats s ON s.id=(SELECT t.id FROM account_traffic_stats t
 WHERE t.account_id=a.id AND t.period_start<=? AND t.period_end>? ORDER BY t.period_start DESC,t.id DESC LIMIT 1)
 WHERE a.disabled=0 AND (a.to_date IS NULL OR a.to_date>?)`, stamp, stamp, stamp)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	limits := map[string]MbpsLimits{}
	for rows.Next() {
		var no string
		var l MbpsLimits
		var up, down, quota int64
		if err = rows.Scan(&no, &l.DownloadMbps, &l.UploadMbps, &up, &down, &quota); err != nil {
			return nil, err
		}
		if up < 0 || down < 0 || up > math.MaxInt64-down {
			return nil, invalid("账户流量无效或溢出")
		}
		l.OverQuota = quota > 0 && (up+down)/(1<<30) >= quota
		if enabled && l.OverQuota {
			l.QuotaLimited = true
			l.DownloadMbps = speed("download")
			l.UploadMbps = speed("upload")
		}
		limits[no] = l
	}
	return limits, rows.Err()
}

// LoadEffectiveLimits 在同一快照内装载全部活跃账户限速，不使用缓存。
// 返回的 map 是本次读取快照；若在后续部署事务外调用，配置或配额可在部署前变化。
func LoadEffectiveLimits(ctx context.Context, db *sql.DB, at time.Time) (map[string]MbpsLimits, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	limits, err := EffectiveLimitOverrides(ctx, tx, at)
	if err != nil {
		return nil, err
	}
	return limits, tx.Commit()
}

// TrafficReading 是 reset=false 的单方向原始累计字节数。
type TrafficReading struct {
	AccountNo string
	Direction string // uplink 或 downlink
	Value     int64
}

// CollectAccountTraffic 原子写入单台服务器的一批账户读数，不写 vnstat/server_metrics。
// 每个方向首次只保存基线，避免将切换前历史流量重复计费；重复采样幂等。
// 读数下降按重启处理，当前值即增量。重启后读数已超过旧值时无法识别重启。
// 跨周期的差值全部归采样时所在周期；调用者须按时间顺序串行采集同一服务器。
// 倍率沿用规格逐次截断到整数 byte，每次每方向的舍入误差小于 1 byte。
// 未知账户、重复方向、过时或冲突采样、负数及任何溢出均使整批回滚。
func CollectAccountTraffic(ctx context.Context, db *sql.DB, serverID int64, at time.Time, readings []TrafficReading) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var multiple sql.NullFloat64
	if err = tx.QueryRowContext(ctx, `SELECT multiple FROM server WHERE id=?`, serverID).Scan(&multiple); err != nil {
		return err
	}
	m := 1.0
	if multiple.Valid {
		m = multiple.Float64
	}
	if m <= 0 || math.IsNaN(m) || math.IsInf(m, 0) {
		return invalid("服务器倍率无效")
	}
	// 十进制有理数运算后截断，不让 float64 的舍入溢出或损坏大整数读数。
	factor, ok := new(big.Rat).SetString(strconv.FormatFloat(m, 'f', -1, 64))
	if !ok {
		return invalid("服务器倍率无效")
	}
	seen := map[string]bool{}
	stamp := FormatTime(at)
	for _, r := range readings {
		if !accountNoRe.MatchString(r.AccountNo) || (r.Direction != "uplink" && r.Direction != "downlink") || r.Value < 0 {
			return invalid("流量读数无效")
		}
		key := strings.ToLower(r.AccountNo) + ":" + r.Direction
		if seen[key] {
			return invalid("重复流量方向")
		}
		seen[key] = true
		var id int64
		if err = tx.QueryRowContext(ctx, `SELECT id FROM account WHERE account_no=?`, r.AccountNo).Scan(&id); err != nil {
			return err
		}
		var previous int64
		var sampled string
		err = tx.QueryRowContext(ctx, `SELECT value,sampled_at FROM account_traffic_counter WHERE server_id=? AND account_no=? AND direction=?`, serverID, r.AccountNo, r.Direction).Scan(&previous, &sampled)
		delta := int64(0)
		switch {
		case errors.Is(err, sql.ErrNoRows):
		case err != nil:
			return err
		case stamp < sampled || (stamp == sampled && previous != r.Value):
			return invalid("过时或冲突的流量采样")
		default:
			delta = r.Value - previous
			if delta < 0 {
				delta = r.Value
			}
		}
		scaled := new(big.Rat).Mul(new(big.Rat).SetInt64(delta), factor)
		bytes := new(big.Int).Quo(scaled.Num(), scaled.Denom())
		if !bytes.IsInt64() {
			return invalid("倍率流量溢出")
		}
		delta = bytes.Int64()
		if delta > 0 {
			u, err := accountUsage(ctx, tx, id, at)
			if err != nil {
				return err
			}
			if u.UsedBytes > math.MaxInt64-delta {
				return invalid("账户流量溢出")
			}
			up, down := int64(0), int64(0)
			if r.Direction == "uplink" {
				up = delta
			} else {
				down = delta
			}
			_, err = tx.ExecContext(ctx, `INSERT INTO account_traffic_stats(account_id,user_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes,create_time,update_time)
 SELECT id,user_id,?,?,bandwidth,?,?,?,? FROM account WHERE id=?
 ON CONFLICT(account_id,period_start) DO UPDATE SET upload_bytes=upload_bytes+excluded.upload_bytes,download_bytes=download_bytes+excluded.download_bytes,update_time=excluded.update_time`, FormatTime(u.PeriodStart), FormatTime(u.PeriodEnd), up, down, stamp, stamp, id)
			if err != nil {
				return err
			}
		}
		if _, err = tx.ExecContext(ctx, `INSERT INTO account_traffic_counter(server_id,account_no,direction,value,sampled_at) VALUES(?,?,?,?,?)
 ON CONFLICT(server_id,account_no,direction) DO UPDATE SET value=excluded.value,sampled_at=excluded.sampled_at`, serverID, r.AccountNo, r.Direction, r.Value, stamp); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ExpireAccounts 供每小时任务调用，禁用所有 to_date<=at 的账户，相关节点置待部署，返回被禁用的账户。
// 无关联节点也禁用；禁用和待部署标记属于同一事务。
func ExpireAccounts(ctx context.Context, db *sql.DB, at time.Time) ([]int64, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, `SELECT id FROM account WHERE disabled=0 AND to_date IS NOT NULL AND to_date<=?`, FormatTime(at))
	if err != nil {
		return nil, err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err = rows.Scan(&id); err != nil {
			rows.Close()
			return nil, err
		}
		ids = append(ids, id)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	for _, id := range ids {
		if err = markAccountPending(ctx, tx, id); err != nil {
			return nil, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE account SET disabled=1,update_time=? WHERE id=?`, FormatTime(at), id); err != nil {
			return nil, err
		}
	}
	if err = tx.Commit(); err != nil {
		return nil, err
	}
	return ids, nil
}

// CleanupTrafficHistory 删除结束超过 30 天的周期及离线超过 30 天的在线历史，保留累计基线。
func CleanupTrafficHistory(ctx context.Context, db *sql.DB, at time.Time) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	days := 30
	var raw string
	e := tx.QueryRowContext(ctx, `SELECT coalesce(config_value,'') FROM system_config WHERE config_key='airopscat.account.traffic.retention-days'`).Scan(&raw)
	if e != nil && e != sql.ErrNoRows {
		return e
	}
	if n, e := strconv.Atoi(raw); e == nil && n > 0 {
		days = n
	}
	cutoff := FormatTime(at.In(Shanghai).AddDate(0, 0, -days))
	for _, query := range []string{`DELETE FROM account_traffic_stats WHERE period_end<?`} {
		if _, err = tx.ExecContext(ctx, query, cutoff); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM account_online_ip WHERE last_online_time<?`, FormatTime(at.In(Shanghai).AddDate(0, 0, -30))); err != nil {
		return err
	}
	return tx.Commit()
}
