package store

import (
	"context"
	"database/sql"
	"fmt"
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
	RecentAlerts                                                     []Alert
	ActiveAlerts                                                     []Alert
}

// DashboardLoad 汇总资源状态、最近触发的五条告警（包含已恢复）及待处理告警。
// 服务器按上海日期统计今天至第七天到期的启用、非外部服务器，续费清单另含同窗口内到期的域名；
// 到期账户列出未来 days 天内到期且未禁用的账户。
// 待处理告警只取进行中且非到期类的告警：到期类与到期清单重复。
func DashboardLoad(ctx context.Context, db *sql.DB, now time.Time, days int) (*Dashboard, error) {
	if days < 1 {
		days = 1
	}
	d := new(Dashboard)
	local := now.In(Shanghai)
	err := db.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM account),
		(SELECT count(*) FROM account WHERE disabled=0 AND (to_date IS NULL OR to_date>@now)),
		(SELECT count(*) FROM account WHERE disabled=1),
		(SELECT count(*) FROM account WHERE to_date<=@now),
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
		sql.Named("now", FormatTime(now)), sql.Named("today", local.Format("2006-01-02")),
		sql.Named("soon", local.AddDate(0, 0, 7).Format("2006-01-02"))).Scan(
		&d.AccountsTotal, &d.AccountsActive, &d.AccountsDisabled, &d.AccountsExpired,
		&d.NodesEnabled, &d.NodesDisabled, &d.PendingPlacements,
		&d.ServersEnabled, &d.ServersExternal, &d.ServersExpiring,
		&d.AlertsActive, &d.AlertsAcknowledged, &d.ServersAbnormal)
	if err != nil {
		return nil, fmt.Errorf("读取概览统计: %w", err)
	}
	rows, err := db.QueryContext(ctx, alertSel+` ORDER BY last_triggered_time DESC, id DESC LIMIT 5`)
	if err != nil {
		return nil, fmt.Errorf("读取近期告警: %w", err)
	}
	d.RecentAlerts, err = scanAlerts(rows)
	if err != nil {
		return nil, fmt.Errorf("读取近期告警: %w", err)
	}
	rows, err = db.QueryContext(ctx, `SELECT id,coalesce(nullif(remark,''),account_no),account_no,to_date,disabled FROM account
		WHERE disabled=0 AND to_date>? AND to_date<? ORDER BY to_date,id`, FormatTime(now), FormatTime(now.AddDate(0, 0, days)))
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
