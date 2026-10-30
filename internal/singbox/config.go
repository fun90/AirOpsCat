package singbox

import (
	"bytes"
	"encoding/json"
	"fmt"
	"strings"
)

// Client 是节点上的托管用户，限速与 IP 字段均为已生效值（0 表示不限制）。
type Client struct {
	UUID         string
	Name         string // 账户号，统计、限速、跨节点配额都以它为键
	DownloadMbps int
	UploadMbps   int
	MaxIPs       int
	QuotaLimited bool // 当前速度是否由流量超额策略覆盖，仅用于同步判断。
}

// Outbound 是作为出站使用的落地节点快照，由其 inbound 反推出站配置。
type Outbound struct {
	NodeID   int64
	Protocol string
	Inbound  string // 落地节点库中的 inbound JSON（不含托管用户）
	ServerIP string // 落地节点生效部署位所在服务器的 IP
	Port     int
}

func (o Outbound) tag() string { return fmt.Sprintf("node_%d", o.NodeID) }

// NodeInput 是本服务器上一个非待删除部署位对应的节点。
type NodeInput struct {
	ID       int64
	Protocol string
	Port     int
	Inbound  string // 节点库中的 inbound JSON，空串表示不生成入站
	Disabled bool
	Out      *Outbound // 节点的落地出站，nil 表示直连
	Clients  []Client
}

// RouteRule 是启用的路由规则；Out 为 nil 表示出站节点没有生效部署位，规则被静默跳过。
type RouteRule struct {
	Type  string // domain/ip/source_ip/port/network/inbound/custom
	Value string // JSON
	Out   *Outbound
}

// ServerConfigInput 是生成单台服务器配置所需的全部数据，Nodes 按 priority, id 排序。
type ServerConfigInput struct {
	Nodes       []NodeInput
	Rules       []RouteRule
	ClashSecret string
}

const (
	defaultHTTPClient = "default-http-client"
	geoipURL          = "https://raw.githubusercontent.com/SagerNet/sing-geoip/refs/heads/rule-set/"
	geositeURL        = "https://raw.githubusercontent.com/SagerNet/sing-geosite/refs/heads/rule-set/"
)

type obj = map[string]any

// BuildServerConfig 生成单台服务器的完整 sing-box 配置（缩进 JSON）。
// 输出由 map 序列化，键按字典序稳定排列。
func BuildServerConfig(in ServerConfigInput) ([]byte, error) {
	var inbounds, outbounds []any
	var nodeRules, managedRules []any
	var ruleSets []any
	var statsUsers []string
	seenStats := map[string]bool{}
	seenOut := map[string]bool{}
	seenSet := map[string]bool{"geoip-cn": true, "geosite-cn": true} // 基础模板已含

	addOutbound := func(o Outbound) error {
		if seenOut[o.tag()] {
			return nil
		}
		ob, err := buildOutbound(o)
		if err != nil {
			return err
		}
		ob["tag"] = o.tag()
		outbounds = append(outbounds, ob)
		seenOut[o.tag()] = true
		return nil
	}

	for _, n := range in.Nodes {
		if n.Disabled || strings.TrimSpace(n.Inbound) == "" {
			continue
		}
		ib, err := buildInbound(n)
		if err != nil {
			return nil, fmt.Errorf("节点 %d: %w", n.ID, err)
		}
		inbounds = append(inbounds, ib)
		if protocolOf(n.Protocol) == "shadowtls" {
			inner, err := shadowTLSInbounds(n, ib)
			if err != nil {
				return nil, fmt.Errorf("节点 %d: %w", n.ID, err)
			}
			inbounds = append(inbounds, inner)
		}

		var names []string
		switch protocolOf(n.Protocol) {
		case "shadowsocks", "socks":
			for _, u := range asObjList(ib["users"]) {
				names = append(names, userName(u))
			}
		default:
			for _, c := range n.Clients {
				names = append(names, c.Name)
			}
		}
		for _, name := range names {
			if name != "" && !seenStats[name] {
				seenStats[name] = true
				statsUsers = append(statsUsers, name)
			}
		}

		if n.Out != nil {
			if n.Out.ServerIP == "" || n.Out.Port == 0 {
				return nil, fmt.Errorf("节点 %d 的落地节点 %d 没有可用的部署服务器", n.ID, n.Out.NodeID)
			}
			if err := addOutbound(*n.Out); err != nil {
				return nil, fmt.Errorf("节点 %d: %w", n.ID, err)
			}
			nodeRules = append(nodeRules, obj{"inbound": []string{fmt.Sprintf("node_%d", n.ID)}, "outbound": n.Out.tag()})
		}
	}

	for _, r := range in.Rules {
		if r.Out == nil || r.Out.ServerIP == "" || r.Out.Port == 0 || strings.TrimSpace(r.Out.Inbound) == "" {
			continue
		}
		if err := addOutbound(*r.Out); err != nil {
			return nil, err
		}
		rules, err := buildRouteRules(r, &ruleSets, seenSet)
		if err != nil {
			return nil, err
		}
		managedRules = append(managedRules, rules...)
	}

	rules := []any{
		obj{"action": "sniff"},
		obj{"port": 53, "action": "hijack-dns"},
		obj{"action": "resolve", "strategy": "prefer_ipv4"},
		obj{"ip_is_private": true, "action": "reject"},
	}
	rules = append(append(rules, nodeRules...), managedRules...)

	stats := obj{"enabled": true}
	if len(statsUsers) > 0 {
		stats["users"] = statsUsers
	}
	cfg := obj{
		"log": obj{"level": "warn", "output": "/var/lib/sing-box/box.log", "timestamp": true},
		"experimental": obj{
			"clash_api":  obj{"external_controller": "127.0.0.1:19191", "secret": in.ClashSecret},
			"cache_file": obj{"enabled": true, "path": "/var/lib/sing-box/cache.db", "cache_id": "airopscat"},
			"v2ray_api":  obj{"listen": "127.0.0.1:101", "stats": stats},
		},
		"dns":          obj{"servers": []any{obj{"type": "tls", "tag": "google", "server": "8.8.8.8"}}, "strategy": "ipv4_only"},
		"http_clients": []any{obj{"tag": defaultHTTPClient}},
		"inbounds":     nonNil(inbounds),
		"outbounds":    append([]any{obj{"type": "direct", "tag": "default-direct"}, obj{"type": "block", "tag": "default-blocked"}}, outbounds...),
		"route": obj{
			"auto_detect_interface": true,
			"default_http_client":   defaultHTTPClient,
			"rules":                 rules,
			"rule_set": append([]any{
				remoteRuleSet("geoip-cn", geoipURL+"geoip-cn.srs"),
				remoteRuleSet("geosite-cn", geositeURL+"geosite-cn.srs"),
			}, ruleSets...),
		},
	}

	var buf bytes.Buffer
	enc := json.NewEncoder(&buf)
	enc.SetEscapeHTML(false)
	enc.SetIndent("", "  ")
	if err := enc.Encode(cfg); err != nil {
		return nil, err
	}
	return buf.Bytes(), nil
}

