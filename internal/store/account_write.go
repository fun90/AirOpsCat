package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"slices"
	"strings"
	"time"
)

func validatePayment(amount *int64, method string) error {
	if amount == nil {
		return nil
	}
	if *amount <= 0 || *amount > maxCents {
		return invalid("请输入有效金额")
	}
	if method == "" {
		return invalid("填写金额时必须选择付款方式")
	}
	if !slices.Contains(PaymentMethods, method) {
		return invalid("付款方式不合法")
	}
	return nil
}

// column 只接受内部常量，不拼接用户输入。
func taken(ctx context.Context, q dbtx, column, val string, exceptID int64) (bool, error) {
	var n int
	err := q.QueryRowContext(ctx, `SELECT count(*) FROM account WHERE `+column+` = ? AND id <> ?`, val, exceptID).Scan(&n)
	return n > 0, err
}

func checkUnique(ctx context.Context, q dbtx, column, label, val string, exceptID int64) error {
	t, err := taken(ctx, q, column, val, exceptID)
	if err != nil {
		return err
	}
	if t {
		return fmt.Errorf("%w：%s已存在", ErrConflict, label)
	}
	return nil
}

// freshValue 生成库中不存在的随机值。
func freshValue(ctx context.Context, q dbtx, column string, gen func() string) (string, error) {
	for range 10 {
		v := gen()
		if t, err := taken(ctx, q, column, v, 0); err != nil || !t {
			return v, err
		}
	}
	return "", errors.New("生成唯一值失败")
}

func userExists(ctx context.Context, q dbtx, id int64) error {
	var n int
	if err := q.QueryRowContext(ctx, `SELECT count(*) FROM user WHERE id = ?`, id).Scan(&n); err != nil {
		return err
	}
	if n == 0 {
		return invalid("用户不存在")
	}
	return nil
}

func setAccountTags(ctx context.Context, q dbtx, accountID int64, tagIDs []int64) error {
	if _, err := q.ExecContext(ctx, `DELETE FROM account_tag WHERE account_id = ?`, accountID); err != nil {
		return err
	}
	for _, id := range tagIDs {
		if _, err := q.ExecContext(ctx, `INSERT INTO account_tag (account_id, tag_id) VALUES (?, ?)`, accountID, id); err != nil {
			return invalid("标签不存在")
		}
	}
	return nil
}

// markAccountPending 把与账户共享标签的节点的部署位置为待部署（决策 24 D2）。
func markAccountPending(ctx context.Context, q dbtx, accountID int64) error {
	_, err := q.ExecContext(ctx, `UPDATE node_server SET deployed = 0, update_time = ? WHERE deployed <> 2 AND node_id IN
		(SELECT nt.node_id FROM account_tag at JOIN node_tag nt ON nt.tag_id = at.tag_id WHERE at.account_id = ?)`,
		FormatTime(nowFunc()), accountID)
	return err
}

// calendarMonths 按日历月差计算流水描述里的月数，最小 1。
func calendarMonths(from, to time.Time) int {
	from, to = from.In(Shanghai), to.In(Shanghai)
	if !to.After(from) {
		return 1
	}
	return max(1, (to.Year()-from.Year())*12+int(to.Month())-int(from.Month()))
}

