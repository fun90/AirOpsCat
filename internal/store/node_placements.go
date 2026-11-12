package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net"
	"regexp"
	"slices"
	"strings"
)

// BatchNodePlacements 原子地为多个节点添加或删除指定服务器上的部署位，返回实际变化的节点数。
// 添加时节点已在该服务器上的跳过；删除时节点不在该服务器上的跳过。
// 添加时 priority 为 nil 沿用自增优先级，exposed 控制是否显示到订阅（落地节点恒不显示）。
// 删除的部署位绑定域名或订阅定制、或是节点最后一个部署位时整体回滚，需到节点编辑页选择转移目标。
func BatchNodePlacements(ctx context.Context, db *sql.DB, nodeIDs, serverIDs []int64, add bool, priority *int, exposed bool) (updated int, err error) {
	nodeIDs, serverIDs = normalizeTagIDs(nodeIDs), normalizeTagIDs(serverIDs)
	if len(nodeIDs) == 0 || len(serverIDs) == 0 {
		return 0, invalid("请选择节点和服务器")
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	for _, id := range nodeIDs {
		n, err := loadNode(ctx, tx, id)
		if err != nil {
			return 0, err
		}
		if n == nil {
			return 0, invalid("部分节点不存在，无法批量调整部署位")
		}
		prevEffective, err := effectiveServer(ctx, tx, id)
		if err != nil {
			return 0, err
		}
		changed := false
		for _, sid := range serverIDs {
			pls, err := Placements(ctx, tx, id)
			if err != nil {
				return 0, err
			}
			i := slices.IndexFunc(pls, func(p *Placement) bool { return p.ServerID == sid && p.Deployed != PendingDelete })
			switch {
			case add && i < 0:
				if err = addPlacement(ctx, tx, n, sid); err != nil {
					return 0, fmt.Errorf("节点 %s：%w", n.Name, err)
				}
				if _, err = tx.ExecContext(ctx, `UPDATE node_server SET priority = coalesce(?, priority), exposed = ? WHERE node_id = ? AND server_id = ? AND deployed <> 2`,
					priority, exposed && n.Type != NodeLanding, id, sid); err != nil {
					return 0, err
				}
			case !add && i >= 0:
				if err = deletePlacement(ctx, tx, n, pls[i].ID, 0); err != nil {
					return 0, fmt.Errorf("节点 %s：%w", n.Name, err)
				}
			default:
				continue
			}
			changed = true
		}
		if !changed {
			continue
		}
		updated++
		if n.Type == NodeLanding {
			cur, err := effectiveServer(ctx, tx, id)
			if err != nil {
				return 0, err
			}
			if cur != prevEffective {
				if err := markReferencingPending(ctx, tx, id); err != nil {
					return 0, err
				}
			}
		}
	}
	return updated, tx.Commit()
}

// BatchServerOption 是批量部署位可选的未禁用服务器。
type BatchServerOption struct {
	ID       int64
	Name, IP string
}

// BatchServerOptions 返回未禁用的服务器。
func BatchServerOptions(ctx context.Context, db *sql.DB) ([]BatchServerOption, error) {
	rows, err := db.QueryContext(ctx, `SELECT id, coalesce(nullif(name,''), ip), ip FROM server WHERE disabled = 0 ORDER BY region, name, id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []BatchServerOption
	for rows.Next() {
		var o BatchServerOption
		if err := rows.Scan(&o.ID, &o.Name, &o.IP); err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	return out, rows.Err()
}

// BatchNodeSwitch 把所选节点当前指向的部署位标记为切走，域名按优先级切到其余部署位；
// 没有指向或没有其他未切走部署位的节点跳过。
func BatchNodeSwitch(ctx context.Context, db *sql.DB, nodeIDs []int64) (switched, skipped int, err error) {
	nodeIDs = normalizeTagIDs(nodeIDs)
	if len(nodeIDs) == 0 {
		return 0, 0, invalid("请选择节点")
	}
	pointed, err := PointedPlacements(ctx, db)
	if err != nil {
		return 0, 0, err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, 0, err
	}
	defer tx.Rollback()
	for _, id := range nodeIDs {
		var ids []any
		for _, p := range pointed[id] {
			ids = append(ids, p.ID)
		}
		if len(ids) == 0 {
			skipped++
			continue
		}
		in := `(?` + strings.Repeat(",?", len(ids)-1) + `)`
		// 其余部署位须能承接切换（与 DNSCandidates 口径一致），否则切走后 DNS 计划为空
		var rest int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM node_server ns JOIN node n ON n.id = ns.node_id JOIN server s ON s.id = ns.server_id
			WHERE ns.node_id = ? AND ns.failover = 0 AND `+dnsPlacementSQL+` AND `+dnsServerUsableSQL+` AND ns.id NOT IN `+in,
			append([]any{id, shanghaiDay(nowFunc())}, ids...)...).Scan(&rest); err != nil {
			return 0, 0, err
		}
		if rest == 0 {
			skipped++
			continue
		}
		if _, err := tx.ExecContext(ctx, `UPDATE node_server SET failover = 1 WHERE id IN `+in, ids...); err != nil {
			return 0, 0, err
		}
		switched++
	}
	return switched, skipped, tx.Commit()
}

