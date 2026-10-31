package store

import (
	"context"
	"database/sql"
	"fmt"
	"slices"
	"time"
)

// RenewalItem 是续费页的一行：服务器或域名。
type RenewalItem struct {
	Table, Name, Supplier, ExpireDate string
	ID                                int64
	Price                             *int64 // 分，对应 PriceMonths 个月
	PriceMonths                       int    // 域名固定按年
}

// ListRenewals 返回未禁用服务器与全部域名，按到期日升序，无到期日的排最后。
func ListRenewals(ctx context.Context, db *sql.DB) ([]RenewalItem, error) {
	rows, err := db.QueryContext(ctx, `SELECT t, id, name, supplier, expire, price, months FROM (
		SELECT 'server' t, id, trim(ip || CASE WHEN coalesce(name,'')<>'' THEN ' (' || name || ')' ELSE '' END) name, coalesce(supplier,'') supplier, coalesce(expire_date,'') expire, price, price_months months FROM server WHERE disabled = 0
		UNION ALL SELECT 'domain', id, coalesce(domain,''), coalesce(supplier,''), coalesce(expire_date,''), price, 12 FROM domain)
		ORDER BY expire = '', expire, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []RenewalItem
	for rows.Next() {
		var it RenewalItem
		var price sql.NullInt64
		if err := rows.Scan(&it.Table, &it.ID, &it.Name, &it.Supplier, &it.ExpireDate, &price, &it.PriceMonths); err != nil {
			return nil, err
		}
		if price.Valid {
			it.Price = &price.Int64
		}
		out = append(out, it)
	}
	return out, rows.Err()
}

// RenewResource 把服务器或域名到期日改为 newExpire（须晚于今天和原到期日），
// 并在同一事务记一笔金额为 amountCents 的支出，月数按日历月差写入流水说明。
func RenewResource(ctx context.Context, db *sql.DB, table string, id int64, newExpire string, amountCents int64, method string) error {
	if table != "server" && table != "domain" {
		return invalid("续费对象不正确")
	}
	next, err := time.ParseInLocation("2006-01-02", newExpire, Shanghai)
	if err != nil {
		return invalid("请填写有效的新到期日")
	}
	if amountCents <= 0 || amountCents > maxCents {
		return invalid("请输入有效金额")
	}
	if !slices.Contains(PaymentMethods, method) {
		return invalid("付款方式不合法")
	}
	return inTx(ctx, db, func(tx *sql.Tx) error {
		var expire, name string
		q := `SELECT coalesce(expire_date,''), coalesce(domain,'') FROM domain WHERE id=?`
		if table == "server" {
			q = `SELECT coalesce(expire_date,''), trim(ip || CASE WHEN coalesce(name,'')<>'' THEN ' (' || name || ')' ELSE '' END) FROM server WHERE id=?`
		}
		if err := tx.QueryRowContext(ctx, q, id).Scan(&expire, &name); err != nil {
			return err // sql.ErrNoRows 由调用方映射为 404
		}
		today := nowFunc().In(Shanghai)
		base := time.Date(today.Year(), today.Month(), today.Day(), 0, 0, 0, 0, Shanghai)
		if expire != "" {
			old, err := time.ParseInLocation("2006-01-02", expire, Shanghai)
			if err != nil {
				return invalid("原到期日格式不正确")
			}
			if old.After(base) {
				base = old
			}
		}
		if !next.After(base) {
			return invalid("新到期日必须晚于 %s", base.Format("2006-01-02"))
		}
		now := FormatTime(nowFunc())
		if _, err := tx.ExecContext(ctx, `UPDATE `+table+` SET expire_date=?, update_time=? WHERE id=?`, newExpire, now, id); err != nil {
			return err
		}
		label := map[string]string{"server": "服务器", "domain": "域名"}[table]
		if _, err := tx.ExecContext(ctx, `INSERT INTO transactions (transaction_date, amount, type, business_table, business_id, description, payment_method, create_time, update_time)
			VALUES (?, ?, 1, ?, ?, ?, ?, ?, ?)`, now, amountCents, table, id, fmt.Sprintf("%s续费：%s %d个月", label, name, calendarMonths(base, next)), method, now, now); err != nil {
			return err
		}
		return nil
	})
}
