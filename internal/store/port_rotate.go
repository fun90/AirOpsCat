package store

import (
	"context"
	"database/sql"
	"slices"
	"strings"
)

// RealityProtocol 是 VLESS+REALITY 节点的协议值；只有这类节点会因回落被刷流量。
const RealityProtocol = "vless-reality"

// PortRotation 记录一个节点的端口变更。
type PortRotation struct {
	NodeID           int64
	Name             string
	OldPort, NewPort int
}

// PortRotateResult 是一次换端口的结果：变更列表，以及需要重新部署的服务器。
type PortRotateResult struct {
	Rotations     []PortRotation
	DeployServers []int64
}

// RotateRealityPorts 给服务器上已启用的 VLESS+REALITY 节点换新端口（取全局未用且未被封禁的端口），
// 节点置为待部署，旧端口记入该服务器的封禁表，由部署成功后补防火墙规则。
// 引用这些节点为出站的代理节点同样置为待部署并计入部署服务器。
func RotateRealityPorts(ctx context.Context, db *sql.DB, serverID int64) (*PortRotateResult, error) {
	res := &PortRotateResult{}
	err := inTx(ctx, db, func(tx *sql.Tx) error {
		rows, err := tx.QueryContext(ctx, `SELECT DISTINCT n.id, n.name, n.port, n.type FROM node n JOIN node_server ns ON ns.node_id = n.id
			WHERE ns.server_id = ? AND ns.deployed <> 2 AND n.disabled = 0 AND n.protocol = ? ORDER BY n.id`, serverID, RealityProtocol)
		if err != nil {
			return err
		}
		type item struct {
			PortRotation
			typ int
		}
		var items []item
		for rows.Next() {
			var it item
			if err := rows.Scan(&it.NodeID, &it.Name, &it.OldPort, &it.typ); err != nil {
				rows.Close()
				return err
			}
			items = append(items, it)
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		if len(items) == 0 {
			return invalid("该服务器上没有已启用的 VLESS+REALITY 节点")
		}
		now := FormatTime(nowFunc())
		servers := map[int64]bool{}
		for _, it := range items {
			// 先把旧端口封禁，再分配：避免新端口恰好取到刚释放的旧端口
			if err := blockServerPort(ctx, tx, serverID, it.OldPort, now); err != nil {
				return err
			}
			port, err := availableNodePort(ctx, tx)
			if err != nil {
				return err
			}
			if _, err := tx.ExecContext(ctx, `UPDATE node SET port = ?, update_time = ? WHERE id = ?`, port, now, it.NodeID); err != nil {
				return err
			}
			if err := markPending(ctx, tx, it.NodeID); err != nil {
				return err
			}
			if it.typ == NodeLanding {
				if err := markReferencingPending(ctx, tx, it.NodeID); err != nil {
					return err
				}
			}
			it.NewPort = port
			res.Rotations = append(res.Rotations, it.PortRotation)
			srv, err := tx.QueryContext(ctx, `SELECT DISTINCT server_id FROM node_server WHERE node_id = ? OR node_id IN (SELECT id FROM node WHERE out_id = ?)`, it.NodeID, it.NodeID)
			if err != nil {
				return err
			}
			for srv.Next() {
				var id int64
				if err := srv.Scan(&id); err != nil {
					srv.Close()
					return err
				}
				servers[id] = true
			}
			srv.Close()
			if err := srv.Err(); err != nil {
				return err
			}
		}
		for id := range servers {
			res.DeployServers = append(res.DeployServers, id)
		}
		slices.Sort(res.DeployServers)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return res, nil
}

// blockServerPort 封禁端口并撤销同一端口尚未执行的远端解封动作。
func blockServerPort(ctx context.Context, tx *sql.Tx, serverID int64, port int, now string) error {
	if _, err := tx.ExecContext(ctx, `DELETE FROM server_unblocked_port WHERE server_id=? AND port=?`, serverID, port); err != nil {
		return err
	}
	_, err := tx.ExecContext(ctx, `INSERT OR IGNORE INTO server_blocked_port(server_id,port,create_time) VALUES(?,?,?)`, serverID, port, now)
	return err
}

// UnblockServerPort 解除封禁并记录远端待解封动作；部署成功执行后才释放端口重用。
func UnblockServerPort(ctx context.Context, db *sql.DB, serverID int64, port int) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		res, err := tx.ExecContext(ctx, `DELETE FROM server_blocked_port WHERE server_id=? AND port=?`, serverID, port)
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		if n == 0 {
			return invalid("封禁端口不存在")
		}
		_, err = tx.ExecContext(ctx, `INSERT INTO server_unblocked_port(server_id,port,create_time) VALUES(?,?,?)
			ON CONFLICT(server_id,port) DO UPDATE SET create_time=excluded.create_time`, serverID, port, FormatTime(nowFunc()))
		return err
	})
}

