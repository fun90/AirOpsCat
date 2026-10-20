package store

import (
	"context"
	"database/sql"
	"math"
	"time"
)

// QuotaAlert 是当前周期已用满配额的活跃账户。
type QuotaAlert struct {
	ID                int64
	AccountNo, Remark string
	PeriodStart       string
	UsedGB, QuotaGB   float64
}

// OverQuotaAccounts 与限速判定口径一致：已用（上+下）/GiB 不小于配额，配额 0 为不限。
func OverQuotaAccounts(ctx context.Context, db *sql.DB, at time.Time) ([]QuotaAlert, error) {
	stamp := FormatTime(at)
	rows, err := db.QueryContext(ctx, `SELECT a.id,a.account_no,coalesce(a.remark,''),s.period_start,s.upload_bytes+s.download_bytes,coalesce(s.bandwidth_quota,a.bandwidth,0)
 FROM account a JOIN account_traffic_stats s ON s.id=(SELECT t.id FROM account_traffic_stats t WHERE t.account_id=a.id AND t.period_start<=? AND t.period_end>? ORDER BY t.period_start DESC,t.id DESC LIMIT 1)
 WHERE a.disabled=0 AND (a.to_date IS NULL OR a.to_date>?)`, stamp, stamp, stamp)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []QuotaAlert
	for rows.Next() {
		var q QuotaAlert
		var used, quota int64
		if err := rows.Scan(&q.ID, &q.AccountNo, &q.Remark, &q.PeriodStart, &used, &quota); err != nil {
			return nil, err
		}
		if quota <= 0 || used/(1<<30) < quota {
			continue
		}
		q.UsedGB, q.QuotaGB = math.Round(float64(used)/(1<<30)*100)/100, float64(quota)
		out = append(out, q)
	}
	return out, rows.Err()
}

// ExpiringItem 是即将到期（含已过期）的资源。
type ExpiringItem struct {
	Kind        string // server / domain / account
	ID          int64
	Name, Extra string
	Expire      string // YYYY-MM-DD
}

// ExpiringResources 返回当天到期的服务器、域名和账户（与既有行为一致，只在到期当天提醒）；
// 已禁用/外部服务器与已禁用账户不提醒。
func ExpiringResources(ctx context.Context, db *sql.DB, at time.Time) ([]ExpiringItem, error) {
	today := time.Date(at.In(Shanghai).Year(), at.In(Shanghai).Month(), at.In(Shanghai).Day(), 0, 0, 0, 0, Shanghai)
	limit := today.Format("2006-01-02")
	var out []ExpiringItem
	add := func(kind, q string) error {
		rows, err := db.QueryContext(ctx, q, limit)
		if err != nil {
			return err
		}
		defer rows.Close()
		for rows.Next() {
			var it ExpiringItem
			it.Kind = kind
			if err := rows.Scan(&it.ID, &it.Name, &it.Extra, &it.Expire); err != nil {
				return err
			}
			if len(it.Expire) > 10 {
				it.Expire = it.Expire[:10]
			}
			out = append(out, it)
		}
		return rows.Err()
	}
	if err := add("server", `SELECT id,coalesce(nullif(name,''),ip),ip,substr(expire_date,1,10) FROM server WHERE disabled=0 AND external_server=0 AND expire_date IS NOT NULL AND expire_date<>'' AND substr(expire_date,1,10)=?`); err != nil {
		return nil, err
	}
	if err := add("domain", `SELECT id,domain,coalesce(supplier,''),substr(expire_date,1,10) FROM domain WHERE domain<>'' AND expire_date IS NOT NULL AND expire_date<>'' AND substr(expire_date,1,10)=?`); err != nil {
		return nil, err
	}
	if err := add("account", `SELECT id,coalesce(nullif(remark,''),account_no),account_no,substr(to_date,1,10) FROM account WHERE disabled=0 AND to_date IS NOT NULL AND to_date<>'' AND substr(to_date,1,10)=?`); err != nil {
		return nil, err
	}
	return out, nil
}
