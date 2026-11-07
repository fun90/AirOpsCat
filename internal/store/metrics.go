package store

import (
	"context"
	"database/sql"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
)

// MonitorServers 解析显式启用的监控服务器（ID 列表或 all），只含启用、非外部、未过期的服务器；空选择表示关闭。
func MonitorServers(ctx context.Context, db *sql.DB, selection string, at time.Time) ([]int64, error) {
	selection = strings.TrimSpace(selection)
	if selection == "" {
		return nil, nil
	}
	allowed := map[int64]bool{}
	if selection != "all" {
		for _, part := range strings.Split(selection, ",") {
			id, err := strconv.ParseInt(strings.TrimSpace(part), 10, 64)
			if err != nil || id <= 0 {
				return nil, fmt.Errorf("监控服务器配置无效")
			}
			allowed[id] = true
		}
	}
	rows, err := db.QueryContext(ctx, `SELECT id FROM server WHERE disabled=0 AND external_server=0 AND (expire_date IS NULL OR expire_date='' OR substr(expire_date,1,10)>=?) ORDER BY id`, at.In(Shanghai).Format("2006-01-02"))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		if selection == "all" || allowed[id] {
			ids = append(ids, id)
		}
	}
	return ids, rows.Err()
}

func InsertMonitorSample(ctx context.Context, db *sql.DB, serverID int64, s monitor.Sample, at time.Time) error {
	_, err := db.ExecContext(ctx, `INSERT INTO server_metrics(server_id,kind,sample_time,cpu_usage,memory_usage,memory_used,memory_total,rx_rate,tx_rate,disk_usage,disk_used,disk_total,singbox_down) VALUES(?,'monitor',?,?,?,?,?,?,?,?,?,?,?)`,
		append([]any{serverID, FormatTime(at), s.CPU, s.Memory, s.MemUsed, s.MemTotal, s.RxRate, s.TxRate}, extraArgs(s)...)...)
	return err
}

// extraArgs 未采到磁盘或 sing-box 状态时写 NULL，避免旧 agent 的缺省值被当成真实数据。
func extraArgs(s monitor.Sample) []any {
	var down any
	switch s.Singbox {
	case "active":
		down = 0
	case "down":
		down = 1
	}
	if s.DiskTotal <= 0 {
		return []any{nil, nil, nil, down}
	}
	return []any{s.Disk, s.DiskUsed, s.DiskTotal, down}
}

// resetDaySQL 是服务器（别名 s）的流量重置日，未设置按 1 号。
const resetDaySQL = `coalesce(cast(strftime('%d',s.bandwidth_date) AS INTEGER),1)`

// latestVnstatSQL 取服务器 s 当前流量周期最近一条 vnstat 的 id，参数由 vnstatPeriodArgs 提供。
const latestVnstatSQL = `(SELECT id FROM server_metrics WHERE server_id=s.id AND kind='vnstat' AND period_year*100+period_month=CASE WHEN ?>=min(` + resetDaySQL + `,?) THEN ? ELSE ? END ORDER BY sample_time DESC,id DESC LIMIT 1)`

// vnstatPeriodArgs：今天是几号、本月天数、本月与上月的 yyyymm；到了重置日周期从本月起，否则从上月起。
func vnstatPeriodArgs(at time.Time) []any {
	at = at.In(Shanghai)
	first := time.Date(at.Year(), at.Month(), 1, 0, 0, 0, 0, Shanghai)
	prev := first.AddDate(0, -1, 0)
	return []any{at.Day(), first.AddDate(0, 1, -1).Day(), at.Year()*100 + int(at.Month()), prev.Year()*100 + int(prev.Month())}
}

// ServerResetDay 返回服务器流量重置日，未设置为 1。
func ServerResetDay(ctx context.Context, db *sql.DB, serverID int64) (int, error) {
	var day int
	err := db.QueryRowContext(ctx, `SELECT `+resetDaySQL+` FROM server s WHERE id=?`, serverID).Scan(&day)
	return day, err
}

// insertVnstatSQL 写入时叠加本周期流量校准值；校准值计入 tx，下游只用上下行合计。
const insertVnstatSQL = `INSERT INTO server_metrics(server_id,kind,sample_time,iface,period_year,period_month,rx_bytes,tx_bytes)
 SELECT id,'vnstat',?,?,?,?,?,?+CASE WHEN traffic_offset_period=? THEN traffic_offset ELSE 0 END FROM server WHERE id=?`

