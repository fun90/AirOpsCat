package store

import (
	"context"
	"database/sql"
	"slices"
	"strings"
)

// BatchAccountTags 批量调整账户标签：APPEND 追加、REPLACE 替换（标签为空即清空）。
// 标签有变化的账户按调整前后的标签把相关节点置为待部署；返回变化与未变化的账户数。
func BatchAccountTags(ctx context.Context, db *sql.DB, accountIDs, tagIDs []int64, mode string) (updated, unchanged int, err error) {
	mode = strings.ToUpper(strings.TrimSpace(mode))
	if mode == "" {
		mode = "REPLACE"
	}
	if mode != "REPLACE" && mode != "APPEND" {
		return 0, 0, invalid("不支持的批量标签调整模式")
	}
	accountIDs, tagIDs = normalizeTagIDs(accountIDs), normalizeTagIDs(tagIDs)
	if len(accountIDs) == 0 {
		return 0, 0, invalid("请选择要调整标签的账户")
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
			if mode == "APPEND" {
				target = normalizeTagIDs(append(target, old.TagIDs...))
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