func insertAccountIncome(ctx context.Context, q dbtx, accountID int64, cents int64, method, desc, remark string) (int64, error) {
	now := FormatTime(nowFunc())
	res, err := q.ExecContext(ctx, `INSERT INTO transactions (transaction_date, amount, type, business_table, business_id, description, payment_method, remark, create_time, update_time)
		VALUES (?, ?, 0, 'account', ?, ?, ?, ?, ?, ?)`, now, cents, accountID, desc, method, nz(remark), now, now)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

func insertAccount(ctx context.Context, q dbtx, a *Account) error {
	var err error
	for _, f := range []struct {
		col, label string
		val        *string
		n          int
	}{{"account_no", "账户编号", &a.AccountNo, 6}, {"auth_code", "订阅码", &a.AuthCode, 16}} {
		if *f.val == "" {
			*f.val, err = freshValue(ctx, q, f.col, func() string { return randHex(f.n) })
		} else {
			err = checkUnique(ctx, q, f.col, f.label, *f.val, 0)
		}
		if err != nil {
			return err
		}
	}
	if err = checkUnique(ctx, q, "uuid", "UUID", a.UUID, 0); err != nil {
		return err
	}
	now := FormatTime(nowFunc())
	res, err := q.ExecContext(ctx, `INSERT INTO account (node_multiple, node_prefix, from_date, to_date, period_type, uuid, account_no, auth_code,
		max_connections, max_ips, bandwidth, download_mbps, upload_mbps, disabled, remark, user_id, create_time, update_time)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		a.NodeMultiple, nz(a.NodePrefix), dateArg(a.FromDate), dateArg(a.ToDate), a.PeriodType, a.UUID, a.AccountNo, a.AuthCode,
		nzInt(a.MaxConnections), nzInt(a.MaxIps), nzInt(a.Bandwidth), nzInt(a.DownloadMbps), nzInt(a.UploadMbps), a.Disabled, nz(a.Remark),
		nzInt(int(a.UserID)), now, now)
	if err != nil {
		if isUnique(err) {
			return fmt.Errorf("%w：账户编号、订阅码或 UUID 已存在", ErrConflict)
		}
		return err
	}
	a.ID, err = res.LastInsertId()
	return err
}

// Onboard 在一个事务内完成开户：可选新建用户、账户、标签关联和首期收入流水。
// 新账户的标签关联节点会置为待部署。
func Onboard(ctx context.Context, db *sql.DB, in OnboardInput) (*OnboardResult, error) {
	if (in.UserID != 0) == (in.NewUser != nil) {
		return nil, invalid("请选择且仅选择一种用户来源")
	}
	a := in.Account
	if err := a.normalize(true); err != nil {
		return nil, err
	}
	if err := validatePayment(in.AmountCents, in.PaymentMethod); err != nil {
		return nil, err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	res := &OnboardResult{}
	if in.NewUser != nil {
		u := *in.NewUser
		if err := insertUser(ctx, tx, &u, in.Password); err != nil {
			return nil, err
		}
		res.UserID = u.ID
	} else {
		if err := userExists(ctx, tx, in.UserID); err != nil {
			return nil, err
		}
		res.UserID = in.UserID
	}
	a.UserID = res.UserID
	if err := insertAccount(ctx, tx, &a); err != nil {
		return nil, err
	}
	res.AccountID = a.ID
	if err := setAccountTags(ctx, tx, a.ID, a.TagIDs); err != nil {
		return nil, err
	}
	if err := markAccountPending(ctx, tx, a.ID); err != nil {
		return nil, err
	}
	if in.AmountCents != nil {
		months := 1
		if a.ToDate != nil {
			months = calendarMonths(*a.FromDate, *a.ToDate)
		}
		if res.TransactionID, err = insertAccountIncome(ctx, tx, a.ID, *in.AmountCents, in.PaymentMethod, fmt.Sprintf("账号首期：%d月", months), a.Remark); err != nil {
			return nil, err
		}
	}
	return res, tx.Commit()
}

func sameIDs(a, b []int64) bool {
	x, y := slices.Clone(a), slices.Clone(b)
	slices.Sort(x)
	slices.Sort(y)
	return slices.Equal(x, y)
}

// expired 判断到期时间是否已过；节点配置只按有效性筛账户，有效期内改到期时间无需重新部署。
func expired(t *time.Time) bool { return t != nil && !t.After(nowFunc()) }

// UpdateAccount 全量覆盖账户字段。AccountNo、AuthCode 不可修改（传空或原值），重置订阅码用 ResetAuthCode；
// TagIDs 为 nil 时不改标签。UUID、启停、到期导致的有效性、标签变化会把相关节点置为待部署；
// 流量配额变化同步到当期统计行。
func UpdateAccount(ctx context.Context, db *sql.DB, id int64, a *Account) error {
	in := *a
	if err := in.normalize(false); err != nil {
		return err
	}
	return inTx(ctx, db, func(tx *sql.Tx) error {
		old, err := loadAccount(ctx, tx, id)
		if err != nil {
			return err
		}
		if old == nil {
			return sql.ErrNoRows
		}
		if in.AccountNo != "" && !strings.EqualFold(in.AccountNo, old.AccountNo) {
			return invalid("账户编号不可修改")
		}
		if in.AuthCode != "" && in.AuthCode != old.AuthCode {
			return invalid("订阅码不可直接修改，请使用重置")
		}
		if err := checkUnique(ctx, tx, "uuid", "UUID", in.UUID, id); err != nil {
			return err
		}
		if in.UserID != 0 && in.UserID != old.UserID {
			if err := userExists(ctx, tx, in.UserID); err != nil {
				return err
			}
		}
		tagsChanged := in.TagIDs != nil && !sameIDs(in.TagIDs, old.TagIDs)
		affectsNodes := in.MaxConnections != old.MaxConnections || in.MaxIps != old.MaxIps || in.DownloadMbps != old.DownloadMbps || in.UploadMbps != old.UploadMbps || in.Bandwidth != old.Bandwidth || tagsChanged || in.UUID != old.UUID || in.Disabled != old.Disabled || expired(in.ToDate) != expired(old.ToDate)
		if affectsNodes { // 先按旧标签置位，覆盖被移除的标签
			if err := markAccountPending(ctx, tx, id); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE account SET node_multiple=?, node_prefix=?, from_date=?, to_date=?, period_type=?, uuid=?,
			max_connections=?, max_ips=?, bandwidth=?, download_mbps=?, upload_mbps=?, disabled=?, remark=?, user_id=?, update_time=? WHERE id=?`,
			in.NodeMultiple, nz(in.NodePrefix), dateArg(in.FromDate), dateArg(in.ToDate), in.PeriodType, in.UUID,
			nzInt(in.MaxConnections), nzInt(in.MaxIps), nzInt(in.Bandwidth), nzInt(in.DownloadMbps), nzInt(in.UploadMbps), in.Disabled, nz(in.Remark),
			nzInt(int(in.UserID)), FormatTime(nowFunc()), id); err != nil {
			if isUnique(err) {
				return fmt.Errorf("%w：UUID 已存在", ErrConflict)
			}
			return err
		}
		if tagsChanged {
			if err := setAccountTags(ctx, tx, id, in.TagIDs); err != nil {
				return err
			}
			if err := cleanUnauthorizedBindings(ctx, tx, id); err != nil {
				return err
			}
		}
		if affectsNodes {
			if err := markAccountPending(ctx, tx, id); err != nil {
				return err
			}
		}
		if in.Bandwidth != old.Bandwidth { // 本轮周期锚点未单独存储：直接改当期行配额
			now := FormatTime(nowFunc())
			if _, err := tx.ExecContext(ctx, `UPDATE account_traffic_stats SET bandwidth_quota = ?, update_time = ? WHERE account_id = ? AND period_start <= ? AND period_end > ?`,
				nzInt(in.Bandwidth), now, id, now, now); err != nil {
				return err
			}
		}
		return nil
	})
}

