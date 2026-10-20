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

func transactionTestHandler(t *testing.T) (http.Handler, *auth.Service) {
	t.Helper()
	base, a := newTestServer(t)
	s, err := New(a.DB, a, nil)
	if err != nil {
		t.Fatal(err)
	}
	m := http.NewServeMux()
	s.transactionRoutes(m)
	baseTemplates, err := template.New("").Funcs(funcs).ParseFS(assets.FS, "templates/layout.html", "templates/pager.html")
	if err != nil {
		t.Fatal(err)
	}
	for _, name := range []string{"transactions", "transaction_form"} {
		page, err := template.Must(baseTemplates.Clone()).ParseFS(assets.FS, "templates/"+name+".html")
		if err != nil {
			t.Fatal(err)
		}
		s.pages[name] = page
	}
	protected := s.requireRole("ADMIN", m)
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/login" {
			base.ServeHTTP(w, r)
			return
		}
		protected.ServeHTTP(w, r)
	}), a
}

func TestTransactionHTTPFlow(t *testing.T) {
	h, a := transactionTestHandler(t)
	cookie := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(cookie.Value)
	if w := do(h, "GET", "/admin/transactions/new", nil, cookie, nil); w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "data-search") {
		t.Fatalf("表单 %d", w.Code)
	}
	post := func(path string, form url.Values) *httptest.ResponseRecorder {
		form.Set("csrf", csrf)
		return do(h, "POST", path, form, cookie, nil)
	}
	good := url.Values{"transaction_date": {"2026-10-20T12:00:00"}, "type": {"0"}, "amount": {"123.45"}, "payment_method": {"WeChat"}, "description": {"<b>收入</b>"}}
	if w := post("/admin/transactions", good); w.Code != http.StatusSeeOther {
		t.Fatalf("创建 %d %s", w.Code, w.Body.String())
	}
	var cents int64
	a.DB.QueryRow(`SELECT amount FROM transactions WHERE id=1`).Scan(&cents)
	if cents != 12345 {
		t.Fatalf("金额应存分: %d", cents)
	}
	list := do(h, "GET", "/admin/transactions?q=收入&type=0&start_date=2026-10-20&end_date=2026-10-20", nil, cookie, nil)
	if list.Code != http.StatusOK || !strings.Contains(list.Body.String(), "&lt;b&gt;收入&lt;/b&gt;") {
		t.Fatalf("列表/转义 %d %s", list.Code, list.Body.String())
	}
	bad := url.Values{"transaction_date": {"2026-10-20T12:00:00"}, "type": {"0"}, "amount": {"1.234"}}
	if w := post("/admin/transactions/1", bad); w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), "最多 2 位小数") {
		t.Fatalf("非法金额 %d %s", w.Code, w.Body.String())
	}
	if w := post("/admin/transactions/1/delete", url.Values{}); w.Code != http.StatusSeeOther {
		t.Fatal(w.Code)
	}
	withoutCSRF := url.Values{}
	for key, values := range good {
		withoutCSRF[key] = append([]string(nil), values...)
	}
	withoutCSRF.Del("csrf")
	if w := do(h, "POST", "/admin/transactions", withoutCSRF, cookie, nil); w.Code != http.StatusForbidden {
		t.Fatalf("缺 CSRF: %d", w.Code)
	}
}
