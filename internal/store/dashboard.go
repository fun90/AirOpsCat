package store

import (
	"context"
	"database/sql"
	"fmt"
	"slices"
	"strings"
	"time"
)

// Dashboard 是概览页的真实统计；账户状态与账户列表筛选口径一致：禁用（手动）与过期各自独立计数，可重叠。
type DashboardExpiry struct {
	ID                       int64
	Name, AccountNo, Expires string
	Disabled                 bool
}

// DashboardRenewal 是 7 天内到期、需要续费的服务器或域名。
type DashboardRenewal struct {
	Table                string // server / domain
	ID                   int64
	Name, Detail, Expire string
}

type Dashboard struct {
	ExpiringAccounts                                                 []DashboardExpiry
	ExpiringRenewals                                                 []DashboardRenewal
	ServersAbnormal                                                  int
	AccountsTotal, AccountsActive, AccountsDisabled, AccountsExpired int
	NodesEnabled, NodesDisabled, PendingPlacements                   int
	ServersEnabled, ServersExternal, ServersExpiring                 int
	AlertsActive, AlertsAcknowledged                                 int
	ActiveAlerts                                                     []Alert
}

// DashboardLoad 汇总资源状态与待处理告警。
// 服务器按上海日期统计今天至第七天到期的启用、非外部服务器，续费清单另含同窗口内到期的域名；
// 到期账户列出未来 days 天内到期且未禁用的账户。
// 待处理告警只取进行中且非到期类的告警：到期类与到期清单重复。
func DashboardLoad(ctx context.Context, db *sql.DB, now time.Time, days int) (*Dashboard, error) {
	if days < 1 {
		days = 1
	}
	d := new(Dashboard)
	local := now.In(Shanghai)
	expireCutoff := FormatTime(AccountExpireCutoff(now))
	err := db.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM account),
		(SELECT count(*) FROM account WHERE disabled=0 AND (to_date IS NULL OR to_date>@expireCutoff)),
		(SELECT count(*) FROM account WHERE disabled=1),
		(SELECT count(*) FROM account WHERE to_date<=@expireCutoff),
		(SELECT count(*) FROM node WHERE disabled=0),
		(SELECT count(*) FROM node WHERE disabled=1),
		(SELECT count(*) FROM node_server ns JOIN node n ON n.id=ns.node_id JOIN server s ON s.id=ns.server_id AND s.disabled=0 WHERE ns.deployed=0 AND `+placementNeedsDeploySQL+`),
		(SELECT count(*) FROM server WHERE disabled=0),
		(SELECT count(*) FROM server WHERE disabled=0 AND external_server=1),
		(SELECT count(*) FROM server WHERE disabled=0 AND external_server=0
			AND substr(expire_date,1,10)>=@today AND substr(expire_date,1,10)<=@soon),
		(SELECT count(*) FROM alert_state WHERE status='ACTIVE'),
		(SELECT count(*) FROM alert_state WHERE status='ACKNOWLEDGED'),
		(SELECT count(DISTINCT resource_id) FROM alert_state WHERE resource_type='server' AND alert_type<>'server-expiring' AND status='ACTIVE')`,
		sql.Named("expireCutoff", expireCutoff), sql.Named("today", local.Format("2006-01-02")),
		sql.Named("soon", local.AddDate(0, 0, 7).Format("2006-01-02"))).Scan(
		&d.AccountsTotal, &d.AccountsActive, &d.AccountsDisabled, &d.AccountsExpired,
		&d.NodesEnabled, &d.NodesDisabled, &d.PendingPlacements,
		&d.ServersEnabled, &d.ServersExternal, &d.ServersExpiring,
		&d.AlertsActive, &d.AlertsAcknowledged, &d.ServersAbnormal)
	if err != nil {
		return nil, fmt.Errorf("读取概览统计: %w", err)
	}
	rows, err := db.QueryContext(ctx, `SELECT id,coalesce(nullif(remark,''),account_no),account_no,to_date,disabled FROM account
		WHERE disabled=0 AND to_date>? AND to_date<? ORDER BY to_date,id`, expireCutoff, FormatTime(now.AddDate(0, 0, days)))
	if err != nil {
		return nil, fmt.Errorf("读取到期账户: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var a DashboardExpiry
		if err := rows.Scan(&a.ID, &a.Name, &a.AccountNo, &a.Expires, &a.Disabled); err != nil {
			return nil, err
		}
		d.ExpiringAccounts = append(d.ExpiringAccounts, a)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows, err = db.QueryContext(ctx, alertSel+` WHERE status='ACTIVE' AND alert_type NOT LIKE '%-expiring'
		ORDER BY last_triggered_time DESC, id DESC LIMIT 50`)
	if err != nil {
		return nil, fmt.Errorf("读取待处理告警: %w", err)
	}
	if d.ActiveAlerts, err = scanAlerts(rows); err != nil {
		return nil, fmt.Errorf("读取待处理告警: %w", err)
	}
	srows, err := db.QueryContext(ctx, `SELECT t,id,name,detail,expire FROM (
		SELECT 'server' t,id,coalesce(nullif(name,''),ip) name,ip detail,substr(expire_date,1,10) expire FROM server
			WHERE disabled=0 AND external_server=0
		UNION ALL SELECT 'domain',id,coalesce(domain,''),'域名',substr(expire_date,1,10) FROM domain)
		WHERE expire>=@today AND expire<=@soon ORDER BY expire,t DESC,id`,
		sql.Named("today", local.Format("2006-01-02")), sql.Named("soon", local.AddDate(0, 0, 7).Format("2006-01-02")))
	if err != nil {
		return nil, fmt.Errorf("读取即将到期续费项: %w", err)
	}
	defer srows.Close()
	for srows.Next() {
		var e DashboardRenewal
		if err := srows.Scan(&e.Table, &e.ID, &e.Name, &e.Detail, &e.Expire); err != nil {
			return nil, err
		}
		d.ExpiringRenewals = append(d.ExpiringRenewals, e)
	}
	return d, srows.Err()
}

// TopologyNodeMeta 是概览运行情况图所需的节点静态信息。
type TopologyNodeMeta struct {
	ID                   int64
	Name, Protocol       string
	Port                 int
	SubNamesByServer     map[string]string
	FallbackSubNames     []string
	HostsByServer        map[string]string
	PointedHostsByServer map[string][]string
	FallbackHosts        []string
}

// TopologyServerMeta 是概览运行情况图所需的服务器静态信息。
type TopologyServerMeta struct {
	ID               int64
	Name, IP, Region string
}

// TopologyMeta 汇总运行情况图所需的在线节点与服务器元数据。
type TopologyMeta struct {
	Nodes   map[int64]TopologyNodeMeta
	Servers map[string]TopologyServerMeta
}

func appendUniqueString(list []string, v string) []string {
	if v == "" || slices.Contains(list, v) {
		return list
	}
	return append(list, v)
}

// OnlineTopologyMeta 按在线节点 ID 和服务器 IP 批量读取订阅名、访问域名、端口与服务器名称。
func OnlineTopologyMeta(ctx context.Context, db *sql.DB, nodeIDs []int64, serverIPs []string) (TopologyMeta, error) {
	out := TopologyMeta{
		Nodes:   map[int64]TopologyNodeMeta{},
		Servers: map[string]TopologyServerMeta{},
	}
	uniqueNodeIDs := normalizeTagIDs(nodeIDs)
	if len(uniqueNodeIDs) > 0 {
		in := `(?` + strings.Repeat(",?", len(uniqueNodeIDs)-1) + `)`
		args := make([]any, len(uniqueNodeIDs))
		for i, id := range uniqueNodeIDs {
			args[i] = id
		}
		rows, err := db.QueryContext(ctx, `SELECT id, coalesce(name,''), coalesce(port,0), coalesce(protocol,'') FROM node WHERE id IN `+in, args...)
		if err != nil {
			return out, fmt.Errorf("读取在线节点信息: %w", err)
		}
		for rows.Next() {
			var m TopologyNodeMeta
			if err := rows.Scan(&m.ID, &m.Name, &m.Port, &m.Protocol); err != nil {
				rows.Close()
				return out, err
			}
			m.SubNamesByServer = map[string]string{}
			m.HostsByServer = map[string]string{}
			m.PointedHostsByServer = map[string][]string{}
			out.Nodes[m.ID] = m
		}
		if err := rows.Err(); err != nil {
			rows.Close()
			return out, err
		}
		rows.Close()

		prows, err := db.QueryContext(ctx, `SELECT ns.id, ns.node_id, s.ip, coalesce(ns.exposed,1), coalesce(ns.deployed,0),
			trim(coalesce(ns.access_host_custom,'')), trim(coalesce(r.full_name, r.name, ''))
			FROM node_server ns JOIN server s ON s.id = ns.server_id
			LEFT JOIN domain_dns_record r ON r.id = ns.access_host_id
			WHERE ns.node_id IN `+in+` AND ns.deployed <> 2
			ORDER BY ns.node_id, ns.priority, ns.id`, args...)
		if err != nil {
			return out, fmt.Errorf("读取在线节点部署位: %w", err)
		}
		byNode := map[int64][]*Placement{}
		hostByPlacement := map[int64]string{}
		for prows.Next() {
			var p Placement
			var custom, rec string
			if err := prows.Scan(&p.ID, &p.NodeID, &p.ServerIP, &p.Exposed, &p.Deployed, &custom, &rec); err != nil {
				prows.Close()
				return out, err
			}
			byNode[p.NodeID] = append(byNode[p.NodeID], &p)
			if custom != "" {
				hostByPlacement[p.ID] = custom
			} else if rec != "" {
				hostByPlacement[p.ID] = rec
			}
		}
		if err := prows.Err(); err != nil {
			prows.Close()
			return out, err
		}
		prows.Close()

		for nid, pls := range byNode {
			m, ok := out.Nodes[nid]
			if !ok {
				continue
			}
			subNames := PlacementSubscriptionNames(m.Name, pls)
			for _, p := range pls {
				if sub := subNames[p.ID]; sub != "" {
					if m.SubNamesByServer[p.ServerIP] == "" {
						m.SubNamesByServer[p.ServerIP] = sub
					}
					m.FallbackSubNames = appendUniqueString(m.FallbackSubNames, sub)
				}
				if host := hostByPlacement[p.ID]; host != "" {
					if m.HostsByServer[p.ServerIP] == "" {
						m.HostsByServer[p.ServerIP] = host
					}
					m.FallbackHosts = appendUniqueString(m.FallbackHosts, host)
				}
			}
			out.Nodes[nid] = m
		}

		// 同时读取 DNS 实际指向（含 failover 切换与账户定制域名）
		dnsArgs := make([]any, 0, len(args)*2)
		dnsArgs = append(dnsArgs, args...)
		dnsArgs = append(dnsArgs, args...)
		drows, err := db.QueryContext(ctx, `WITH refs(node_id, rid) AS (
			SELECT node_id, access_host_id FROM node_server
				WHERE node_id IN `+in+` AND access_host_id IS NOT NULL AND deployed <> 2
			UNION
			SELECT node_id, domain_dns_record_id FROM account_node_subscription_domain_binding
				WHERE node_id IN `+in+` AND enabled = 1 AND domain_dns_record_id IS NOT NULL)
			SELECT refs.node_id, trim(coalesce(r.content,'')), trim(coalesce(r.full_name, r.name, ''))
			FROM refs JOIN domain_dns_record r ON r.id = refs.rid
			WHERE trim(coalesce(r.content,'')) <> '' AND trim(coalesce(r.full_name, r.name, '')) <> ''
			ORDER BY refs.node_id, r.id`, dnsArgs...)
		if err != nil {
			return out, fmt.Errorf("读取在线节点域名指向: %w", err)
		}
		for drows.Next() {
			var nid int64
			var ip, host string
			if err := drows.Scan(&nid, &ip, &host); err != nil {
				drows.Close()
				return out, err
			}
			if m, ok := out.Nodes[nid]; ok {
				m.PointedHostsByServer[ip] = appendUniqueString(m.PointedHostsByServer[ip], host)
				m.FallbackHosts = appendUniqueString(m.FallbackHosts, host)
				out.Nodes[nid] = m
			}
		}
		if err := drows.Err(); err != nil {
			drows.Close()
			return out, err
		}
		drows.Close()
	}

	var uniqueIPs []string
	for _, ip := range serverIPs {
		uniqueIPs = appendUniqueString(uniqueIPs, strings.TrimSpace(ip))
	}
	if len(uniqueIPs) > 0 {
		in := `(?` + strings.Repeat(",?", len(uniqueIPs)-1) + `)`
		args := make([]any, len(uniqueIPs))
		for i, ip := range uniqueIPs {
			args[i] = ip
		}
		rows, err := db.QueryContext(ctx, `SELECT id, coalesce(nullif(name,''), ip), ip, coalesce(region,'') FROM server WHERE ip IN `+in+` ORDER BY disabled, id`, args...)
		if err != nil {
			return out, fmt.Errorf("读取在线服务器信息: %w", err)
		}
		defer rows.Close()
		for rows.Next() {
			var s TopologyServerMeta
			if err := rows.Scan(&s.ID, &s.Name, &s.IP, &s.Region); err != nil {
				return out, err
			}
			if _, exists := out.Servers[s.IP]; !exists {
				out.Servers[s.IP] = s
			}
		}
		if err := rows.Err(); err != nil {
			return out, err
		}
	}
	return out, nil
}
