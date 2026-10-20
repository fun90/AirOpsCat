package web

import (
	"html/template"
	"net/http"
	"net/url"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/dbbackup"
	assets "github.com/fun90/airopscat/web"
)

func TestBackupHTTPFlow(t *testing.T) {
	_, a := newTestServer(t)
	s, err := New(a.DB, a, nil)
	if err != nil {
		t.Fatal(err)
	}
	s.Backup = &dbbackup.Manager{DB: a.DB, Dir: t.TempDir()}
	base, _ := template.New("").Funcs(funcs).ParseFS(assets.FS, "templates/layout.html", "templates/pager.html")
	page, err := template.Must(base.Clone()).ParseFS(assets.FS, "templates/backups.html")
	if err != nil {
		t.Fatal(err)
	}
	s.pages["backups"] = page
	m := http.NewServeMux()
	m.HandleFunc("POST /login", s.login)
	admin := http.NewServeMux()
	s.backupRoutes(admin)
	m.Handle("/admin/", s.requireRole("ADMIN", admin))
	c := login(t, m, "admin@example.com")
	csrf := a.CSRFToken(c.Value)

	if w := do(m, "POST", "/admin/backups", url.Values{}, c, nil); w.Code != 403 {
		t.Fatalf("无 CSRF 应拒绝: %d", w.Code)
	}
	if w := do(m, "POST", "/admin/backups", url.Values{"csrf": {csrf}}, c, nil); w.Code != 303 {
		t.Fatalf("创建 %d", w.Code)
	}
	l, _ := s.Backup.List()
	if len(l) != 1 {
		t.Fatalf("备份数 %d", len(l))
	}
	if w := do(m, "GET", "/admin/backups", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), l[0].Name) {
		t.Fatalf("列表 %d", w.Code)
	}
	if w := do(m, "GET", "/admin/backups/"+l[0].Name+"/download", nil, c, nil); w.Code != 200 || !strings.HasPrefix(w.Body.String(), "SQLite format 3") {
		t.Fatalf("下载 %d", w.Code)
	}
	if w := do(m, "GET", "/admin/backups/..%2Fa.db/download", nil, c, nil); w.Code != 404 {
		t.Fatalf("穿越 %d", w.Code)
	}
	if w := do(m, "POST", "/admin/backups/"+l[0].Name+"/delete", url.Values{"csrf": {csrf}}, c, nil); w.Code != 303 {
		t.Fatalf("删除 %d", w.Code)
	}
	if l, _ := s.Backup.List(); len(l) != 0 {
		t.Fatal("应已删除")
	}
}
