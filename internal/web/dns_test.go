package web

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
)

func TestDNSHTTPFlow(t *testing.T) {
	var mu sync.Mutex
	var calls []string
	var batch map[string][]map[string]any
	cf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		defer mu.Unlock()
		calls = append(calls, r.Method+" "+r.URL.Path)
		if r.Header.Get("Authorization") != "Bearer tok-abc" {
			w.WriteHeader(403)
			fmt.Fprint(w, `{"success":false,"errors":[{"message":"bad token"}]}`)
			return
		}
		switch {
		case strings.HasSuffix(r.URL.Path, "/tokens/verify"):
			fmt.Fprint(w, `{"success":true,"result":{}}`)
		case strings.HasSuffix(r.URL.Path, "/batch"):
			json.NewDecoder(r.Body).Decode(&batch)
			fmt.Fprint(w, `{"success":true,"result":{"posts":[{"id":"new1"}]}}`)
		default:
			fmt.Fprint(w, `{"success":true,"result":[{"id":"e1","name":"www.example.com","type":"CNAME","content":"example.com","ttl":1,"proxied":true}],"result_info":{"total_pages":1}}`)
		}
	}))
	defer cf.Close()

	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	s.DNSBaseURL = cf.URL
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	post := func(path string, v url.Values) *httptest.ResponseRecorder {
		v.Set("csrf", csrf)
		return do(h, "POST", path, v, c, nil)
	}
	for _, p := range []string{"/admin/dns-providers", "/admin/dns-providers/new", "/admin/domains", "/admin/domains/new"} {
		if w := do(h, "GET", p, nil, c, nil); w.Code != 200 {
			t.Fatalf("%s %d %s", p, w.Code, w.Body.String())
		}
	}
	if w := do(h, "POST", "/admin/dns-providers", url.Values{"name": {"x"}}, c, nil); w.Code != 403 {
		t.Fatalf("无 CSRF 应拒绝: %d", w.Code)
	}
	if w := post("/admin/dns-providers", url.Values{"name": {"CF"}, "account_id": {"acc"}, "api_token": {"tok-abc"}, "enabled": {"1"}}); w.Code != 303 {
		t.Fatalf("保存服务商 %d %s", w.Code, w.Body.String())
	}
	body := do(h, "GET", "/admin/dns-providers", nil, c, nil).Body.String()
	if !strings.Contains(body, "CF") || strings.Contains(body, "tok-abc") {
		t.Fatal("列表应显示名称且不回显 Token")
	}
	if body := do(h, "GET", "/admin/dns-providers/1/edit", nil, c, nil).Body.String(); strings.Contains(body, "tok-abc") {
		t.Fatal("编辑页不应回显 Token")
	}
	if w := post("/admin/dns-providers/1/test", url.Values{}); w.Code != 303 || strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatalf("测试连接 %d %s", w.Code, w.Header().Get("Location"))
	}
	if w := post("/admin/domains", url.Values{"domain": {"example.com"}, "provider_id": {"1"}}); w.Code != 422 {
		t.Fatalf("绑定服务商缺 Zone 应 422: %d", w.Code)
	}
	if w := post("/admin/domains", url.Values{"domain": {"example.com"}, "provider_id": {"1"}, "zone_id": {"z1"}}); w.Code != 303 {
		t.Fatalf("保存域名 %d %s", w.Code, w.Body.String())
	}
	if _, err := a.DB.Exec(`INSERT INTO server(id,name,ip) VALUES(99,'dns-test','192.0.2.99')`); err != nil {
		t.Fatal(err)
	}
	if w := post("/admin/dns-providers/1/delete", url.Values{}); !strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatal("被引用的服务商不能删除")
	}
	if w := post("/admin/domains/1/records", url.Values{"name": {"hk"}, "content": {"192.0.2.99"}, "ttl": {"300"}, "server_id": {"99"}}); w.Code != 303 {
		t.Fatalf("新增记录 %d %s", w.Code, w.Body.String())
	}
	if _, err := a.DB.Exec(`INSERT INTO node(id,name,type) VALUES(99,'香港入口',0); INSERT INTO node_server(id,node_id,server_id,deployed,access_host_id) VALUES(99,99,99,1,1);
	INSERT INTO account(id,period_type,uuid,account_no,remark,auth_code) VALUES(99,'MONTHLY','dns-test-uuid','订阅-99','测试账户备注','dns-test-code');
	INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,domain_dns_record_id) VALUES(99,99,99,1);
	INSERT INTO account_node_subscription_placement(binding_id,node_server_id) VALUES(99,99)`); err != nil {
		t.Fatal(err)
	}
	if w := post("/admin/domains/1/records", url.Values{"name": {"bad"}, "content": {"not-ip"}, "ttl": {"300"}}); w.Code != 422 {
		t.Fatalf("非法 IP 应 422: %d", w.Code)
	}
	list := do(h, "GET", "/admin/domains/1/records", nil, c, nil).Body.String()
	if !strings.Contains(list, "hk.example.com") || !strings.Contains(list, "待创建") || !strings.Contains(list, `name="confirm" value="push"`) || !strings.Contains(list, "部署位") || !strings.Contains(list, "香港入口-1") || !strings.Contains(list, "账户订阅") || strings.Contains(list, "账户订阅定制") || !strings.Contains(list, "测试账户备注") || !strings.Contains(list, `href="/admin/accounts/99/edit"`) || !strings.Contains(list, `hk.example.com<span class="d-md-none text-secondary small"> · A · 192.0.2.99`) {
		t.Fatalf("记录页缺少账户订阅或移动端首行内容: %s", list)
	}
	if _, err := a.DB.Exec(`UPDATE domain_dns_record SET status='SYNCED' WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	mobileList := do(h, "GET", "/admin/domains/1/records", nil, c, nil).Body.String()
	if !strings.Contains(mobileList, `class="d-md-none status-dot status-green me-1"`) || !strings.Contains(mobileList, `aria-label="已同步"`) {
		t.Fatal("移动端已同步记录应显示域名前的状态圆点")
	}
	if _, err := a.DB.Exec(`UPDATE domain_dns_record SET status='PENDING_CREATE' WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	for _, path := range []string{"/admin/domains/1/records/new", "/admin/domains/1/records/1/edit"} {
		w := do(h, "GET", path, nil, c, nil)
		if w.Code != 200 {
			t.Fatal(path, w.Code)
		}
		if strings.Contains(w.Body.String(), "schedule_") || strings.Contains(w.Body.String(), "时段") {
			t.Fatal("DNS 表单不应再显示时段配置", path)
		}
		if !strings.Contains(w.Body.String(), `data-dns-server-ip`) || !strings.Contains(w.Body.String(), `value="99" data-ip="192.0.2.99"`) {
			t.Fatal("DNS 表单应提供服务器 IP 以便切换时自动填写", path)
		}
		if path == "/admin/domains/1/records/1/edit" && !strings.Contains(w.Body.String(), "关联部署位：香港入口-1") {
			t.Fatal("DNS 编辑页应显示对应订阅名称", path)
		}
		if path == "/admin/domains/1/records/1/edit" && (!strings.Contains(w.Body.String(), "关联账户订阅：") || strings.Contains(w.Body.String(), "关联账户订阅定制") || !strings.Contains(w.Body.String(), "测试账户备注") || !strings.Contains(w.Body.String(), `href="/admin/accounts/99/edit"`)) {
			t.Fatal("DNS 编辑页应显示可点击的关联账户订阅", path)
		}
	}

	mu.Lock()
	calls = nil
	mu.Unlock()
	if w := post("/admin/domains/1/push", url.Values{}); !strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatal("缺少 confirm 应拒绝")
	}
	mu.Lock()
	if len(calls) != 0 {
		t.Fatalf("未确认不得访问 Cloudflare: %v", calls)
	}
	mu.Unlock()
	if w := post("/admin/domains/1/push", url.Values{"confirm": {"push"}}); w.Code != 303 || strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatalf("推送 %d %s", w.Code, w.Header().Get("Location"))
	}
	if len(batch["posts"]) != 1 || batch["posts"][0]["name"] != "hk.example.com" {
		t.Fatalf("批量请求 %v", batch)
	}
	var ext string
	a.DB.QueryRow(`SELECT external_record_id FROM domain_dns_record WHERE id=1`).Scan(&ext)
	if ext != "new1" {
		t.Fatalf("外部 ID %q", ext)
	}
	if w := post("/admin/domains/1/pull", url.Values{}); w.Code != 303 || strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatalf("拉取 %d %s", w.Code, w.Header().Get("Location"))
	}
	if body := do(h, "GET", "/admin/domains/1/records", nil, c, nil).Body.String(); !strings.Contains(body, "www.example.com") {
		t.Fatal("拉取的记录应出现")
	}
	// 错误的 Token：失败信息安全，本地待推送保留
	post("/admin/dns-providers/1", url.Values{"name": {"CF"}, "account_id": {"acc"}, "api_token": {"wrong"}, "enabled": {"1"}})
	post("/admin/domains/1/records", url.Values{"name": {"us"}, "content": {"192.0.2.2"}, "ttl": {"300"}})
	w := post("/admin/domains/1/push", url.Values{"confirm": {"push"}})
	if loc := w.Header().Get("Location"); !strings.Contains(loc, "err=") || strings.Contains(loc, "wrong") {
		t.Fatalf("失败应提示且不含 Token: %s", loc)
	}
	var st string
	a.DB.QueryRow(`SELECT status FROM domain_dns_record WHERE full_name='us.example.com'`).Scan(&st)
	if st != "PENDING_CREATE" {
		t.Fatalf("失败后应保留待创建: %s", st)
	}
	if w := post("/admin/domains/1/records/999/delete", url.Values{}); w.Code != 404 {
		t.Fatalf("不存在的记录 %d", w.Code)
	}
}
