package store

import (
	"bytes"
	"context"
	"crypto/md5"
	"database/sql"
	"encoding/hex"
	"encoding/json"
	"errors"
	"regexp"
	"slices"
	"sort"
	"strconv"
	"strings"
	"unicode/utf8"

	"github.com/fun90/airopscat/internal/singbox"
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

// ProtocolsFor 返回节点类型允许的协议（决策沿用 spec-deploy 1.1）。
func ProtocolsFor(nodeType int) []string {
	if nodeType == NodeLanding {
		return []string{"shadowsocks", "socks"}
	}
	return []string{"vless", "vless-reality", "hysteria2", "shadowtls", "anytls"}
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
	OutStatus                                                              int // 出站落地节点的 DeployStatus，仅列表填充
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

var numberSuffix = regexp.MustCompile(`-\d+$`)

// NodeNameWithNumber 保证名称以 -N 结尾，缺省补 -1（订阅按此编号去重）。
func NodeNameWithNumber(name string) string {
	name = strings.TrimSpace(name)
	if name == "" || numberSuffix.MatchString(name) {
		return name
	}
	return name + "-1"
}

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
	cnt := func(extra string) string {
		return "(SELECT count(*) FROM node_server ns WHERE ns.node_id = n.id" + extra + ")"
	}
	all, del, dep := cnt(""), cnt(" AND ns.deployed = 2"), cnt(" AND ns.deployed = 1")
	switch f.Status {
	case -1:
		w = append(w, all+" = 0")
	case PendingDelete:
		w = append(w, all+" > 0 AND "+all+" = "+del)
	case Deployed:
		w = append(w, all+" > 0 AND "+all+" = "+dep)
	case PendingDeploy:
		w = append(w, all+" > 0 AND "+all+" <> "+del+" AND "+all+" <> "+dep)
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
		if n.OutID != nil {
			var o Node
			if err = db.QueryRowContext(ctx, `SELECT count(*), coalesce(sum(deployed = 1), 0), coalesce(sum(deployed = 2), 0)
				FROM node_server WHERE node_id = ?`, *n.OutID).Scan(&o.PlacementTotal, &o.PlacementDeployed, &o.PlacementDeleting); err != nil {
				return nil, 0, err
			}
			n.OutStatus = o.DeployStatus()
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
		return invalid("节点地区不能为空，新建时按地区和类型选择初始部署位")
	case n.Type != NodeProxy && n.Type != NodeLanding:
		return invalid("节点类型不正确")
	case n.Port < 1 || n.Port > 65535:
		return invalid("端口范围 1-65535")
	}
	if !slices.Contains(ProtocolsFor(n.Type), n.Protocol) {
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
