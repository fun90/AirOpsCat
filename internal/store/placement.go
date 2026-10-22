package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net"
	"strings"
)

// serverMatches 判断服务器是否适合承载节点（不看服务器是否禁用）：
// 节点地区非空且与服务器地区区分大小写相等；代理节点只能落在非外部的代理服务器，落地节点可落在任何服务器。
func serverMatches(nodeRegion string, nodeType int, srvRegion sql.NullString, srvType int, srvExternal bool) bool {
	nr := strings.TrimSpace(nodeRegion)
	if nr == "" || !srvRegion.Valid || strings.TrimSpace(srvRegion.String) != nr {
		return false
	}
	return serverTypeAllowed(nodeType, srvType, srvExternal)
}

// serverTypeAllowed 是手动添加也必须遵守的类型规则：代理节点只能落在非外部的代理服务器。
func serverTypeAllowed(nodeType, srvType int, srvExternal bool) bool {
	return nodeType == NodeLanding || (!srvExternal && srvType != NodeLanding)
}

type serverRow struct {
	id       int64
	name     string
	region   sql.NullString
	typ      int
	external bool
	disabled bool
}

func allServers(ctx context.Context, q dbtx) ([]serverRow, error) {
	rows, err := q.QueryContext(ctx, `SELECT id, coalesce(name, ip), region, coalesce(server_type,0), external_server, disabled FROM server ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []serverRow
	for rows.Next() {
		var s serverRow
		if err := rows.Scan(&s.id, &s.name, &s.region, &s.typ, &s.external, &s.disabled); err != nil {
			return nil, err
		}
		list = append(list, s)
	}
	return list, rows.Err()
}

// removeOrMarkDeleted 曾部署过的部署位标记待删除，否则直接删除。
func removeOrMarkDeleted(ctx context.Context, q dbtx, p *Placement) error {
	if p.Deployed == PendingDelete {
		return nil
	}
	var deployedBefore int
	if err := q.QueryRowContext(ctx, `SELECT p.deployed IN (1, 2) OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = p.id)
		FROM node_server p WHERE p.id = ?`, p.ID).Scan(&deployedBefore); err != nil {
		return err
	}
	if deployedBefore == 1 {
		_, err := q.ExecContext(ctx, `UPDATE node_server SET deployed = 2, update_time = ? WHERE id = ?`, FormatTime(nowFunc()), p.ID)
		return err
	}
	_, err := q.ExecContext(ctx, `DELETE FROM node_server WHERE id = ?`, p.ID) // 订阅定制引用、部署记录随外键级联删除
	return err
}

// initPlacements 只在新建节点时按「地区 + 类型」创建初始部署位；之后部署位由管理员手动增删。
// 端口冲突直接报错。
func initPlacements(ctx context.Context, q dbtx, n *Node) error {
	servers, err := allServers(ctx, q)
	if err != nil {
		return err
	}
	priority := 0
	for _, s := range servers {
		if s.disabled || !serverMatches(n.Region, n.Type, s.region, s.typ, s.external) {
			continue
		}
		if other, err := portConflict(ctx, q, s.id, n.Port, n.ID); err != nil {
			return err
		} else if other != "" {
			return fmt.Errorf("%w：端口 %d 在服务器 %s 上已被节点 %s 占用", ErrConflict, n.Port, s.name, other)
		}
		priority++
		if err := insertPlacement(ctx, q, n, s.id, priority); err != nil {
			return err
		}
	}
	return nil
}

func insertPlacement(ctx context.Context, q dbtx, n *Node, serverID int64, priority int) error {
	exposed := n.Type != NodeLanding // 落地节点是代理节点的出站，默认不进订阅
	now := FormatTime(nowFunc())
	_, err := q.ExecContext(ctx, `INSERT INTO node_server (node_id, server_id, exposed, priority, deployed, create_time, update_time)
		VALUES (?, ?, ?, ?, 0, ?, ?)`, n.ID, serverID, exposed, priority, now, now)
	return err
}

// PlacementServerOption 是节点可手动添加部署位的服务器，不限地区。
type PlacementServerOption struct {
	ID               int64
	Name, IP, Region string
}

// PlacementServerOptions 返回未禁用、类型允许且尚无有效部署位的服务器。
func PlacementServerOptions(ctx context.Context, db *sql.DB, nodeID int64) ([]PlacementServerOption, error) {
	var nodeType int
	if err := db.QueryRowContext(ctx, `SELECT coalesce(type,0) FROM node WHERE id = ?`, nodeID).Scan(&nodeType); err != nil {
		return nil, err
	}
	rows, err := db.QueryContext(ctx, `SELECT s.id, coalesce(nullif(s.name,''), s.ip), s.ip, coalesce(s.region,''), coalesce(s.server_type,0), s.external_server
		FROM server s WHERE s.disabled = 0 AND NOT EXISTS (SELECT 1 FROM node_server ns WHERE ns.server_id = s.id AND ns.node_id = ? AND ns.deployed <> 2)
		ORDER BY s.region, s.name, s.id`, nodeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []PlacementServerOption
	for rows.Next() {
		var o PlacementServerOption
		var typ int
		var external bool
		if err := rows.Scan(&o.ID, &o.Name, &o.IP, &o.Region, &typ, &external); err != nil {
			return nil, err
		}
		if serverTypeAllowed(nodeType, typ, external) {
			out = append(out, o)
		}
	}
	return out, rows.Err()
}

// addPlacement 手动为节点添加部署位，不限地区；待删除的同服务器部署位恢复为待部署。
func addPlacement(ctx context.Context, tx dbtx, n *Node, serverID int64) error {
	var name string
	var typ int
	var external, disabled bool
	if err := tx.QueryRowContext(ctx, `SELECT coalesce(nullif(name,''), ip), coalesce(server_type,0), external_server, disabled FROM server WHERE id = ?`, serverID).
		Scan(&name, &typ, &external, &disabled); err != nil {
		return invalid("请选择有效的服务器")
	}
	if disabled {
		return invalid("服务器 %s 已禁用", name)
	}
	if !serverTypeAllowed(n.Type, typ, external) {
		return invalid("代理节点只能部署在非外部的代理服务器上")
	}
	if other, err := portConflict(ctx, tx, serverID, n.Port, n.ID); err != nil {
		return err
	} else if other != "" {
		return fmt.Errorf("%w：端口 %d 在服务器 %s 上已被节点 %s 占用", ErrConflict, n.Port, name, other)
	}
	var id int64
	var deployed int
	err := tx.QueryRowContext(ctx, `SELECT id, deployed FROM node_server WHERE node_id = ? AND server_id = ?`, n.ID, serverID).Scan(&id, &deployed)
	switch {
	case err == nil && deployed != PendingDelete:
		return fmt.Errorf("%w：节点已部署在服务器 %s 上", ErrConflict, name)
	case err == nil:
		if _, err := tx.ExecContext(ctx, `UPDATE node_server SET deployed = 0, update_time = ? WHERE id = ?`, FormatTime(nowFunc()), id); err != nil {
			return err
		}
	case errors.Is(err, sql.ErrNoRows):
		var maxPriority int
		if err := tx.QueryRowContext(ctx, `SELECT coalesce(max(priority),0) FROM node_server WHERE node_id = ?`, n.ID).Scan(&maxPriority); err != nil {
			return err
		}
		if err := insertPlacement(ctx, tx, n, serverID, maxPriority+1); err != nil {
			return err
		}
	default:
		return err
	}
	return nil
}

// deletePlacement 删除部署位，并把它的访问域名和订阅定制转移到同节点另一个部署过的部署位。
// 节点只剩这一个部署位、域名被其他节点共用、DNS 自动操作未完成时拒绝。
func deletePlacement(ctx context.Context, tx dbtx, n *Node, placementID, targetID int64) error {
	pls, err := Placements(ctx, tx, n.ID)
	if err != nil {
		return err
	}
	var p, target *Placement
	active := 0
	for _, x := range pls {
		if x.Deployed != PendingDelete {
			active++
		}
		if x.ID == placementID {
			p = x
		}
		if x.ID == targetID && targetID != placementID {
			target = x
		}
	}
	if p == nil || p.Deployed == PendingDelete {
		return invalid("要删除的部署位不存在或已待删除")
	}
	if active <= 1 {
		return invalid("这是节点最后一个部署位，不能删除；如需下线请删除节点")
	}

	// 需转移的域名：部署位访问域名 + 指向该部署位的订阅定制域名
	records := map[int64]bool{}
	if p.AccessHostID != nil {
		records[*p.AccessHostID] = true
	}
	var bindings int
	rows, err := tx.QueryContext(ctx, `SELECT b.domain_dns_record_id FROM account_node_subscription_placement bp
		JOIN account_node_subscription_domain_binding b ON b.id = bp.binding_id WHERE bp.node_server_id = ?`, p.ID)
	if err != nil {
		return err
	}
	for rows.Next() {
		var rid sql.NullInt64
		if err := rows.Scan(&rid); err != nil {
			rows.Close()
			return err
		}
		bindings++
		if rid.Valid {
			records[rid.Int64] = true
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	if len(records) > 0 || bindings > 0 {
		if target == nil {
			return invalid("该部署位绑定了域名或订阅定制，请选择要转移到的部署位")
		}
		// 待部署但曾部署过的部署位仍在服务器上运行，可以承接域名
		if target.Deployed == PendingDelete || !target.Running {
			return invalid("目标部署位 %s 从未部署，请先完成部署再转移域名", target.ServerName)
		}
	}
	now := FormatTime(nowFunc())
	for rid := range records {
		if err := transferRecord(ctx, tx, rid, p, target, now); err != nil {
			return err
		}
	}
	if p.AccessHostID != nil {
		// 目标已有访问域名时保留目标的；转移的记录仍改绑到目标服务器，解析不中断
		if _, err := tx.ExecContext(ctx, `UPDATE node_server SET access_host_id = ?, update_time = ? WHERE id = ? AND access_host_id IS NULL`, *p.AccessHostID, now, target.ID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE node_server SET access_host_id = NULL WHERE id = ?`, p.ID); err != nil {
			return err
		}
	}
	if bindings > 0 {
		if _, err := tx.ExecContext(ctx, `UPDATE account_node_subscription_placement SET node_server_id = ? WHERE node_server_id = ?`, target.ID, p.ID); err != nil {
			return err
		}
	}
	return removeOrMarkDeleted(ctx, tx, p)
}

