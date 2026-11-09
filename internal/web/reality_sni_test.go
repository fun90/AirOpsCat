package web

import (
	"encoding/json"
	"net/url"
	"strings"
	"testing"
)

func TestRealitySNIManagementAndGeneration(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	if w := do(h, "GET", "/admin/reality-sni", nil, nil, nil); w.Code != 303 && w.Code != 302 {
		t.Fatalf("匿名访问: %d", w.Code)
	}
	if w := do(h, "POST", "/admin/reality-sni", url.Values{"domain": {"new.example.test"}}, c, nil); w.Code != 403 {
		t.Fatalf("CSRF: %d", w.Code)
	}
	if w := do(h, "GET", "/admin/reality-sni", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "www.python.org") {
		t.Fatalf("管理页: %d", w.Code)
	}
	for _, path := range []string{"/admin/reality-sni/0", "/admin/reality-sni/nope", "/admin/reality-sni/-1/toggle"} {
		if w := do(h, "POST", path, url.Values{"csrf": {csrf}, "domain": {"new.example.test"}}, c, nil); w.Code != 404 {
			t.Errorf("非法 ID %s: %d", path, w.Code)
		}
	}
	if w := do(h, "POST", "/admin/reality-sni", url.Values{"csrf": {csrf}, "domain": {"https://example.test"}, "note": {"保留输入"}}, c, nil); w.Code != 422 || !strings.Contains(w.Body.String(), "保留输入") {
		t.Fatalf("错误回显: %d", w.Code)
	}
	if _, err := a.DB.Exec(`UPDATE reality_sni SET enabled=0`); err != nil {
		t.Fatal(err)
	}
	if w := do(h, "GET", "/admin/nodes/new", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "没有启用的 Reality SNI") {
		t.Fatalf("空池新建: %d", w.Code)
	}
	if w := do(h, "GET", "/admin/nodes/default-inbound?protocol=vless-reality", nil, c, nil); w.Code != 400 {
		t.Fatalf("空池生成: %d", w.Code)
	}
	if w := do(h, "GET", "/admin/nodes/default-inbound?protocol=shadowtls", nil, c, nil); w.Code != 200 {
		t.Fatalf("ShadowTLS 不依赖池: %d", w.Code)
	}
	if w := do(h, "POST", "/admin/reality-sni", url.Values{"csrf": {csrf}, "domain": {"TLS.Example.Test."}, "enabled": {"1"}}, c, nil); w.Code != 303 {
		t.Fatalf("新增: %d", w.Code)
	}
	var id string
	if err := a.DB.QueryRow(`SELECT id FROM reality_sni WHERE domain='tls.example.test'`).Scan(&id); err != nil {
		t.Fatal(err)
	}
	if w := do(h, "GET", "/admin/reality-sni?edit="+id, nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), `value="tls.example.test"`) {
		t.Fatal("编辑回显")
	}
	if w := do(h, "POST", "/admin/reality-sni", url.Values{"csrf": {csrf}, "domain": {"TLS.EXAMPLE.TEST"}}, c, nil); w.Code != 422 {
		t.Fatalf("重复候选: %d", w.Code)
	}
	w := do(h, "GET", "/admin/nodes/default-inbound?protocol=vless-reality", nil, c, nil)
	var inbound struct {
		TLS struct {
			ServerName string `json:"server_name"`
			Reality    struct {
				PrivateKey string `json:"private_key"`
				Handshake  struct {
					Server string
					Port   int `json:"server_port"`
				}
			}
		}
	}
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &inbound) != nil {
		t.Fatalf("生成: %d", w.Code)
	}
	if inbound.TLS.ServerName != "tls.example.test" || inbound.TLS.Reality.Handshake.Server != "tls.example.test" || inbound.TLS.Reality.Handshake.Port != 443 || inbound.TLS.Reality.PrivateKey == "" {
		t.Fatalf("候选消费不正确")
	}
	if _, err := a.DB.Exec(`INSERT INTO node(name,type,protocol,inbound) VALUES('existing',0,'vless-reality',?)`, w.Body.String()); err != nil {
		t.Fatal(err)
	}
	for _, action := range []string{"toggle", "delete"} {
		if w := do(h, "POST", "/admin/reality-sni/"+id+"/"+action, url.Values{"csrf": {csrf}, "enabled": {"0"}}, c, nil); w.Code != 303 {
			t.Fatalf("%s: %d", action, w.Code)
		}
	}
	var saved string
	if err := a.DB.QueryRow(`SELECT inbound FROM node WHERE name='existing'`).Scan(&saved); err != nil || saved != w.Body.String() {
		t.Fatal("候选操作不得改写已有节点")
	}
	for _, path := range []string{"/admin/reality-sni/9999/delete", "/admin/reality-sni/9999/toggle"} {
		if w := do(h, "POST", path, url.Values{"csrf": {csrf}}, c, nil); w.Code != 404 {
			t.Errorf("不存在记录 %s: %d", path, w.Code)
		}
	}
}