// UnblockedPorts 返回尚待部署清理远端规则的端口。
func UnblockedPorts(ctx context.Context, db *sql.DB, serverID int64) ([]int, error) {
	rows, err := db.QueryContext(ctx, `SELECT port FROM server_unblocked_port WHERE server_id=? ORDER BY port`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ports []int
	for rows.Next() {
		var p int
		if err := rows.Scan(&p); err != nil {
			return nil, err
		}
		ports = append(ports, p)
	}
	return ports, rows.Err()
}

// ConfirmUnblockedPorts 只确认本次成功执行的端口，避免并发补录的新动作被误清理。
func ConfirmUnblockedPorts(ctx context.Context, db *sql.DB, serverID int64, ports []int) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		for _, p := range ports {
			if _, err := tx.ExecContext(ctx, `DELETE FROM server_unblocked_port WHERE server_id=? AND port=?`, serverID, p); err != nil {
				return err
			}
		}
		return nil
	})
}

// BlockedPorts 返回服务器上需要防火墙封禁的端口。
func BlockedPorts(ctx context.Context, db *sql.DB, serverID int64) ([]int, error) {
	rows, err := db.QueryContext(ctx, `SELECT port FROM server_blocked_port WHERE server_id = ? ORDER BY port`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []int
	for rows.Next() {
		var p int
		if err := rows.Scan(&p); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// ServerRealityNodes 返回各服务器上已启用的 VLESS+REALITY 节点数；只含可通过 SSH 部署（未禁用、非外部）的服务器。
func ServerRealityNodes(ctx context.Context, db *sql.DB, ids []int64) (map[int64]int, error) {
	out := map[int64]int{}
	if len(ids) == 0 {
		return out, nil
	}
	args := []any{RealityProtocol}
	for _, id := range ids {
		args = append(args, id)
	}
	rows, err := db.QueryContext(ctx, `SELECT s.id, count(DISTINCT n.id) FROM server s JOIN node_server ns ON ns.server_id = s.id JOIN node n ON n.id = ns.node_id
		WHERE n.protocol = ? AND n.disabled = 0 AND ns.deployed <> 2 AND s.disabled = 0 AND s.external_server = 0
		AND s.id IN (?`+strings.Repeat(",?", len(ids)-1)+`) GROUP BY s.id`, args...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var id int64
		var n int
		if err := rows.Scan(&id, &n); err != nil {
			return nil, err
		}
		out[id] = n
	}
	return out, rows.Err()
}

// ServerNodePorts 返回服务器上所有未待删除部署位的节点端口。
func ServerNodePorts(ctx context.Context, db *sql.DB, serverID int64) (map[int]bool, error) {
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT n.port FROM node n JOIN node_server ns ON ns.node_id = n.id WHERE ns.server_id = ? AND ns.deployed <> 2`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int]bool{}
	for rows.Next() {
		var p int
		if err := rows.Scan(&p); err != nil {
			return nil, err
		}
		out[p] = true
	}
	return out, rows.Err()
}
