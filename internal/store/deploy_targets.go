package store

import (
	"context"
	"database/sql"
	"strings"
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

// deployTargetFrom 是选取部署目标的公共连接；禁用服务器部署必然跳过，不作为目标。
const deployTargetFrom = ` FROM node_server ns JOIN node n ON n.id = ns.node_id JOIN server s ON s.id = ns.server_id AND s.disabled = 0`

// NodeDeployServers 返回节点需要部署的服务器；force=false 时只含有需要部署的部署位的服务器。
func NodeDeployServers(ctx context.Context, db *sql.DB, nodeID int64, force bool) ([]int64, error) {
	q := `SELECT DISTINCT ns.server_id` + deployTargetFrom + ` WHERE ns.node_id = ?`
	if !force {
		q += ` AND ` + placementNeedsDeploySQL
	}
	return serverIDs(ctx, db, q+` ORDER BY ns.server_id`, nodeID)
}

// PendingDeployServers 返回需要部署位变更或待解封远端规则的启用服务器。
func PendingDeployServers(ctx context.Context, db *sql.DB) ([]int64, error) {
	return serverIDs(ctx, db, `SELECT DISTINCT ns.server_id`+deployTargetFrom+` WHERE `+placementNeedsDeploySQL+`
		UNION SELECT s.id FROM server_unblocked_port u JOIN server s ON s.id=u.server_id WHERE s.disabled=0 ORDER BY 1`)
}

// AccountDeployServers 返回账户经标签可用的所有节点所在的启用服务器（账户部署，整机重推）。
func AccountDeployServers(ctx context.Context, db *sql.DB, accountID int64) ([]int64, error) {
	return serverIDs(ctx, db, `SELECT DISTINCT ns.server_id FROM account_tag at JOIN node_tag nt ON nt.tag_id = at.tag_id
		JOIN node_server ns ON ns.node_id = nt.node_id JOIN server s ON s.id = ns.server_id AND s.disabled = 0
		WHERE at.account_id = ? ORDER BY ns.server_id`, accountID)
}

// RetryDeployServers 返回最近部署失败且部署位未确认或远端解封待执行的启用服务器。
func RetryDeployServers(ctx context.Context, db *sql.DB) ([]int64, error) {
	return serverIDs(ctx, db, `SELECT DISTINCT ns.server_id`+deployTargetFrom+`
		JOIN server_deployment sd ON sd.server_id = ns.server_id AND sd.success = 0
		WHERE `+placementNeedsDeploySQL+`
		UNION SELECT s.id FROM server_unblocked_port u JOIN server s ON s.id=u.server_id AND s.disabled=0
		JOIN server_deployment sd ON sd.server_id=s.id AND sd.success=0 ORDER BY 1`)
}

// NodeRelatedServers 返回节点变更可能影响的启用服务器：节点自身部署位所在服务器、以它为出站的代理节点部署位所在服务器、
// 以及以它为出站的已启用路由规则所选服务器（与 markPending、markReferencingPending 的覆盖范围一致），供自动部署取候选。
func NodeRelatedServers(ctx context.Context, db *sql.DB, nodeID int64) ([]int64, error) {
	return serverIDs(ctx, db, `SELECT DISTINCT ns.server_id FROM node_server ns JOIN server s ON s.id = ns.server_id AND s.disabled = 0
		WHERE ns.node_id = ? OR ns.node_id IN (SELECT id FROM node WHERE out_id = ?)
		OR ns.server_id IN (SELECT rs.server_id FROM route_rule_server rs JOIN route_rule r ON r.id = rs.route_rule_id WHERE r.outbound_node_id = ? AND r.enabled = 1)
		ORDER BY ns.server_id`, nodeID, nodeID, nodeID)
}

// ServerRelatedServers 返回服务器自身（self 为 true 时）及引用其上落地节点的代理、路由规则所在的启用服务器：
// 改 IP 只影响引用方（self=false），迁移与报废还涉及自身。
func ServerRelatedServers(ctx context.Context, db *sql.DB, serverID int64, self bool) ([]int64, error) {
	return serverIDs(ctx, db, `SELECT DISTINCT ns.server_id FROM node_server ns JOIN server s ON s.id = ns.server_id AND s.disabled = 0
		WHERE (? AND ns.server_id = ?) OR ns.node_id IN (SELECT o.id FROM node o JOIN node_server p ON p.node_id = o.out_id WHERE p.server_id = ?)
		OR ns.server_id IN (SELECT rs.server_id FROM route_rule_server rs JOIN route_rule r ON r.id = rs.route_rule_id
			JOIN node_server p ON p.node_id = r.outbound_node_id WHERE p.server_id = ? AND r.enabled = 1)
		ORDER BY ns.server_id`, self, serverID, serverID, serverID)
}

// DeployableServers 从 ids 中筛出启用且有部署位的服务器（路由规则变化直接部署这些服务器，不经待部署标记）。
func DeployableServers(ctx context.Context, db *sql.DB, ids []int64) ([]int64, error) {
	if len(ids) == 0 {
		return nil, nil
	}
	args := make([]any, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	return serverIDs(ctx, db, `SELECT s.id FROM server s WHERE s.disabled = 0 AND EXISTS (SELECT 1 FROM node_server ns WHERE ns.server_id = s.id)
		AND s.id IN (?`+strings.Repeat(",?", len(ids)-1)+`) ORDER BY s.id`, args...)
}
