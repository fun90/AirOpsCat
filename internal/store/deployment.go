package store

import (
	"context"
	"database/sql"
	"errors"
)

// SSHTarget 是连接服务器所需的字段；Auth 仍是密文，由调用方解密。
type SSHTarget struct {
	ID       int64
	Name     string
	IP       string
	Port     int
	AuthType string
	Username string
	Auth     string
	HostKey  string
	Disabled bool
	External bool
}

// SSHTargetByID 读取服务器的 SSH 连接信息，不存在返回 nil。
func SSHTargetByID(ctx context.Context, db *sql.DB, id int64) (*SSHTarget, error) {
	var t SSHTarget
	err := db.QueryRowContext(ctx, `SELECT id, coalesce(name, ip), ip, coalesce(ssh_port,22), coalesce(auth_type,'PASSWORD'),
		coalesce(username,''), coalesce(auth,''), coalesce(host_key,''), disabled, external_server FROM server WHERE id = ?`, id).
		Scan(&t.ID, &t.Name, &t.IP, &t.Port, &t.AuthType, &t.Username, &t.Auth, &t.HostKey, &t.Disabled, &t.External)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &t, err
}

// TrustHostKey 首次信任：仅在尚未保存指纹时写入，返回库中最终生效的指纹（并发时以先写入者为准）。
func TrustHostKey(ctx context.Context, db *sql.DB, serverID int64, fingerprint string) (string, error) {
	if _, err := db.ExecContext(ctx, `UPDATE server SET host_key = ? WHERE id = ? AND coalesce(host_key,'') = ''`, fingerprint, serverID); err != nil {
		return "", err
	}
	var cur string
	err := db.QueryRowContext(ctx, `SELECT coalesce(host_key,'') FROM server WHERE id = ?`, serverID).Scan(&cur)
	return cur, err
}

// ClearHostKey 清除已信任的主机公钥（服务器重装后由编辑页调用）。
func ClearHostKey(ctx context.Context, db *sql.DB, serverID int64) error {
	_, err := db.ExecContext(ctx, `UPDATE server SET host_key = NULL WHERE id = ?`, serverID)
	return err
}

// DeploymentRevision 覆盖所有配置依赖表的写入；部署期间有变更则不确认部署位状态。
func DeploymentRevision(ctx context.Context, db *sql.DB) (int64, error) {
	var rev int64
	err := db.QueryRowContext(ctx, `SELECT revision FROM deploy_revision WHERE id=1`).Scan(&rev)
	return rev, err
}

// recordDeployment 在短事务内写入整机下发结果。
// 成功且部署期间数据未变化时同步部署位状态：待删除物理删除（节点无剩余部署位且未被引用则一并删除），
// 待部署且节点未禁用的置已部署（禁用节点保持待部署，决策 20 第 5 条）；失败只写结果，部署位不变。
func recordDeployment(ctx context.Context, db *sql.DB, serverID int64, configSHA256 string, success bool, message string, revision *int64, snapshots []DeploymentSnapshot) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := FormatTime(nowFunc())
	applyPlacements := true
	if success && revision != nil {
		var current int64
		if err := tx.QueryRowContext(ctx, `SELECT revision FROM deploy_revision WHERE id=1`).Scan(&current); err != nil {
			return err
		}
		if current != *revision {
			applyPlacements = false
			message += "；部署期间数据已变化，保留原部署位状态，请再次部署"
		}
	}
	if success {
		if err := saveDeploymentSnapshots(ctx, tx, serverID, snapshots, now); err != nil {
			return err
		}
	}
	if success && applyPlacements {
		// 确认部署及清理待删除项不改变待下发配置；事务内保留版本，避免并行部署互相失效。
		var confirmedRevision int64
		if err := tx.QueryRowContext(ctx, `SELECT revision FROM deploy_revision WHERE id=1`).Scan(&confirmedRevision); err != nil {
			return err
		}
		rows, err := tx.QueryContext(ctx, `SELECT ns.id, ns.node_id FROM node_server ns WHERE ns.server_id = ? AND ns.deployed = ?`, serverID, PendingDelete)
		if err != nil {
			return err
		}
		type pl struct{ id, nodeID int64 }
		var del []pl
		for rows.Next() {
			var p pl
			if err := rows.Scan(&p.id, &p.nodeID); err != nil {
				rows.Close()
				return err
			}
			del = append(del, p)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for _, p := range del {
			if _, err := tx.ExecContext(ctx, `DELETE FROM node_server WHERE id = ?`, p.id); err != nil { // 订阅定制引用、部署记录随外键级联删除
				return err
			}
			// 仍被其他节点出站或路由规则引用的节点保留，不算错误
			if _, err := tx.ExecContext(ctx, `DELETE FROM node WHERE id = ?
				AND NOT EXISTS (SELECT 1 FROM node_server WHERE node_id = node.id)
				AND NOT EXISTS (SELECT 1 FROM node o WHERE o.out_id = node.id)
				AND NOT EXISTS (SELECT 1 FROM route_rule r WHERE r.outbound_node_id = node.id)`, p.nodeID); err != nil {
				return err
			}
		}
		if _, err := tx.ExecContext(ctx, `UPDATE node_server SET deployed = ?, update_time = ?
			WHERE server_id = ? AND deployed = 0 AND EXISTS (SELECT 1 FROM node n WHERE n.id = node_server.node_id AND n.disabled = 0)`,
			Deployed, now, serverID); err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE deploy_revision SET revision=? WHERE id=1`, confirmedRevision); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `INSERT INTO server_deployment (server_id, config_sha256, success, message, deployed_at) VALUES (?,?,?,?,?)
		ON CONFLICT(server_id) DO UPDATE SET config_sha256 = excluded.config_sha256, success = excluded.success,
		message = excluded.message, deployed_at = excluded.deployed_at`, serverID, nz(configSHA256), success, nz(message), now); err != nil {
		return err
	}
	return tx.Commit()
}
