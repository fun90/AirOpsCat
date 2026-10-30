package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

type Tag struct {
	ID          int64
	Name        string
	Description string
	Color       string
	Accounts    int
	Nodes       int
}

func TagList(ctx context.Context, db *sql.DB, search string, page, size int) ([]*Tag, int, error) {
	var total int
	like := "%" + search + "%"
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM tag WHERE name LIKE ? OR description LIKE ?`, like, like).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT t.id, t.name, coalesce(t.description,''), coalesce(t.color,''),
		(SELECT count(*) FROM account_tag WHERE tag_id = t.id), (SELECT count(*) FROM node_tag WHERE tag_id = t.id)
		FROM tag t WHERE t.name LIKE ? OR t.description LIKE ? ORDER BY t.id DESC LIMIT ? OFFSET ?`, like, like, size, (page-1)*size)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*Tag
	for rows.Next() {
		var t Tag
		if err := rows.Scan(&t.ID, &t.Name, &t.Description, &t.Color, &t.Accounts, &t.Nodes); err != nil {
			return nil, 0, err
		}
		list = append(list, &t)
	}
	return list, total, rows.Err()
}

func TagByID(ctx context.Context, db *sql.DB, id int64) (*Tag, error) {
	var t Tag
	err := db.QueryRowContext(ctx, `SELECT id, name, coalesce(description,''), coalesce(color,'') FROM tag WHERE id = ?`, id).
		Scan(&t.ID, &t.Name, &t.Description, &t.Color)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &t, err
}

func isUnique(err error) bool {
	return err != nil && strings.Contains(err.Error(), "UNIQUE constraint failed")
}

// SaveTag 新建（ID=0）或更新标签；名称重复返回 ErrConflict。
func SaveTag(ctx context.Context, db *sql.DB, t *Tag) error {
	now := FormatTime(nowFunc())
	var err error
	if t.ID == 0 {
		var res sql.Result
		res, err = db.ExecContext(ctx, `INSERT INTO tag (name, description, color, create_time, update_time) VALUES (?,?,?,?,?)`,
			t.Name, nz(t.Description), nz(t.Color), now, now)
		if err == nil {
			t.ID, err = res.LastInsertId()
		}
	} else {
		_, err = db.ExecContext(ctx, `UPDATE tag SET name=?, description=?, color=?, update_time=? WHERE id=?`,
			t.Name, nz(t.Description), nz(t.Color), now, t.ID)
	}
	if isUnique(err) {
		return fmt.Errorf("%w：标签名称已存在", ErrConflict)
	}
	return err
}

// DeleteTag 删除标签；仍被账户或节点使用时拒绝，避免悄悄收回授权。
func DeleteTag(ctx context.Context, db *sql.DB, id int64) error {
	var a, n int
	db.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM account_tag WHERE tag_id=?), (SELECT count(*) FROM node_tag WHERE tag_id=?)`, id, id).Scan(&a, &n)
	if a+n > 0 {
		return fmt.Errorf("%w：标签仍被 %d 个账户、%d 个节点使用，请先解除关联", ErrConflict, a, n)
	}
	_, err := db.ExecContext(ctx, `DELETE FROM tag WHERE id = ?`, id)
	return err
}

// AllTags 返回全部标签，用于表单选择。
// TagNodeIDs 返回各标签下可供账户使用的节点：启用的代理节点且至少有一个未待删除的部署位。
func TagNodeIDs(ctx context.Context, db *sql.DB) (map[int64][]int64, error) {
	rows, err := db.QueryContext(ctx, `SELECT nt.tag_id, nt.node_id FROM node_tag nt JOIN node n ON n.id = nt.node_id
		WHERE n.disabled = 0 AND n.type = 0 AND EXISTS (SELECT 1 FROM node_server p WHERE p.node_id = n.id AND p.deployed <> 2)
		ORDER BY nt.tag_id, nt.node_id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64][]int64{}
	for rows.Next() {
		var tag, node int64
		if err := rows.Scan(&tag, &node); err != nil {
			return nil, err
		}
		out[tag] = append(out[tag], node)
	}
	return out, rows.Err()
}

func AllTags(ctx context.Context, db *sql.DB) ([]*Tag, error) {
	rows, err := db.QueryContext(ctx, `SELECT id, name, coalesce(color,'') FROM tag ORDER BY name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Tag
	for rows.Next() {
		var t Tag
		if err := rows.Scan(&t.ID, &t.Name, &t.Color); err != nil {
			return nil, err
		}
		list = append(list, &t)
	}
	return list, rows.Err()
}
