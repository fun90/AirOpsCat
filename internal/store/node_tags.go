package store

import (
	"context"
	"database/sql"
	"slices"
	"strings"
)

// BatchNodeTags 原子调整节点授权标签，只将实际变化的节点置待部署。
func BatchNodeTags(ctx context.Context, db *sql.DB, nodeIDs, tagIDs []int64, mode string) (updated, unchanged int, err error) {
	mode = strings.ToUpper(strings.TrimSpace(mode))
	if mode == "" {
		mode = "REPLACE"
	}
	if mode != "REPLACE" && mode != "APPEND" {
		return 0, 0, invalid("不支持的批量标签调整模式")
	}
	nodeIDs, tagIDs = normalizeTagIDs(nodeIDs), normalizeTagIDs(tagIDs)
	if len(nodeIDs) == 0 {
		return 0, 0, invalid("请选择要调整标签的节点")
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()
	for _, id := range tagIDs {
		var count int
		if err = tx.QueryRowContext(ctx, `SELECT count(*) FROM tag WHERE id=?`, id).Scan(&count); err != nil {
			return 0, 0, err
		}
		if count != 1 {
			return 0, 0, invalid("部分标签不存在，无法批量调整")
		}
	}
	for _, id := range nodeIDs {
		node, e := loadNode(ctx, tx, id)
		if e != nil {
			return 0, 0, e
		}
		if node == nil {
			return 0, 0, invalid("部分节点不存在，无法批量调整标签")
		}
		target := slices.Clone(tagIDs)
		if mode == "APPEND" {
			target = normalizeTagIDs(append(target, node.TagIDs...))
		}
		current := slices.Clone(node.TagIDs)
		slices.Sort(current)
		slices.Sort(target)
		if slices.Equal(current, target) {
			unchanged++
			continue
		}
		if err = replaceTags(ctx, tx, id, target); err != nil {
			return 0, 0, err
		}
		stamp := FormatTime(nowFunc())
		if _, err = tx.ExecContext(ctx, `UPDATE node SET update_time=? WHERE id=?`, stamp, id); err != nil {
			return 0, 0, err
		}
		if _, err = tx.ExecContext(ctx, `UPDATE node_server SET deployed=0,update_time=? WHERE node_id=? AND deployed<>2`, stamp, id); err != nil {
			return 0, 0, err
		}
		updated++
	}
	if updated > 0 {
		if err = cleanUnauthorizedBindings(ctx, tx, 0); err != nil {
			return 0, 0, err
		}
	}
	if err = tx.Commit(); err != nil {
		return 0, 0, err
	}
	return updated, unchanged, nil
}