// RenewAccount 设置新的到期时间（须晚于当前时刻及已有到期时间），自动启用被禁用的账户，
// amountCents 非 nil 时按「起点取 max(旧到期, 当前)」记一笔收入；恢复失效或禁用账户时相关节点置为待部署。
func RenewAccount(ctx context.Context, db *sql.DB, id int64, newToDate time.Time, amountCents *int64, paymentMethod string) error {
	now := nowFunc()
	if !newToDate.After(now) {
		return invalid("新的到期时间必须晚于当前时间")
	}
	if err := validatePayment(amountCents, paymentMethod); err != nil {
		return err
	}
	return inTx(ctx, db, func(tx *sql.Tx) error {
		old, err := loadAccount(ctx, tx, id)
		if err != nil {
			return err
		}
		if old == nil {
			return sql.ErrNoRows
		}
		if old.ToDate != nil && !newToDate.After(*old.ToDate) {
			return invalid("续期到期时间必须晚于原到期时间；调整到期时间请编辑账户")
		}
		if _, err := tx.ExecContext(ctx, `UPDATE account SET to_date = ?, disabled = 0, update_time = ? WHERE id = ?`,
			FormatTime(newToDate), FormatTime(now), id); err != nil {
			return err
		}
		if old.Disabled || (old.ToDate != nil && !old.ToDate.After(now)) {
			if err := markAccountPending(ctx, tx, id); err != nil {
				return err
			}
		}
		if amountCents != nil {
			start := now
			if old.ToDate != nil && old.ToDate.After(now) {
				start = *old.ToDate
			}
			desc := fmt.Sprintf("账号：%d月", calendarMonths(start, newToDate))
			if _, err := insertAccountIncome(ctx, tx, id, *amountCents, paymentMethod, desc, old.Remark); err != nil {
				return err
			}
		}
		return nil
	})
}

