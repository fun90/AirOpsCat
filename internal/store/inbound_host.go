package store

import (
	"context"
	"database/sql"
	"strings"
)

// DefaultInboundHost 选择 Hysteria2/AnyTLS 默认入站的主机名。
// 已有节点优先取生效部署位（不看地区）：已同步访问域名，否则服务器 IP；
// 新建节点优先取表单中首个待添加部署位的服务器 IP（新部署位尚未绑定访问域名）；
// 未选部署位或尚无部署位时按地区/类型匹配规则选首台服务器，与新建节点的初始部署位一致。
func DefaultInboundHost(ctx context.Context, db *sql.DB, nodeID, serverID int64, region string, nodeType int) (string, error) {
	if nodeType != NodeProxy && nodeType != NodeLanding {
		return "", invalid("节点类型不正确")
	}
	region = strings.TrimSpace(region)
	if nodeID == 0 && serverID != 0 {
		var host string
		err := db.QueryRowContext(ctx, `SELECT ip FROM server WHERE id=? AND disabled=0`, serverID).Scan(&host)
		if err == sql.ErrNoRows {
			return "", invalid("部署位服务器不存在或已禁用")
		}
		return host, err
	}
	if nodeID != 0 {
		node, err := NodeByID(ctx, db, nodeID)
		if err != nil {
			return "", err
		}
		if node == nil {
			return "", sql.ErrNoRows
		}
		var host string
		err = db.QueryRowContext(ctx, `SELECT CASE WHEN r.status='SYNCED' AND trim(coalesce(r.full_name,''))<>'' THEN trim(r.full_name) ELSE s.ip END
 FROM node_server p JOIN server s ON s.id=p.server_id LEFT JOIN domain_dns_record r ON r.id=p.access_host_id
 WHERE p.node_id=? AND p.deployed<>2 ORDER BY p.priority, p.id LIMIT 1`, nodeID).Scan(&host)
		if err == nil {
			return host, nil
		}
		if err != sql.ErrNoRows {
			return "", err
		}
	}
	if region == "" {
		return "", invalid("请先填写节点地区")
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