// RestoreNodeFailover 取消所选节点全部部署位的切走，域名回到原始解析，返回实际变化的部署位数。
func RestoreNodeFailover(ctx context.Context, db *sql.DB, nodeIDs []int64) (int64, error) {
	nodeIDs = normalizeTagIDs(nodeIDs)
	if len(nodeIDs) == 0 {
		return 0, invalid("请选择节点")
	}
	args := make([]any, len(nodeIDs))
	for i, id := range nodeIDs {
		args[i] = id
	}
	res, err := db.ExecContext(ctx, `UPDATE node_server SET failover = 0 WHERE failover = 1 AND node_id IN (?`+strings.Repeat(",?", len(nodeIDs)-1)+`)`, args...)
	if err != nil {
		return 0, err
	}
	return res.RowsAffected()
}

// RestorePlacementFailover 只取消节点某个部署位的切走，其余部署位保持不变。
func RestorePlacementFailover(ctx context.Context, db *sql.DB, nodeID, placementID int64) error {
	res, err := db.ExecContext(ctx, `UPDATE node_server SET failover = 0 WHERE id = ? AND node_id = ? AND failover = 1`, placementID, nodeID)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return invalid("部署位不存在或未切走")
	}
	return nil
}

// SwitchNodePlacement 让节点域名都切到指定部署位：其余部署位标记切走，目标取消切走。
// 目标须处于 DNS 切换的运行口径（与 DNSCandidates 一致），否则切换计划为空却提示成功。
func SwitchNodePlacement(ctx context.Context, db *sql.DB, nodeID, placementID int64) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		if err := checkSwitchTarget(ctx, tx, nodeID, placementID); err != nil {
			return err
		}
		_, err := tx.ExecContext(ctx, `UPDATE node_server SET failover = (id <> ?) WHERE node_id = ? AND deployed <> 2`, placementID, nodeID)
		return err
	})
}

// checkSwitchTarget 校验部署位能承接 DNS 切换，不能时返回具体原因。
func checkSwitchTarget(ctx context.Context, q dbtx, nodeID, placementID int64) error {
	var deployed, typ int
	var running, nodeDisabled, external, usable bool
	var server string
	err := q.QueryRowContext(ctx, `SELECT ns.deployed, ns.deployed = 1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = ns.id AND coalesce(d.disabled,0) = 0),
		coalesce(n.type,0), n.disabled, s.external_server, `+dnsServerUsableSQL+`, coalesce(nullif(s.name,''), s.ip)
		FROM node_server ns JOIN node n ON n.id = ns.node_id JOIN server s ON s.id = ns.server_id WHERE ns.id = ? AND ns.node_id = ?`,
		shanghaiDay(nowFunc()), placementID, nodeID).Scan(&deployed, &running, &typ, &nodeDisabled, &external, &usable, &server)
	switch {
	case errors.Is(err, sql.ErrNoRows) || err == nil && deployed == PendingDelete:
		return invalid("部署位不存在或待删除")
	case err != nil:
		return err
	case typ != NodeProxy:
		return invalid("落地节点不参与 DNS 切换")
	case nodeDisabled:
		return invalid("节点已禁用，不能切换")
	case !running:
		return invalid("服务器 %s 上的部署位尚未部署运行，请先部署", server)
	case external:
		return invalid("服务器 %s 是外部服务器，不能承接切换", server)
	case !usable:
		return invalid("服务器 %s 已禁用或已到期，不能承接切换", server)
	}
	return nil
}

