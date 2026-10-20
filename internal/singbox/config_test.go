package singbox

import (
	"encoding/json"
	"reflect"
	"strings"
	"testing"
)

func build(t *testing.T, in ServerConfigInput) map[string]any {
	t.Helper()
	b, err := BuildServerConfig(in)
	if err != nil {
		t.Fatal(err)
	}
	var cfg map[string]any
	if err := json.Unmarshal(b, &cfg); err != nil {
		t.Fatal(err)
	}
	return cfg
}

func list(v any) []any { l, _ := v.([]any); return l }

const (
	realityInbound = `{"type":"vless","tls":{"enabled":true,"reality":{"enabled":true,"private_key":"PRIV","public_key":"PUB","short_id":["ab"]}}}`
	hy2Inbound     = `{"type":"hysteria2","up_mbps":24,"down_mbps":24}`
	ssInbound      = `{"type":"shadowsocks","method":"aes-256-gcm","users":[{"name":"x","password":"SSPW"}]}`
)

func landing() *Outbound {
	return &Outbound{NodeID: 7, Protocol: "shadowsocks", Inbound: ssInbound, ServerIP: "9.9.9.9", Port: 20001}
}

// spec 2.7 的示例场景
func TestBuildServerConfigSpecExample(t *testing.T) {
	client := Client{UUID: "UUID-1", Name: "A10001", DownloadMbps: 50, MaxIPs: 3}
	cfg := build(t, ServerConfigInput{
		ClashSecret: "S3CRET",
		Nodes: []NodeInput{
			{ID: 12, Protocol: "vless-reality", Port: 10001, Inbound: realityInbound, Out: landing(), Clients: []Client{client}},
			{ID: 13, Protocol: "hysteria2", Port: 10002, Inbound: hy2Inbound, Clients: []Client{client}},
		},
		Rules: []RouteRule{{Type: "domain", Value: `["geosite:openai","example.com"]`, Out: landing()}},
	})

	exp := cfg["experimental"].(map[string]any)
	if exp["clash_api"].(map[string]any)["secret"] != "S3CRET" {
		t.Fatal(exp)
	}
	if users := exp["v2ray_api"].(map[string]any)["stats"].(map[string]any)["users"]; !reflect.DeepEqual(users, []any{"A10001"}) {
		t.Fatal(users)
	}

	ibs := list(cfg["inbounds"])
	if len(ibs) != 2 {
		t.Fatal(ibs)
	}
	vless := ibs[0].(map[string]any)
	if vless["tag"] != "node_12" || vless["listen"] != "::" || vless["listen_port"] != float64(10001) {
		t.Fatal(vless)
	}
	reality := vless["tls"].(map[string]any)["reality"].(map[string]any)
	if _, ok := reality["public_key"]; ok || reality["private_key"] != "PRIV" {
		t.Fatalf("应删除 public_key 保留 private_key: %v", reality)
	}
	want := map[string]any{"uuid": "UUID-1", "name": "A10001", "flow": "xtls-rprx-vision", "download_mbps": float64(50), "max_ips": float64(3)}
	if got := list(vless["users"]); len(got) != 1 || !reflect.DeepEqual(got[0], want) {
		t.Fatalf("vless users: %v", got)
	}
	hy := ibs[1].(map[string]any)
	wantHy := map[string]any{"name": "A10001", "password": "UUID-1", "download_mbps": float64(50), "max_ips": float64(3)}
	if got := list(hy["users"]); len(got) != 1 || !reflect.DeepEqual(got[0], wantHy) {
		t.Fatalf("hysteria2 users: %v", got)
	}

	outs := list(cfg["outbounds"])
	if len(outs) != 3 {
		t.Fatalf("落地出站应只出现一次: %v", outs)
	}
	wantOut := map[string]any{"type": "shadowsocks", "server": "9.9.9.9", "server_port": float64(20001), "method": "aes-256-gcm", "password": "SSPW", "tag": "node_7"}
	if !reflect.DeepEqual(outs[2], wantOut) {
		t.Fatal(outs[2])
	}

	route := cfg["route"].(map[string]any)
	wantRules := []any{
		map[string]any{"action": "sniff"},
		map[string]any{"port": float64(53), "action": "hijack-dns"},
		map[string]any{"action": "resolve", "strategy": "prefer_ipv4"},
		map[string]any{"ip_is_private": true, "action": "reject"},
		map[string]any{"inbound": []any{"node_12"}, "outbound": "node_7"},
		map[string]any{"domain": "example.com", "outbound": "node_7"},
		map[string]any{"rule_set": []any{"geosite-openai"}, "outbound": "node_7"},
	}
	if !reflect.DeepEqual(route["rules"], wantRules) {
		t.Fatalf("route rules: %v", route["rules"])
	}
	var tags []string
	for _, rs := range list(route["rule_set"]) {
		tags = append(tags, rs.(map[string]any)["tag"].(string))
	}
	if !reflect.DeepEqual(tags, []string{"geoip-cn", "geosite-cn", "geosite-openai"}) {
		t.Fatal(tags)
	}
	last := list(route["rule_set"])[2].(map[string]any)
	if last["url"] != geositeURL+"geosite-openai.srs" || last["http_client"] != "default-http-client" {
		t.Fatal(last)
	}
}

