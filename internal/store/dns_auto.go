package store

import (
	"context"
	"database/sql"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/dns"
)

type AutoDNSRecord struct {
	DNSRecord
	PlanInput dns.AutoRecord
	Operation *DNSOperation
}
type DNSOperation struct {
	RecordID                  int64
	Old, Target, Home, Reason string
}

func AutoDNSRecords(ctx context.Context, db *sql.DB, domainID int64) ([]AutoDNSRecord, error) {
	rows, err := db.QueryContext(ctx, `SELECT id FROM domain_dns_record WHERE domain_id=? AND upper(type)='A' AND status='SYNCED' AND coalesce(external_record_id,'')<>'' ORDER BY id`, domainID)
	if err != nil {
		return nil, err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
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
	var out []AutoDNSRecord
	for _, id := range ids {
		r, err := DNSRecordByID(ctx, db, id)
		if err != nil {
			return nil, err
		}
		if r == nil {
			continue
		}
		a := AutoDNSRecord{DNSRecord: *r}
		p := dns.AutoRecord{ID: id, Content: r.Content}
		if err := db.QueryRowContext(ctx, `SELECT coalesce(schedule_enabled,0),coalesce(schedule_start,''),coalesce(schedule_end,''),coalesce(schedule_content_in,''),coalesce(schedule_content_out,''),coalesce(failover_home_content,'') FROM domain_dns_record WHERE id=?`, id).Scan(&p.Scheduled, &p.Start, &p.End, &p.In, &p.Out, &p.Home); err != nil {
			return nil, err
		}
		refs, err := db.QueryContext(ctx, `SELECT node_id,server_id FROM node_server WHERE access_host_id=? AND deployed<>2
 UNION ALL SELECT ns.node_id,ns.server_id FROM account_node_subscription_domain_binding b JOIN node_server ns ON ns.id=(SELECT ns2.id FROM account_node_subscription_placement bp JOIN node_server ns2 ON ns2.id=bp.node_server_id WHERE bp.binding_id=b.id AND ns2.node_id=b.node_id AND ns2.deployed<>2 ORDER BY ns2.id LIMIT 1) WHERE b.domain_dns_record_id=? AND b.enabled=1`, id, id)
		if err != nil {
			return nil, err
		}
		for refs.Next() {
			var n, s int64
			if err := refs.Scan(&n, &s); err != nil {
				refs.Close()
				return nil, err
			}
			if p.NodeID != 0 && (p.NodeID != n || p.ServerID != s) {
				p.Ambiguous = true
			}
			p.NodeID, p.ServerID = n, s
		}
		err = refs.Err()
		refs.Close()
		if err != nil {
			return nil, err
		}
		a.PlanInput = p
		var op DNSOperation
		op.RecordID = id
		e := db.QueryRowContext(ctx, `SELECT old_content,target_content,coalesce(home_content,''),reason FROM dns_auto_operation WHERE record_id=?`, id).Scan(&op.Old, &op.Target, &op.Home, &op.Reason)
		if e == nil {
			a.Operation = &op
		} else if e != sql.ErrNoRows {
			return nil, e
		}
		out = append(out, a)
	}
	return out, nil
}
func DNSCandidates(ctx context.Context, db *sql.DB, at time.Time) ([]dns.Candidate, error) {
	rows, err := db.QueryContext(ctx, `SELECT ns.id,ns.node_id,ns.server_id,s.ip,ns.priority,coalesce(ns.cheap_start,''),coalesce(ns.cheap_end,''),coalesce(s.bandwidth,0),v.rx_bytes,v.tx_bytes,coalesce(v.sample_time,'')
 FROM node_server ns JOIN node n ON n.id=ns.node_id JOIN server s ON s.id=ns.server_id
 LEFT JOIN server_metrics v ON v.id=(SELECT id FROM server_metrics WHERE server_id=s.id AND kind='vnstat' AND period_year=? AND period_month=? ORDER BY sample_time DESC,id DESC LIMIT 1)
 WHERE ns.deployed=1 AND n.type=0 AND n.disabled=0 AND s.disabled=0 AND s.external_server=0 AND (s.expire_date IS NULL OR substr(s.expire_date,1,10)>=?)`, at.In(Shanghai).Year(), int(at.In(Shanghai).Month()), at.In(Shanghai).Format("2006-01-02"))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []dns.Candidate
	for rows.Next() {
		var c dns.Candidate
		var priority, rx, tx sql.NullInt64
		var bw int64
		var sampled string
		if err := rows.Scan(&c.ID, &c.NodeID, &c.ServerID, &c.IP, &priority, &c.CheapStart, &c.CheapEnd, &bw, &rx, &tx, &sampled); err != nil {
			return nil, err
		}
		if priority.Valid {
			c.Priority = &priority.Int64
		}
		c.Known = bw <= 0
		if bw > 0 && rx.Valid && tx.Valid {
			t, e := ParseTime(sampled)
			if e == nil && !t.After(at) && at.Sub(t) <= 15*time.Minute {
				c.Known = true
				c.Usage = (float64(rx.Int64) + float64(tx.Int64)) / float64(bw) / (1 << 30)
			}
		}
		out = append(out, c)
	}
	return out, rows.Err()
}
func AutoDNSDomains(ctx context.Context, db *sql.DB, selection string) ([]int64, error) {
	if strings.TrimSpace(selection) == "" {
		return nil, nil
	}
	allowed := map[int64]bool{}
	if selection != "all" {
		for _, s := range strings.Split(selection, ",") {
			id, e := strconv.ParseInt(strings.TrimSpace(s), 10, 64)
			if e != nil || id <= 0 {
				return nil, fmt.Errorf("自动 DNS 域名配置无效")
			}
			allowed[id] = true
		}
	}
	rows, err := db.QueryContext(ctx, `SELECT id FROM domain WHERE dns_provider_config_id IS NOT NULL ORDER BY id`)
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
func BeginDNSOperation(ctx context.Context, db *sql.DB, r AutoDNSRecord, p dns.Plan) (*DNSOperation, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	var content, status, stamp string
	if err := tx.QueryRowContext(ctx, `SELECT content,status,coalesce(update_time,'') FROM domain_dns_record WHERE id=?`, r.ID).Scan(&content, &status, &stamp); err != nil {
		return nil, err
	}
	if content != r.Content || status != DNSSynced {
		return nil, fmt.Errorf("记录已变化，等待下一轮")
	}
	op := &DNSOperation{r.ID, r.Content, p.Target, p.Home, p.Reason}
	if _, err := tx.ExecContext(ctx, `INSERT INTO dns_auto_operation(record_id,old_content,target_content,home_content,reason,created_at)VALUES(?,?,?,?,?,?)`, r.ID, r.Content, p.Target, nz(p.Home), p.Reason, FormatTime(time.Now())); err != nil {
		return nil, err
	}
	return op, tx.Commit()
}

// FinishDNSOperation 删除操作锁和写回必须原子完成；失败时保留操作以便下一轮对账。
func FinishDNSOperation(ctx context.Context, db *sql.DB, op *DNSOperation, applied bool) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if _, err := tx.ExecContext(ctx, `DELETE FROM dns_auto_operation WHERE record_id=?`, op.RecordID); err != nil {
		return err
	}
	if applied {
		if _, err := tx.ExecContext(ctx, `UPDATE domain_dns_record SET content=?,failover_home_content=?,last_sync_time=?,update_time=? WHERE id=? AND content=? AND status='SYNCED'`, op.Target, nz(op.Home), FormatTime(time.Now()), FormatTime(time.Now()), op.RecordID, op.Old); err != nil {
			return err
		}
	}
	return tx.Commit()
}
