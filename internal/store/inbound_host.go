package store

import (
	"context"
	"database/sql"
	"strings"
)

// DefaultInboundHost 按保存时的地区/类型匹配规则选择首个可用部署目标。
// 编辑时优先现有部署位，地区或类型变化后改用新目标，避免套用旧服务器地址。
func DefaultInboundHost(ctx context.Context, db *sql.DB, nodeID int64, region string, nodeType int) (string, error) {
	if nodeType != NodeProxy && nodeType != NodeLanding {
		return "", invalid("节点类型不正确")
	}
	region = strings.TrimSpace(region)
	if region == "" {
		return "", invalid("请先填写节点地区")
	}
	if nodeID != 0 {
		node, err := NodeByID(ctx, db, nodeID)
		if err != nil {
			return "", err
		}
		if node == nil {
			return "", sql.ErrNoRows
		}
		if node.Region == region && node.Type == nodeType {
			var host string
			err := db.QueryRowContext(ctx, `SELECT CASE WHEN r.status='SYNCED' AND trim(coalesce(r.full_name,''))<>'' THEN trim(r.full_name) ELSE s.ip END
 FROM node_server p JOIN server s ON s.id=p.server_id LEFT JOIN domain_dns_record r ON r.id=p.access_host_id
 WHERE p.node_id=? AND p.deployed<>2 AND s.disabled=0 AND trim(s.region)=? AND (?=1 OR (s.external_server=0 AND coalesce(s.server_type,0)<>1)) ORDER BY p.priority IS NULL,p.priority,p.id LIMIT 1`, nodeID, region, nodeType).Scan(&host)
			if err == nil {
				return host, nil
			}
			if err != sql.ErrNoRows {
				return "", err
			}
		}
	}
	servers, err := allServers(ctx, db)
	if err != nil {
		return "", err
	}
	for _, s := range servers {
		if !s.disabled && serverMatches(region, nodeType, s.region, s.typ, s.external) {
			var host string
			if err := db.QueryRowContext(ctx, `SELECT ip FROM server WHERE id=?`, s.id).Scan(&host); err != nil {
				return "", err
			}
			return host, nil
		}
	}
	return "", invalid("没有匹配地区和类型的可用服务器，请先配置服务器")
}
