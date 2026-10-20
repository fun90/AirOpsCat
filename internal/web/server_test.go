package web

import (
	"context"
	"html"
	"net/http"
	"net/http/httptest"
	"net/url"
	"path/filepath"
	"regexp"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

func newTestServer(t *testing.T) (http.Handler, *auth.Service) {
	t.Helper()
	ctx := context.Background()
	db, err := store.Open(ctx, filepath.Join(t.TempDir(), "w.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	hash, _ := auth.HashPassword("secret123")
	store.CreateUser(ctx, db, "admin@example.com", hash, "ADMIN", "")
	store.CreateUser(ctx, db, "vip@example.com", hash, "VIP", "")
	a := auth.NewService(db, []byte("session-key"), []byte("csrf-key"))
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(db, a, field)
	if err != nil {
		t.Fatal(err)
	}
	return s.Handler(), a
}

func do(h http.Handler, method, path string, form url.Values, cookie *http.Cookie, hdr map[string]string) *httptest.ResponseRecorder {
	var body *strings.Reader
	if form != nil {
		body = strings.NewReader(form.Encode())
	} else {
		body = strings.NewReader("")
	}
	r := httptest.NewRequest(method, path, body)
	if form != nil {
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	}
	if cookie != nil {
		r.AddCookie(cookie)
	}
	for k, v := range hdr {
		r.Header.Set(k, v)
	}
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	return w
}

func login(t *testing.T, h http.Handler, email string) *http.Cookie {
	t.Helper()
	w := do(h, "POST", "/login", url.Values{"email": {email}, "password": {"secret123"}}, nil, nil)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("登录失败: %d %s", w.Code, w.Body.String())
	}
	for _, c := range w.Result().Cookies() {
		if c.Name == auth.CookieName {
			if !c.HttpOnly || c.SameSite != http.SameSiteLaxMode {
				t.Fatal("Cookie 缺少 HttpOnly/SameSite")
			}
			return c
		}
	}
	t.Fatal("未下发会话 Cookie")
	return nil
}

func TestAdminRequiresLogin(t *testing.T) {
	h, _ := newTestServer(t)
	if w := do(h, "GET", "/admin", nil, nil, nil); w.Code != http.StatusFound || w.Header().Get("Location") != "/login" {
		t.Fatalf("未登录应跳转登录页: %d", w.Code)
	}
	if w := do(h, "GET", "/admin", nil, login(t, h, "vip@example.com"), nil); w.Code != http.StatusForbidden {
		t.Fatalf("非管理员应被拒绝: %d", w.Code)
	}
	w := do(h, "GET", "/admin", nil, login(t, h, "admin@example.com"), nil)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "概览") {
		t.Fatalf("管理员应能访问: %d", w.Code)
	}
}

func TestLoginFailureAndCrossSite(t *testing.T) {
	h, _ := newTestServer(t)
	w := do(h, "POST", "/login", url.Values{"email": {"admin@example.com"}, "password": {"bad"}}, nil, nil)
	if w.Code != http.StatusUnauthorized || !strings.Contains(w.Body.String(), "邮箱或密码错误") {
		t.Fatalf("错误密码: %d", w.Code)
	}
	w = do(h, "POST", "/login", url.Values{"email": {"admin@example.com"}, "password": {"secret123"}}, nil, map[string]string{"Origin": "https://evil.example"})
	if w.Code != http.StatusForbidden {
		t.Fatalf("跨站登录提交应被拒绝: %d", w.Code)
	}
}

func TestLogoutRequiresCSRF(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	if w := do(h, "POST", "/logout", url.Values{"csrf": {"wrong"}}, c, nil); w.Code != http.StatusForbidden {
		t.Fatalf("错误 CSRF 应被拒绝: %d", w.Code)
	}
	w := do(h, "POST", "/logout", url.Values{"csrf": {a.CSRFToken(c.Value)}}, c, nil)
	if w.Code != http.StatusSeeOther {
		t.Fatalf("退出失败: %d", w.Code)
	}
	if got := w.Result().Cookies(); len(got) == 0 || got[0].MaxAge >= 0 {
		t.Fatal("退出应删除 Cookie")
	}
}

func TestHealthzAndStatic(t *testing.T) {
	h, _ := newTestServer(t)
	if w := do(h, "GET", "/healthz", nil, nil, nil); w.Code != http.StatusOK {
		t.Fatal(w.Code)
	}
	if w := do(h, "GET", "/static/tabler/css/tabler.min.css", nil, nil, nil); w.Code != http.StatusOK {
		t.Fatal(w.Code)
	}
}

func TestPagerPreservesSeparateQueryParameters(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	for i := 0; i < 21; i++ {
		if _, err := a.DB.Exec(`INSERT INTO node (name,type,protocol,port,region) VALUES ('搜索&节点',0,'vless',443,'HK')`); err != nil {
			t.Fatal(err)
		}
	}
	w := do(h, "GET", "/admin/nodes?q="+url.QueryEscape("搜索&节点")+"&type=0&status=-1", nil, c, nil)
	if w.Code != http.StatusOK {
		t.Fatal(w.Code)
	}
	matches := regexp.MustCompile(`href="([^"]+)"[^>]*>下一页`).FindStringSubmatch(w.Body.String())
	if len(matches) != 2 {
		t.Fatal("未找到下一页链接")
	}
	u, err := url.Parse(html.UnescapeString(matches[1]))
	if err != nil {
		t.Fatal(err)
	}
	q := u.Query()
	if q.Get("q") != "搜索&节点" || q.Get("type") != "0" || q.Get("status") != "-1" || q.Get("page") != "2" {
		t.Fatalf("筛选参数被错误编码: %s", u)
	}
	if next := do(h, "GET", "/admin/nodes"+u.String(), nil, c, nil); next.Code != http.StatusOK {
		t.Fatal(next.Code)
	}
}
