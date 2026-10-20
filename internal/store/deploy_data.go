package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"

	"github.com/fun90/airopscat/internal/singbox"
)

// ClashSecretKey 是 Clash API secret 的系统配置键。
const ClashSecretKey = "airopscat.sing-box.clash-api.secret"

// LoadOptions 是装载服务器配置输入的可选项。
type LoadOptions struct {
	// Overrides 按账户号返回覆盖的下行、上行限速（ok 为 true 时整体替换账户自身的两个值）；nil 时在装载事务内读取系统超额限速配置。
	Overrides func(accountNo string) (down, up int, ok bool)
}

// landingInfo 是出站节点及其生效部署位的服务器 IP。
type landingInfo struct {
	out      *singbox.Outbound
	coreType string
}

// LoadServerConfigInput 装载生成单台服务器 sing-box 配置所需的数据（spec-deploy 2.1）。
func LoadServerConfigInput(ctx context.Context, db *sql.DB, serverID int64, opts LoadOptions) (*singbox.ServerConfigInput, error) {
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	in, err := loadServerConfigInput(ctx, tx, serverID, opts)
	if err != nil {
		return nil, err
	}
	return in, tx.Commit()
}

func loadServerConfigInput(ctx context.Context, db dbtx, serverID int64, opts LoadOptions) (*singbox.ServerConfigInput, error) {
	var exists bool
	if err := db.QueryRowContext(ctx, `SELECT EXISTS (SELECT 1 FROM server WHERE id = ?)`, serverID).Scan(&exists); err != nil {
		return nil, err
	}
	if !exists {
		return nil, fmt.Errorf("服务器 %d 不存在", serverID)
	}

	at := nowFunc()
	if opts.Overrides == nil {
		limits, err := EffectiveLimitOverrides(ctx, db, at)
		if err != nil {
			return nil, err
		}
		opts.Overrides = func(no string) (int, int, bool) {
			limit, ok := limits[no]
			return limit.DownloadMbps, limit.UploadMbps, ok
		}
	}
	in := &singbox.ServerConfigInput{}
	var secret sql.NullString
	err := db.QueryRowContext(ctx, `SELECT config_value FROM system_config WHERE config_key = ?`, ClashSecretKey).Scan(&secret)
	if err != nil && !errors.Is(err, sql.ErrNoRows) {
		return nil, err
	}
	in.ClashSecret = secret.String // 不做任何裁剪，secret 原样写入

	landings := map[int64]*landingInfo{}
	loadLanding := func(id int64) (*landingInfo, error) {
		if l, ok := landings[id]; ok {
			return l, nil
		}
		l, err := effectiveLanding(ctx, db, id)
		if err == nil {
			landings[id] = l
		}
		return l, err
	}

	nodes, err := serverNodes(ctx, db, serverID)
	if err != nil {
		return nil, err
	}
	now := FormatTime(at)
	for _, n := range nodes {
		node := singbox.NodeInput{ID: n.id, Protocol: n.protocol, Port: n.port, Inbound: n.inbound, Disabled: n.disabled}
		if n.outID.Valid {
			l, err := loadLanding(n.outID.Int64)
			if err != nil {
				return nil, err
			}
			if l == nil || l.out.ServerIP == "" {
				return nil, fmt.Errorf("节点 %d 的出站节点 %d 没有可用的部署服务器", n.id, n.outID.Int64)
			}
			node.Out = l.out
		}
		// ShadowTLS 双层入站和 vless/hysteria2 共用账户授权。
		if strings.EqualFold(strings.TrimSpace(n.coreType), "sing-box") && !n.disabled {
			switch strings.ToLower(strings.TrimSpace(n.protocol)) {
			case "vless", "vless-reality", "hysteria2", "shadowtls":
				if node.Clients, err = nodeClients(ctx, db, n.id, now, opts.Overrides); err != nil {
					return nil, err
				}
			}
		}
		in.Nodes = append(in.Nodes, node)
	}

	rows, err := db.QueryContext(ctx, `SELECT r.rule_type, r.rule_value, r.outbound_node_id FROM route_rule r
		JOIN route_rule_server rs ON rs.route_rule_id = r.id
		WHERE rs.server_id = ? AND r.enabled = 1 AND lower(trim(r.core_type)) = 'sing-box' ORDER BY r.id`, serverID)
	if err != nil {
		return nil, err
	}
	type ruleRow struct {
		typ, value string
		outID      int64
	}
	var rules []ruleRow
	for rows.Next() {
		var r ruleRow
		if err := rows.Scan(&r.typ, &r.value, &r.outID); err != nil {
			rows.Close()
			return nil, err
		}
		rules = append(rules, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for _, r := range rules {
		l, err := loadLanding(r.outID)
		if err != nil {
			return nil, err
		}
		// 出站节点缺失生效部署位、非 sing-box 或无 inbound 时静默跳过
		if l == nil || !strings.EqualFold(strings.TrimSpace(l.coreType), "sing-box") || strings.TrimSpace(l.out.Inbound) == "" {
			continue
		}
		in.Rules = append(in.Rules, singbox.RouteRule{Type: r.typ, Value: r.value, Out: l.out})
	}
	return in, nil
}

type serverNode struct {
	id                          int64
	protocol, inbound, coreType string
	port                        int
	outID                       sql.NullInt64
	disabled                    bool
}

// serverNodes 返回服务器上非待删除部署位对应的节点，按 priority, id 排序。
func serverNodes(ctx context.Context, q dbtx, serverID int64) ([]serverNode, error) {
	rows, err := q.QueryContext(ctx, `SELECT n.id, coalesce(n.protocol,''), coalesce(n.port,0), coalesce(n.inbound,''), coalesce(n.core_type,''), n.out_id, n.disabled
		FROM node_server p JOIN node n ON n.id = p.node_id
		WHERE p.server_id = ? AND p.deployed <> 2 ORDER BY p.priority, p.id`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []serverNode
	for rows.Next() {
		var n serverNode
		if err := rows.Scan(&n.id, &n.protocol, &n.port, &n.inbound, &n.coreType, &n.outID, &n.disabled); err != nil {
			return nil, err
		}
		list = append(list, n)
	}
	return list, rows.Err()
}

// effectiveLanding 取节点生效部署位（非待删除中 priority、id 最小）所在服务器的 IP；没有则返回 nil。
func effectiveLanding(ctx context.Context, q dbtx, nodeID int64) (*landingInfo, error) {
	l := &landingInfo{out: &singbox.Outbound{NodeID: nodeID}}
	err := q.QueryRowContext(ctx, `SELECT coalesce(n.protocol,''), coalesce(n.port,0), coalesce(n.inbound,''), coalesce(n.core_type,''), s.ip
		FROM node n JOIN node_server p ON p.node_id = n.id AND p.deployed <> 2 JOIN server s ON s.id = p.server_id
		WHERE n.id = ? ORDER BY p.priority, p.id LIMIT 1`, nodeID).
		Scan(&l.out.Protocol, &l.out.Port, &l.out.Inbound, &l.coreType, &l.out.ServerIP)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return l, err
}

// nodeClients 返回节点标签与账户标签有交集、未禁用且未过期的账户；覆盖限速后整体替换账户自身的两个值。
func nodeClients(ctx context.Context, q dbtx, nodeID int64, now string, overrides func(string) (int, int, bool)) ([]singbox.Client, error) {
	rows, err := q.QueryContext(ctx, `SELECT DISTINCT a.id, a.uuid, a.account_no, coalesce(a.download_mbps,0), coalesce(a.upload_mbps,0), coalesce(a.max_ips,0)
		FROM account a JOIN account_tag at ON at.account_id = a.id JOIN node_tag nt ON nt.tag_id = at.tag_id
		WHERE nt.node_id = ? AND a.disabled = 0 AND (a.to_date IS NULL OR a.to_date > ?) ORDER BY a.id`, nodeID, now)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []singbox.Client
	for rows.Next() {
		var id int64
		var c singbox.Client
		if err := rows.Scan(&id, &c.UUID, &c.Name, &c.DownloadMbps, &c.UploadMbps, &c.MaxIPs); err != nil {
			return nil, err
		}
		if overrides != nil {
			if down, up, ok := overrides(c.Name); ok {
				c.DownloadMbps, c.UploadMbps = down, up
			}
		}
		list = append(list, c)
	}
	return list, rows.Err()
}