// ServerPlacement 是服务器上的一个部署位，供服务器编辑页展示与切换。
type ServerPlacement struct {
	ID, NodeID int64
	NodeName   string
	AccessHost string
	Priority   int
	Deployed   int
	Failover   bool
	Running    bool // 曾成功部署过，可承接切换过来的域名
	Peers      int  // 同节点其他未待删除部署位数
}

// ServerPlacements 返回服务器上的全部部署位。
func ServerPlacements(ctx context.Context, db *sql.DB, serverID int64) ([]ServerPlacement, error) {
	rows, err := db.QueryContext(ctx, `SELECT ns.id, ns.node_id, coalesce(n.name,''), coalesce(nullif(ns.access_host_custom,''), r.full_name, r.name, ''), coalesce(ns.priority,0), ns.deployed, ns.failover,
		ns.deployed = 1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = ns.id),
		(SELECT count(*) FROM node_server o WHERE o.node_id = ns.node_id AND o.id <> ns.id AND o.deployed <> 2)
		FROM node_server ns JOIN node n ON n.id = ns.node_id LEFT JOIN domain_dns_record r ON r.id = ns.access_host_id
		WHERE ns.server_id = ? ORDER BY n.name, ns.id`, serverID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []ServerPlacement
	for rows.Next() {
		var p ServerPlacement
		if err := rows.Scan(&p.ID, &p.NodeID, &p.NodeName, &p.AccessHost, &p.Priority, &p.Deployed, &p.Failover, &p.Running, &p.Peers); err != nil {
			return nil, err
		}
		list = append(list, p)
	}
	return list, rows.Err()
}

// IPOnlyPlacement 是订阅里直连服务器 IP 或外部自定义域名的部署位：DNS 切换改不到它，切走后客户端仍连原服务器。
type IPOnlyPlacement struct {
	ID, NodeID, ServerID int64
	NodeName, Server     string
}

// IPOnlyPlacements 返回订阅会输出、但没有已同步访问域名的部署位（外部自定义域名同样不受本系统切换），只统计节点还有其他部署位（可切走）的情况。
// 口径与 SubscriptionNodes 一致：显示到订阅或被账户订阅定制选中，且已有部署快照。
// 被账户订阅定制选中的部署位，若该绑定带已同步的订阅域名，则该账户按域名连接，不算直连 IP。
func IPOnlyPlacements(ctx context.Context, db *sql.DB) ([]IPOnlyPlacement, error) {
	rows, err := db.QueryContext(ctx, `SELECT ns.id, ns.node_id, ns.server_id, coalesce(n.name,''), coalesce(nullif(s.name,''), s.ip)
		FROM node_server ns JOIN node n ON n.id = ns.node_id JOIN server s ON s.id = ns.server_id
		LEFT JOIN domain_dns_record r ON r.id = ns.access_host_id
		WHERE ns.deployed <> 2 AND n.type = 0 AND n.disabled = 0
		AND NOT (coalesce(r.status,'') = 'SYNCED' AND trim(coalesce(r.full_name,'')) <> '')
		AND (coalesce(ns.exposed, 1) <> 0 OR EXISTS (SELECT 1 FROM account_node_subscription_placement bp
			JOIN account_node_subscription_domain_binding b ON b.id = bp.binding_id LEFT JOIN domain_dns_record br ON br.id = b.domain_dns_record_id
			WHERE bp.node_server_id = ns.id AND b.enabled = 1 AND NOT (coalesce(br.status,'') = 'SYNCED' AND trim(coalesce(br.full_name,'')) <> '')))
		AND EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = ns.id AND coalesce(d.snapshot_json,'') <> '')
		AND EXISTS (SELECT 1 FROM node_server o WHERE o.node_id = ns.node_id AND o.id <> ns.id AND o.deployed <> 2)
		ORDER BY n.name, ns.priority, ns.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []IPOnlyPlacement
	for rows.Next() {
		var p IPOnlyPlacement
		if err := rows.Scan(&p.ID, &p.NodeID, &p.ServerID, &p.NodeName, &p.Server); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

// AutoSwitchedServers 返回因流量或时段被自动切走（非手动切走）的原服务器：其 IP 是某条记录保存的原始解析。
func AutoSwitchedServers(ctx context.Context, db *sql.DB) (map[int64]bool, error) {
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT s.id FROM domain_dns_record r JOIN server s ON s.ip = r.failover_home_content
		WHERE coalesce(r.failover_home_content,'') <> '' AND r.content <> r.failover_home_content
		AND NOT EXISTS (SELECT 1 FROM node_server ns WHERE ns.server_id = s.id AND ns.failover = 1 AND (ns.access_host_id = r.id
			OR ns.node_id IN (SELECT node_id FROM account_node_subscription_domain_binding WHERE domain_dns_record_id = r.id AND enabled = 1)))`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]bool{}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		out[id] = true
	}
	return out, rows.Err()
}

