package store

import (
	"context"
	"database/sql"
	"fmt"
	"slices"
	"strings"
)

// BatchNodePlacements 原子地为多个节点添加或删除指定服务器上的部署位，返回实际变化的节点数。
// 添加时节点已在该服务器上的跳过；删除时节点不在该服务器上的跳过。
// 删除的部署位绑定域名或订阅定制、或是节点最后一个部署位时整体回滚，需到节点编辑页选择转移目标。
func BatchNodePlacements(ctx context.Context, db *sql.DB, nodeIDs, serverIDs []int64, add bool) (updated int, err error) {
	nodeIDs, serverIDs = normalizeTagIDs(nodeIDs), normalizeTagIDs(serverIDs)
	if len(nodeIDs) == 0 || len(serverIDs) == 0 {
		return 0, invalid("请选择节点和服务器")
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	for _, id := range nodeIDs {
		n, err := loadNode(ctx, tx, id)
		if err != nil {
			return 0, err
		}
		if n == nil {
			return 0, invalid("部分节点不存在，无法批量调整部署位")
		}
		prevEffective, err := effectiveServer(ctx, tx, id)
		if err != nil {
			return 0, err
		}
		changed := false
		for _, sid := range serverIDs {
			pls, err := Placements(ctx, tx, id)
			if err != nil {
				return 0, err
			}
			i := slices.IndexFunc(pls, func(p *Placement) bool { return p.ServerID == sid && p.Deployed != PendingDelete })
			switch {
			case add && i < 0:
				if err = addPlacement(ctx, tx, n, sid); err != nil {
					return 0, fmt.Errorf("节点 %s：%w", n.Name, err)
				}
			case !add && i >= 0:
				if err = deletePlacement(ctx, tx, n, pls[i].ID, 0); err != nil {
					return 0, fmt.Errorf("节点 %s：%w", n.Name, err)
				}
			default:
				continue
			}
			changed = true
		}
		if !changed {
			continue
		}
		updated++
		if n.Type == NodeLanding {
			cur, err := effectiveServer(ctx, tx, id)
			if err != nil {
				return 0, err
			}
			if cur != prevEffective {
				if err := markReferencingPending(ctx, tx, id); err != nil {
					return 0, err
				}
			}
		}
	}
	return updated, tx.Commit()
}

// BatchServerOption 是批量部署位可选的未禁用服务器。
type BatchServerOption struct {
	ID       int64
	Name, IP string
}

// BatchServerOptions 返回未禁用的服务器。
func BatchServerOptions(ctx context.Context, db *sql.DB) ([]BatchServerOption, error) {
	rows, err := db.QueryContext(ctx, `SELECT id, coalesce(nullif(name,''), ip), ip FROM server WHERE disabled = 0 ORDER BY region, name, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BatchServerOption
	for rows.Next() {
		var o BatchServerOption
		if err := rows.Scan(&o.ID, &o.Name, &o.IP); err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	return out, rows.Err()
}

// BatchNodeSwitch 把所选节点当前指向的部署位标记为切走，域名按优先级切到其余部署位；
// 没有指向或没有其他未切走部署位的节点跳过。
func BatchNodeSwitch(ctx context.Context, db *sql.DB, nodeIDs []int64) (switched, skipped int, err error) {
	nodeIDs = normalizeTagIDs(nodeIDs)
	if len(nodeIDs) == 0 {
		return 0, 0, invalid("请选择节点")
	}
	pointed, err := PointedPlacements(ctx, db)
	if err != nil {
		return 0, 0, err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()
	for _, id := range nodeIDs {
		var ids []any
		for _, p := range pointed[id] {
			ids = append(ids, p.ID)
		}
		if len(ids) == 0 {
			skipped++
			continue
		}
		in := `(?` + strings.Repeat(",?", len(ids)-1) + `)`
		var rest int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM node_server WHERE node_id = ? AND deployed <> 2 AND failover = 0 AND id NOT IN `+in,
			append([]any{id}, ids...)...).Scan(&rest); err != nil {
			return 0, 0, err
		}
		if rest == 0 {
			skipped++
			continue
		}
		if _, err := tx.ExecContext(ctx, `UPDATE node_server SET failover = 1 WHERE id IN `+in, ids...); err != nil {
			return 0, 0, err
		}
		switched++
	}
	return switched, skipped, tx.Commit()
}

// RestoreNodeFailover 取消所选节点全部部署位的切走，域名回到原始解析，返回实际变化的部署位数。
func RestoreNodeFailover(ctx context.Context, db *sql.DB, nodeIDs []int64) (int64, error) {
	nodeIDs = normalizeTagIDs(nodeIDs)
	if len(nodeIDs) == 0 {
		return 0, invalid("请选择节点")
	}
	args := make([]any, len(nodeIDs))
	for i, id := range nodeIDs {
		args[i] = id
	}
	res, err := db.ExecContext(ctx, `UPDATE node_server SET failover = 0 WHERE failover = 1 AND node_id IN (?`+strings.Repeat(",?", len(nodeIDs)-1)+`)`, args...)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// RestorePlacementFailover 只取消节点某个部署位的切走，其余部署位保持不变。
func RestorePlacementFailover(ctx context.Context, db *sql.DB, nodeID, placementID int64) error {
	res, err := db.ExecContext(ctx, `UPDATE node_server SET failover = 0 WHERE id = ? AND node_id = ? AND failover = 1`, placementID, nodeID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return invalid("部署位不存在或未切走")
	}
	return nil
}

// SwitchNodePlacement 让节点域名都切到指定部署位：其余部署位标记切走，目标取消切走。
func SwitchNodePlacement(ctx context.Context, db *sql.DB, nodeID, placementID int64) error {
	res, err := db.ExecContext(ctx, `UPDATE node_server SET failover = (id <> ?) WHERE node_id = ? AND deployed <> 2
		AND EXISTS (SELECT 1 FROM node_server t WHERE t.id = ? AND t.node_id = ? AND t.deployed <> 2)`, placementID, nodeID, placementID, nodeID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return invalid("部署位不存在或待删除")
	}
	return nil
}

// FailoverServers 按节点返回已手动切走的部署位服务器名，供列表标注。
func FailoverServers(ctx context.Context, db *sql.DB) (map[int64][]string, error) {
	rows, err := db.QueryContext(ctx, `SELECT ns.node_id, coalesce(nullif(s.name,''), s.ip) FROM node_server ns JOIN server s ON s.id = ns.server_id
		WHERE ns.failover = 1 AND ns.deployed <> 2 ORDER BY ns.node_id, ns.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64][]string{}
	for rows.Next() {
		var node int64
		var name string
		if err := rows.Scan(&node, &name); err != nil {
			return nil, err
		}
		out[node] = append(out[node], name)
	}
	return out, rows.Err()
}
