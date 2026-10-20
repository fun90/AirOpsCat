package web

import (
	"html/template"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/auth"
	assets "github.com/fun90/airopscat/web"
)

func routeRuleTestHandler(t *testing.T) (http.Handler, *auth.Service) {
	t.Helper()
	_, a := newTestServer(t)
	// newTestServer 的正式 Handler 尚由主线程注册；这个受同一 requireRole 保护的 mux 专测本纵切路由。
	s, err := New(a.DB, a, nil)
	if err != nil {
		t.Fatal(err)
	}
	base, err := template.New("").Funcs(funcs).ParseFS(assets.FS, "templates/layout.html", "templates/pager.html")
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"route_rules", "route_rule_form"} {
		page, err := template.Must(base.Clone()).ParseFS(assets.FS, "templates/"+name+".html")
		if err != nil {
			t.Fatal(err)
		}
		s.pages[name] = page
	}
	m := http.NewServeMux()
	m.HandleFunc("POST /login", s.login)
	admin := http.NewServeMux()
	s.routeRuleRoutes(admin)
	m.Handle("/admin", s.requireRole("ADMIN", admin))
	m.Handle("/admin/", s.requireRole("ADMIN", admin))
	return m, a
}

func TestRouteRuleHTTPFlow(t *testing.T) {
	h, a := routeRuleTestHandler(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	if w := do(h, "GET", "/admin/routes", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "暂无路由规则") {
		t.Fatalf("列表 %d %s", w.Code, w.Body.String())
	}
	if w := do(h, "GET", "/admin/routes/new", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "data-search") {
		t.Fatalf("表单 %d", w.Code)
	}
	a.DB.Exec(`INSERT INTO server(id,name,ip) VALUES(1,'HK','192.0.2.1'),(2,'US','192.0.2.2')`)
	a.DB.Exec(`INSERT INTO node(id,name,type,protocol,core_type) VALUES(1,'landing',1,'socks','sing-box')`)
	a.DB.Exec(`INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,1,1)`)
	post := func(path string, v url.Values) *httptest.ResponseRecorder {
		v.Set("csrf", csrf)
		return do(h, "POST", path, v, c, nil)
	}
	good := url.Values{"name": {"OpenAI"}, "rule_type": {"domain"}, "rule_value": {`["example.com","geosite:openai"]`}, "outbound_node_id": {"1"}, "servers": {"1", "2"}, "enabled": {"1"}, "remark": {"<b>安全</b>"}}
	if w := post("/admin/routes", good); w.Code != 303 || !strings.Contains(w.Header().Get("Location"), "%E6%89%8B%E5%8A%A8") {
		t.Fatalf("创建 %d %s", w.Code, w.Header().Get("Location"))
	}
	var deployed int
	a.DB.QueryRow(`SELECT deployed FROM node_server`).Scan(&deployed)
	if deployed != 1 {
		t.Fatal("保存不应置待部署")
	}
	list := do(h, "GET", "/admin/routes?q=OpenAI", nil, c, nil)
	if list.Code != 200 || !strings.Contains(list.Body.String(), "OpenAI") || strings.Contains(list.Body.String(), "<b>安全</b>") {
		t.Fatalf("搜索或转义失败 %s", list.Body.String())
	}
	edit := do(h, "GET", "/admin/routes/1/edit", nil, c, nil)
	if edit.Code != 200 || !strings.Contains(edit.Body.String(), `option value="2" selected`) {
		t.Fatalf("编辑未回显服务器 %d", edit.Code)
	}
	bad := url.Values{"name": {"bad"}, "rule_type": {"custom"}, "rule_value": {`{"outbound":"oops"}`}, "outbound_node_id": {"1"}, "servers": {"2"}}
	if w := post("/admin/routes/1", bad); w.Code != 422 || !strings.Contains(w.Body.String(), "不能包含 outbound") || !strings.Contains(w.Body.String(), `option value="2" selected`) {
		t.Fatalf("非法回显 %d %s", w.Code, w.Body.String())
	}
	if w := post("/admin/routes/1/toggle", url.Values{"enabled": {"0"}}); w.Code != 303 {
		t.Fatal(w.Code)
	}
	var enabled int
	a.DB.QueryRow(`SELECT enabled FROM route_rule WHERE id=1`).Scan(&enabled)
	if enabled != 0 {
		t.Fatal("未停用")
	}
	if w := post("/admin/routes/1/delete", url.Values{}); w.Code != 303 {
		t.Fatal(w.Code)
	}
	var n int
	a.DB.QueryRow(`SELECT count(*) FROM route_rule`).Scan(&n)
	if n != 0 {
		t.Fatal("未删除")
	}
	if w := do(h, "POST", "/admin/routes", url.Values{}, c, nil); w.Code != 403 {
		t.Fatalf("无csrf %d", w.Code)
	}
	vip := login(t, h, "vip@example.com")
	if w := do(h, "POST", "/admin/routes", good, vip, nil); w.Code != 403 {
		t.Fatalf("非管理员 %d", w.Code)
	}
}
