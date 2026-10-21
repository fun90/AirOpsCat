package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"math"
	"slices"
	"strings"
	"time"
	"unicode/utf8"
)

const (
	TransactionIncome  = 0
	TransactionExpense = 1
)

var TransactionBusinessTables = []string{"account", "domain", "server"}

type Transaction struct {
	ID              int64
	TransactionDate time.Time
	Amount          int64
	Type            int
	BusinessTable   string
	BusinessID      *int64
	BusinessName    string
	Description     string
	PaymentMethod   string
	Remark          string
}

type TransactionFilter struct {
	Q             string
	Type          *int
	BusinessTable string
	StartDate     *time.Time
	EndDate       *time.Time
}

// TransactionBusinessOption 是表单中可选择的关联业务，避免手填 ID。
type TransactionBusinessOption struct {
	Table string
	ID    int64
	Name  string
}

const transactionColumns = `t.id,t.transaction_date,t.amount,t.type,coalesce(t.business_table,''),t.business_id,
	coalesce(t.description,''),coalesce(t.payment_method,''),coalesce(t.remark,''),
	CASE t.business_table
		WHEN 'account' THEN coalesce(nullif(a.remark,''),a.account_no,'未知账户')
		WHEN 'domain' THEN coalesce(nullif(d.domain,''),'未知域名')
		WHEN 'server' THEN coalesce(nullif(trim(coalesce(s.ip,'') || CASE WHEN coalesce(s.name,'') <> '' THEN ' (' || s.name || ')' ELSE '' END),''),'未知服务器')
		ELSE '' END`

func scanTransaction(row interface{ Scan(...any) error }) (*Transaction, error) {
	var t Transaction
	var date string
	var businessID sql.NullInt64
	if err := row.Scan(&t.ID, &date, &t.Amount, &t.Type, &t.BusinessTable, &businessID, &t.Description, &t.PaymentMethod, &t.Remark, &t.BusinessName); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	var err error
	if t.TransactionDate, err = ParseTime(date); err != nil {
		return nil, err
	}
	if businessID.Valid {
		t.BusinessID = &businessID.Int64
	}
	if t.BusinessTable != "" && t.BusinessName == "" {
		t.BusinessName = "未知关联业务"
	}
	return &t, nil
}

func transactionWhere(f TransactionFilter) (string, []any) {
	var where []string
	var args []any
	if q := strings.TrimSpace(f.Q); q != "" {
		like := "%" + likeEscape(q) + "%"
		where = append(where, `(coalesce(t.description,'') LIKE ? ESCAPE '\' OR coalesce(t.remark,'') LIKE ? ESCAPE '\' OR coalesce(t.payment_method,'') LIKE ? ESCAPE '\')`)
		args = append(args, like, like, like)
	}
	if f.Type != nil {
		where, args = append(where, "t.type = ?"), append(args, *f.Type)
	}
	if f.BusinessTable != "" {
		where, args = append(where, "t.business_table = ?"), append(args, f.BusinessTable)
	}
	if f.StartDate != nil {
		where, args = append(where, "t.transaction_date >= ?"), append(args, FormatTime(*f.StartDate))
	}
	if f.EndDate != nil {
		where, args = append(where, "t.transaction_date <= ?"), append(args, FormatTime(*f.EndDate))
	}
	if len(where) == 0 {
		return "", args
	}
	return " WHERE " + strings.Join(where, " AND "), args
}

