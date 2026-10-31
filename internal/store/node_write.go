package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strings"
)

// portConflict 判断服务器上是否已有其他节点（含待删除部署位）占用该端口，返回占用者名称。
func portConflict(ctx context.Context, q dbtx, serverID int64, port int, nodeID int64) (string, error) {
	var name string
	err := q.QueryRowContext(ctx, `SELECT n.name FROM node_server ns JOIN node n ON n.id = ns.node_id
		WHERE ns.server_id = ? AND n.port = ? AND n.id <> ? LIMIT 1`, serverID, port, nodeID).Scan(&name)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return name, err
}

func markPending(ctx context.Context, q dbtx, nodeID int64) error {
	_, err := q.ExecContext(ctx, `UPDATE node_server SET deployed = 0, update_time = ? WHERE node_id = ? AND deployed <> 2`, FormatTime(nowFunc()), nodeID)
	return err
}

func markReferencingPending(ctx context.Context, q dbtx, landingID int64) error {
	_, err := q.ExecContext(ctx, `UPDATE node_server SET deployed = 0, update_time = ? WHERE deployed <> 2 AND node_id IN (SELECT id FROM node WHERE out_id = ?)`,
		FormatTime(nowFunc()), landingID)
	return err
}

// effectiveServer 返回节点的生效部署位所在服务器：非待删除中 priority 最小、id 最小。
func effectiveServer(ctx context.Context, q dbtx, nodeID int64) (int64, error) {
	var sid int64
	err := q.QueryRowContext(ctx, `SELECT server_id FROM node_server WHERE node_id = ? AND deployed <> 2 ORDER BY priority, id LIMIT 1`, nodeID).Scan(&sid)
	if errors.Is(err, sql.ErrNoRows) {
		return 0, nil
	}
	return sid, err
}

func replaceTags(ctx context.Context, q dbtx, nodeID int64, tagIDs []int64) error {
	if _, err := q.ExecContext(ctx, `DELETE FROM node_tag WHERE node_id = ?`, nodeID); err != nil {
		return err
	}
	seen := map[int64]bool{}
	for _, id := range tagIDs {
		if seen[id] {
			continue
		}
		seen[id] = true
		if _, err := q.ExecContext(ctx, `INSERT INTO node_tag (node_id, tag_id) VALUES (?, ?)`, nodeID, id); err != nil {
			return invalid("标签不存在")
		}
	}
	return nil
}

func normalizeTagIDs(ids []int64) []int64 {
	seen := map[int64]bool{}
	var out []int64
	for _, id := range ids {
		if !seen[id] {
			seen[id] = true
			out = append(out, id)
		}
	}
	return out
}

// availableNodePort 在写事务内从防火墙节点端口范围取全局未用端口，避免未来部署位合并冲突。
func availableNodePort(ctx context.Context, q dbtx) (int, error) {
	rows, err := q.QueryContext(ctx, `SELECT DISTINCT port FROM node WHERE port BETWEEN 8000 AND 10000 ORDER BY port`)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	candidate := 8000
	for rows.Next() {
		var port int
		if err := rows.Scan(&port); err != nil {
			return 0, err
		}
		if port > candidate {
			break
		}
		if port == candidate {
			candidate++
		}
	}
	if err := rows.Err(); err != nil {
		return 0, err
	}
	if candidate > 10000 {
		return 0, invalid("没有可自动分配的端口，请手动指定")
	}
	return candidate, nil
}