func vnstatArgs(serverID int64, v monitor.Vnstat, at time.Time) []any {
	return []any{FormatTime(at), v.Iface, v.Year, v.Month, v.RxBytes, v.TxBytes, fmt.Sprintf("%04d-%02d", v.Year, v.Month), serverID}
}

func InsertVnstatSample(ctx context.Context, db *sql.DB, serverID int64, v monitor.Vnstat, at time.Time) error {
	_, err := db.ExecContext(ctx, insertVnstatSQL, vnstatArgs(serverID, v, at)...)
	return err
}

// CalibrateServerTraffic 把服务器当前周期已用流量（上+下，字节）校准为 usedBytes：
// 差值写入 traffic_offset（仅本周期生效），并同步修正本周期最新一条 vnstat 样本，使页面立即生效。
func CalibrateServerTraffic(ctx context.Context, db *sql.DB, serverID, usedBytes int64, at time.Time) error {
	if usedBytes < 0 {
		return fmt.Errorf("%w：已用流量不能为负", ErrInvalid)
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var sampleID, total, offset int64
	var period int
	var offsetPeriod sql.NullString
	err = tx.QueryRowContext(ctx, `SELECT v.id,v.period_year*100+v.period_month,coalesce(v.rx_bytes,0)+coalesce(v.tx_bytes,0),s.traffic_offset,s.traffic_offset_period
 FROM server s JOIN server_metrics v ON v.id=`+latestVnstatSQL+` WHERE s.id=?`,
		append(vnstatPeriodArgs(at), serverID)...).Scan(&sampleID, &period, &total, &offset, &offsetPeriod)
	if err == sql.ErrNoRows {
		return fmt.Errorf("%w：当前周期暂无 vnstat 数据，无法校准", ErrInvalid)
	}
	if err != nil {
		return err
	}
	periodText := fmt.Sprintf("%04d-%02d", period/100, period%100)
	var oldEff int64
	if offsetPeriod.Valid && offsetPeriod.String == periodText {
		oldEff = offset
	}
	newOffset := usedBytes - (total - oldEff)
	if _, err := tx.ExecContext(ctx, `UPDATE server SET traffic_offset=?,traffic_offset_period=? WHERE id=?`, newOffset, periodText, serverID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE server_metrics SET tx_bytes=coalesce(tx_bytes,0)+? WHERE id=?`, newOffset-oldEff, sampleID); err != nil {
		return err
	}
	return tx.Commit()
}

// MetricPoints 返回 since 之后某指标列（cpu_usage / memory_usage / disk_usage / singbox_down）的采样，按时间升序。
func MetricPoints(ctx context.Context, db *sql.DB, serverID int64, column string, since time.Time) ([]monitor.Point, error) {
	if column != "cpu_usage" && column != "memory_usage" && column != "disk_usage" && column != "singbox_down" {
		return nil, fmt.Errorf("不支持的指标列")
	}
	rows, err := db.QueryContext(ctx, `SELECT sample_time,`+column+` FROM server_metrics WHERE server_id=? AND kind='monitor' AND sample_time>=? AND `+column+` IS NOT NULL ORDER BY sample_time`, serverID, FormatTime(since))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []monitor.Point
	for rows.Next() {
		var ts string
		var v float64
		if err := rows.Scan(&ts, &v); err != nil {
			return nil, err
		}
		t, err := ParseTime(ts)
		if err != nil {
			continue
		}
		out = append(out, monitor.Point{At: t, Value: v})
	}
	return out, rows.Err()
}

// VnstatGrowth 返回 since 之后 vnstat 累计流量（上+下）的增量字节数；只累加同一统计周期内相邻样本的正增量，周期切换或计数回退不计入。
// first 为窗口内最早样本时间，无样本时为零值，供调用方判断窗口是否被覆盖。
func VnstatGrowth(ctx context.Context, db *sql.DB, serverID int64, since time.Time) (growth int64, first time.Time, err error) {
	rows, err := db.QueryContext(ctx, `SELECT sample_time,period_year*100+period_month,coalesce(rx_bytes,0)+coalesce(tx_bytes,0) FROM server_metrics WHERE server_id=? AND kind='vnstat' AND sample_time>=? ORDER BY sample_time,id`, serverID, FormatTime(since))
	if err != nil {
		return 0, time.Time{}, err
	}
	defer rows.Close()
	var prevPeriod int
	var prevTotal int64
	seen := false
	for rows.Next() {
		var ts string
		var period int
		var total int64
		if err := rows.Scan(&ts, &period, &total); err != nil {
			return 0, time.Time{}, err
		}
		t, perr := ParseTime(ts)
		if perr != nil {
			continue
		}
		if !seen {
			first, seen = t, true
		} else if period == prevPeriod && total > prevTotal {
			growth += total - prevTotal
		}
		prevPeriod, prevTotal = period, total
	}
	return growth, first, rows.Err()
}

// 月流量额度单位：服务商有的按 GB（1000³）有的按 GiB（1024³）计。
const (
	UnitGB  = "GB"
	UnitGiB = "GiB"
)

// BandwidthUnitOrDefault 把空值或未知值归为既有的 GiB 口径。
func BandwidthUnitOrDefault(u string) string {
	if u == UnitGB {
		return UnitGB
	}
	return UnitGiB
}

// UnitBytes 返回一个额度单位对应的字节数。
func UnitBytes(u string) float64 {
	if u == UnitGB {
		return 1e9
	}
	return 1 << 30
}

// ServerStatus 是监控页每台服务器的最近状态。
type ServerStatus struct {
	Iface              string
	AgentSeen          string
	ID                 int64
	Name, IP           string
	BandwidthGB        int64 // 月流量额度数值，单位见 BandwidthUnit，0 不限
	BandwidthUnit      string
	ResetDay           int // 流量重置日
	Sampled            string
	CPU, Memory, Disk  *float64
	SingboxDown        *bool // nil 表示未知
	RxRate, TxRate     *int64
	VnstatTime         string
	VnstatRx, VnstatTx int64
	HasVnstat          bool
}

// UsageRatio 为 vnstat 当前周期（上+下）占额度的比例；额度未设置返回 0。
func (s ServerStatus) UsageRatio() float64 {
	if s.BandwidthGB <= 0 || !s.HasVnstat {
		return 0
	}
	return (float64(s.VnstatRx) + float64(s.VnstatTx)) / (float64(s.BandwidthGB) * UnitBytes(s.BandwidthUnit))
}

// ServerStatuses 只列启用的非外部服务器；最近一条 monitor 与最近一条（当前流量周期）vnstat。
func ServerStatuses(ctx context.Context, db *sql.DB, q string, page, size int, at time.Time) ([]ServerStatus, int, error) {
	like := "%" + q + "%"
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM server WHERE disabled=0 AND external_server=0 AND (coalesce(name,'') LIKE ? OR ip LIKE ?)`, like, like).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT s.id,coalesce(s.name,''),s.ip,coalesce(s.bandwidth,0),coalesce(s.bandwidth_unit,'GiB'),`+resetDaySQL+`,
 coalesce(m.sample_time,''),m.cpu_usage,m.memory_usage,m.disk_usage,m.singbox_down,m.rx_rate,m.tx_rate,
 coalesce(v.iface,''),coalesce(v.sample_time,''),coalesce(v.rx_bytes,0),coalesce(v.tx_bytes,0),v.id IS NOT NULL,
 coalesce((SELECT last_seen FROM monitor_agent WHERE server_id=s.id),'')
 FROM server s
 LEFT JOIN server_metrics m ON m.id=(SELECT id FROM server_metrics WHERE server_id=s.id AND kind='monitor' ORDER BY sample_time DESC,id DESC LIMIT 1)
 LEFT JOIN server_metrics v ON v.id=`+latestVnstatSQL+`
 WHERE s.disabled=0 AND s.external_server=0 AND (coalesce(s.name,'') LIKE ? OR s.ip LIKE ?) ORDER BY s.name,s.id LIMIT ? OFFSET ?`,
		append(vnstatPeriodArgs(at), like, like, size, (page-1)*size)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []ServerStatus
	for rows.Next() {
		var s ServerStatus
		var cpu, mem, disk sql.NullFloat64
		var sb sql.NullInt64
		var rx, tx sql.NullInt64
		if err := rows.Scan(&s.ID, &s.Name, &s.IP, &s.BandwidthGB, &s.BandwidthUnit, &s.ResetDay, &s.Sampled, &cpu, &mem, &disk, &sb, &rx, &tx, &s.Iface, &s.VnstatTime, &s.VnstatRx, &s.VnstatTx, &s.HasVnstat, &s.AgentSeen); err != nil {
			return nil, 0, err
		}
		if cpu.Valid {
			s.CPU = &cpu.Float64
		}
		if mem.Valid {
			s.Memory = &mem.Float64
		}
		if sb.Valid {
			down := sb.Int64 != 0
			s.SingboxDown = &down
		}
		if disk.Valid {
			s.Disk = &disk.Float64
		}
		if rx.Valid {
			s.RxRate = &rx.Int64
		}
		if tx.Valid {
			s.TxRate = &tx.Int64
		}
		out = append(out, s)
	}
	return out, total, rows.Err()
}

// CleanupMetrics 按天数删除过期指标，分批执行，返回删除总数。
func CleanupMetrics(ctx context.Context, db *sql.DB, keepDays, batch int, at time.Time) (int64, error) {
	if keepDays < 1 {
		keepDays = 30
	}
	if batch < 1 {
		batch = 1000
	}
	cutoff := FormatTime(at.AddDate(0, 0, -keepDays))
	var total int64
	for {
		res, err := db.ExecContext(ctx, `DELETE FROM server_metrics WHERE id IN (SELECT id FROM server_metrics WHERE sample_time<? LIMIT ?)`, cutoff, batch)
		if err != nil {
			return total, err
		}
		n, _ := res.RowsAffected()
		total += n
		if n < int64(batch) {
			return total, nil
		}
	}
}

// 降采样参数：一定时间以前的 monitor 样本每台服务器每 2 分钟保留最早一条（与 SSH 采集粒度一致），
// vnstat 样本每个流量周期每小时保留最后一条（周期最新累计值始终保留）。
const (
	thinMonitorBucket = 120 // 秒
	thinLookback      = 7   // 天；每次只处理切分点之前这么多天，更早的数据按保留期删除
)

// ThinMetrics 对 before 之前的指标降采样，返回删除行数。before 向下对齐到整点，使 2 分钟与 1 小时的桶不被切开，
// 重复执行不再删除；按服务器、按天分块，每块一条语句走 (server_id, kind, sample_time) 索引，写锁时间短。
func ThinMetrics(ctx context.Context, db *sql.DB, before time.Time) (int64, error) {
	end := before.In(Shanghai).Truncate(time.Hour)
	start := end.AddDate(0, 0, -thinLookback)
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT server_id FROM server_metrics`)
	if err != nil {
		return 0, err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return 0, err
		}
		ids = append(ids, id)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return 0, err
	}
	// 桶键只用于分组：strftime 把上海时间文本当 UTC 解析，整点边界不受影响。
	monitorSQL := `DELETE FROM server_metrics WHERE server_id=? AND kind='monitor' AND sample_time>=? AND sample_time<? AND id NOT IN
 (SELECT min(id) FROM server_metrics WHERE server_id=? AND kind='monitor' AND sample_time>=? AND sample_time<? GROUP BY CAST(strftime('%s',sample_time) AS INTEGER)/` + strconv.Itoa(thinMonitorBucket) + `)`
	const vnstatSQL = `DELETE FROM server_metrics WHERE server_id=? AND kind='vnstat' AND sample_time>=? AND sample_time<? AND id NOT IN
 (SELECT max(id) FROM server_metrics WHERE server_id=? AND kind='vnstat' AND sample_time>=? AND sample_time<? GROUP BY period_year,period_month,substr(sample_time,1,13))`
	var total int64
	for _, id := range ids {
		for from := start; from.Before(end); from = from.AddDate(0, 0, 1) {
			to := from.AddDate(0, 0, 1)
			if to.After(end) {
				to = end
			}
			f, t := FormatTime(from), FormatTime(to)
			for _, q := range []string{monitorSQL, vnstatSQL} {
				res, err := db.ExecContext(ctx, q, id, f, t, id, f, t)
				if err != nil {
					return total, err
				}
				n, _ := res.RowsAffected()
				total += n
			}
		}
	}
	return total, nil
}

// AlertResolveFingerprint 恢复指定指纹的告警；用于同一类型下按 cpu/memory/traffic 区分的告警。
func AlertResolveFingerprint(ctx context.Context, db *sql.DB, alertType, resourceType string, resourceID int64, fingerprint string, now time.Time) (bool, error) {
	ts := FormatTime(now)
	res, err := db.ExecContext(ctx, `UPDATE alert_state SET status='RECOVERED',recovered_time=?,update_time=? WHERE alert_type=? AND resource_type=? AND resource_id=? AND fingerprint=? AND status<>'RECOVERED'`, ts, ts, alertType, resourceType, resourceID, fingerprint)
	if err != nil {
		return false, err
	}
	n, _ := res.RowsAffected()
	return n > 0, nil
}
