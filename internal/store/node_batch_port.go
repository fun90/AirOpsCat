package store

import (
	"context"
	"database/sql"
	"slices"
)

// ReallocateNodePorts 为所选代理节点在同一个 immediate 写事务内重新分配全局未用端口。
// 可选把旧端口写入各部署位所在服务器的防刷封禁表。
func ReallocateNodePorts(ctx context.Context, db *sql.DB, ids []int64, blockOld bool) (*PortRotateResult, error) {
	ids = normalizeTagIDs(ids)
	if len(ids) == 0 {
		return nil, invalid("请选择节点")
	}
	res := &PortRotateResult{}
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		type item struct {
			id   int64
			name string
			old  int
		}
		var items []item
		reserved := map[int]bool{}
		for _, id := range ids {
			n, err := loadNode(ctx, tx, id)
			if err != nil {
				return err
			}
			if n == nil {
				return invalid("所选节点不存在")
			}
			if n.Type != NodeProxy {
				continue
			}
			items = append(items, item{id, n.Name, n.Port})
			reserved[n.Port] = true
		}
		if len(items) == 0 {
			return invalid("所选节点中没有代理节点")
		}
		servers := map[int64]bool{}
		now := FormatTime(nowFunc())
		for _, it := range items {
			if blockOld {
				rows, err := tx.QueryContext(ctx, `SELECT server_id FROM node_server WHERE node_id=?`, it.id)
				if err != nil {
					return err
				}
				var sids []int64
				for rows.Next() {
					var sid int64
					if err := rows.Scan(&sid); err != nil {
						rows.Close()
						return err
					}
					sids = append(sids, sid)
				}
				rows.Close()
				if err := rows.Err(); err != nil {
					return err
				}
				for _, sid := range sids {
					// 同机其它节点仍在使用旧端口时不能封禁它。
					var inUse bool
					if err := tx.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM node_server ns JOIN node n ON n.id=ns.node_id WHERE ns.server_id=? AND ns.node_id<>? AND n.port=?)`, sid, it.id, it.old).Scan(&inUse); err != nil {
						return err
					}
					if inUse {
						return invalid("旧端口 %d 仍被服务器 #%d 上其他节点使用", it.old, sid)
					}
					if err := blockServerPort(ctx, tx, sid, it.old, now); err != nil {
						return err
					}
				}
			}
			port, err := availableNodePortExcept(ctx, tx, reserved)
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE node SET port=?,update_time=? WHERE id=?`, port, now, it.id); err != nil {
				return err
			}
			if err := markPending(ctx, tx, it.id); err != nil {
				return err
			}
			res.Rotations = append(res.Rotations, PortRotation{NodeID: it.id, Name: it.name, OldPort: it.old, NewPort: port})
			rows, err := tx.QueryContext(ctx, `SELECT DISTINCT ns.server_id FROM node_server ns WHERE ns.node_id=? OR ns.node_id IN (SELECT id FROM node WHERE out_id=?)
    OR ns.server_id IN (SELECT rs.server_id FROM route_rule_server rs JOIN route_rule r ON r.id=rs.route_rule_id WHERE r.outbound_node_id=? AND r.enabled=1)`, it.id, it.id, it.id)
			if err != nil {
				return err
			}
			for rows.Next() {
				var sid int64
				if err := rows.Scan(&sid); err != nil {
					rows.Close()
					return err
				}
				servers[sid] = true
			}
			rows.Close()
			if err := rows.Err(); err != nil {
				return err
			}
		}
		for sid := range servers {
			res.DeployServers = append(res.DeployServers, sid)
		}
		slices.Sort(res.DeployServers)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return res, nil
}
