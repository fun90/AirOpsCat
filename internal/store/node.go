package store

import (
	"bytes"
	"context"
	"crypto/md5"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/fun90/airopscat/internal/singbox"
	"regexp"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"
)

// 部署位状态
const (
	PendingDeploy = 0
	Deployed      = 1
	PendingDelete = 2
)

// 节点类型
const (
	NodeProxy   = 0
	NodeLanding = 1
)

// ErrInvalid 表示用户输入不合法，调用方应把消息回显给用户。
var ErrInvalid = errors.New("输入不合法")

func invalid(f string, a ...any) error {
	return fmt.Errorf("%w：%s", ErrInvalid, fmt.Sprintf(f, a...))
}

// ProtocolsFor 返回节点类型允许的协议（决策沿用 spec-deploy 1.1）。
func ProtocolsFor(nodeType int) []string {
	if nodeType == NodeLanding {
		return []string{"shadowsocks", "socks"}
	}
	return []string{"vless", "vless-reality", "hysteria2", "shadowtls"}
}

type Node struct {
	ID       int64
	Name     string
	Type     int
	Protocol string
	Port     int
	Inbound  string
	OutID    *int64
	OutName  string
	Region   string
	Remark   string
	Disabled bool
	TagIDs   []int64
	Tags     []*Tag
	// 列表用的部署位统计
	PlacementTotal, PlacementDeployed, PlacementPending, PlacementDeleting int
}

// DeployStatus 汇总部署位状态：全部待删除→待删除；全部已部署→已部署；没有部署位→无；其余→待部署。
func (n *Node) DeployStatus() int {
	switch {
	case n.PlacementTotal == 0:
		return -1
	case n.PlacementDeleting == n.PlacementTotal:
		return PendingDelete
	case n.PlacementDeployed == n.PlacementTotal:
		return Deployed
	}
	return PendingDeploy
}

type Placement struct {
	ID           int64
	NodeID       int64
	ServerID     int64
	ServerName   string
	ServerIP     string
	Disabled     bool // 服务器已禁用
	AccessHostID *int64
	Exposed      bool
	Priority     int
	Deployed     int
	CheapStart   string
	CheapEnd     string
}

// PlacementEdit 是节点表单对已有部署位属性的修改。
type PlacementEdit struct {
	ServerID     int64
	Priority     int
	Exposed      bool
	CheapStart   string
	CheapEnd     string
	AccessHostID *int64
}

type dbtx interface {
	ExecContext(context.Context, string, ...any) (sql.Result, error)
	QueryContext(context.Context, string, ...any) (*sql.Rows, error)
	QueryRowContext(context.Context, string, ...any) *sql.Row
}

var numberSuffix = regexp.MustCompile(`-\d+$`)

// NodeNameWithNumber 保证名称以 -N 结尾，缺省补 -1（订阅按此编号去重）。
func NodeNameWithNumber(name string) string {
	name = strings.TrimSpace(name)
	if name == "" || numberSuffix.MatchString(name) {
		return name
	}
	return name + "-1"
}

var hhmm = regexp.MustCompile(`^([01]\d|2[0-3]):[0-5]\d$`)

func normJSON(s string) string {
	if strings.TrimSpace(s) == "" {
		return ""
	}
	var v any
	if json.Unmarshal([]byte(s), &v) != nil {
		return s
	}
	b, _ := json.Marshal(v) // map 键按字典序输出，等价于规范化
	return string(b)
}

// signature 是「实质变更」签名：这些字段变化后需要重新部署。
func (n *Node) signature(tagIDs []int64) string {
	ids := append([]int64(nil), tagIDs...)
	sort.Slice(ids, func(i, j int) bool { return ids[i] < ids[j] })
	parts := []string{strconv.Itoa(n.Port), strconv.Itoa(n.Type), n.Protocol, normJSON(n.Inbound), "", strconv.FormatBool(n.Disabled)}
	if n.OutID != nil {
		parts[4] = strconv.FormatInt(*n.OutID, 10)
	}
	for _, id := range ids {
		parts = append(parts, strconv.FormatInt(id, 10))
	}
	sum := md5.Sum([]byte(strings.Join(parts, "|")))
	return hex.EncodeToString(sum[:])
}

