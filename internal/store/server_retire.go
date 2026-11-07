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
		// 禁用保留的服务器也不能留下可继续完成的旧迁移。
		if _, err := tx.ExecContext(ctx, `DELETE FROM server_migration WHERE source_server_id=? OR target_server_id=?`, id, id); err != nil {
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
		var migrationID int64
		if err := tx.QueryRowContext(ctx, `INSERT INTO server_migration(source_server_id,target_server_id,create_time) VALUES(?,?,?)
			ON CONFLICT(source_server_id,target_server_id) DO UPDATE SET create_time=create_time RETURNING id`, srcID, dstID, FormatTime(nowFunc())).Scan(&migrationID); err != nil {
			return err
		}
		for _, p := range pls {
			if p.deployed == PendingDelete {
				continue
			}
			active++
			var targetID int64
			err := tx.QueryRowContext(ctx, `SELECT id FROM node_server WHERE node_id = ? AND server_id = ? AND deployed <> 2`, p.nodeID, dstID).Scan(&targetID)
			if err != nil && !errors.Is(err, sql.ErrNoRows) {
				return err
			}
			created := 0
			if err == nil {
				if _, err := tx.ExecContext(ctx, `INSERT INTO server_migration_placement(migration_id,source_placement_id,target_placement_id,created) VALUES(?,?,?,0)
					ON CONFLICT(migration_id,source_placement_id) DO NOTHING`, migrationID, p.id, targetID); err != nil {
					return err
				}
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
			if err := tx.QueryRowContext(ctx, `SELECT id FROM node_server WHERE node_id=? AND server_id=? AND deployed<>2`, n.ID, dstID).Scan(&targetID); err != nil {
				return err
			}
			created = 1
			if _, err := tx.ExecContext(ctx, `INSERT INTO server_migration_placement(migration_id,source_placement_id,target_placement_id,created) VALUES(?,?,?,?)
				ON CONFLICT(migration_id,source_placement_id) DO NOTHING`, migrationID, p.id, targetID, created); err != nil {
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
		var migrationID int64
		if err := tx.QueryRowContext(ctx, `SELECT id FROM server_migration WHERE source_server_id=? AND target_server_id=?`, srcID, dstID).Scan(&migrationID); errors.Is(err, sql.ErrNoRows) {
			return invalid("请先添加迁移部署位")
		} else if err != nil {
			return err
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
			err := tx.QueryRowContext(ctx, `SELECT b.id, b.deployed = 1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = b.id)
				FROM server_migration_placement mp JOIN node_server b ON b.id=mp.target_placement_id
				WHERE mp.migration_id=? AND mp.source_placement_id=? AND b.node_id=? AND b.server_id=? AND b.deployed<>2`, migrationID, p.id, p.nodeID, dstID).Scan(&target, &running)
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
		if _, err := tx.ExecContext(ctx, `DELETE FROM server_migration WHERE id=?`, migrationID); err != nil {
			return err
		}
		return writeLog(ctx, tx, "SERVER", "INFO", &srcID, srcName, fmt.Sprintf("整机迁移完成：%d 个部署位已转移到 %s，本服务器部署位待删除", moved, dstName),
			fmt.Sprintf("action=migrate-finish target=%d", dstID))
	})
	if err != nil {
		return 0, err
	}
	return moved, nil
}

// CancelServerMigration 只移除本次迁移新建的目标部署位，原有目标部署位保持不变。
func CancelServerMigration(ctx context.Context, db *sql.DB, srcID, dstID int64) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		var id int64
		if err := tx.QueryRowContext(ctx, `SELECT id FROM server_migration WHERE source_server_id=? AND target_server_id=?`, srcID, dstID).Scan(&id); errors.Is(err, sql.ErrNoRows) {
			return invalid("迁移记录不存在")
		} else if err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, `SELECT target_placement_id FROM server_migration_placement WHERE migration_id=? AND created=1`, id)
		if err != nil {
			return err
		}
		var targets []int64
		for rows.Next() {
			var pid int64
			if err := rows.Scan(&pid); err != nil {
				rows.Close()
				return err
			}
			targets = append(targets, pid)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, pid := range targets {
			var other int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM server_migration_placement WHERE target_placement_id=? AND migration_id<>?`, pid, id).Scan(&other); err != nil {
				return err
			}
			if other > 0 {
				return fmt.Errorf("%w：目标部署位 #%d 同时属于其他迁移，请先处理该迁移", ErrConflict, pid)
			}
			var nodeID int64
			if err := tx.QueryRowContext(ctx, `SELECT node_id FROM node_server WHERE id=?`, pid).Scan(&nodeID); errors.Is(err, sql.ErrNoRows) {
				continue
			} else if err != nil {
				return err
			}
			n, err := loadNode(ctx, tx, nodeID)
			if err != nil {
				return err
			}
			prev, err := effectiveServer(ctx, tx, nodeID)
			if err != nil {
				return err
			}
			// 域名及订阅定制可能在迁移期间新增；按正常部署位删除校验，失败时整体回滚。
			if err := deletePlacement(ctx, tx, n, pid, 0); err != nil {
				// 只有目标是唯一剩余部署位时，允许删除没有绑定的部署位。
				if !errors.Is(err, ErrInvalid) || !strings.Contains(err.Error(), "最后一个部署位") {
					return err
				}
				bound, e := placementBound(ctx, tx, pid)
				if e != nil {
					return e
				}
				if bound {
					return err
				}
				p, e := Placements(ctx, tx, nodeID)
				if e != nil {
					return e
				}
				for _, x := range p {
					if x.ID == pid {
						if e := removeOrMarkDeleted(ctx, tx, x); e != nil {
							return e
						}
					}
				}
			}
			if n.Type == NodeLanding {
				cur, err := effectiveServer(ctx, tx, nodeID)
				if err != nil {
					return err
				}
				if prev != cur {
					if err := markReferencingPending(ctx, tx, nodeID); err != nil {
						return err
					}
				}
			}
		}
		if _, err := tx.ExecContext(ctx, `DELETE FROM server_migration WHERE id=?`, id); err != nil {
			return err
		}
		return writeLog(ctx, tx, "SERVER", "INFO", &srcID, "", fmt.Sprintf("已取消迁移到服务器 #%d", dstID), "action=migrate-cancel")
	})
}

// MigrationTarget 是存在迁移记录的目标；源服务器全部非待删除部署位必须仍有对应映射。
// Pending 为其中尚未部署运行的部署位数，为 0 时可完成迁移。
type MigrationTarget struct {
	ID       int64
	Name, IP string
	Pending  int
}

// MigrationTargets 返回源服务器已有迁移记录的目标；禁用目标仍显示以便取消迁移，完成动作会拒绝。
func MigrationTargets(ctx context.Context, db *sql.DB, srcID int64) ([]MigrationTarget, error) {
	rows, err := db.QueryContext(ctx, `SELECT s.id, coalesce(nullif(s.name,''), s.ip), s.ip,
		(SELECT count(*) FROM server_migration_placement mp JOIN node_server b ON b.id=mp.target_placement_id
		 WHERE mp.migration_id=m.id AND b.deployed<>2 AND (s.disabled=1 OR NOT (b.deployed=1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id=b.id))))
		 + (SELECT count(*) FROM node_server a WHERE a.server_id=m.source_server_id AND a.deployed<>2
		 AND NOT EXISTS (SELECT 1 FROM server_migration_placement mp JOIN node_server b ON b.id=mp.target_placement_id
		 WHERE mp.migration_id=m.id AND mp.source_placement_id=a.id AND b.deployed<>2))
		FROM server_migration m JOIN server s ON s.id=m.target_server_id
		WHERE m.source_server_id=? ORDER BY s.name,s.id`, srcID)
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