func TestDisabledAndEmptyInboundSkipped(t *testing.T) {
	cfg := build(t, ServerConfigInput{Nodes: []NodeInput{
		{ID: 1, Protocol: "vless", Port: 1, Inbound: `{"type":"vless"}`, Disabled: true, Clients: []Client{{UUID: "u", Name: "A1"}}},
		{ID: 2, Protocol: "vless", Port: 2, Inbound: ``, Clients: []Client{{UUID: "u", Name: "A2"}}},
		{ID: 3, Protocol: "vless", Port: 3, Inbound: `{"type":"vless"}`},
	}})
	ibs := list(cfg["inbounds"])
	if len(ibs) != 1 || ibs[0].(map[string]any)["tag"] != "node_3" {
		t.Fatal(ibs)
	}
	stats := cfg["experimental"].(map[string]any)["v2ray_api"].(map[string]any)["stats"].(map[string]any)
	if _, ok := stats["users"]; ok {
		t.Fatalf("无统计用户时应省略 users: %v", stats)
	}
}

func TestMergeUsersReplace(t *testing.T) {
	cfg := build(t, ServerConfigInput{Nodes: []NodeInput{
		{ID: 1, Protocol: "vless", Port: 1,
			Inbound: `{"type":"vless","users":[{"uuid":"U1","name":"old","flow":"x"},{"uuid":"U9","name":"manual"}]}`,
			Clients: []Client{{UUID: "U1", Name: "A1"}, {UUID: "U2", Name: "A2", UploadMbps: 5}}},
		{ID: 2, Protocol: "hysteria2", Port: 2,
			Inbound: `{"type":"hysteria2","users":[{"name":"A1","password":"old","max_ips":9},{"name":"keep","password":"k"}]}`,
			Clients: []Client{{UUID: "U1", Name: "A1"}}},
	}})
	ibs := list(cfg["inbounds"])
	wantV := []any{
		map[string]any{"uuid": "U1", "name": "A1", "flow": "xtls-rprx-vision"}, // 整体替换，旧 flow/name 不保留
		map[string]any{"uuid": "U9", "name": "manual"},
		map[string]any{"uuid": "U2", "name": "A2", "flow": "xtls-rprx-vision", "upload_mbps": float64(5)},
	}
	if got := list(ibs[0].(map[string]any)["users"]); !reflect.DeepEqual(got, wantV) {
		t.Fatalf("vless: %v", got)
	}
	wantH := []any{
		map[string]any{"name": "A1", "password": "U1"},
		map[string]any{"name": "keep", "password": "k"},
	}
	if got := list(ibs[1].(map[string]any)["users"]); !reflect.DeepEqual(got, wantH) {
		t.Fatalf("hysteria2: %v", got)
	}
}