// SaveNode 新建（ID=0）或更新节点及其标签、部署位属性，返回节点 ID。
// 新建时传入 ops 则按 ops.AddServerIDs 创建初始部署位，否则按地区和类型匹配；之后地区不再影响部署位；实质变更会把该节点的部署位置为待部署。
func SaveNode(ctx context.Context, db *sql.DB, n *Node, tagIDs []int64, edits []PlacementEdit, ops ...PlacementOps) (int64, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	tagIDs = normalizeTagIDs(tagIDs)
	if n.ID == 0 && n.Port == 0 {
		port, err := availableNodePort(ctx, tx)
		if err != nil {
			return 0, err
		}
		n.Port = port
	}
	if err := validateNode(ctx, tx, n); err != nil {
		return 0, err
	}
	now := FormatTime(nowFunc())
	if n.ID == 0 {
		res, err := tx.ExecContext(ctx, `INSERT INTO node (name, type, protocol, port, inbound, out_id, region, remark, disabled, create_time, update_time)
			VALUES (?,?,?,?,?,?,?,?,?,?,?)`, n.Name, n.Type, n.Protocol, n.Port, n.Inbound, n.OutID, n.Region, nz(n.Remark), n.Disabled, now, now)
		if err != nil {
			return 0, err
		}
		if n.ID, err = res.LastInsertId(); err != nil {
			return 0, err
		}
		if err := replaceTags(ctx, tx, n.ID, tagIDs); err != nil {
			return 0, err
		}
		if len(ops) == 0 {
			if err := initPlacements(ctx, tx, n); err != nil {
				return 0, err
			}
		}
		for _, op := range ops {
			for _, sid := range op.AddServerIDs {
				if err := addPlacement(ctx, tx, n, sid); err != nil {
					return 0, err
				}
			}
			if err := applyNewHosts(ctx, tx, n.ID, op.NewHosts); err != nil {
				return 0, err
			}
		}
		return n.ID, tx.Commit()
	}

	old, err := loadNode(ctx, tx, n.ID)
	if err != nil {
		return 0, err
	}
	if old == nil {
		return 0, sql.ErrNoRows
	}
	if n.Disabled && !old.Disabled {
		if err := checkNotOutbound(ctx, tx, n.ID, "禁用"); err != nil {
			return 0, err
		}
	}
	oldSig, oldOutSig := old.signature(old.TagIDs), old.outboundSignature()
	prevEffective, err := effectiveServer(ctx, tx, n.ID)
	if err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE node SET name=?, type=?, protocol=?, port=?, inbound=?, out_id=?, region=?, disabled=?, update_time=? WHERE id=?`,
		n.Name, n.Type, n.Protocol, n.Port, n.Inbound, n.OutID, n.Region, n.Disabled, now, n.ID); err != nil {
		return 0, err
	}
	if err := replaceTags(ctx, tx, n.ID, tagIDs); err != nil {
		return 0, err
	}
	if err := cleanUnauthorizedBindings(ctx, tx, 0); err != nil {
		return 0, err
	}
	// 顺序：先改已有部署位属性，再添加，最后删除（转移目标可以使用刚改好的访问域名）
	if err := applyPlacementEdits(ctx, tx, n, edits); err != nil {
		return 0, err
	}
	for _, op := range ops {
		for _, sid := range op.AddServerIDs {
			if err := addPlacement(ctx, tx, n, sid); err != nil {
				return 0, err
			}
		}
		if err := applyNewHosts(ctx, tx, n.ID, op.NewHosts); err != nil {
			return 0, err
		}
		for _, d := range op.Deletes {
			if err := deletePlacement(ctx, tx, n, d.PlacementID, d.TargetID); err != nil {
				return 0, err
			}
		}
	}
	// 端口变化时，已有部署位所在服务器上不能冲突
	pls, err := Placements(ctx, tx, n.ID)
	if err != nil {
		return 0, err
	}
	var badType []string
	for _, p := range pls {
		if p.Deployed == PendingDelete {
			continue
		}
		if other, err := portConflict(ctx, tx, p.ServerID, n.Port, n.ID); err != nil {
			return 0, err
		} else if other != "" {
			return 0, fmt.Errorf("%w：端口 %d 在服务器 %s 上已被节点 %s 占用", ErrConflict, n.Port, p.ServerName, other)
		}
		if !serverTypeAllowed(n.Type, p.ServerType, p.ServerExternal) {
			badType = append(badType, p.ServerName)
		}
	}
	if len(badType) > 0 {
		return 0, invalid("代理节点只能部署在非外部的代理服务器上，请先删除部署位：%s", strings.Join(badType, "、"))
	}
	if n.signature(tagIDs) != oldSig {
		if err := markPending(ctx, tx, n.ID); err != nil {
			return 0, err
		}
	}
	if n.Type == NodeLanding {
		cur, err := effectiveServer(ctx, tx, n.ID)
		if err != nil {
			return 0, err
		}
		if n.outboundSignature() != oldOutSig || cur != prevEffective {
			if err := markReferencingPending(ctx, tx, n.ID); err != nil {
				return 0, err
			}
		}
	}
	return n.ID, tx.Commit()
}

// checkNotOutbound 拒绝删除或禁用仍被节点、路由规则引用为出站的节点：
// 代理配置不看落地是否禁用，禁用后代理仍会把流量转给已撤掉入站的落地。
func checkNotOutbound(ctx context.Context, q dbtx, id int64, action string) error {
	var refs int
	if err := q.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM node WHERE out_id = ?) + (SELECT count(*) FROM route_rule WHERE outbound_node_id = ?)`, id, id).Scan(&refs); err != nil {
		return err
	}
	if refs > 0 {
		return fmt.Errorf("%w：该节点被 %d 个节点或路由规则引用为出站，请先解除引用再%s", ErrConflict, refs, action)
	}
	return nil
}

