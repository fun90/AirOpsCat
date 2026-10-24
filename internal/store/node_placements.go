package store

import (
	"context"
	"database/sql"
	"fmt"
	"slices"
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
