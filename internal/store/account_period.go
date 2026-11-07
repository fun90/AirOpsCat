package store

import (
	"context"
	"database/sql"
	"fmt"
	"time"
)

// periodLabel 是日志里的周期文本。
func periodLabel(u *TrafficUsage) string {
	return u.PeriodStart.Format("2006-01-02") + " 至 " + u.PeriodEnd.Format("2006-01-02")
}

var periodNames = map[string]string{PeriodMonthly: "月度", PeriodYearly: "年度"}

// restartTrafficPeriod 在周期类型变化时从 at 起开始新周期：锚点移到 at，当期行截断到 at 保留为历史，
// 从 at 起的行（同一时刻再次修改留下的零长度周期）删除；新周期用量从 0 计，配额取账户配额。
// 超额解除与限速由 limit-sync 自动下发，无需部署。
func restartTrafficPeriod(ctx context.Context, tx *sql.Tx, old *Account, period string, at time.Time) error {
	u, err := accountUsage(ctx, tx, old.ID, at)
	if err != nil {
		return err
	}
	stamp := FormatTime(at)
	if _, err := tx.ExecContext(ctx, `DELETE FROM account_traffic_stats WHERE account_id=? AND period_start>=?`, old.ID, stamp); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE account_traffic_stats SET period_end=?, update_time=? WHERE account_id=? AND period_start<? AND period_end>?`,
		stamp, stamp, old.ID, stamp, stamp); err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE account SET traffic_anchor=? WHERE id=?`, stamp, old.ID); err != nil {
		return err
	}
	return writeLog(ctx, tx, "ACCOUNT", "INFO", &old.ID, accountLabel(old),
		fmt.Sprintf("流量周期由%s改为%s，从现在开始新周期", periodNames[old.PeriodType], periodNames[period]),
		fmt.Sprintf("原周期 %s 截断到 %s，已用 %.2f GB 保留为历史，本期用量从 0 计", periodLabel(u), stamp, float64(u.UsedBytes)/(1<<30)))
}

func accountForPeriodOp(ctx context.Context, tx *sql.Tx, id int64) (*Account, error) {
	a, err := loadAccount(ctx, tx, id)
	if err != nil {
		return nil, err
	}
	if a == nil {
		return nil, sql.ErrNoRows
	}
	return a, nil
}

// AddAccountPeriodQuota 只给当期加量 gb（GB，与配额单位一致）：改当期统计行的配额快照，当期行不存在时按当前周期创建；
// 不改账户配额，下一周期恢复账户配额。不限量账户拒绝加量。超额判定与限速由 limit-sync 按新配额自动下发，无需部署。
// 返回加量后的当期配额。
func AddAccountPeriodQuota(ctx context.Context, db *sql.DB, id int64, gb int, at time.Time) (int64, error) {
	if gb < 1 || gb > 100000 {
		return 0, invalid("加量需为 1–100000 的整数 GB")
	}
	var quota int64
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		a, err := accountForPeriodOp(ctx, tx, id)
		if err != nil {
			return err
		}
		u, err := accountUsage(ctx, tx, id, at)
		if err != nil {
			return err
		}
		if u.QuotaGB <= 0 {
			return invalid("该账户本期不限量，无需加量")
		}
		quota = u.QuotaGB + int64(gb)
		stamp := FormatTime(at)
		if _, err := tx.ExecContext(ctx, `INSERT INTO account_traffic_stats(account_id,user_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes,create_time,update_time)
 SELECT id,user_id,?,?,?,0,0,?,? FROM account WHERE id=?
 ON CONFLICT(account_id,period_start) DO UPDATE SET bandwidth_quota=excluded.bandwidth_quota,update_time=excluded.update_time`,
			FormatTime(u.PeriodStart), FormatTime(u.PeriodEnd), quota, stamp, stamp, id); err != nil {
			return err
		}
		return writeLog(ctx, tx, "ACCOUNT", "INFO", &id, accountLabel(a), fmt.Sprintf("本期加量 %d GB", gb),
			fmt.Sprintf("周期 %s，本期配额 %d → %d GB；账户配额 %d GB 不变", periodLabel(u), u.QuotaGB, quota, a.Bandwidth))
	})
	if err != nil {
		return 0, err
	}
	return quota, nil
}

// ResetAccountPeriodUsage 把当期统计行的上下行用量清零，不改配额与服务器读数基线；当期没有统计行时用量本为 0。
// 超额解除由 limit-sync 自动下发，无需部署。返回清零前的用量字节数。
func ResetAccountPeriodUsage(ctx context.Context, db *sql.DB, id int64, at time.Time) (int64, error) {
	var used int64
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		a, err := accountForPeriodOp(ctx, tx, id)
		if err != nil {
			return err
		}
		u, err := accountUsage(ctx, tx, id, at)
		if err != nil {
			return err
		}
		used = u.UsedBytes
		if used == 0 {
			return nil
		}
		if _, err := tx.ExecContext(ctx, `UPDATE account_traffic_stats SET upload_bytes=0,download_bytes=0,update_time=? WHERE account_id=? AND period_start=?`,
			FormatTime(at), id, FormatTime(u.PeriodStart)); err != nil {
			return err
		}
		return writeLog(ctx, tx, "ACCOUNT", "INFO", &id, accountLabel(a), "重置本期用量",
			fmt.Sprintf("周期 %s，清零前用量 %.2f GB", periodLabel(u), float64(used)/(1<<30)))
	})
	if err != nil {
		return 0, err
	}
	return used, nil
}