// outboundSignature 不含标签：标签只影响本节点用户，不影响引用它作出站的代理节点。
func (n *Node) outboundSignature() string { return n.signature(nil) }

const nodeCols = `n.id, coalesce(n.name,''), coalesce(n.type,0), coalesce(n.protocol,''), coalesce(n.port,0), coalesce(n.inbound,''), n.out_id,
	coalesce((SELECT name FROM node o WHERE o.id = n.out_id),''), coalesce(n.region,''), coalesce(n.remark,''), n.disabled`

func scanNode(row interface{ Scan(...any) error }, extra ...any) (*Node, error) {
	var n Node
	var out sql.NullInt64
	dst := append([]any{&n.ID, &n.Name, &n.Type, &n.Protocol, &n.Port, &n.Inbound, &out, &n.OutName, &n.Region, &n.Remark, &n.Disabled}, extra...)
	if err := row.Scan(dst...); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	if out.Valid {
		n.OutID = &out.Int64
	}
	return &n, nil
}

type NodeFilter struct {
	Search string
	Type   int // -1 不限
	Status int // -2 不限；-1 无部署位；0/1/2 汇总状态
	TagID  int64
}

func (f NodeFilter) where() (string, []any) {
	w, args := []string{"1=1"}, []any{}
	if f.Search != "" {
		like := "%" + f.Search + "%"
		w = append(w, `(n.name LIKE ? OR n.protocol LIKE ? OR n.region LIKE ? OR n.remark LIKE ? OR CAST(n.port AS TEXT) LIKE ?)`)
		args = append(args, like, like, like, like, like)
	}
	if f.Type >= 0 {
		w, args = append(w, "n.type = ?"), append(args, f.Type)
	}
	if f.TagID > 0 {
		w, args = append(w, "EXISTS (SELECT 1 FROM node_tag nt WHERE nt.node_id = n.id AND nt.tag_id = ?)"), append(args, f.TagID)
	}
	const cnt = "(SELECT count(*) FROM node_server ns WHERE ns.node_id = n.id%s)"
	switch f.Status {
	case -1:
		w = append(w, fmt.Sprintf(cnt, "")+" = 0")
	case PendingDelete:
		w = append(w, fmt.Sprintf(cnt, "")+" > 0 AND "+fmt.Sprintf(cnt, "")+" = "+fmt.Sprintf(cnt, " AND ns.deployed = 2"))
	case Deployed:
		w = append(w, fmt.Sprintf(cnt, "")+" > 0 AND "+fmt.Sprintf(cnt, "")+" = "+fmt.Sprintf(cnt, " AND ns.deployed = 1"))
	case PendingDeploy:
		w = append(w, fmt.Sprintf(cnt, "")+" > 0 AND "+fmt.Sprintf(cnt, "")+" <> "+fmt.Sprintf(cnt, " AND ns.deployed = 2")+
			" AND "+fmt.Sprintf(cnt, "")+" <> "+fmt.Sprintf(cnt, " AND ns.deployed = 1"))
	}
	return strings.Join(w, " AND "), args
}