func nonNil(l []any) []any {
	if l == nil {
		return []any{}
	}
	return l
}

func protocolOf(p string) string { return strings.ToLower(strings.TrimSpace(p)) }

// parseObject 解析 JSON 对象；数字保留原文，避免大整数被转成浮点。
func parseObject(s string) (obj, error) {
	dec := json.NewDecoder(strings.NewReader(s))
	dec.UseNumber()
	var o obj
	if err := dec.Decode(&o); err != nil {
		return nil, fmt.Errorf("JSON 不是对象: %w", err)
	}
	if o == nil {
		return nil, fmt.Errorf("JSON 不是对象")
	}
	return o, nil
}

func asObj(v any) obj {
	o, _ := v.(map[string]any)
	return o
}

func asObjList(v any) []obj {
	list, _ := v.([]any)
	var out []obj
	for _, item := range list {
		if o := asObj(item); o != nil {
			out = append(out, o)
		}
	}
	return out
}

// userName 取入站用户名：name 优先，其次 username（socks）。
func userName(u obj) string {
	for _, k := range []string{"name", "username"} {
		if v, ok := u[k]; ok && v != nil {
			return fmt.Sprint(v)
		}
	}
	return ""
}

// buildInbound 改写节点的 inbound JSON，其余字段原样保留。
func buildInbound(n NodeInput) (obj, error) {
	ib, err := parseObject(n.Inbound)
	if err != nil {
		return nil, fmt.Errorf("inbound %w", err)
	}
	if reality := asObj(asObj(ib["tls"])["reality"]); reality != nil {
		delete(reality, "public_key") // 公钥只供订阅使用，服务端不接受
	}
	ib["tag"] = fmt.Sprintf("node_%d", n.ID)
	ib["listen"] = "::"
	ib["listen_port"] = n.Port

	switch protocolOf(n.Protocol) {
	case "vless", "vless-reality":
		managed := make([]obj, 0, len(n.Clients))
		for _, c := range n.Clients {
			managed = append(managed, c.limits(obj{"uuid": c.UUID, "name": c.Name, "flow": "xtls-rprx-vision"}))
		}
		ib["users"] = mergeUsers(asObjList(ib["users"]), managed, "uuid")
	case "hysteria2", "anytls":
		managed := make([]obj, 0, len(n.Clients))
		for _, c := range n.Clients {
			managed = append(managed, c.limits(obj{"name": c.Name, "password": c.UUID}))
		}
		ib["users"] = mergeUsers(asObjList(ib["users"]), managed, "name", "password")
	}
	return ib, nil
}

// limits 仅在值大于 0 时写入 fork 专有的限速与 IP 字段。
func (c Client) limits(u obj) obj {
	if c.DownloadMbps > 0 {
		u["download_mbps"] = c.DownloadMbps
	}
	if c.UploadMbps > 0 {
		u["upload_mbps"] = c.UploadMbps
	}
	if c.MaxIPs > 0 {
		u["max_ips"] = c.MaxIPs
	}
	return u
}

