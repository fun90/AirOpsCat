package web

import (
	"context"
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
	assets "github.com/fun90/airopscat/web"
)

func TestSettingsHandlers(t *testing.T) {
	h, a := settingsTestHandler(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	if w := do(h, "GET", "/admin/settings", nil, c, nil); w.Code != http.StatusOK || strings.Contains(w.Body.String(), "known-token") || !strings.Contains(w.Body.String(), "已设置") {
		t.Fatalf("敏感配置渲染错误：%d %s", w.Code, w.Body.String())
	}
	if w := do(h, "POST", "/admin/settings", url.Values{"group": {"account"}, "airopscat.ratelimit.enabled": {"wrong"}}, c, nil); w.Code != http.StatusForbidden {
		t.Fatalf("缺 CSRF：%d", w.Code)
	}
	if w := do(h, "POST", "/admin/settings", url.Values{"csrf": {csrf}, "group": {"account"}, "airopscat.ratelimit.enabled": {"wrong"}}, c, nil); !strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatalf("非法输入应返回提示：%s", w.Header().Get("Location"))
	}
	// 复选框带隐藏 false：取最后一个值；整组有一项非法则全部不生效
	if w := do(h, "POST", "/admin/settings", url.Values{"csrf": {csrf}, "group": {"account"}, "airopscat.ratelimit.enabled": {"false", "true"}, "airopscat.api.token": {""}, "airopscat.nope": {"x"}}, c, nil); !strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatalf("未知键应拒绝整组：%s", w.Header().Get("Location"))
	}
	var v string
	a.DB.QueryRow(`SELECT config_value FROM system_config WHERE config_key='airopscat.ratelimit.enabled'`).Scan(&v)
	if v != "false" {
		t.Fatalf("整组应回滚：%s", v)
	}
	if w := do(h, "POST", "/admin/settings", url.Values{"csrf": {csrf}, "group": {"account"}, "airopscat.ratelimit.enabled": {"false", "true"}}, c, nil); w.Code != http.StatusSeeOther || strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatalf("保存：%d %s", w.Code, w.Header().Get("Location"))
	}
	a.DB.QueryRow(`SELECT config_value FROM system_config WHERE config_key='airopscat.ratelimit.enabled'`).Scan(&v)
	if v != "true" {
		t.Fatalf("应保存为 true：%s", v)
	}
	w := do(h, "GET", "/admin/logs?category=DEPLOY&q=%E9%9A%90%E8%97%8F", nil, c, nil)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "系统日志") || strings.Contains(w.Body.String(), "secret") || !strings.Contains(w.Body.String(), "&lt;script&gt;") {
		t.Fatalf("日志渲染/转义失败：%d %s", w.Code, w.Body.String())
	}
}

func settingsTestHandler(t *testing.T) (http.Handler, *auth.Service) {
	t.Helper()
	ctx := context.Background()
	db, _ := store.Open(ctx, t.TempDir()+"/settings.db")
	t.Cleanup(func() { db.Close() })
	hash, _ := auth.HashPassword("secret123")
	store.CreateUser(ctx, db, "admin@example.com", hash, "ADMIN", "")
	a := auth.NewService(db, []byte("session-key"), []byte("csrf-key"))
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, _ := New(db, a, field)
	s.pages["settings"] = template.Must(template.Must(s.pages["accounts"].Clone()).ParseFS(assets.FS, "templates/settings.html"))
	s.pages["logs"] = template.Must(template.Must(s.pages["accounts"].Clone()).ParseFS(assets.FS, "templates/logs.html"))
	db.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.api.token','v1:opaque','open'),('airopscat.ratelimit.enabled','false','account')`)
	store.Log(ctx, db, "DEPLOY", "INFO", nil, "node", "token=secret <script>", `{"password":"secret"}`)
	mux := http.NewServeMux()
	mux.HandleFunc("GET /login", s.loginPage)
	mux.HandleFunc("POST /login", s.login)
	admin := http.NewServeMux()
	s.settingsRoutes(admin)
	mux.Handle("/admin/", s.requireRole("ADMIN", admin))
	return mux, a
}
