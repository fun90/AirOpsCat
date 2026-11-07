package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// serverPlacementRow 是报废、迁移时读取的服务器部署位。
type serverPlacementRow struct {
	id, nodeID int64
	nodeName   string
	nodeType   int
	deployed   int
	priority   int
	exposed    bool
}

func serverPlacementRows(ctx context.Context, q dbtx, serverID int64) ([]serverPlacementRow, error) {
	rows, err := q.QueryContext(ctx, `SELECT ns.id, ns.node_id, coalesce(n.name,''), coalesce(n.type,0), ns.deployed, coalesce(ns.priority,0), coalesce(ns.exposed,1) <> 0
		FROM node_server ns JOIN node n ON n.id = ns.node_id WHERE ns.server_id = ? ORDER BY n.name, ns.id`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []serverPlacementRow
	for rows.Next() {
		var p serverPlacementRow
		if err := rows.Scan(&p.id, &p.nodeID, &p.nodeName, &p.nodeType, &p.deployed, &p.priority, &p.exposed); err != nil {
			return nil, err
		}
		list = append(list, p)
	}
	return list, rows.Err()
}

func serverLabel(ctx context.Context, q dbtx, id int64) (name, ip string, disabled bool, err error) {
	err = q.QueryRowContext(ctx, `SELECT coalesce(nullif(name,''), ip), ip, disabled FROM server WHERE id = ?`, id).Scan(&name, &ip, &disabled)
	return
}

// blockedError 汇总需人工处理的项目；整体回滚。
func blockedError(items []string) error {
	return fmt.Errorf("%w：以下项目需先处理：%s", ErrConflict, strings.Join(items, "；"))
}

// userError 判断是否为可提示给用户的业务错误（收集后整体回滚），其余错误直接返回。
func userError(err error) bool { return errors.Is(err, ErrInvalid) || errors.Is(err, ErrConflict) }

// trimErr 去掉业务错误前缀，便于拼接成列表。
func trimErr(err error) string {
	s := err.Error()
	for _, p := range []string{ErrInvalid.Error() + "：", ErrConflict.Error() + "："} {
		s = strings.TrimPrefix(s, p)
	}
	return s
}

// placementBound 判断部署位是否绑定了访问域名或订阅定制。
func placementBound(ctx context.Context, q dbtx, placementID int64) (bool, error) {
	var bound bool
	err := q.QueryRowContext(ctx, `SELECT access_host_id IS NOT NULL OR EXISTS (SELECT 1 FROM account_node_subscription_placement bp WHERE bp.node_server_id = node_server.id)
		FROM node_server WHERE id = ?`, placementID).Scan(&bound)
	return bound, err
}

// RetireResult 是服务器报废的结果。
type RetireResult struct {
	Removed, Transferred, DeletedNodes int
	ServerDeleted                      bool
}

// RetireServer 报废服务器：不连接服务器，在一个事务内物理删除其全部部署位（订阅定制引用、部署记录随外键级联删除），
// 远端配置不清理。绑定访问域名或订阅定制的部署位先转移到同节点优先级最高的其他可用部署位（DNS 记录待同步）；
// 无处承接、域名正切换解析到本服务器、或唯一部署位在此的被引用落地节点，整体拒绝并列出。
// 原为待删除且已无剩余部署位、未被引用的节点一并删除；deleteServer 为 true 时删除服务器记录，否则保留为禁用。
func RetireServer(ctx context.Context, db *sql.DB, id int64, deleteServer bool) (*RetireResult, error) {
	res := &RetireResult{ServerDeleted: deleteServer}
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		name, ip, _, err := serverLabel(ctx, tx, id)
		if errors.Is(err, sql.ErrNoRows) {
			return sql.ErrNoRows
		}
		if err != nil {
			return err
		}
		pls, err := serverPlacementRows(ctx, tx, id)
		if err != nil {
			return err
		}
		var items []string
		landings := map[int64]string{}
		for _, p := range pls {
			if p.deployed == PendingDelete {
				continue
			}
			if p.nodeType == NodeLanding {
				landings[p.nodeID] = p.nodeName
			}
			bound, err := placementBound(ctx, tx, p.id)
			if err != nil {
				return err
			}
			if !bound {
				continue
			}
			n, err := loadNode(ctx, tx, p.nodeID)
			if err != nil {
				return err
			}
			all, err := Placements(ctx, tx, p.nodeID)
			if err != nil {
				return err
			}
			var target *Placement
			for _, x := range all { // 已按优先级排序
				if x.ServerID != id && x.Deployed != PendingDelete && x.Running && !x.Disabled {
					target = x
					break
				}
			}
			if target == nil {
				items = append(items, fmt.Sprintf("节点 %s 的访问域名或订阅定制没有其他可用部署位承接", p.nodeName))
				continue
			}
			if err := deletePlacement(ctx, tx, n, p.id, target.ID); err != nil {
				if !userError(err) {
					return err
				}
				items = append(items, fmt.Sprintf("节点 %s：%s", p.nodeName, trimErr(err)))
				continue
			}
			res.Transferred++
		}
		// 其他服务器的域名被自动或手动切换到本服务器，删除后解析会落空
		rows, err := tx.QueryContext(ctx, `SELECT coalesce(r.full_name, r.name), EXISTS (SELECT 1 FROM dns_auto_operation o WHERE o.record_id = r.id)
			FROM domain_dns_record r WHERE upper(r.type) = 'A' AND r.status <> 'PENDING_DELETE'
			AND ((r.content = ? AND coalesce(r.failover_home_content,'') NOT IN ('', r.content))
				OR EXISTS (SELECT 1 FROM dns_auto_operation o WHERE o.record_id = r.id AND ? IN (o.old_content, o.target_content)))
			ORDER BY r.id`, ip, ip)
		if err != nil {
			return err
		}
		for rows.Next() {
			var full string
			var busy bool
			if err := rows.Scan(&full, &busy); err != nil {
				rows.Close()
				return err
			}
			if busy {
				items = append(items, fmt.Sprintf("域名 %s 的 DNS 自动切换尚未完成", full))
			} else {
				items = append(items, fmt.Sprintf("域名 %s 当前切换解析到本服务器，请先回切", full))
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for lid, lname := range landings {
			var refs int
			var rest bool
			if err := tx.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM node WHERE out_id = ?) + (SELECT count(*) FROM route_rule WHERE outbound_node_id = ?),
				EXISTS (SELECT 1 FROM node_server WHERE node_id = ? AND server_id <> ? AND deployed <> 2)`, lid, lid, lid, id).Scan(&refs, &rest); err != nil {
				return err
			}
			if refs > 0 && !rest {
				items = append(items, fmt.Sprintf("落地节点 %s 被引用为出站，且本服务器是它唯一的部署位", lname))
			}
		}
		if len(items) > 0 {
			return blockedError(items)
		}

		r, err := tx.ExecContext(ctx, `DELETE FROM node_server WHERE server_id = ?`, id) // 订阅定制引用、部署记录随外键级联删除
		if err != nil {
			return err
		}
		removed, _ := r.RowsAffected()
		res.Removed = int(removed)
		for lid := range landings {
			if err := markReferencingPending(ctx, tx, lid); err != nil {
				return err
			}
		}
		// 与 recordDeployment 一致：原为待删除、已无剩余部署位且未被引用的节点一并删除
		for _, p := range pls {
			if p.deployed != PendingDelete {
				continue
			}
			r, err := tx.ExecContext(ctx, `DELETE FROM node WHERE id = ?
				AND NOT EXISTS (SELECT 1 FROM node_server WHERE node_id = node.id)
				AND NOT EXISTS (SELECT 1 FROM node o WHERE o.out_id = node.id)
				AND NOT EXISTS (SELECT 1 FROM route_rule r WHERE r.outbound_node_id = node.id)`, p.nodeID)
			if err != nil {
				return err
			}
			n, _ := r.RowsAffected()
			res.DeletedNodes += int(n)
		}
		action := "保留为禁用"
		if deleteServer {
			action = "已删除服务器记录"
			if _, err := tx.ExecContext(ctx, `DELETE FROM server WHERE id = ?`, id); err != nil {
				return err
			}
		} else if _, err := tx.ExecContext(ctx, `UPDATE server SET disabled = 1, update_time = ? WHERE id = ?`, FormatTime(nowFunc()), id); err != nil {
			return err
		}
		msg := fmt.Sprintf("服务器报废：强制移除 %d 个部署位，转移域名 %d 处，清理待删除节点 %d 个，%s；未连接服务器，远端配置未清理",
			res.Removed, res.Transferred, res.DeletedNodes, action)
		return writeLog(ctx, tx, "SERVER", "WARN", &id, name, msg, "action=retire ip="+ip)
	})
	if err != nil {
		return nil, err
	}
	return res, nil
}

// MigrateServerPlacements 整机迁移第一步：为源服务器上全部非待删除部署位的节点在目标服务器添加部署位，
// 继承优先级与是否显示到订阅；节点已在目标上的跳过。任一端口冲突或类型不符整体回滚并列出。返回新增数。
func MigrateServerPlacements(ctx context.Context, db *sql.DB, srcID, dstID int64) (int, error) {
	if srcID == dstID {
		return 0, invalid("目标服务器不能是源服务器")
	}
	added := 0
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		srcName, _, _, err := serverLabel(ctx, tx, srcID)
		if errors.Is(err, sql.ErrNoRows) {
			return sql.ErrNoRows
		}
		if err != nil {
			return err
		}
		dstName, _, _, err := serverLabel(ctx, tx, dstID)
		if errors.Is(err, sql.ErrNoRows) {
			return invalid("请选择有效的目标服务器")
		}
		if err != nil {
			return err
		}
		pls, err := serverPlacementRows(ctx, tx, srcID)
		if err != nil {
			return err
		}
		var items []string
		active := 0
		for _, p := range pls {
			if p.deployed == PendingDelete {
				continue
			}
			active++
			var exists bool
			if err := tx.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM node_server WHERE node_id = ? AND server_id = ? AND deployed <> 2)`, p.nodeID, dstID).Scan(&exists); err != nil {
				return err
			}
			if exists {
				continue
			}
			n, err := loadNode(ctx, tx, p.nodeID)
			if err != nil {
				return err
			}
			prev, err := effectiveServer(ctx, tx, n.ID)
			if err != nil {
				return err
			}
			if err := addPlacement(ctx, tx, n, dstID); err != nil {
				if !userError(err) {
					return err
				}
				items = append(items, fmt.Sprintf("节点 %s：%s", n.Name, trimErr(err)))
				continue
			}
			if _, err := tx.ExecContext(ctx, `UPDATE node_server SET priority = ?, exposed = ? WHERE node_id = ? AND server_id = ? AND deployed <> 2`,
				p.priority, p.exposed && n.Type != NodeLanding, n.ID, dstID); err != nil {
				return err
			}
			added++
			if n.Type == NodeLanding {
				cur, err := effectiveServer(ctx, tx, n.ID)
				if err != nil {
					return err
				}
				if cur != prev {
					if err := markReferencingPending(ctx, tx, n.ID); err != nil {
						return err
					}
				}
			}
		}
		if active == 0 {
			return invalid("源服务器没有可迁移的部署位")
		}
		if len(items) > 0 {
			return blockedError(items)
		}
		if added == 0 {
			return nil
		}
		return writeLog(ctx, tx, "SERVER", "INFO", &srcID, srcName, fmt.Sprintf("整机迁移：已在 %s 上添加 %d 个部署位，待部署目标服务器后完成迁移", dstName, added),
			fmt.Sprintf("action=migrate target=%d", dstID))
	})
	if err != nil {
		return 0, err
	}
	return added, nil
}

