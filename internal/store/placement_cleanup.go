package store

import "context"

// cleanupPlacementNodes 只清理本次移除部署位涉及的节点，先代理再落地，保留仍被引用的节点。
func cleanupPlacementNodes(ctx context.Context, q dbtx, ids map[int64]bool) error {
	for _, typ := range []int{NodeProxy, NodeLanding} {
		for id := range ids {
			if _, err := q.ExecContext(ctx, `DELETE FROM node WHERE id=? AND type=? AND NOT EXISTS (SELECT 1 FROM node_server WHERE node_id=node.id)
				AND NOT EXISTS (SELECT 1 FROM node o WHERE o.out_id=node.id)
				AND NOT EXISTS (SELECT 1 FROM route_rule r WHERE r.outbound_node_id=node.id)`, id, typ); err != nil {
				return err
			}
		}
	}
	return nil
}

// cleanupDisabledPlacements 在禁用服务器的事务内清理已请求删除的部署位，不连接远端。
func cleanupDisabledPlacements(ctx context.Context, q dbtx, serverID int64) error {
	rows, err := q.QueryContext(ctx, `SELECT n.id, coalesce(n.out_id,0) FROM node_server ns JOIN node n ON n.id=ns.node_id
		JOIN server s ON s.id=ns.server_id WHERE s.id=? AND s.disabled=1 AND ns.deployed=2`, serverID)
	if err != nil {
		return err
	}
	ids := map[int64]bool{}
	for rows.Next() {
		var id, outID int64
		if err := rows.Scan(&id, &outID); err != nil {
			rows.Close()
			return err
		}
		ids[id] = true
		if outID != 0 {
			ids[outID] = true
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	if _, err := q.ExecContext(ctx, `DELETE FROM node_server WHERE server_id=? AND deployed=2
		AND EXISTS (SELECT 1 FROM server WHERE id=? AND disabled=1)`, serverID, serverID); err != nil {
		return err
	}
	return cleanupPlacementNodes(ctx, q, ids)
}