// NodeList 分页查询节点，附带部署位统计和标签。
func NodeList(ctx context.Context, db *sql.DB, f NodeFilter, page, size int) ([]*Node, int, error) {
	where, args := f.where()
	var total int
	if err := db.QueryRowContext(ctx, "SELECT count(*) FROM node n WHERE "+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT `+nodeCols+`,
		(SELECT count(*) FROM node_server ns WHERE ns.node_id = n.id),
		(SELECT count(*) FROM node_server ns WHERE ns.node_id = n.id AND ns.deployed = 1),
		(SELECT count(*) FROM node_server ns WHERE ns.node_id = n.id AND ns.deployed = 0),
		(SELECT count(*) FROM node_server ns WHERE ns.node_id = n.id AND ns.deployed = 2)
		FROM node n WHERE `+where+` ORDER BY n.id DESC LIMIT ? OFFSET ?`, append(args, size, (page-1)*size)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*Node
	for rows.Next() {
		var n *Node
		var t, d, p, x int
		n, err = scanNode(rows, &t, &d, &p, &x)
		if err != nil {
			return nil, 0, err
		}
		n.PlacementTotal, n.PlacementDeployed, n.PlacementPending, n.PlacementDeleting = t, d, p, x
		list = append(list, n)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	rows.Close()
	for _, n := range list {
		if n.Tags, err = nodeTags(ctx, db, n.ID); err != nil {
			return nil, 0, err
		}
	}
	return list, total, nil
}

func nodeTags(ctx context.Context, q dbtx, nodeID int64) ([]*Tag, error) {
	rows, err := q.QueryContext(ctx, `SELECT t.id, t.name, coalesce(t.color,'') FROM node_tag nt JOIN tag t ON t.id = nt.tag_id WHERE nt.node_id = ? ORDER BY t.id`, nodeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var tags []*Tag
	for rows.Next() {
		var t Tag
		if err := rows.Scan(&t.ID, &t.Name, &t.Color); err != nil {
			return nil, err
		}
		tags = append(tags, &t)
	}
	return tags, rows.Err()
}

func loadNode(ctx context.Context, q dbtx, id int64) (*Node, error) {
	n, err := scanNode(q.QueryRowContext(ctx, `SELECT `+nodeCols+` FROM node n WHERE n.id = ?`, id))
	if err != nil || n == nil {
		return n, err
	}
	if n.Tags, err = nodeTags(ctx, q, id); err != nil {
		return nil, err
	}
	for _, t := range n.Tags {
		n.TagIDs = append(n.TagIDs, t.ID)
	}
	return n, nil
}

// NodeByID 返回节点（含标签），不存在返回 nil。
func NodeByID(ctx context.Context, db *sql.DB, id int64) (*Node, error) { return loadNode(ctx, db, id) }

// LandingNodes 返回可作为出站的落地节点。
func LandingNodes(ctx context.Context, db *sql.DB) ([]*Node, error) {
	rows, err := db.QueryContext(ctx, `SELECT `+nodeCols+` FROM node n WHERE n.type = 1 ORDER BY n.name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Node
	for rows.Next() {
		n, err := scanNode(rows)
		if err != nil {
			return nil, err
		}
		list = append(list, n)
	}
	return list, rows.Err()
}

// Regions 返回已有的地区（服务器和节点），用于表单联想。
func Regions(ctx context.Context, db *sql.DB) ([]string, error) {
	rows, err := db.QueryContext(ctx, `SELECT region FROM server WHERE region IS NOT NULL AND region <> ''
		UNION SELECT region FROM node WHERE region IS NOT NULL AND region <> '' ORDER BY 1`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []string
	for rows.Next() {
		var r string
		rows.Scan(&r)
		list = append(list, r)
	}
	return list, rows.Err()
}

// Placements 返回节点的全部部署位。
func Placements(ctx context.Context, q dbtx, nodeID int64) ([]*Placement, error) {
	rows, err := q.QueryContext(ctx, `SELECT ns.id, ns.node_id, ns.server_id, coalesce(s.name,''), s.ip, s.disabled, ns.access_host_id,
		coalesce(ns.exposed,1) <> 0, coalesce(ns.priority,0), ns.deployed, coalesce(ns.cheap_start,''), coalesce(ns.cheap_end,'')
		FROM node_server ns JOIN server s ON s.id = ns.server_id WHERE ns.node_id = ? ORDER BY ns.priority, ns.id`, nodeID)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Placement
	for rows.Next() {
		var p Placement
		var host sql.NullInt64
		if err := rows.Scan(&p.ID, &p.NodeID, &p.ServerID, &p.ServerName, &p.ServerIP, &p.Disabled, &host, &p.Exposed, &p.Priority,
			&p.Deployed, &p.CheapStart, &p.CheapEnd); err != nil {
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

func validateNode(ctx context.Context, q dbtx, n *Node) error {
	n.Name = NodeNameWithNumber(n.Name)
	n.Region = strings.TrimSpace(n.Region)
	n.Protocol = strings.ToLower(strings.TrimSpace(n.Protocol))
	switch {
	case n.Name == "":
		return invalid("节点名称不能为空")
	case utf8.RuneCountInString(n.Name) > 257:
		return invalid("节点名称不能超过 257 个字符")
	case n.Region == "":
		return invalid("节点地区不能为空，部署位按地区和类型自动关联服务器")
	case n.Type != NodeProxy && n.Type != NodeLanding:
		return invalid("节点类型不正确")
	case n.Port < 1 || n.Port > 65535:
		return invalid("端口范围 1-65535")
	}
	ok := false
	for _, p := range ProtocolsFor(n.Type) {
		ok = ok || p == n.Protocol
	}
	if !ok {
		return invalid("协议 %s 不适用于该节点类型，可选：%s", n.Protocol, strings.Join(ProtocolsFor(n.Type), "、"))
	}
	var obj map[string]any
	if err := json.Unmarshal([]byte(n.Inbound), &obj); err != nil || obj == nil {
		return invalid("入站配置必须是 JSON 对象")
	}
	if n.Protocol == "shadowtls" {
		if err := singbox.ValidateShadowTLS(n.Inbound); err != nil {
			return invalid("%s", err)
		}
	}
	var compact bytes.Buffer
	if json.Compact(&compact, []byte(n.Inbound)) == nil { // 存紧凑形式，保持键顺序
		n.Inbound = compact.String()
	}
	for _, k := range []string{"tag", "listen", "listen_port"} {
		if _, has := obj[k]; has {
			return invalid("入站配置不能包含 %s（由系统生成）", k)
		}
	}
	if n.OutID != nil {
		if n.Type != NodeProxy {
			return invalid("只有代理节点可以指定出站")
		}
		if n.ID != 0 && *n.OutID == n.ID {
			return invalid("出站不能是自己")
		}
		var t int
		if err := q.QueryRowContext(ctx, `SELECT type FROM node WHERE id = ?`, *n.OutID).Scan(&t); err != nil || t != NodeLanding {
			return invalid("出站必须是已存在的落地节点")
		}
	}
	return nil
}

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

// availableNodePort 在写事务内从 10000 起取全局未用端口，避免未来部署位合并冲突。
func availableNodePort(ctx context.Context, q dbtx) (int, error) {
	rows, err := q.QueryContext(ctx, `SELECT DISTINCT port FROM node WHERE port>=10000 ORDER BY port`)
	if err != nil {
		return 0, err
	}
	defer rows.Close()
	candidate := 10000
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
	if candidate > 65535 {
		return 0, invalid("没有可自动分配的端口，请手动指定")
	}
	return candidate, nil
}

// SaveNode 新建（ID=0）或更新节点及其标签、部署位属性，返回节点 ID。
// 部署位本身由地区和类型自动同步；实质变更会把该节点的部署位置为待部署。
func SaveNode(ctx context.Context, db *sql.DB, n *Node, tagIDs []int64, edits []PlacementEdit) (int64, error) {
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
		if err := syncNode(ctx, tx, n.ID, true); err != nil {
			return 0, err
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
	oldSig, oldOutSig := old.signature(old.TagIDs), old.outboundSignature()
	prevEffective, err := effectiveServer(ctx, tx, n.ID)
	if err != nil {
		return 0, err
	}
	if _, err := tx.ExecContext(ctx, `UPDATE node SET name=?, type=?, protocol=?, port=?, inbound=?, out_id=?, region=?, remark=?, disabled=?, update_time=? WHERE id=?`,
		n.Name, n.Type, n.Protocol, n.Port, n.Inbound, n.OutID, n.Region, nz(n.Remark), n.Disabled, now, n.ID); err != nil {
		return 0, err
	}
	if err := replaceTags(ctx, tx, n.ID, tagIDs); err != nil {
		return 0, err
	}
	if err := cleanUnauthorizedBindings(ctx, tx, 0); err != nil {
		return 0, err
	}
	// 端口变化时，已有部署位所在服务器上不能冲突
	pls, err := Placements(ctx, tx, n.ID)
	if err != nil {
		return 0, err
	}
	for _, p := range pls {
		if p.Deployed == PendingDelete {
			continue
		}
		if other, err := portConflict(ctx, tx, p.ServerID, n.Port, n.ID); err != nil {
			return 0, err
		} else if other != "" {
			return 0, fmt.Errorf("%w：端口 %d 在服务器 %s 上已被节点 %s 占用", ErrConflict, n.Port, p.ServerName, other)
		}
	}
	if err := syncNode(ctx, tx, n.ID, true); err != nil {
		return 0, err
	}
	if err := applyPlacementEdits(ctx, tx, n, edits); err != nil {
		return 0, err
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

func applyPlacementEdits(ctx context.Context, q dbtx, n *Node, edits []PlacementEdit) error {
	for _, e := range edits {
		start, end := strings.TrimSpace(e.CheapStart), strings.TrimSpace(e.CheapEnd)
		if start != "" || end != "" {
			if !hhmm.MatchString(start) || !hhmm.MatchString(end) || start == end {
				return invalid("便宜时段起止需为不同的 HH:mm")
			}
		}
		if e.AccessHostID != nil {
			var n int
			q.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record WHERE id = ? AND server_id = ?`, *e.AccessHostID, e.ServerID).Scan(&n)
			if n == 0 {
				return invalid("访问域名必须是已绑定到该服务器的 DNS 记录")
			}
		}
		exposed := e.Exposed && n.Type != NodeLanding // 落地节点不进订阅
		if _, err := q.ExecContext(ctx, `UPDATE node_server SET priority=?, exposed=?, cheap_start=?, cheap_end=?, access_host_id=?, update_time=?
			WHERE node_id=? AND server_id=? AND deployed <> 2`, e.Priority, exposed, nz(start), nz(end), e.AccessHostID, FormatTime(nowFunc()), n.ID, e.ServerID); err != nil {
			return err
		}
	}
	return nil
}

// ToggleNode 启用/禁用节点，本节点及引用它的代理节点置为待部署。
func ToggleNode(ctx context.Context, db *sql.DB, id int64, disabled bool) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE node SET disabled = ?, update_time = ? WHERE id = ?`, disabled, FormatTime(nowFunc()), id)
	if err != nil {
		return err
	}
	if c, _ := res.RowsAffected(); c == 0 {
		return sql.ErrNoRows
	}
	if err := markPending(ctx, tx, id); err != nil {
		return err
	}
	if err := markReferencingPending(ctx, tx, id); err != nil {
		return err
	}
	return tx.Commit()
}

// DeleteNode 删除节点：未部署过的部署位直接删除，部署过的标记待删除（部署后从服务器移除）。
// 返回 true 表示节点已物理删除。仍被其他节点引用为出站时拒绝。
func DeleteNode(ctx context.Context, db *sql.DB, id int64) (bool, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return false, err
	}
	defer tx.Rollback()
	var refs int
	tx.QueryRowContext(ctx, `SELECT (SELECT count(*) FROM node WHERE out_id = ?) + (SELECT count(*) FROM route_rule WHERE outbound_node_id = ?)`, id, id).Scan(&refs)
	if refs > 0 {
		return false, fmt.Errorf("%w：该节点被 %d 个节点或路由规则引用为出站，请先解除引用", ErrConflict, refs)
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
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
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
	return tx.Commit()
}