// FinishServerMigration 整机迁移第二步：目标服务器上对应部署位均已部署运行后，把源部署位的访问域名、DNS 记录
// 与订阅定制引用转移过去，并把源部署位标记待删除（从未部署过的直接删除）。返回处理的部署位数。
func FinishServerMigration(ctx context.Context, db *sql.DB, srcID, dstID int64) (int, error) {
	if srcID == dstID {
		return 0, invalid("目标服务器不能是源服务器")
	}
	moved := 0
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		srcName, _, _, err := serverLabel(ctx, tx, srcID)
		if errors.Is(err, sql.ErrNoRows) {
			return sql.ErrNoRows
		}
		if err != nil {
			return err
		}
		dstName, _, dstDisabled, err := serverLabel(ctx, tx, dstID)
		if errors.Is(err, sql.ErrNoRows) {
			return invalid("请选择有效的目标服务器")
		}
		if err != nil {
			return err
		}
		if dstDisabled {
			return invalid("目标服务器 %s 已禁用", dstName)
		}
		pls, err := serverPlacementRows(ctx, tx, srcID)
		if err != nil {
			return err
		}
		type pair struct {
			src    serverPlacementRow
			target int64
		}
		var pairs []pair
		var items []string
		for _, p := range pls {
			if p.deployed == PendingDelete {
				continue
			}
			var target int64
			var running bool
			err := tx.QueryRowContext(ctx, `SELECT id, deployed = 1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = node_server.id)
				FROM node_server WHERE node_id = ? AND server_id = ? AND deployed <> 2`, p.nodeID, dstID).Scan(&target, &running)
			switch {
			case errors.Is(err, sql.ErrNoRows):
				items = append(items, fmt.Sprintf("节点 %s 在目标服务器上没有部署位", p.nodeName))
			case err != nil:
				return err
			case !running:
				items = append(items, fmt.Sprintf("节点 %s 在目标服务器上尚未部署运行", p.nodeName))
			default:
				pairs = append(pairs, pair{p, target})
			}
		}
		if len(items) > 0 {
			return blockedError(items)
		}
		if len(pairs) == 0 {
			return invalid("源服务器没有可迁移的部署位")
		}
		// 先全部转移再标记引用落地的代理节点待部署，避免影响后续转移的运行判定
		prevLanding := map[int64]int64{}
		for _, pr := range pairs {
			n, err := loadNode(ctx, tx, pr.src.nodeID)
			if err != nil {
				return err
			}
			if n.Type == NodeLanding {
				if prevLanding[n.ID], err = effectiveServer(ctx, tx, n.ID); err != nil {
					return err
				}
			}
			if err := deletePlacement(ctx, tx, n, pr.src.id, pr.target); err != nil {
				if !userError(err) {
					return err
				}
				items = append(items, fmt.Sprintf("节点 %s：%s", n.Name, trimErr(err)))
				continue
			}
			moved++
		}
		if len(items) > 0 {
			return blockedError(items)
		}
		for lid, prev := range prevLanding {
			cur, err := effectiveServer(ctx, tx, lid)
			if err != nil {
				return err
			}
			if cur != prev {
				if err := markReferencingPending(ctx, tx, lid); err != nil {
					return err
				}
			}
		}
		return writeLog(ctx, tx, "SERVER", "INFO", &srcID, srcName, fmt.Sprintf("整机迁移完成：%d 个部署位已转移到 %s，本服务器部署位待删除", moved, dstName),
			fmt.Sprintf("action=migrate-finish target=%d", dstID))
	})
	if err != nil {
		return 0, err
	}
	return moved, nil
}