// SetAccountDisabled 启停账户，相关节点置为待部署。
func SetAccountDisabled(ctx context.Context, db *sql.DB, id int64, disabled bool) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		if err := execOne(tx.ExecContext(ctx, `UPDATE account SET disabled = ?, update_time = ? WHERE id = ?`, disabled, FormatTime(nowFunc()), id)); err != nil {
			return err
		}
		return markAccountPending(ctx, tx, id)
	})
}

// ResetAuthCode 重新生成订阅码并返回；订阅码不进节点配置，不需要部署。
func ResetAuthCode(ctx context.Context, db *sql.DB, id int64) (string, error) {
	var code string
	err := inTx(ctx, db, func(tx *sql.Tx) (err error) {
		if code, err = freshValue(ctx, tx, "auth_code", func() string { return randHex(16) }); err != nil {
			return err
		}
		return execOne(tx.ExecContext(ctx, `UPDATE account SET auth_code = ?, update_time = ? WHERE id = ?`, code, FormatTime(nowFunc()), id))
	})
	if err != nil {
		return "", err
	}
	return code, nil
}

// DeleteAccount 删除账户及其订阅定制绑定、告警状态、在线记录和流量统计，收支流水保留。
// 相关节点置为待部署。
func DeleteAccount(ctx context.Context, db *sql.DB, id int64) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		a, err := loadAccount(ctx, tx, id)
		if err != nil {
			return err
		}
		if a == nil {
			return sql.ErrNoRows
		}
		if err := markAccountPending(ctx, tx, id); err != nil {
			return err
		}
		var records []int64
		rows, err := tx.QueryContext(ctx, `SELECT domain_dns_record_id FROM account_node_subscription_domain_binding WHERE account_id = ? AND domain_dns_record_id IS NOT NULL`, id)
		if err != nil {
			return err
		}
		for rows.Next() {
			var r int64
			if err := rows.Scan(&r); err != nil {
				rows.Close()
				return err
			}
			records = append(records, r)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, stmt := range []struct {
			sql string
			arg any
		}{
			{`DELETE FROM alert_state WHERE resource_type = 'account' AND resource_id = ?`, id},
			{`DELETE FROM account_online_ip WHERE account_no = ?`, a.AccountNo},
			{`DELETE FROM account WHERE id = ?`, id}, // 绑定、标签关联、流量统计随外键级联删除
		} {
			if _, err := tx.ExecContext(ctx, stmt.sql, stmt.arg); err != nil {
				return err
			}
		}
		for _, r := range records {
			if err := releaseBindingRecord(ctx, tx, &r); err != nil {
				return err
			}
		}
		return nil
	})
}
