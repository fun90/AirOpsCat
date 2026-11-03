package store

import (
	"context"
	"database/sql"
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

// LandingProbeTarget 是落地节点的部署位（已部署、节点与服务器均启用，外部服务器也检测），
// Probers 是引用该落地且已部署的代理节点所在服务器（启用、非外部），由它们 SSH 拨测，贴近真实出站链路。
type LandingProbeTarget struct {
	ProbeTarget
	Probers []int64
}

func LandingProbeTargets(ctx context.Context, db *sql.DB) ([]LandingProbeTarget, error) {
	rows, err := db.QueryContext(ctx, `SELECT p.id,s.ip,coalesce(n.port,0),coalesce(n.protocol,''),coalesce(n.inbound,'')
 FROM node_server p JOIN node n ON n.id=p.node_id JOIN server s ON s.id=p.server_id
 WHERE p.deployed=1 AND n.disabled=0 AND n.type=? AND s.disabled=0 ORDER BY p.id`, NodeLanding)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []LandingProbeTarget
	for rows.Next() {
		var t LandingProbeTarget
		if err := rows.Scan(&t.ID, &t.IP, &t.Port, &t.Protocol, &t.Inbound); err != nil {
			return nil, err
		}
		out = append(out, t)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	prow, err := db.QueryContext(ctx, `SELECT DISTINCT p.id,px.server_id
 FROM node_server p JOIN node n ON n.id=p.node_id
 JOIN node x ON x.out_id=n.id AND x.disabled=0 AND coalesce(x.type,0)<>?
 JOIN node_server px ON px.node_id=x.id AND px.deployed=1
 JOIN server s2 ON s2.id=px.server_id AND s2.disabled=0 AND s2.external_server=0
 WHERE n.type=? ORDER BY p.id,px.server_id`, NodeLanding, NodeLanding)
	if err != nil {
		return nil, err
	}
	defer prow.Close()
	probers := map[int64][]int64{}
	for prow.Next() {
		var pid, sid int64
		if err := prow.Scan(&pid, &sid); err != nil {
			return nil, err
		}
		probers[pid] = append(probers[pid], sid)
	}
	if err := prow.Err(); err != nil {
		return nil, err
	}
	for i := range out {
		out[i].Probers = probers[out[i].ID]
	}
	return out, nil
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

// PrunePlacementProbes 删除不在有效（部署位, 探测机）组合内的结果：探测机被移出配置、部署位出范围或落地不再被该代理服务器引用。
func PrunePlacementProbes(ctx context.Context, db *sql.DB, valid map[[2]int64]bool) error {
	rows, err := db.QueryContext(ctx, `SELECT placement_id,probe_server_id FROM placement_probe`)
	if err != nil {
		return err
	}
	var stale [][2]int64
	for rows.Next() {
		var k [2]int64
		if err := rows.Scan(&k[0], &k[1]); err != nil {
			rows.Close()
			return err
		}
		if !valid[k] {
			stale = append(stale, k)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, k := range stale {
		if _, err := db.ExecContext(ctx, `DELETE FROM placement_probe WHERE placement_id=? AND probe_server_id=?`, k[0], k[1]); err != nil {
			return err
		}
	}
	return nil
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

// UnreachablePlacements 按节点类型返回不可达部署位：landing 为 true 取落地节点，否则取代理节点。
func UnreachablePlacements(ctx context.Context, db *sql.DB, landing bool) ([]UnreachablePlacement, error) {
	rows, err := db.QueryContext(ctx, `SELECT s.id,coalesce(nullif(s.name,''),s.ip),coalesce(nullif(n.name,''),'#'||n.id),coalesce(n.protocol,''),coalesce(n.port,0),
 (SELECT group_concat(DISTINCT msg) FROM placement_probe WHERE placement_id=p.id),
 (SELECT singbox_down FROM server_metrics WHERE server_id=s.id AND kind='monitor' ORDER BY sample_time DESC,id DESC LIMIT 1)
 FROM node_server p JOIN node n ON n.id=p.node_id JOIN server s ON s.id=p.server_id WHERE (coalesce(n.type,0)=?)=? AND p.id IN (`+unreachableSQL+`) ORDER BY s.id,p.id`, NodeLanding, landing, ProbeFailRounds)
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
