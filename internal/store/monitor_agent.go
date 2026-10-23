package store

import (
	"context"
	"crypto/rand"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
)

// RotateMonitorAgentToken 只保存摘要；明文只返回给安装流程，不复用开放接口的全局 Token。
func RotateMonitorAgentToken(ctx context.Context, db *sql.DB, serverID int64) (string, error) {
	var raw [32]byte
	if _, err := rand.Read(raw[:]); err != nil {
		return "", err
	}
	token := hex.EncodeToString(raw[:])
	hash := sha256.Sum256([]byte(token))
	_, err := db.ExecContext(ctx, `INSERT INTO monitor_agent(server_id,token_hash) VALUES(?,?) ON CONFLICT(server_id) DO UPDATE SET token_hash=excluded.token_hash`, serverID, hex.EncodeToString(hash[:]))
	return token, err
}

func MonitorSSHServers(ctx context.Context, db *sql.DB, selection string, at time.Time) ([]int64, error) {
	ids, err := MonitorServers(ctx, db, selection, at)
	if err != nil {
		return nil, err
	}
	var out []int64
	for _, id := range ids {
		var active bool
		if err := db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM monitor_agent WHERE server_id=? AND last_seen IS NOT NULL)`, id).Scan(&active); err != nil {
			return nil, err
		}
		if !active {
			out = append(out, id)
		}
	}
	return out, nil
}

// SaveMonitorReport 与基线一起提交，防止重复请求和并发请求重复生成速率。
func SaveMonitorReport(ctx context.Context, db *sql.DB, r monitor.AgentReport, at time.Time) error {
	if err := r.Validate(); err != nil {
		return err
	}
	if delta := at.Unix() - r.SampledAt; delta < -30 || delta > 30 {
		return fmt.Errorf("采样时间已过期")
	}
	var vn monitor.Vnstat
	var hasVN bool
	var err error
	if r.Vnstat != "" {
		vn, hasVN, err = monitor.ParseVnstat(r.Vnstat, at.In(Shanghai))
		if err != nil {
			return err
		}
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var p monitor.AgentReport
	err = tx.QueryRowContext(ctx, `SELECT sampled_at,boot_id,uptime_ms,cpu_idle,cpu_total,rx_bytes,tx_bytes FROM monitor_agent WHERE server_id=?`, r.ServerID).Scan(&p.SampledAt, &p.BootID, &p.UptimeMS, &p.CPUIdle, &p.CPUTotal, &p.RxBytes, &p.TxBytes)
	if errors.Is(err, sql.ErrNoRows) {
		return fmt.Errorf("监控 agent 未登记")
	}
	if err != nil {
		return err
	}
	if r.SampledAt <= p.SampledAt {
		return fmt.Errorf("重复或乱序采样")
	}
	if sample, ok := monitor.SampleFromCounters(p, r); ok {
		_, err = tx.ExecContext(ctx, `INSERT INTO server_metrics(server_id,kind,sample_time,cpu_usage,memory_usage,memory_used,memory_total,rx_rate,tx_rate) VALUES(?,'monitor',?,?,?,?,?,?,?)`, r.ServerID, FormatTime(at), sample.CPU, sample.Memory, sample.MemUsed, sample.MemTotal, sample.RxRate, sample.TxRate)
		if err != nil {
			return err
		}
	}
	if hasVN {
		_, err = tx.ExecContext(ctx, `INSERT INTO server_metrics(server_id,kind,sample_time,iface,period_year,period_month,rx_bytes,tx_bytes) VALUES(?,'vnstat',?,?,?,?,?,?)`, r.ServerID, FormatTime(at), vn.Iface, vn.Year, vn.Month, vn.RxBytes, vn.TxBytes)
		if err != nil {
			return err
		}
	}
	_, err = tx.ExecContext(ctx, `UPDATE monitor_agent SET last_seen=?,sampled_at=?,boot_id=?,uptime_ms=?,cpu_idle=?,cpu_total=?,rx_bytes=?,tx_bytes=? WHERE server_id=?`, FormatTime(at), r.SampledAt, r.BootID, r.UptimeMS, r.CPUIdle, r.CPUTotal, r.RxBytes, r.TxBytes, r.ServerID)
	if err != nil {
		return err
	}
	return tx.Commit()
}