// FailoverServers 按节点返回已手动切走的部署位服务器名，供列表标注。
func FailoverServers(ctx context.Context, db *sql.DB) (map[int64][]string, error) {
	rows, err := db.QueryContext(ctx, `SELECT ns.node_id, coalesce(nullif(s.name,''), s.ip) FROM node_server ns JOIN server s ON s.id = ns.server_id
		WHERE ns.failover = 1 AND ns.deployed <> 2 ORDER BY ns.node_id, ns.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64][]string{}
	for rows.Next() {
		var node int64
		var name string
		if err := rows.Scan(&node, &name); err != nil {
			return nil, err
		}
		out[node] = append(out[node], name)
	}
	return out, rows.Err()
}

type PlacementBrief struct {
	Server   string
	Network  string // TCP / UDP
	Deployed int
	Probes   []ProbeState // 各探测机最新定时检测结果，未检测为空
}

// Unreachable 与告警口径一致：所有探测机都已连续失败 ProbeFailRounds 轮。
func (p PlacementBrief) Unreachable() bool {
	for _, r := range p.Probes {
		if r.Fails < ProbeFailRounds {
			return false
		}
	}
	return len(p.Probes) > 0
}

// Flaky 表示有探测机最近一次失败但尚未达到不可达。
func (p PlacementBrief) Flaky() bool {
	for _, r := range p.Probes {
		if !r.OK {
			return !p.Unreachable()
		}
	}
	return false
}

// NodePlacementBriefs 按节点返回部署位服务器名、部署状态与定时检测结果，供列表点开查看。
func NodePlacementBriefs(ctx context.Context, db *sql.DB) (map[int64][]PlacementBrief, error) {
	probes := map[int64][]ProbeState{}
	prow, err := db.QueryContext(ctx, `SELECT pp.placement_id,coalesce(nullif(s.name,''),s.ip),pp.ok,pp.ms,pp.msg,pp.fails,pp.checked_time,coalesce(pp.last_ok_time,'')
 FROM placement_probe pp JOIN server s ON s.id=pp.probe_server_id ORDER BY pp.probe_server_id`)
	if err != nil {
		return nil, err
	}
	defer prow.Close()
	for prow.Next() {
		var id int64
		var st ProbeState
		if err := prow.Scan(&id, &st.ProbeName, &st.OK, &st.Ms, &st.Msg, &st.Fails, &st.Checked, &st.LastOK); err != nil {
			return nil, err
		}
		probes[id] = append(probes[id], st)
	}
	if err := prow.Err(); err != nil {
		return nil, err
	}
	rows, err := db.QueryContext(ctx, `SELECT ns.id, ns.node_id, coalesce(nullif(s.name,''), s.ip), CASE WHEN `+placementSyncedSQL+` THEN 1 ELSE ns.deployed END, coalesce(n.protocol,'') FROM node_server ns
		JOIN server s ON s.id = ns.server_id JOIN node n ON n.id = ns.node_id
		ORDER BY ns.node_id, ns.priority, ns.id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64][]PlacementBrief{}
	for rows.Next() {
		var id, node int64
		var p PlacementBrief
		if err := rows.Scan(&id, &node, &p.Server, &p.Deployed, &p.Network); err != nil {
			return nil, err
		}
		p.Network = map[bool]string{true: "UDP", false: "TCP"}[p.Network == "hysteria2"]
		p.Probes = probes[id]
		out[node] = append(out[node], p)
	}
	return out, rows.Err()
}

