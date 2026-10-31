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

// ServerStatus 是监控页每台服务器的最近状态。
type ServerStatus struct {
	Iface              string
	AgentSeen          string
	ID                 int64
	Name, IP           string
	BandwidthGB        int64 // 月流量额度，十进制 GB，0 不限
	ResetDay           int   // 流量重置日
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
	return (float64(s.VnstatRx) + float64(s.VnstatTx)) / (float64(s.BandwidthGB) * 1e9)
}

// ServerStatuses 只列启用的非外部服务器；最近一条 monitor 与最近一条（当前流量周期）vnstat。
func ServerStatuses(ctx context.Context, db *sql.DB, q string, page, size int, at time.Time) ([]ServerStatus, int, error) {
	like := "%" + q + "%"
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM server WHERE disabled=0 AND external_server=0 AND (coalesce(name,'') LIKE ? OR ip LIKE ?)`, like, like).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT s.id,coalesce(s.name,''),s.ip,coalesce(s.bandwidth,0),`+resetDaySQL+`,
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
		if err := rows.Scan(&s.ID, &s.Name, &s.IP, &s.BandwidthGB, &s.ResetDay, &s.Sampled, &cpu, &mem, &disk, &sb, &rx, &tx, &s.Iface, &s.VnstatTime, &s.VnstatRx, &s.VnstatTx, &s.HasVnstat, &s.AgentSeen); err != nil {
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
