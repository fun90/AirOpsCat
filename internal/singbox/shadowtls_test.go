package singbox

import (
	"reflect"
	"testing"
)

func TestShadowTLSManagedPair(t *testing.T) {
	raw := `{"type":"shadowtls","version":3,"users":[{"name":"default","password":"legacy"}],"password":"legacy","handshake":{"server":"www.apple.com","server_port":443},"strict_mode":true}`
	n := NodeInput{ID: 42, Protocol: "shadowtls", Port: 10443, Inbound: raw, Clients: []Client{{UUID: "secret", Name: "A1", DownloadMbps: 12, UploadMbps: 8, MaxIPs: 2}}, Out: landing()}
	cfg := build(t, ServerConfigInput{Nodes: []NodeInput{n}})
	ib := list(cfg["inbounds"])
	if len(ib) != 2 {
		t.Fatal(ib)
	}
	outer, inner := ib[0].(map[string]any), ib[1].(map[string]any)
	if outer["tag"] != "node_42_shadowtls" || outer["detour"] != "node_42" || inner["tag"] != "node_42" || inner["managed"] != false || inner["listen"] != "127.0.0.1" {
		t.Fatal(ib)
	}
	if outer["password"] != nil || outer["shadowsocks"] != nil {
		t.Fatal("遗留认证或控制端字段泄漏")
	}
	if !reflect.DeepEqual(list(outer["users"]), []any{map[string]any{"name": "shadowtls_42_0", "password": "legacy"}}) {
		t.Fatal(outer)
	}
	user := list(inner["users"])[0].(map[string]any)
	if user["name"] != "A1" || user["download_mbps"] != float64(12) || user["max_ips"] != float64(2) {
		t.Fatal(user)
	}
	rules := list(cfg["route"].(map[string]any)["rules"])
	r := rules[4].(map[string]any)
	if !reflect.DeepEqual(r["inbound"], []any{"node_42"}) {
		t.Fatal(r)
	}
	stats := cfg["experimental"].(map[string]any)["v2ray_api"].(map[string]any)["stats"].(map[string]any)
	if !reflect.DeepEqual(stats["users"], []any{"A1"}) {
		t.Fatal("统计应只含账户名", stats)
	}
	n.Clients = nil
	cfg = build(t, ServerConfigInput{Nodes: []NodeInput{n}})
	if inner := list(cfg["inbounds"])[1].(map[string]any); len(list(inner["users"])) != 0 || inner["managed"] != true {
		t.Fatal("零账户仍保留内层用户")
	}

}

func TestShadowTLSValidation(t *testing.T) {
	for _, raw := range []string{`{"type":"shadowtls","version":"3","users":[{"password":"p"}],"handshake":{"server":"example.com"}}`, `{"type":"shadowtls","version":2}`, `{"type":"shadowtls","version":3,"handshake":{"server":"example.com"},"detour":"other"}`, `{"type":"shadowtls","version":3,"handshake":{"server":"example.com"},"shadowsocks":{"method":"2022-blake3-aes-128-gcm"}}`} {
		if ValidateShadowTLS(raw) == nil {
			t.Fatal("应拒绝", raw)
		}
	}
	raw, err := DefaultInbound("shadowtls")
	if err != nil {
		t.Fatal(err)
	}
	if err := ValidateShadowTLS(string(raw)); err != nil {
		t.Fatal(err)
	}
}
