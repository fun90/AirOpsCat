package web

import (
	"html"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestEditorReturnNavigation(t *testing.T) {
	h, a := newTestServer(t)
	cookie := login(t, h, "admin@example.com")
	for _, section := range []string{"accounts", "dns-providers", "domains", "nodes", "routes", "servers", "transactions", "users"} {
		t.Run(section, func(t *testing.T) {
			list := "/admin/" + section
			entry := list + "/new"
			assertEditorExit(t, do(h, "GET", entry, nil, cookie, nil), http.StatusOK, list)
			assertEditorExit(t, do(h, "GET", entry+"?back="+url.QueryEscape("/admin?msg=旧提示"), nil, cookie, nil), http.StatusOK, "/admin")
		})
	}

	source := "/admin/transactions?page=2&q=%E6%94%B6%E5%85%A5&start_date="
	entry := "/admin/transactions/new?back=" + url.QueryEscape(source)
	list := do(h, "GET", source, nil, cookie, nil)
	if list.Code != http.StatusOK || !strings.Contains(list.Body.String(), `href="`+html.EscapeString(entry)+`"`) {
		t.Fatalf("列表未生成含筛选条件的编辑入口，状态 %d", list.Code)
	}
	assertEditorExit(t, do(h, "GET", entry, nil, cookie, map[string]string{"Referer": "http://example.com/admin/servers"}), http.StatusOK, source)
	form := url.Values{"csrf": {a.CSRFToken(cookie.Value)}, "back": {source}, "transaction_date": {"2026-10-20T12:00:00"}, "type": {"0"}, "amount": {"1.234"}}
	assertEditorExit(t, do(h, "POST", "/admin/transactions", form, cookie, nil), http.StatusUnprocessableEntity, source)
	form.Set("amount", "12.34")
	w := do(h, "POST", "/admin/transactions", form, cookie, nil)
	target, err := url.Parse(w.Header().Get("Location"))
	if err != nil || w.Code != http.StatusSeeOther || target.Query().Get("msg") == "" {
		t.Fatalf("保存重定向 %d %q", w.Code, w.Header().Get("Location"))
	}
	if got := adminPageURL(target.String()).String(); got != source {
		t.Fatalf("保存回到 %q，期望 %q", got, source)
	}
}

func assertEditorExit(t *testing.T, w *httptest.ResponseRecorder, status int, target string) {
	t.Helper()
	body := w.Body.String()
	if w.Code != status || strings.Count(body, `href="`+html.EscapeString(target)+`" class="btn btn-`) < 2 ||
		!strings.Contains(body, `name="back" value="`+html.EscapeString(target)+`"`) {
		t.Fatalf("状态 %d，返回、取消和表单目标应为 %q", w.Code, target)
	}
}

func TestNestedReturnNavigation(t *testing.T) {
	domains := "/admin/domains?page=2&q=example"
	records := navURL("/admin/domains/3/records?q=hk", domains)
	edit := navURL("/admin/domains/3/records/7/edit", records)
	if got := editReturnURL(httptest.NewRequest("GET", edit, nil), "/admin/domains/3/records"); got != records {
		t.Fatalf("记录编辑返回 %q，期望 %q", got, records)
	}
	if got := editReturnURL(httptest.NewRequest("GET", records, nil), "/admin/domains"); got != domains {
		t.Fatalf("记录列表返回 %q，期望 %q", got, domains)
	}
}

func TestInvalidEditReturnUsesFallback(t *testing.T) {
	for _, raw := range []string{"//example.org/admin", "https://example.org/admin", "/other", "/admin/../login", "/admin/%5Cother", "/admin/nodes/1/edit?page=2"} {
		r := httptest.NewRequest("GET", "/admin/nodes/1/edit?back="+url.QueryEscape(raw), nil)
		if got := editReturnURL(r, "/admin/nodes"); got != "/admin/nodes" {
			t.Errorf("返回参数 %q 的回退地址为 %q", raw, got)
		}
	}
}

func TestActionRedirectKeepsItsDestination(t *testing.T) {
	form := url.Values{"back": {"/admin/nodes?page=2"}}
	r := httptest.NewRequest("POST", "http://example.com/admin/users/2/unlock", strings.NewReader(form.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r.Header.Set("Referer", "http://example.com"+navURL("/admin/users/2/edit", "/admin/users?page=3"))
	w := httptest.NewRecorder()
	redirectMsg(w, r, "/admin/users/2/edit", "msg", "已解锁")
	target := adminPageURL(w.Header().Get("Location"))
	if w.Code != http.StatusSeeOther || target == nil || target.Path != "/admin/users/2/edit" || target.Query().Get("back") != "/admin/users?page=3" {
		t.Fatalf("辅助操作跳转 %d %q", w.Code, w.Header().Get("Location"))
	}
}

func TestTagEditReturnsToFilteredList(t *testing.T) {
	h, a := newTestServer(t)
	cookie := login(t, h, "admin@example.com")
	if _, err := a.DB.Exec(`INSERT INTO tag(id,name) VALUES(1,'example')`); err != nil {
		t.Fatal(err)
	}
	const list = "/admin/tags?page=2&q=example"
	const editor = "/admin/tags?edit=1&page=2&q=example"
	w := do(h, "GET", editor, nil, cookie, nil)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), `href="`+html.EscapeString(list)+`" class="btn btn-link">取消`) {
		t.Fatalf("标签取消未返回筛选列表，状态 %d", w.Code)
	}
	form := url.Values{"csrf": {a.CSRFToken(cookie.Value)}, "back": {editor}, "name": {"example"}}
	for _, action := range []string{"/admin/tags/1", "/admin/tags/1/delete"} {
		w := do(h, "POST", action, form, cookie, map[string]string{"Referer": "http://example.com" + editor})
		target := adminPageURL(w.Header().Get("Location"))
		if w.Code != http.StatusSeeOther || target == nil || target.String() != list {
			t.Errorf("%s 跳转 %d %q", action, w.Code, w.Header().Get("Location"))
		}
	}
}

func TestServerEditQuickActionsAndMaintenanceBack(t *testing.T) {
	h, a := newTestServer(t)
	cookie := login(t, h, "admin@example.com")
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name) VALUES(1,'192.0.2.10','hk-1')`); err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO node(id,name,type) VALUES(1,'n-1',0)`); err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO node_server(node_id,server_id,deployed,failover) VALUES(1,1,1,0)`); err != nil {
		t.Fatal(err)
	}
	const listURL = "/admin/servers?q=hk"
	list := do(h, "GET", listURL, nil, cookie, nil)
	wantInit := navURL("/admin/maintenance?server_id=1", listURL)
	if list.Code != http.StatusOK || !strings.Contains(list.Body.String(), `href="`+html.EscapeString(wantInit)+`"`) {
		t.Fatalf("服务器列表初始化链接应携带返回地址，状态 %d", list.Code)
	}

	// 新增页不显示单机快捷操作栏
	newPage := do(h, "GET", "/admin/servers/new", nil, cookie, nil)
	if newPage.Code != http.StatusOK || strings.Contains(newPage.Body.String(), "/ping") {
		t.Fatalf("新增服务器页不应显示快捷操作，状态 %d", newPage.Code)
	}

	editURL := navURL("/admin/servers/1/edit", listURL)
	edit := do(h, "GET", editURL, nil, cookie, nil)
	if edit.Code != http.StatusOK {
		t.Fatalf("编辑页状态 %d", edit.Code)
	}
	body := edit.Body.String()
	for _, want := range []string{
		`action="/admin/servers/1/ping"`,
		`href="/admin/servers/1/config"`,
		`href="` + html.EscapeString(navURL("/admin/maintenance?server_id=1", editURL)) + `"`,
		`href="` + html.EscapeString(navURL("/admin/monitor/1", editURL)) + `"`,
		`data-bs-target="#calibrate-modal"`,
		`action="/admin/servers/1/failover"`,
		`action="/admin/servers/1/reboot"`,
		`action="/admin/servers/1/clear-host-key"`,
	} {
		if !strings.Contains(body, want) {
			t.Errorf("服务器编辑页缺少常用操作 %s", want)
		}
	}

	csrf := a.CSRFToken(cookie.Value)
	ref := map[string]string{"Referer": "http://example.com" + editURL}
	for _, tc := range []struct {
		path string
		form url.Values
	}{
		{"/admin/servers/1/clear-host-key", url.Values{"csrf": {csrf}, "from": {"edit"}}},
		{"/admin/servers/1/failover", url.Values{"csrf": {csrf}, "from": {"edit"}, "on": {"1"}}},
		{"/admin/servers/1/calibrate-traffic", url.Values{"csrf": {csrf}, "from": {"edit"}, "used": {"-1"}}},
	} {
		w := do(h, "POST", tc.path, tc.form, cookie, ref)
		target := adminPageURL(w.Header().Get("Location"))
		if w.Code != http.StatusSeeOther || target == nil || target.Path != "/admin/servers/1/edit" || target.Query().Get("back") != listURL {
			t.Errorf("%s 带 from=edit 跳转 %d %q，期望停留在编辑页并保留 back", tc.path, w.Code, w.Header().Get("Location"))
		}
	}
}