// MigrationTarget 是可完成整机迁移的目标：源服务器全部非待删除部署位的节点在其上都有部署位。
// Pending 为其中尚未部署运行的部署位数，为 0 时可完成迁移。
type MigrationTarget struct {
	ID       int64
	Name, IP string
	Pending  int
}

// MigrationTargets 返回源服务器可完成迁移的目标服务器（未禁用）。
func MigrationTargets(ctx context.Context, db *sql.DB, srcID int64) ([]MigrationTarget, error) {
	rows, err := db.QueryContext(ctx, `SELECT s.id, coalesce(nullif(s.name,''), s.ip), s.ip,
		(SELECT count(*) FROM node_server b WHERE b.server_id = s.id AND b.deployed <> 2
			AND b.node_id IN (SELECT a.node_id FROM node_server a WHERE a.server_id = ? AND a.deployed <> 2)
			AND NOT (b.deployed = 1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = b.id)))
		FROM server s WHERE s.id <> ? AND s.disabled = 0
		AND EXISTS (SELECT 1 FROM node_server a WHERE a.server_id = ? AND a.deployed <> 2)
		AND NOT EXISTS (SELECT 1 FROM node_server a WHERE a.server_id = ? AND a.deployed <> 2
			AND NOT EXISTS (SELECT 1 FROM node_server b WHERE b.server_id = s.id AND b.node_id = a.node_id AND b.deployed <> 2))
		ORDER BY s.name, s.id`, srcID, srcID, srcID, srcID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []MigrationTarget
	for rows.Next() {
		var m MigrationTarget
		if err := rows.Scan(&m.ID, &m.Name, &m.IP, &m.Pending); err != nil {
			return nil, err
		}
		out = append(out, m)
	}
	return out, rows.Err()
}
