package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// BatchDeleteNodes 先处理所选代理，再处理落地；同批代理对落地的引用视为待移除。
// 批次外节点或路由规则仍引用落地时整体拒绝并列出节点。
func BatchDeleteNodes(ctx context.Context, db *sql.DB, ids []int64) (int, error) {
	ids = normalizeTagIDs(ids)
	if len(ids) == 0 {
		return 0, invalid("请选择节点")
	}
	deleted := 0
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		selected := map[int64]bool{}
		var proxies, landings []int64
		for _, id := range ids {
			n, err := loadNode(ctx, tx, id)
			if err != nil {
				return err
			}
			if n == nil {
				return sql.ErrNoRows
			}
			selected[id] = true
			if n.Type == NodeLanding {
				landings = append(landings, id)
			} else {
				proxies = append(proxies, id)
			}
		}
		var blocked []string
		for _, id := range landings {
			rows, err := tx.QueryContext(ctx, `SELECT id,name FROM node WHERE out_id=?`, id)
			if err != nil {
				return err
			}
			for rows.Next() {
				var ref int64
				var name string
				if err := rows.Scan(&ref, &name); err != nil {
					rows.Close()
					return err
				}
				if !selected[ref] {
					blocked = append(blocked, fmt.Sprintf("落地节点 #%d 被节点 %s 引用", id, name))
				}
			}
			rows.Close()
			if err := rows.Err(); err != nil {
				return err
			}
			var rules int
			if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM route_rule WHERE outbound_node_id=?`, id).Scan(&rules); err != nil {
				return err
			}
			if rules > 0 {
				blocked = append(blocked, fmt.Sprintf("落地节点 #%d 被 %d 条路由规则引用", id, rules))
			}
		}
		if len(blocked) > 0 {
			return fmt.Errorf("%w：%s", ErrConflict, strings.Join(blocked, "；"))
		}
		for _, id := range append(proxies, landings...) {
			pls, err := Placements(ctx, tx, id)
			if err != nil {
				return err
			}
			for _, p := range pls {
				if err := removeOrMarkDeleted(ctx, tx, p); err != nil {
					return err
				}
			}
			// 待删除的代理仍受外键保护；待其部署后再清理无部署位的落地。
			if _, err := tx.ExecContext(ctx, `DELETE FROM node WHERE id=? AND NOT EXISTS (SELECT 1 FROM node_server WHERE node_id=node.id)
    AND NOT EXISTS (SELECT 1 FROM node WHERE out_id=node.id) AND NOT EXISTS (SELECT 1 FROM route_rule WHERE outbound_node_id=node.id)`, id); err != nil {
				return err
			}
			deleted++
		}
		return nil
	})
	if errors.Is(err, sql.ErrNoRows) {
		return 0, invalid("所选节点不存在")
	}
	return deleted, err
}