// TransactionList 按发生日期倒序分页。金额始终以整数分返回。
func TransactionList(ctx context.Context, db *sql.DB, f TransactionFilter, page, size int) ([]*Transaction, int, error) {
	where, args := transactionWhere(f)
	var total int
	if err := db.QueryRowContext(ctx, "SELECT count(*) FROM transactions t"+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	limit, offset := pageArgs(page, size)
	rows, err := db.QueryContext(ctx, `SELECT `+transactionColumns+` FROM transactions t
		LEFT JOIN account a ON t.business_table = 'account' AND a.id = t.business_id
		LEFT JOIN domain d ON t.business_table = 'domain' AND d.id = t.business_id
		LEFT JOIN server s ON t.business_table = 'server' AND s.id = t.business_id`+where+` ORDER BY t.transaction_date DESC,t.id DESC LIMIT ? OFFSET ?`, append(args, limit, offset)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*Transaction
	for rows.Next() {
		t, err := scanTransaction(rows)
		if err != nil {
			return nil, 0, err
		}
		list = append(list, t)
	}
	return list, total, rows.Err()
}

type TransactionTotals struct {
	Income, Expense, Balance int64
}

// TransactionSummary 汇总当前筛选的全部记录，金额单位为分。
func TransactionSummary(ctx context.Context, db *sql.DB, f TransactionFilter) (TransactionTotals, error) {
	where, args := transactionWhere(f)
	var totals TransactionTotals
	err := db.QueryRowContext(ctx, `SELECT coalesce(sum(CASE WHEN type=0 THEN amount ELSE 0 END),0), coalesce(sum(CASE WHEN type=1 THEN amount ELSE 0 END),0) FROM transactions t`+where, args...).Scan(&totals.Income, &totals.Expense)
	if err != nil {
		return totals, err
	}
	if (totals.Expense < 0 && totals.Income > math.MaxInt64+totals.Expense) || (totals.Expense > 0 && totals.Income < math.MinInt64+totals.Expense) {
		return totals, fmt.Errorf("收支净额超出整数范围")
	}
	totals.Balance = totals.Income - totals.Expense
	return totals, nil
}

func TransactionByID(ctx context.Context, db *sql.DB, id int64) (*Transaction, error) {
	return scanTransaction(db.QueryRowContext(ctx, `SELECT `+transactionColumns+` FROM transactions t
		LEFT JOIN account a ON t.business_table = 'account' AND a.id = t.business_id
		LEFT JOIN domain d ON t.business_table = 'domain' AND d.id = t.business_id
		LEFT JOIN server s ON t.business_table = 'server' AND s.id = t.business_id WHERE t.id = ?`, id))
}

func validateTransaction(ctx context.Context, q dbtx, t *Transaction) error {
	t.BusinessTable = strings.TrimSpace(t.BusinessTable)
	t.Description = strings.TrimSpace(t.Description)
	t.PaymentMethod = strings.TrimSpace(t.PaymentMethod)
	t.Remark = strings.TrimSpace(t.Remark)
	if t.Amount <= 0 {
		return invalid("金额必须大于 0")
	}
	if t.Type != TransactionIncome && t.Type != TransactionExpense {
		return invalid("收支类型不正确")
	}
	for _, v := range []struct{ label, value string }{{"说明", t.Description}, {"付款方式", t.PaymentMethod}, {"备注", t.Remark}} {
		if utf8.RuneCountInString(v.value) > 255 {
			return invalid("%s不能超过 255 个字符", v.label)
		}
	}
	if t.PaymentMethod != "" && !slices.Contains(PaymentMethods, t.PaymentMethod) {
		return invalid("付款方式不合法")
	}
	if (t.BusinessTable == "") != (t.BusinessID == nil) {
		return invalid("关联业务需同时选择类型和记录")
	}
	if t.BusinessTable == "" {
		return nil
	}
	if !slices.Contains(TransactionBusinessTables, t.BusinessTable) || *t.BusinessID <= 0 {
		return invalid("关联业务不正确")
	}
	var count int
	if err := q.QueryRowContext(ctx, `SELECT count(*) FROM `+t.BusinessTable+` WHERE id = ?`, *t.BusinessID).Scan(&count); err != nil {
		return err
	}
	if count == 0 {
		return invalid("所选关联业务不存在")
	}
	return nil
}

// SaveTransaction 新建（ID=0）或全量更新一笔流水；关联业务必须来自允许表且确实存在。
func SaveTransaction(ctx context.Context, db *sql.DB, t *Transaction) error {
	if t.TransactionDate.IsZero() {
		t.TransactionDate = nowFunc()
	}
	t.TransactionDate = t.TransactionDate.In(Shanghai)
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	if err := validateTransaction(ctx, tx, t); err != nil {
		return err
	}
	now := FormatTime(nowFunc())
	if t.ID == 0 {
		res, err := tx.ExecContext(ctx, `INSERT INTO transactions (transaction_date,amount,type,business_table,business_id,description,payment_method,remark,create_time,update_time) VALUES (?,?,?,?,?,?,?,?,?,?)`, FormatTime(t.TransactionDate), t.Amount, t.Type, nz(t.BusinessTable), t.BusinessID, nz(t.Description), nz(t.PaymentMethod), nz(t.Remark), now, now)
		if err != nil {
			return err
		}
		t.ID, err = res.LastInsertId()
		if err != nil {
			return err
		}
	} else {
		res, err := tx.ExecContext(ctx, `UPDATE transactions SET transaction_date=?,amount=?,type=?,business_table=?,business_id=?,description=?,payment_method=?,remark=?,update_time=? WHERE id=?`, FormatTime(t.TransactionDate), t.Amount, t.Type, nz(t.BusinessTable), t.BusinessID, nz(t.Description), nz(t.PaymentMethod), nz(t.Remark), now, t.ID)
		if err != nil {
			return err
		}
		if n, _ := res.RowsAffected(); n == 0 {
			return sql.ErrNoRows
		}
	}
	return tx.Commit()
}

func DeleteTransaction(ctx context.Context, db *sql.DB, id int64) error {
	res, err := db.ExecContext(ctx, "DELETE FROM transactions WHERE id = ?", id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

// TransactionBusinessOptions 返回可关联的现有业务记录，用于可搜索选择器。
func TransactionBusinessOptions(ctx context.Context, db *sql.DB) ([]TransactionBusinessOption, error) {
	rows, err := db.QueryContext(ctx, `SELECT 'account',id,coalesce(nullif(remark,''),account_no) FROM account
		UNION ALL SELECT 'domain',id,coalesce(domain,'') FROM domain
		UNION ALL SELECT 'server',id,trim(ip || CASE WHEN coalesce(name,'') <> '' THEN ' (' || name || ')' ELSE '' END) FROM server
		ORDER BY 1,3,2`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []TransactionBusinessOption
	for rows.Next() {
		var o TransactionBusinessOption
		if err := rows.Scan(&o.Table, &o.ID, &o.Name); err != nil {
			return nil, err
		}
		if o.Name == "" {
			o.Name = fmt.Sprintf("记录 #%d", o.ID)
		}
		list = append(list, o)
	}
	return list, rows.Err()
}