type Placement struct {
	ID             int64
	NodeID         int64
	ServerID       int64
	ServerName     string
	ServerIP       string
	ServerRegion   string
	ServerType     int
	ServerExternal bool
	Disabled       bool // 服务器已禁用
	AccessHostID   *int64
	CustomHost     string // 外部自定义访问域名，与 AccessHostID 互斥
	Exposed        bool
	Priority       int
	Deployed       int
	Running        bool // 曾成功部署过（服务器上已有该节点），可承接转移的域名
	CheapStart     string
	CheapEnd       string
	Failover       bool // 已手动切走
}

// PlacementOps 是节点表单暂存的部署位增删，随节点保存在同一事务中执行。
type PlacementOps struct {
	AddServerIDs []int64
	Deletes      []PlacementDelete
	NewHosts     []*NewHost
}

// NewHost 为部署位新建访问域名：在主域名下建指向服务器 IP 的 A 记录，保存成功后 RecordID 为新记录。
type NewHost struct {
	ServerID, DomainID int64
	Name               string
	RecordID           int64
}

// PlacementDelete 删除部署位，TargetID 为承接访问域名和订阅定制的部署位（无绑定时可为 0）。
type PlacementDelete struct {
	PlacementID, TargetID int64
}

// PlacementEdit 是节点表单对已有部署位属性的修改。
type PlacementEdit struct {
	ServerID     int64
	Priority     int
	Exposed      bool
	CheapStart   string
	CheapEnd     string
	AccessHostID *int64
	CustomHost   string // 非空时使用外部自定义访问域名，忽略 AccessHostID
}

var hhmm = regexp.MustCompile(`^([01]\d|2[0-3]):[0-5]\d$`)

