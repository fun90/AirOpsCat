package store

import (
	"context"
	"database/sql"
	"regexp"
	"slices"
	"strconv"
	"strings"
)

// BatchAccountTags 批量调整账户标签：APPEND 追加、REMOVE 移除所选标签、REPLACE 替换（标签为空即清空）。
// 标签有变化的账户按调整前后的标签把相关节点置为待部署；返回变化与未变化的账户数。
func BatchAccountTags(ctx context.Context, db *sql.DB, accountIDs, tagIDs []int64, mode string) (updated, unchanged int, err error) {
	mode = strings.ToUpper(strings.TrimSpace(mode))
	if mode == "" {
		mode = "REPLACE"
	}
	if mode != "REPLACE" && mode != "APPEND" && mode != "REMOVE" {
		return 0, 0, invalid("不支持的批量标签调整模式")
	}
	accountIDs, tagIDs = normalizeTagIDs(accountIDs), normalizeTagIDs(tagIDs)
	if len(accountIDs) == 0 {
		return 0, 0, invalid("请选择要调整标签的账户")
	}
	if mode == "REMOVE" && len(tagIDs) == 0 {
		return 0, 0, invalid("请选择要移除的标签")
	}
	err = inTx(ctx, db, func(tx *sql.Tx) error {
		for _, id := range tagIDs {
			var n int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM tag WHERE id=?`, id).Scan(&n); err != nil {
				return err
			}
			if n != 1 {
				return invalid("部分标签不存在，无法批量调整")
			}
		}
		for _, id := range accountIDs {
			old, err := loadAccount(ctx, tx, id)
			if err != nil {
				return err
			}
			if old == nil {
				return invalid("部分账户不存在，无法批量调整标签")
			}
			target := slices.Clone(tagIDs)
			switch mode {
			case "APPEND":
				target = normalizeTagIDs(append(target, old.TagIDs...))
			case "REMOVE":
				target = slices.DeleteFunc(slices.Clone(old.TagIDs), func(id int64) bool { return slices.Contains(tagIDs, id) })
			}
			if sameIDs(old.TagIDs, target) {
				unchanged++
				continue
			}
			if err := markAccountPending(ctx, tx, id); err != nil { // 先按旧标签置位，覆盖被移除的标签
				return err
			}
			if err := setAccountTags(ctx, tx, id, target); err != nil {
				return err
			}
			if err := cleanUnauthorizedBindings(ctx, tx, id); err != nil {
				return err
			}
			if err := markAccountPending(ctx, tx, id); err != nil {
				return err
			}
			updated++
		}
		return nil
	})
	if err != nil {
		return 0, 0, err
	}
	return updated, unchanged, nil
}

// LastIncome 是账户最近一笔账户收入流水，供续期弹窗预填；Months 为从描述「账号：N月 / 账号首期：N月」解析出的月数，无法解析为 0。
type LastIncome struct {
	Cents  int64
	Method string
	Months int
}

var incomeMonthsRe = regexp.MustCompile(`^账号(?:首期)?：(\d{1,3})月$`)

// AccountLastIncomes 返回各账户最近一笔（按交易时间、ID 倒序）收入流水；没有流水的账户不在结果中。
func AccountLastIncomes(ctx context.Context, db *sql.DB, ids []int64) (map[int64]LastIncome, error) {
	out := map[int64]LastIncome{}
	if len(ids) == 0 {
		return out, nil
	}
	args := make([]any, len(ids))
	for i, id := range ids {
		args[i] = id
	}
	rows, err := db.QueryContext(ctx, `SELECT t.business_id, t.amount, coalesce(t.payment_method,''), coalesce(t.description,'') FROM transactions t
		WHERE t.id = (SELECT x.id FROM transactions x WHERE x.business_table = 'account' AND x.business_id = t.business_id AND x.type = 0
			ORDER BY x.transaction_date DESC, x.id DESC LIMIT 1)
		AND t.business_id IN (?`+strings.Repeat(",?", len(ids)-1)+`)`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var v LastIncome
		var desc string
		if err := rows.Scan(&id, &v.Cents, &v.Method, &desc); err != nil {
			return nil, err
		}
		if m := incomeMonthsRe.FindStringSubmatch(strings.TrimSpace(desc)); m != nil {
			v.Months, _ = strconv.Atoi(m[1])
		}
		if !slices.Contains(PaymentMethods, v.Method) {
			v.Method = ""
		}
		out[id] = v
	}
	return out, rows.Err()
}