// transferRecord 把 A 记录改绑到目标服务器并改解析值，推送 DNS 后生效。
func transferRecord(ctx context.Context, tx dbtx, rid int64, p, target *Placement, now string) error {
	var full, status, typ string
	if err := tx.QueryRowContext(ctx, `SELECT coalesce(full_name,name), status, upper(type) FROM domain_dns_record WHERE id = ?`, rid).
		Scan(&full, &status, &typ); err != nil {
		return err
	}
	if ip := net.ParseIP(target.ServerIP); typ != "A" || ip == nil || ip.To4() == nil {
		return invalid("域名 %s 只能转移到 IPv4 的 A 记录目标", full)
	}
	var busy int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM dns_auto_operation WHERE record_id = ?`, rid).Scan(&busy); err != nil {
		return err
	}
	if busy > 0 {
		return fmt.Errorf("%w：域名 %s 的 DNS 自动切换尚未完成，请稍后重试", ErrConflict, full)
	}
	if status == DNSPendingDelete {
		return invalid("域名 %s 是待删除记录，请先在 DNS 中手动调整", full)
	}
	rows, err := tx.QueryContext(ctx, `SELECT DISTINCT n.name FROM node_server ns JOIN node n ON n.id = ns.node_id WHERE ns.access_host_id = ? AND ns.node_id <> ?
		UNION SELECT DISTINCT n.name FROM account_node_subscription_domain_binding b JOIN node n ON n.id = b.node_id
		WHERE b.domain_dns_record_id = ? AND NOT EXISTS (SELECT 1 FROM account_node_subscription_placement bp WHERE bp.binding_id = b.id AND bp.node_server_id = ?)`,
		rid, p.NodeID, rid, p.ID)
	if err != nil {
		return err
	}
	var users []string
	for rows.Next() {
		var name string
		if err := rows.Scan(&name); err != nil {
			rows.Close()
			return err
		}
		users = append(users, name)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	if len(users) > 0 {
		return fmt.Errorf("%w：域名 %s 还被 %s 使用，无法转移", ErrConflict, full, strings.Join(users, "、"))
	}
	if status == DNSSynced || status == DNSSyncFailed {
		status = DNSPendingUpdate
	}
	_, err = tx.ExecContext(ctx, `UPDATE domain_dns_record SET server_id = ?, content = ?, failover_home_content = NULL, status = ?, update_time = ? WHERE id = ?`,
		target.ServerID, target.ServerIP, status, now, rid)
	return err
}

// PlacementDomains 返回各部署位删除时需要转移的域名与订阅定制说明，供删除确认展示。
func PlacementDomains(ctx context.Context, db *sql.DB, nodeID int64) (map[int64][]string, error) {
	rows, err := db.QueryContext(ctx, `SELECT ns.id, '访问域名 ' || coalesce(r.full_name, r.name) FROM node_server ns JOIN domain_dns_record r ON r.id = ns.access_host_id WHERE ns.node_id = ?
		UNION ALL SELECT bp.node_server_id, '订阅定制 ' || coalesce(a.account_no, '#' || b.account_id) || coalesce('（' || r.full_name || '）', '')
		FROM account_node_subscription_placement bp JOIN account_node_subscription_domain_binding b ON b.id = bp.binding_id
		LEFT JOIN account a ON a.id = b.account_id LEFT JOIN domain_dns_record r ON r.id = b.domain_dns_record_id
		JOIN node_server ns ON ns.id = bp.node_server_id WHERE ns.node_id = ?`, nodeID, nodeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64][]string{}
	for rows.Next() {
		var id int64
		var label string
		if err := rows.Scan(&id, &label); err != nil {
			return nil, err
		}
		out[id] = append(out[id], label)
	}
	return out, rows.Err()
}
