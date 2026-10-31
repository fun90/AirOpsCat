package store

import (
	"context"
	"crypto/sha256"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"

	"github.com/fun90/airopscat/internal/singbox"
)

// DeploymentSnapshot 是一次实际下发的部署位数据；Inbound 在 JSON 中保留对象形式。
type DeploymentSnapshot struct {
	NodeID       int64          `json:"nodeId"`
	NodeServerID int64          `json:"nodeServerId"`
	ServerID     int64          `json:"serverId"`
	AccessHostID *int64         `json:"accessHostId"`
	Port         int            `json:"port"`
	Protocol     string         `json:"protocol"`
	CoreType     string         `json:"coreType"`
	Type         int            `json:"type"`
	Inbound      map[string]any `json:"inbound"`
	OutID        *int64         `json:"outId"`
	Disabled     bool           `json:"disabled"`
	Name         string         `json:"name"`
	Remark       string         `json:"remark"`
	TagIDs       []int64        `json:"tagIds"`
}

// CapturedDeployment 在同一数据库读取版本中保存配置输入与部署快照。
type CapturedDeployment struct {
	Input     *singbox.ServerConfigInput
	Revision  int64
	Snapshots []DeploymentSnapshot
}

// CaptureDeployment 只持有短读事务；调用方在返回后构建配置并执行 SSH。
func CaptureDeployment(ctx context.Context, db *sql.DB, serverID int64, opts LoadOptions) (*CapturedDeployment, error) {
	tx, err := db.BeginTx(ctx, &sql.TxOptions{ReadOnly: true})
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	c := &CapturedDeployment{}
	if err := tx.QueryRowContext(ctx, `SELECT revision FROM deploy_revision WHERE id=1`).Scan(&c.Revision); err != nil {
		return nil, err
	}
	if c.Input, err = loadServerConfigInput(ctx, tx, serverID, opts); err != nil {
		return nil, err
	}
	rows, err := tx.QueryContext(ctx, `SELECT n.id,p.id,p.server_id,p.access_host_id,coalesce(n.port,0),coalesce(n.protocol,''),
		coalesce(n.core_type,''),n.type,coalesce(n.inbound,''),n.out_id,n.disabled,coalesce(n.name,''),coalesce(n.remark,'')
		FROM node_server p JOIN node n ON n.id=p.node_id WHERE p.server_id=? AND p.deployed<>2 ORDER BY p.priority,p.id`, serverID)
	if err != nil {
		return nil, err
	}
	for rows.Next() {
		var s DeploymentSnapshot
		var access, out sql.NullInt64
		var inbound string
		if err := rows.Scan(&s.NodeID, &s.NodeServerID, &s.ServerID, &access, &s.Port, &s.Protocol, &s.CoreType, &s.Type, &inbound, &out, &s.Disabled, &s.Name, &s.Remark); err != nil {
			rows.Close()
			return nil, err
		}
		if access.Valid {
			s.AccessHostID = &access.Int64
		}
		if out.Valid {
			s.OutID = &out.Int64
		}
		s.Inbound = map[string]any{}
		if strings.TrimSpace(inbound) != "" {
			if err := json.Unmarshal([]byte(inbound), &s.Inbound); err != nil {
				rows.Close()
				return nil, fmt.Errorf("节点 %d 的入站快照无效: %w", s.NodeID, err)
			}
			if s.Inbound == nil {
				rows.Close()
				return nil, fmt.Errorf("节点 %d 的入站快照必须是对象", s.NodeID)
			}
		}
		c.Snapshots = append(c.Snapshots, s)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return nil, err
	}
	for i := range c.Snapshots {
		s := &c.Snapshots[i]
		s.TagIDs = []int64{}
		rows, err := tx.QueryContext(ctx, `SELECT tag_id FROM node_tag WHERE node_id=? ORDER BY tag_id`, s.NodeID)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			var id int64
			if err := rows.Scan(&id); err != nil {
				rows.Close()
				return nil, err
			}
			s.TagIDs = append(s.TagIDs, id)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return nil, err
		}
	}
	if err := tx.Commit(); err != nil {
		return nil, err
	}
	return c, nil
}

// RecordDeploymentSnapshots 原子记录整机结果与本次提交的精确快照。
// revision 冲突仅阻止确认部署位，不阻止记录实际已下发的快照；失败不更新快照。
func RecordDeploymentSnapshots(ctx context.Context, db *sql.DB, serverID int64, hash string, success bool, message string, revision int64, snapshots []DeploymentSnapshot) error {
	return recordDeployment(ctx, db, serverID, hash, success, message, &revision, snapshots)
}

func saveDeploymentSnapshots(ctx context.Context, tx *sql.Tx, serverID int64, snapshots []DeploymentSnapshot, now string) error {
	for _, s := range snapshots {
		if s.ServerID != serverID {
			return fmt.Errorf("部署快照服务器不匹配: %d", s.ServerID)
		}
		data, err := json.Marshal(s)
		if err != nil {
			return err
		}
		sum := sha256.Sum256(data)
		// 部署期间被物理删除的部署位不能重新创建；保留现有外键约束。
		_, err = tx.ExecContext(ctx, `INSERT INTO node_deployment
			(node_id,node_server_id,version,snapshot_hash,snapshot_json,server_id,core_type,protocol,type,port,disabled,deployed_at,create_time,update_time)
			SELECT ?,?,1,?,?,?,?,?,?,?,?,?,?,? WHERE EXISTS (SELECT 1 FROM node_server WHERE id=? AND node_id=? AND server_id=?)
			ON CONFLICT(node_server_id) DO UPDATE SET version=node_deployment.version+1,snapshot_hash=excluded.snapshot_hash,
			snapshot_json=excluded.snapshot_json,server_id=excluded.server_id,core_type=excluded.core_type,protocol=excluded.protocol,
			type=excluded.type,port=excluded.port,disabled=excluded.disabled,deployed_at=excluded.deployed_at,update_time=excluded.update_time`,
			s.NodeID, s.NodeServerID, hex.EncodeToString(sum[:]), string(data), s.ServerID, s.CoreType, s.Protocol, s.Type, s.Port, s.Disabled, now, now, now,
			s.NodeServerID, s.NodeID, serverID)
		if err != nil {
			return err
		}
	}
	return nil
}