// ss/socks 保留静态用户；ShadowTLS 组合单独测试。
func TestStatsUsersForUnmanagedProtocols(t *testing.T) {
	clients := []Client{{UUID: "U1", Name: "A1"}}
	cfg := build(t, ServerConfigInput{Nodes: []NodeInput{
		{ID: 1, Protocol: "shadowsocks", Port: 1, Inbound: ssInbound, Clients: clients},
		{ID: 2, Protocol: "socks", Port: 2, Inbound: `{"type":"socks","users":[{"username":"sk","password":"p"}]}`, Clients: clients},
	}})
	stats := cfg["experimental"].(map[string]any)["v2ray_api"].(map[string]any)["stats"].(map[string]any)
	if !reflect.DeepEqual(stats["users"], []any{"x", "sk"}) {
		t.Fatal(stats["users"])
	}
	for _, ib := range list(cfg["inbounds"]) {
		if n := len(list(ib.(map[string]any)["users"])); n != 1 {
			t.Fatalf("不应注入托管用户: %v", ib)
		}
	}
}

func TestRuleSetDedup(t *testing.T) {
	cfg := build(t, ServerConfigInput{Rules: []RouteRule{
		{Type: "domain", Value: `["geosite:cn","geosite:openai"]`, Out: landing()},
		{Type: "domain", Value: `"geosite:OpenAI"`, Out: landing()},
		{Type: "ip", Value: `["geoip:cn","geoip:us","1.1.1.1/32"]`, Out: landing()},
	}})
	var tags []string
	for _, rs := range list(cfg["route"].(map[string]any)["rule_set"]) {
		tags = append(tags, rs.(map[string]any)["tag"].(string))
	}
	if !reflect.DeepEqual(tags, []string{"geoip-cn", "geosite-cn", "geosite-openai", "geoip-us"}) {
		t.Fatalf("geoip-cn/geosite-cn 不应重复: %v", tags)
	}
	rules := list(cfg["route"].(map[string]any)["rules"])[4:]
	if len(rules) != 4 {
		t.Fatalf("规则数: %v", rules)
	}
	if !reflect.DeepEqual(rules[2], map[string]any{"ip_cidr": "1.1.1.1/32", "outbound": "node_7"}) {
		t.Fatal(rules[2])
	}
	if len(list(cfg["outbounds"])) != 3 {
		t.Fatalf("出站应去重: %v", cfg["outbounds"])
	}
}

func TestOtherRuleTypes(t *testing.T) {
	cfg := build(t, ServerConfigInput{Rules: []RouteRule{
		{Type: "port", Value: `[80,443]`, Out: landing()},
		{Type: "network", Value: `"udp"`, Out: landing()},
		{Type: "custom", Value: `{"protocol":"bittorrent"}`, Out: landing()},
		{Type: "domain", Value: `"x.com"`, Out: nil}, // 出站缺失，静默跳过
	}})
	rules := list(cfg["route"].(map[string]any)["rules"])[4:]
	want := []any{
		map[string]any{"port": []any{float64(80), float64(443)}, "outbound": "node_7"},
		map[string]any{"network": "udp", "outbound": "node_7"},
		map[string]any{"protocol": "bittorrent", "outbound": "node_7"},
	}
	if !reflect.DeepEqual(rules, want) {
		t.Fatal(rules)
	}
}

func TestLandingWithoutServerFails(t *testing.T) {
	out := landing()
	out.ServerIP = ""
	_, err := BuildServerConfig(ServerConfigInput{Nodes: []NodeInput{
		{ID: 1, Protocol: "vless", Port: 1, Inbound: `{"type":"vless"}`, Out: out},
	}})
	if err == nil || !strings.Contains(err.Error(), "没有可用的部署服务器") {
		t.Fatal(err)
	}
}

func TestUnsupportedOutboundProtocolFails(t *testing.T) {
	out := &Outbound{NodeID: 8, Protocol: "vless", Inbound: `{"type":"vless"}`, ServerIP: "1.2.3.4", Port: 1}
	_, err := BuildServerConfig(ServerConfigInput{Nodes: []NodeInput{
		{ID: 1, Protocol: "vless", Port: 1, Inbound: `{"type":"vless"}`, Out: out},
	}})
	if err == nil || !strings.Contains(err.Error(), "不支持作为出站") {
		t.Fatal(err)
	}
}

func TestInvalidInboundFails(t *testing.T) {
	_, err := BuildServerConfig(ServerConfigInput{Nodes: []NodeInput{{ID: 1, Protocol: "vless", Port: 1, Inbound: `[1]`}}})
	if err == nil {
		t.Fatal("非对象 inbound 应报错")
	}
}
