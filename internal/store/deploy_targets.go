package store

import (
	"context"
	"database/sql"
)

func serverIDs(ctx context.Context, db *sql.DB, query string, args ...any) ([]int64, error) {
	rows, err := db.QueryContext(ctx, query, args...)
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
		ids = append(ids, id)
	}
	return ids, rows.Err()
}

// NodeDeployServers 返回节点需要部署的服务器；force=false 时只含有待部署/待删除部署位的服务器。
func NodeDeployServers(ctx context.Context, db *sql.DB, nodeID int64, force bool) ([]int64, error) {
	q := `SELECT DISTINCT server_id FROM node_server WHERE node_id = ?`
	if !force {
		q += ` AND deployed IN (0, 2)`
	}
	return serverIDs(ctx, db, q+` ORDER BY server_id`, nodeID)
}

// PendingDeployServers 返回所有存在待部署/待删除部署位的服务器。
func PendingDeployServers(ctx context.Context, db *sql.DB) ([]int64, error) {
	return serverIDs(ctx, db, `SELECT DISTINCT server_id FROM node_server WHERE deployed IN (0, 2) ORDER BY server_id`)
}

// AccountDeployServers 返回账户经标签可用的所有节点所在的服务器（账户部署，整机重推）。
func AccountDeployServers(ctx context.Context, db *sql.DB, accountID int64) ([]int64, error) {
	return serverIDs(ctx, db, `SELECT DISTINCT ns.server_id FROM account_tag at JOIN node_tag nt ON nt.tag_id = at.tag_id
		JOIN node_server ns ON ns.node_id = nt.node_id WHERE at.account_id = ? ORDER BY ns.server_id`, accountID)
}
