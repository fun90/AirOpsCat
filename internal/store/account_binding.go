package store

import (
	"context"
	"database/sql"
	"fmt"
	"regexp"
	"strings"
)

// AccountBinding 只定制订阅展示，不改变标签授权或节点配置。
type AccountBinding struct {
	ID, AccountID, NodeID, PlacementID       int64
	NodeName, ServerName, RecordName, Remark string
	RecordID                                 *int64
	Enabled                                  bool
	NewDomainID                              int64  // 新增域名：所属主域名
	NewName                                  string // 新增域名：子域名前缀，非空时创建指向部署位 IP 的 A 记录
}

type BindingChoice struct {
	NodeID, PlacementID      int64
	NodeName, ServerName, IP string
	Records                  []BindingRecord // 与该部署位相关、可选的 A 记录
}
type BindingRecord struct {
	ID   int64
	Name string
}
type BindingDomain struct {
	ID   int64
	Name string
}

func AccountBindings(ctx context.Context, db *sql.DB, accountID int64) ([]*AccountBinding, error) {
	rows, err := db.QueryContext(ctx, `SELECT b.id,b.account_id,b.node_id,coalesce(p.node_server_id,0),n.name,coalesce(s.name,s.ip,''),coalesce(r.full_name,''),coalesce(b.remark,''),b.domain_dns_record_id,b.enabled FROM account_node_subscription_domain_binding b JOIN node n ON n.id=b.node_id LEFT JOIN account_node_subscription_placement p ON p.binding_id=b.id LEFT JOIN node_server ns ON ns.id=p.node_server_id LEFT JOIN server s ON s.id=ns.server_id LEFT JOIN domain_dns_record r ON r.id=b.domain_dns_record_id WHERE b.account_id=? ORDER BY b.id`, accountID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*AccountBinding
	for rows.Next() {
		var b AccountBinding
		var rid sql.NullInt64
		if err := rows.Scan(&b.ID, &b.AccountID, &b.NodeID, &b.PlacementID, &b.NodeName, &b.ServerName, &b.RecordName, &b.Remark, &rid, &b.Enabled); err != nil {
			return nil, err
		}
		if rid.Valid {
			b.RecordID = &rid.Int64
		}
		list = append(list, &b)
	}
	return list, rows.Err()
}

// BindingChoices 返回账户可选的部署位（含各自相关的 A 记录）与可新增子域名的主域名。
// 相关记录：已绑定到该部署位服务器，或未绑定且解析值等于服务器 IP 的 A 记录。
func BindingChoices(ctx context.Context, db *sql.DB, accountID int64) ([]BindingChoice, []BindingDomain, error) {
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT n.id,ns.id,n.name,coalesce(s.name,s.ip),s.ip,s.id FROM account_tag at JOIN node_tag nt ON nt.tag_id=at.tag_id JOIN node n ON n.id=nt.node_id JOIN node_server ns ON ns.node_id=n.id JOIN server s ON s.id=ns.server_id WHERE at.account_id=? AND ns.deployed<>2 ORDER BY n.name,ns.priority,ns.id`, accountID)
	if err != nil {
		return nil, nil, err
	}
	var choices []BindingChoice
	var sids []int64
	for rows.Next() {
		var c BindingChoice
		var sid int64
		if err := rows.Scan(&c.NodeID, &c.PlacementID, &c.NodeName, &c.ServerName, &c.IP, &sid); err != nil {
			rows.Close()
			return nil, nil, err
		}
		choices = append(choices, c)
		sids = append(sids, sid)
	}
	if err := rows.Err(); err != nil {
		rows.Close()
		return nil, nil, err
	}
	rows.Close()
	for i := range choices {
		rs, err := db.QueryContext(ctx, `SELECT id,coalesce(full_name,name) FROM domain_dns_record WHERE upper(type)='A' AND status<>'PENDING_DELETE' AND coalesce(schedule_enabled,0)=0 AND (server_id=? OR (server_id IS NULL AND content=?)) ORDER BY full_name`, sids[i], choices[i].IP)
		if err != nil {
			return nil, nil, err
		}
		for rs.Next() {
			var r BindingRecord
			if err := rs.Scan(&r.ID, &r.Name); err != nil {
				rs.Close()
				return nil, nil, err
			}
			choices[i].Records = append(choices[i].Records, r)
		}
		rs.Close()
	}
	ds, err := db.QueryContext(ctx, `SELECT id,coalesce(domain,'') FROM domain WHERE domain<>'' ORDER BY domain`)
	if err != nil {
		return nil, nil, err
	}
	defer ds.Close()
	var domains []BindingDomain
	for ds.Next() {
		var d BindingDomain
		if err := ds.Scan(&d.ID, &d.Name); err != nil {
			return nil, nil, err
		}
		domains = append(domains, d)
	}
	return choices, domains, ds.Err()
}

var subdomainRe = regexp.MustCompile(`^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$`)

// createBindingRecord 在主域名下新建指向服务器 IP 的 A 记录（待创建，实际解析下发由 DNS 阶段处理）。
func createBindingRecord(ctx context.Context, tx *sql.Tx, domainID int64, name string, serverID int64, ip string) (int64, error) {
	name = strings.ToLower(strings.TrimSpace(name))
	if !subdomainRe.MatchString(name) || len(name) > 120 {
		return 0, invalid("子域名只能包含小写字母、数字、连字符和点")
	}
	var domain string
	var prov sql.NullInt64
	if err := tx.QueryRowContext(ctx, `SELECT domain,dns_provider_config_id FROM domain WHERE id=?`, domainID).Scan(&domain, &prov); err != nil {
		return 0, invalid("请选择有效的主域名")
	}
	full := name + "." + domain
	var dup int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record WHERE domain_id=? AND full_name=? AND status<>'PENDING_DELETE'`, domainID, full).Scan(&dup); err != nil {
		return 0, err
	}
	if dup > 0 {
		return 0, fmt.Errorf("%w：域名 %s 已存在，请直接选择", ErrConflict, full)
	}
	now := FormatTime(nowFunc())
	res, err := tx.ExecContext(ctx, `INSERT INTO domain_dns_record(domain_id,server_id,dns_provider_config_id,name,full_name,type,content,ttl,proxied,status,create_time,update_time) VALUES(?,?,?,?,?,'A',?,300,0,'PENDING_CREATE',?,?)`, domainID, serverID, prov, name, full, ip, now, now)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

// releaseBindingRecord 只解除无人引用的服务器关联，保留 DNS 记录本身。
func releaseBindingRecord(ctx context.Context, q dbtx, id *int64) error {
	if id == nil {
		return nil
	}
	_, err := q.ExecContext(ctx, `UPDATE domain_dns_record SET server_id=NULL WHERE id=? AND NOT EXISTS(SELECT 1 FROM node_server WHERE access_host_id=?) AND NOT EXISTS(SELECT 1 FROM account_node_subscription_domain_binding WHERE domain_dns_record_id=?)`, *id, *id, *id)
	return err
}

func SaveAccountBinding(ctx context.Context, db *sql.DB, b *AccountBinding) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var nodeID, serverID int64
	var ip string
	err = tx.QueryRowContext(ctx, `SELECT ns.node_id,ns.server_id,s.ip FROM node_server ns JOIN server s ON s.id=ns.server_id WHERE ns.id=? AND ns.deployed<>2`, b.PlacementID).Scan(&nodeID, &serverID, &ip)
	if err != nil {
		return invalid("请选择有效的部署位")
	}
	b.NodeID = nodeID
	var allowed int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM account_tag at JOIN node_tag nt ON nt.tag_id=at.tag_id WHERE at.account_id=? AND nt.node_id=?`, b.AccountID, nodeID).Scan(&allowed); err != nil {
		return err
	}
	if allowed == 0 {
		return invalid("节点不属于该账户，请先分配标签")
	}
	var oldRecord sql.NullInt64
	if b.ID != 0 {
		var owner int64
		if err := tx.QueryRowContext(ctx, `SELECT account_id,domain_dns_record_id FROM account_node_subscription_domain_binding WHERE id=?`, b.ID).Scan(&owner, &oldRecord); err != nil {
			return invalid("订阅定制不存在")
		}
		if owner != b.AccountID {
			return invalid("订阅定制不属于该账户")
		}
	} else {
		err := tx.QueryRowContext(ctx, `SELECT id,domain_dns_record_id FROM account_node_subscription_domain_binding WHERE account_id=? AND node_id=?`, b.AccountID, nodeID).Scan(&b.ID, &oldRecord)
		if err != nil && err != sql.ErrNoRows {
			return err
		}
	}
	if b.NewName != "" {
		id, err := createBindingRecord(ctx, tx, b.NewDomainID, b.NewName, serverID, ip)
		if err != nil {
			return err
		}
		b.RecordID = &id
	}
	if b.RecordID != nil {
		var typ, status, content string
		var scheduled bool
		var sid sql.NullInt64
		err := tx.QueryRowContext(ctx, `SELECT upper(type),status,content,coalesce(schedule_enabled,0),server_id FROM domain_dns_record WHERE id=?`, *b.RecordID).Scan(&typ, &status, &content, &scheduled, &sid)
		if err != nil || typ != "A" || status == "PENDING_DELETE" || scheduled {
			return invalid("请选择非时段计划的有效 A 记录")
		}
		if sid.Valid && sid.Int64 != serverID {
			return invalid("域名已绑定到其他服务器")
		}
		if !sid.Valid && content != ip {
			return invalid("未绑定域名的解析值必须等于部署位服务器 IP")
		}
		var refs int
		err = tx.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM node_server WHERE access_host_id=? AND id<>?) + (SELECT count(*) FROM account_node_subscription_domain_binding x LEFT JOIN account_node_subscription_placement p ON p.binding_id=x.id WHERE x.domain_dns_record_id=? AND x.id<>? AND (x.node_id<>? OR coalesce(p.node_server_id,0)<>?))`, *b.RecordID, b.PlacementID, *b.RecordID, b.ID, nodeID, b.PlacementID).Scan(&refs)
		if err != nil {
			return err
		}
		if refs > 0 {
			return invalid("域名已被不同节点或部署位使用")
		}
		if _, err := tx.ExecContext(ctx, `UPDATE domain_dns_record SET server_id=? WHERE id=?`, serverID, *b.RecordID); err != nil {
			return err
		}
	}
	now := FormatTime(nowFunc())
	if b.ID == 0 {
		res, err := tx.ExecContext(ctx, `INSERT INTO account_node_subscription_domain_binding(account_id,node_id,domain_dns_record_id,enabled,remark,create_time,update_time) VALUES(?,?,?,?,?,?,?)`, b.AccountID, nodeID, b.RecordID, b.Enabled, nz(b.Remark), now, now)
		if err != nil {
			return err
		}
		b.ID, err = res.LastInsertId()
		if err != nil {
			return err
		}
	} else {
		_, err = tx.ExecContext(ctx, `UPDATE account_node_subscription_domain_binding SET node_id=?,domain_dns_record_id=?,enabled=?,remark=?,update_time=? WHERE id=?`, nodeID, b.RecordID, b.Enabled, nz(b.Remark), now, b.ID)
		if isUnique(err) {
			return fmt.Errorf("%w：该节点已有订阅定制", ErrConflict)
		}
		if err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM account_node_subscription_placement WHERE binding_id=?`, b.ID); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO account_node_subscription_placement(binding_id,node_server_id) VALUES(?,?)`, b.ID, b.PlacementID); err != nil {
		return err
	}
	if oldRecord.Valid && (b.RecordID == nil || oldRecord.Int64 != *b.RecordID) {
		if err := releaseBindingRecord(ctx, tx, &oldRecord.Int64); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func DeleteAccountBinding(ctx context.Context, db *sql.DB, accountID, id int64) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var old sql.NullInt64
	if err := tx.QueryRowContext(ctx, `SELECT domain_dns_record_id FROM account_node_subscription_domain_binding WHERE id=? AND account_id=?`, id, accountID).Scan(&old); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM account_node_subscription_domain_binding WHERE id=?`, id); err != nil {
		return err
	}
	if old.Valid {
		if err := releaseBindingRecord(ctx, tx, &old.Int64); err != nil {
			return err
		}
	}
	return tx.Commit()
}

func cleanUnauthorizedBindings(ctx context.Context, q dbtx, accountID int64) error {
	rows, err := q.QueryContext(ctx, `SELECT b.id,b.domain_dns_record_id FROM account_node_subscription_domain_binding b WHERE (?=0 OR b.account_id=?) AND NOT EXISTS(SELECT 1 FROM account_tag at JOIN node_tag nt ON nt.tag_id=at.tag_id WHERE at.account_id=b.account_id AND nt.node_id=b.node_id)`, accountID, accountID)
	if err != nil {
		return err
	}
	type stale struct {
		id     int64
		record sql.NullInt64
	}
	var all []stale
	for rows.Next() {
		var s stale
		if err := rows.Scan(&s.id, &s.record); err != nil {
			rows.Close()
			return err
		}
		all = append(all, s)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, s := range all {
		if _, err := q.ExecContext(ctx, `DELETE FROM account_node_subscription_domain_binding WHERE id=?`, s.id); err != nil {
			return err
		}
		if s.record.Valid {
			if err := releaseBindingRecord(ctx, q, &s.record.Int64); err != nil {
				return err
			}
		}
	}
	return nil
}