// Placements 返回节点的全部部署位。
func Placements(ctx context.Context, q dbtx, nodeID int64) ([]*Placement, error) {
	rows, err := q.QueryContext(ctx, `SELECT ns.id, ns.node_id, ns.server_id, coalesce(s.name,''), s.ip, coalesce(s.region,''), coalesce(s.server_type,0), s.external_server, s.disabled, ns.access_host_id, coalesce(ns.access_host_custom,''),
		coalesce(ns.exposed,1) <> 0, coalesce(ns.priority,0), ns.deployed,
		ns.deployed = 1 OR EXISTS (SELECT 1 FROM node_deployment d WHERE d.node_server_id = ns.id), coalesce(ns.cheap_start,''), coalesce(ns.cheap_end,''), ns.failover
		FROM node_server ns JOIN server s ON s.id = ns.server_id WHERE ns.node_id = ? ORDER BY ns.priority, ns.id`, nodeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Placement
	for rows.Next() {
		var p Placement
		var host sql.NullInt64
		if err := rows.Scan(&p.ID, &p.NodeID, &p.ServerID, &p.ServerName, &p.ServerIP, &p.ServerRegion, &p.ServerType, &p.ServerExternal, &p.Disabled, &host, &p.CustomHost, &p.Exposed, &p.Priority,
			&p.Deployed, &p.Running, &p.CheapStart, &p.CheapEnd, &p.Failover); err != nil {
			return nil, err
		}
		if host.Valid {
			p.AccessHostID = &host.Int64
		}
		list = append(list, &p)
	}
	return list, rows.Err()
}

// AccessHostOption 是部署位可选的访问域名：已绑定到该服务器的 DNS 记录。
type AccessHostOption struct {
	ID       int64
	ServerID int64
	FullName string
}

func AccessHostOptions(ctx context.Context, q dbtx, serverIDs []int64) (map[int64][]AccessHostOption, error) {
	out := map[int64][]AccessHostOption{}
	for _, sid := range serverIDs {
		rows, err := q.QueryContext(ctx, `SELECT id, coalesce(full_name, name) FROM domain_dns_record WHERE server_id = ? ORDER BY full_name`, sid)
		if err != nil {
			return nil, err
		}
		for rows.Next() {
			o := AccessHostOption{ServerID: sid}
			rows.Scan(&o.ID, &o.FullName)
			out[sid] = append(out[sid], o)
		}
		rows.Close()
	}
	return out, nil
}

func applyPlacementEdits(ctx context.Context, q dbtx, n *Node, edits []PlacementEdit) error {
	for _, e := range edits {
		start, end := strings.TrimSpace(e.CheapStart), strings.TrimSpace(e.CheapEnd)
		if start != "" || end != "" {
			if !hhmm.MatchString(start) || !hhmm.MatchString(end) || start == end {
				return invalid("便宜时段起止需为不同的 HH:mm")
			}
		}
		custom, err := normalizeCustomHost(e.CustomHost)
		if err != nil {
			return err
		}
		if custom != "" {
			e.AccessHostID = nil
		}
		if e.AccessHostID != nil {
			var n int
			q.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record WHERE id = ? AND server_id = ?`, *e.AccessHostID, e.ServerID).Scan(&n)
			if n == 0 {
				return invalid("访问域名必须是已绑定到该服务器的 DNS 记录")
			}
		}
		exposed := e.Exposed && n.Type != NodeLanding // 落地节点不进订阅
		if _, err := q.ExecContext(ctx, `UPDATE node_server SET priority=?, exposed=?, cheap_start=?, cheap_end=?, access_host_id=?, access_host_custom=?, update_time=?
			WHERE node_id=? AND server_id=? AND deployed <> 2`, e.Priority, exposed, nz(start), nz(end), e.AccessHostID, nz(custom), FormatTime(nowFunc()), n.ID, e.ServerID); err != nil {
			return err
		}
	}
	return nil
}

// normalizeCustomHost 规范化外部自定义访问域名：小写、去末尾点；必须是至少两级的合法域名，不接受 IP 和通配符。空串表示不使用。
func normalizeCustomHost(s string) (string, error) {
	s = strings.TrimSuffix(strings.ToLower(strings.TrimSpace(s)), ".")
	if s == "" {
		return "", nil
	}
	if net.ParseIP(s) != nil || len(s) > 253 || !strings.Contains(s, ".") || !subdomainRe.MatchString(s) {
		return "", invalid("自定义访问域名 %s 不是合法域名（只能包含小写字母、数字、连字符和点，不能是 IP）", s)
	}
	return s, nil
}

// applyNewHosts 新建访问域名并替换部署位当前的访问域名；DNS 推送由调用方在提交后处理。
func applyNewHosts(ctx context.Context, tx *sql.Tx, nodeID int64, hosts []*NewHost) error {
	for _, h := range hosts {
		var ip string
		err := tx.QueryRowContext(ctx, `SELECT s.ip FROM node_server ns JOIN server s ON s.id = ns.server_id WHERE ns.node_id = ? AND ns.server_id = ? AND ns.deployed <> 2`, nodeID, h.ServerID).Scan(&ip)
		if err == sql.ErrNoRows {
			return invalid("部署位不存在，无法新增域名")
		}
		if err != nil {
			return err
		}
		id, err := createBindingRecord(ctx, tx, h.DomainID, h.Name, h.ServerID, ip)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, `UPDATE node_server SET access_host_id = ?, access_host_custom = NULL, update_time = ? WHERE node_id = ? AND server_id = ? AND deployed <> 2`,
			id, FormatTime(nowFunc()), nodeID, h.ServerID); err != nil {
			return err
		}
		h.RecordID = id
	}
	return nil
}
