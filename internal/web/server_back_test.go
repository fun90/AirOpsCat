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
