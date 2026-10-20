package store

import (
	"context"
	"database/sql"
	"fmt"
	"strings"
)

// serverMatches 判断服务器是否适合承载节点（不看服务器是否禁用）：
// 节点地区非空且与服务器地区区分大小写相等；代理节点只能落在非外部的代理服务器，落地节点可落在任何服务器。
func serverMatches(nodeRegion string, nodeType int, srvRegion sql.NullString, srvType int, srvExternal bool) bool {
	nr := strings.TrimSpace(nodeRegion)
	if nr == "" || !srvRegion.Valid || strings.TrimSpace(srvRegion.String) != nr {
		return false
	}
	if nodeType != NodeLanding {
		return !srvExternal && srvType != NodeLanding
	}
	return true
}

type serverRow struct {
	id       int64
	name     string
	region   sql.NullString
	typ      int
	external bool
	disabled bool
}

func allServers(ctx context.Context, q dbtx) ([]serverRow, error) {
	rows, err := q.QueryContext(ctx, `SELECT id, coalesce(name, ip), region, coalesce(server_type,0), external_server, disabled FROM server ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []serverRow
	for rows.Next() {
		var s serverRow
		if err := rows.Scan(&s.id, &s.name, &s.region, &s.typ, &s.external, &s.disabled); err != nil {
			return nil, err
		}
		list = append(list, s)
	}
	return list, rows.Err()
}

// removeOrMarkDeleted 曾部署过的部署位标记待删除，否则直接删除。
func removeOrMarkDeleted(ctx context.Context, q dbtx, p *Placement) error {
	if p.Deployed == PendingDelete {
		return nil
	}
	var deployedBefore int
	if err := q.QueryRowContext(ctx, `SELECT p.deployed IN (1, 2) OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = p.id)
		FROM node_server p WHERE p.id = ?`, p.ID).Scan(&deployedBefore); err != nil {
		return err
	}
	if deployedBefore == 1 {
		_, err := q.ExecContext(ctx, `UPDATE node_server SET deployed = 2, update_time = ? WHERE id = ?`, FormatTime(nowFunc()), p.ID)
		return err
	}
	_, err := q.ExecContext(ctx, `DELETE FROM node_server WHERE id = ?`, p.ID) // 订阅定制引用、部署记录随外键级联删除
	return err
}

// syncNode 让节点的部署位与「地区 + 类型」匹配的服务器一致：只增删部署位，不改已有部署位的属性。
// strict 时新部署位端口冲突直接报错，否则跳过。
func syncNode(ctx context.Context, q dbtx, nodeID int64, strict bool) error {
	n, err := loadNode(ctx, q, nodeID)
	if err != nil || n == nil {
		return err
	}
	if strings.TrimSpace(n.Region) == "" {
		return nil // 未填地区的存量节点不动部署位
	}
	servers, err := allServers(ctx, q)
	if err != nil {
		return err
	}
	byID := map[int64]serverRow{}
	for _, s := range servers {
		byID[s.id] = s
	}
	existing, err := Placements(ctx, q, nodeID)
	if err != nil {
		return err
	}
	have := map[int64]bool{}
	maxPriority := 0
	for _, p := range existing {
		have[p.ServerID] = true
		if p.Priority > maxPriority {
			maxPriority = p.Priority
		}
		s, ok := byID[p.ServerID]
		if ok && serverMatches(n.Region, n.Type, s.region, s.typ, s.external) {
			if p.Deployed == PendingDelete {
				if _, err := q.ExecContext(ctx, `UPDATE node_server SET deployed = 0, update_time = ? WHERE id = ?`, FormatTime(nowFunc()), p.ID); err != nil {
					return err
				}
			}
			continue
		}
		if err := removeOrMarkDeleted(ctx, q, p); err != nil {
			return err
		}
	}
	for _, s := range servers {
		if have[s.id] || s.disabled || !serverMatches(n.Region, n.Type, s.region, s.typ, s.external) {
			continue
		}
		if other, err := portConflict(ctx, q, s.id, n.Port, n.ID); err != nil {
			return err
		} else if other != "" {
			if strict {
				return fmt.Errorf("%w：端口 %d 在服务器 %s 上已被节点 %s 占用", ErrConflict, n.Port, s.name, other)
			}
			continue
		}
		maxPriority++
		exposed := n.Type != NodeLanding // 落地节点是代理节点的出站，默认不进订阅
		now := FormatTime(nowFunc())
		if _, err := q.ExecContext(ctx, `INSERT INTO node_server (node_id, server_id, exposed, priority, deployed, create_time, update_time)
			VALUES (?, ?, ?, ?, 0, ?, ?)`, n.ID, s.id, exposed, maxPriority, now, now); err != nil {
			return err
		}
	}
	return nil
}

// SyncAll 服务器新建、修改后同步全部节点的部署位；端口冲突的部署位跳过。
func SyncAll(ctx context.Context, db *sql.DB) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	rows, err := tx.QueryContext(ctx, `SELECT id FROM node ORDER BY id`)
	if err != nil {
		return err
	}
	var ids []int64
	for rows.Next() {
		var id int64
		rows.Scan(&id)
		ids = append(ids, id)
	}
	rows.Close()
	for _, id := range ids {
		if err := syncNode(ctx, tx, id, false); err != nil {
			return err
		}
	}
	return tx.Commit()
}
