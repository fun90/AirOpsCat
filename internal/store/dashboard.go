package store

import (
	"context"
	"database/sql"
	"fmt"
	"time"
)

// Dashboard 是概览页的真实统计；账户状态与账户列表筛选口径一致。
type DashboardExpiry struct {
	ID                       int64
	Name, AccountNo, Expires string
	Disabled                 bool
}

type Dashboard struct {
	Expiring24Hours                                                  []DashboardExpiry
	AccountsTotal, AccountsActive, AccountsDisabled, AccountsExpired int
	NodesEnabled, NodesDisabled, PendingPlacements                   int
	ServersEnabled, ServersExternal, ServersExpiring                 int
	AlertsActive, AlertsAcknowledged                                 int
	RecentAlerts                                                     []Alert
}

// DashboardLoad 汇总资源状态及最近触发的五条告警（包含已恢复）。
// 服务器按上海日期统计今天至第七天到期的启用、非外部服务器。
func DashboardLoad(ctx context.Context, db *sql.DB, now time.Time) (*Dashboard, error) {
	d := new(Dashboard)
	local := now.In(Shanghai)
	err := db.QueryRowContext(ctx, `SELECT
		(SELECT count(*) FROM account),
		(SELECT count(*) FROM account WHERE disabled=0 AND (to_date IS NULL OR to_date>=@now)),
		(SELECT count(*) FROM account WHERE disabled=1),
		(SELECT count(*) FROM account WHERE disabled=0 AND to_date<@now),
		(SELECT count(*) FROM node WHERE disabled=0),
		(SELECT count(*) FROM node WHERE disabled=1),
		(SELECT count(*) FROM node_server WHERE deployed=0),
		(SELECT count(*) FROM server WHERE disabled=0),
		(SELECT count(*) FROM server WHERE external_server=1),
		(SELECT count(*) FROM server WHERE disabled=0 AND external_server=0
			AND substr(expire_date,1,10)>=@today AND substr(expire_date,1,10)<=@soon),
		(SELECT count(*) FROM alert_state WHERE status='ACTIVE'),
		(SELECT count(*) FROM alert_state WHERE status='ACKNOWLEDGED')`,
		sql.Named("now", FormatTime(now)), sql.Named("today", local.Format("2006-01-02")),
		sql.Named("soon", local.AddDate(0, 0, 7).Format("2006-01-02"))).Scan(
		&d.AccountsTotal, &d.AccountsActive, &d.AccountsDisabled, &d.AccountsExpired,
		&d.NodesEnabled, &d.NodesDisabled, &d.PendingPlacements,
		&d.ServersEnabled, &d.ServersExternal, &d.ServersExpiring,
		&d.AlertsActive, &d.AlertsAcknowledged)
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
	start := now
	rows, err = db.QueryContext(ctx, `SELECT id,coalesce(nullif(remark,''),account_no),account_no,to_date,disabled FROM account WHERE to_date>=? AND to_date<? ORDER BY to_date,id`, FormatTime(start), FormatTime(start.Add(24*time.Hour)))
	if err != nil {
		return nil, fmt.Errorf("读取24小时内到期账户: %w", err)
	}
	defer rows.Close()
	for rows.Next() {
		var a DashboardExpiry
		if err := rows.Scan(&a.ID, &a.Name, &a.AccountNo, &a.Expires, &a.Disabled); err != nil {
			return nil, err
		}
		d.Expiring24Hours = append(d.Expiring24Hours, a)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	return d, nil
}