// ToggleNode 启用/禁用节点，本节点及引用它的代理节点置为待部署；仍被引用为出站时拒绝禁用。
func ToggleNode(ctx context.Context, db *sql.DB, id int64, disabled bool) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		if disabled {
			if err := checkNotOutbound(ctx, tx, id, "禁用"); err != nil {
				return err
			}
		}
		if err := execOne(tx.ExecContext(ctx, `UPDATE node SET disabled = ?, update_time = ? WHERE id = ?`, disabled, FormatTime(nowFunc()), id)); err != nil {
			return err
		}
		if err := markPending(ctx, tx, id); err != nil {
			return err
		}
		return markReferencingPending(ctx, tx, id)
	})
}

// DeleteNode 删除节点：未部署过的部署位直接删除，部署过的标记待删除（部署后从服务器移除）。
// 返回 true 表示节点已物理删除。仍被其他节点引用为出站时拒绝。
func DeleteNode(ctx context.Context, db *sql.DB, id int64) (bool, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	if err := checkNotOutbound(ctx, tx, id, "删除"); err != nil {
		return false, err
	}
	pls, err := Placements(ctx, tx, id)
	if err != nil {
		return false, err
	}
	for _, p := range pls {
		if err := removeOrMarkDeleted(ctx, tx, p); err != nil {
			return false, err
		}
	}
	left, err := Placements(ctx, tx, id)
	if err != nil {
		return false, err
	}
	if len(left) == 0 {
		if _, err := tx.ExecContext(ctx, `DELETE FROM node WHERE id = ?`, id); err != nil {
			return false, err
		}
		return true, tx.Commit()
	}
	return false, tx.Commit()
}

// CancelDeleteNode 把待删除的部署位恢复为待部署，需重新校验端口。
func CancelDeleteNode(ctx context.Context, db *sql.DB, id int64) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		n, err := loadNode(ctx, tx, id)
		if err != nil || n == nil {
			return sql.ErrNoRows
		}
		pls, err := Placements(ctx, tx, id)
		if err != nil {
			return err
		}
		cnt := 0
		for _, p := range pls {
			if p.Deployed != PendingDelete {
				continue
			}
			cnt++
			if other, err := portConflict(ctx, tx, p.ServerID, n.Port, id); err != nil {
				return err
			} else if other != "" {
				return fmt.Errorf("%w：端口 %d 在服务器 %s 上已被节点 %s 占用，无法取消删除", ErrConflict, n.Port, p.ServerName, other)
			}
		}
		if cnt == 0 {
			return invalid("节点不是待删除状态")
		}
		_, err = tx.ExecContext(ctx, `UPDATE node_server SET deployed = 0, update_time = ? WHERE node_id = ? AND deployed = 2`, FormatTime(nowFunc()), id)
		if err != nil {
			return err
		}
		return nil
	})
}
