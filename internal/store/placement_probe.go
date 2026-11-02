package store

import (
	"context"
	"database/sql"
	"strings"
	"time"
)

// ProbeFailRounds 是判定部署位不可达所需的连续失败轮数（每台探测机都要达到）。
const ProbeFailRounds = 3

// ProbeTarget 是定时检测的部署位：已部署、节点与服务器均启用、非外部服务器、非落地节点。
type ProbeTarget struct {
	ID           int64
	IP, Protocol string
	Inbound      string
	Port         int
}

func ProbeTargets(ctx context.Context, db *sql.DB) ([]ProbeTarget, error) {
	rows, err := db.QueryContext(ctx, `SELECT p.id,s.ip,coalesce(n.port,0),coalesce(n.protocol,''),coalesce(n.inbound,'')
 FROM node_server p JOIN node n ON n.id=p.node_id JOIN server s ON s.id=p.server_id
 WHERE p.deployed=1 AND n.disabled=0 AND coalesce(n.type,0)<>? AND s.disabled=0 AND s.external_server=0 ORDER BY p.id`, NodeLanding)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []ProbeTarget
	for rows.Next() {
		var t ProbeTarget
		if err := rows.Scan(&t.ID, &t.IP, &t.Port, &t.Protocol, &t.Inbound); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	return out, rows.Err()
}

type ProbeOutcome struct {
	PlacementID int64
	OK          bool
	Ms          int
	Msg         string
}

// SavePlacementProbes 写入一台探测机本轮的结果：成功清零连续失败数，失败加一。
func SavePlacementProbes(ctx context.Context, db *sql.DB, probeID int64, results []ProbeOutcome, at time.Time) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	ts := FormatTime(at)
	for _, r := range results {
		var lastOK any
		if r.OK {
			lastOK = ts
		}
		if _, err := tx.ExecContext(ctx, `INSERT INTO placement_probe(placement_id,probe_server_id,ok,ms,msg,fails,checked_time,last_ok_time) VALUES(?,?,?,?,?,?,?,?)
 ON CONFLICT(placement_id,probe_server_id) DO UPDATE SET ok=excluded.ok,ms=excluded.ms,msg=excluded.msg,checked_time=excluded.checked_time,
 fails=CASE WHEN excluded.ok THEN 0 ELSE placement_probe.fails+1 END,last_ok_time=coalesce(excluded.last_ok_time,placement_probe.last_ok_time)`,
			r.PlacementID, probeID, r.OK, r.Ms, r.Msg, map[bool]int{true: 0, false: 1}[r.OK], ts, lastOK); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// ClearPlacementProbes 删除一台探测机的全部结果：探测机不可用时其旧结果不再参与判定。
func ClearPlacementProbes(ctx context.Context, db *sql.DB, probeID int64) error {
	_, err := db.ExecContext(ctx, `DELETE FROM placement_probe WHERE probe_server_id=?`, probeID)
	return err
}

// PrunePlacementProbes 删除已移出探测机配置、或已不在检测范围内的部署位的结果。
func PrunePlacementProbes(ctx context.Context, db *sql.DB, probeIDs, placementIDs []int64) error {
	in := func(col string, ids []int64) (string, []any) {
		if len(ids) == 0 {
			return "1=1", nil
		}
		args := make([]any, len(ids))
		for i, id := range ids {
			args[i] = id
		}
		return col + " NOT IN (?" + strings.Repeat(",?", len(ids)-1) + ")", args
	}
	a, aa := in("probe_server_id", probeIDs)
	b, ba := in("placement_id", placementIDs)
	_, err := db.ExecContext(ctx, `DELETE FROM placement_probe WHERE `+a+` OR `+b, append(aa, ba...)...)
	return err
}

// ProbeState 是部署位在一台探测机上的最新检测结果。
type ProbeState struct {
	ProbeName, Msg, Checked, LastOK string
	OK                              bool
	Ms, Fails                       int
}

// NodeProbeStates 按部署位返回节点在各探测机上的最新结果，供节点编辑页展示。
func NodeProbeStates(ctx context.Context, db *sql.DB, nodeID int64) (map[int64][]ProbeState, error) {
	rows, err := db.QueryContext(ctx, `SELECT pp.placement_id,coalesce(nullif(s.name,''),s.ip),pp.ok,pp.ms,pp.msg,pp.fails,pp.checked_time,coalesce(pp.last_ok_time,'')
 FROM placement_probe pp JOIN node_server p ON p.id=pp.placement_id JOIN server s ON s.id=pp.probe_server_id WHERE p.node_id=? ORDER BY pp.probe_server_id`, nodeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64][]ProbeState{}
	for rows.Next() {
		var id int64
		var s ProbeState
		if err := rows.Scan(&id, &s.ProbeName, &s.OK, &s.Ms, &s.Msg, &s.Fails, &s.Checked, &s.LastOK); err != nil {
			return nil, err
		}
		out[id] = append(out[id], s)
	}
	return out, rows.Err()
}

// unreachableSQL 选出所有探测机都已连续失败 ProbeFailRounds 轮（参数）的部署位 id。
const unreachableSQL = `SELECT placement_id FROM placement_probe GROUP BY placement_id HAVING min(fails)>=?`

// UnreachablePlacement 是判定为不可达的部署位，附服务器最近一次 sing-box 状态供定位原因。
type UnreachablePlacement struct {
	ServerID                  int64
	ServerName, NodeName, Msg string
	Protocol                  string
	Port                      int
	SingboxDown               *bool // nil 表示监控未采到
}

func UnreachablePlacements(ctx context.Context, db *sql.DB) ([]UnreachablePlacement, error) {
	rows, err := db.QueryContext(ctx, `SELECT s.id,coalesce(nullif(s.name,''),s.ip),coalesce(nullif(n.name,''),'#'||n.id),coalesce(n.protocol,''),coalesce(n.port,0),
 (SELECT group_concat(DISTINCT msg) FROM placement_probe WHERE placement_id=p.id),
 (SELECT singbox_down FROM server_metrics WHERE server_id=s.id AND kind='monitor' ORDER BY sample_time DESC,id DESC LIMIT 1)
 FROM node_server p JOIN node n ON n.id=p.node_id JOIN server s ON s.id=p.server_id WHERE p.id IN (`+unreachableSQL+`) ORDER BY s.id,p.id`, ProbeFailRounds)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []UnreachablePlacement
	for rows.Next() {
		var u UnreachablePlacement
		var sb sql.NullInt64
		if err := rows.Scan(&u.ServerID, &u.ServerName, &u.NodeName, &u.Protocol, &u.Port, &u.Msg, &sb); err != nil {
			return nil, err
		}
		if sb.Valid {
			down := sb.Int64 != 0
			u.SingboxDown = &down
		}
		out = append(out, u)
	}
	return out, rows.Err()
}