// mergeUsers 先放原有用户，再让托管用户按键覆盖或追加；同键时托管用户整体替换，位置保持。
func mergeUsers(current, managed []obj, keys ...string) []any {
	var merged []obj
	index := map[string]int{}
	put := func(u obj) {
		k := userKey(u, keys)
		if i, ok := index[k]; ok {
			merged[i] = u
			return
		}
		index[k] = len(merged)
		merged = append(merged, u)
	}
	for _, u := range current {
		put(u)
	}
	for _, u := range managed {
		put(u)
	}
	out := make([]any, len(merged))
	for i, u := range merged {
		out[i] = u
	}
	return out
}

func userKey(u obj, keys []string) string {
	for _, k := range keys {
		if v, ok := u[k]; ok && v != nil && fmt.Sprint(v) != "" {
			return k + ":" + fmt.Sprint(v)
		}
	}
	b, _ := json.Marshal(u) // map 键有序，可作为稳定键
	return string(b)
}

// buildOutbound 由落地节点的 inbound 反推出站。落地协议只有 shadowsocks/socks，
// 其他协议直接报错：不复制服务端 tls 字段，也不静默丢弃规则。
func buildOutbound(o Outbound) (obj, error) {
	ib, err := parseObject(o.Inbound)
	if err != nil {
		return nil, fmt.Errorf("落地节点 %d inbound %w", o.NodeID, err)
	}
	var first obj
	if users := asObjList(ib["users"]); len(users) > 0 {
		first = users[0]
	}
	ob := obj{"server": o.ServerIP, "server_port": o.Port}
	switch p := protocolOf(o.Protocol); p {
	case "shadowsocks":
		ob["type"] = p
		ob["method"] = ib["method"]
		if first != nil {
			ob["password"] = first["password"]
		} else {
			ob["password"] = ib["password"]
		}
	case "socks":
		ob["type"] = p
		if first != nil {
			ob["username"] = first["username"]
			ob["password"] = first["password"]
		}
	default:
		return nil, fmt.Errorf("落地节点 %d 的协议 %q 不支持作为出站", o.NodeID, o.Protocol)
	}
	return ob, nil
}

func remoteRuleSet(tag, url string) obj {
	return obj{"tag": tag, "type": "remote", "format": "binary", "url": url, "http_client": defaultHTTPClient}
}

// buildRouteRules 把一条路由规则转成 sing-box 规则，geosite:/geoip: 值转成远程 rule_set 并追加到 ruleSets。
func buildRouteRules(r RouteRule, ruleSets *[]any, seen map[string]bool) ([]any, error) {
	tag := r.Out.tag()
	dec := json.NewDecoder(strings.NewReader(r.Value))
	dec.UseNumber()
	var value any
	if err := dec.Decode(&value); err != nil {
		return nil, fmt.Errorf("路由规则值不是合法 JSON: %w", err)
	}

	switch r.Type {
	case "custom":
		rule, ok := value.(map[string]any)
		if !ok {
			return nil, fmt.Errorf("custom 路由规则值必须是 JSON 对象")
		}
		rule["outbound"] = tag
		return []any{rule}, nil
	case "port", "network", "inbound":
		return []any{obj{r.Type: value, "outbound": tag}}, nil
	case "domain", "ip", "source_ip":
	default:
		return nil, fmt.Errorf("不支持的路由规则类型 %q", r.Type)
	}

	field := map[string]string{"domain": "domain", "ip": "ip_cidr", "source_ip": "source_ip_cidr"}[r.Type]
	values, _ := value.([]any)
	if _, isList := value.([]any); !isList && value != nil {
		values = []any{value}
	}
	var plain []any
	var setTags []string
	for _, v := range values {
		s := strings.TrimSpace(fmt.Sprint(v))
		if s == "" {
			continue
		}
		kind, name, found := strings.Cut(s, ":")
		name = strings.ToLower(strings.TrimSpace(name))
		if !found || (kind != "geosite" && kind != "geoip") {
			plain = append(plain, v)
			continue
		}
		if name == "" {
			continue
		}
		setTag := kind + "-" + name
		setTags = append(setTags, setTag)
		if !seen[setTag] {
			seen[setTag] = true
			base := geositeURL
			if kind == "geoip" {
				base = geoipURL
			}
			*ruleSets = append(*ruleSets, remoteRuleSet(setTag, base+setTag+".srs"))
		}
	}

	var rules []any
	if len(plain) > 0 {
		var v any = plain
		if len(plain) == 1 {
			v = plain[0]
		}
		rules = append(rules, obj{field: v, "outbound": tag})
	}
	if len(setTags) > 0 {
		rules = append(rules, obj{"rule_set": setTags, "outbound": tag})
	}
	return rules, nil
}
