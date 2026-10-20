package web

import (
	"context"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/maintenance"
	"github.com/fun90/airopscat/internal/store"
)

type maintenanceRemote struct {
	calls []string
	fail  bool
}

func (f *maintenanceRemote) Run(_ context.Context, c string) (string, string, int, error) {
	f.calls = append(f.calls, c)
	if strings.Contains(c, "mktemp") {
		return "/tmp/airopscat-maintenance.Test123", "", 0, nil
	}
	if f.fail {
		return "", "", 12, nil
	}
	return "", "", 0, nil
}
func (f *maintenanceRemote) WriteFile(context.Context, string, []byte) error { return nil }
func (f *maintenanceRemote) Close() error                                    { return nil }
func TestMaintenanceFlow(t *testing.T) {
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	s.ScriptDir = t.TempDir()
	os.WriteFile(filepath.Join(s.ScriptDir, "01-test.sh"), []byte("# @title: 测试\necho ok\n"), 0600)
	script, _ := maintenance.Read(s.ScriptDir, "01-test.sh")
	token, _ := field.Encrypt("secret-token")
	if _, err := a.DB.Exec(`INSERT INTO system_config(config_key,config_value,group_key)VALUES('airopscat.api.token',?,'open')`, token); err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name)VALUES(1,'192.0.2.1','测试')`); err != nil {
		t.Fatal(err)
	}
	remote := &maintenanceRemote{}
	dials := 0
	s.MaintenanceDeployer.Dial = func(context.Context, *store.SSHTarget) (deploy.Remote, error) { dials++; return remote, nil }
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	v := url.Values{"csrf": {csrf}, "server_id": {"1"}, "script": {script.Name}, "hash": {script.Hash}, "confirm": {"run"}}
	if w := do(h, "GET", "/admin/maintenance", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "预览脚本") {
		t.Fatal(w.Code)
	}
	noCSRF := url.Values{"server_id": {"1"}}
	if w := do(h, "POST", "/admin/maintenance/run", noCSRF, c, nil); w.Code != 403 {
		t.Fatal(w.Code)
	}
	v.Set("confirm", "")
	if w := do(h, "POST", "/admin/maintenance/run", v, c, nil); w.Code != 422 {
		t.Fatal(w.Code)
	}
	v.Set("confirm", "run")
	v.Set("hash", "wrong")
	if w := do(h, "POST", "/admin/maintenance/run", v, c, nil); w.Code != 409 {
		t.Fatal(w.Code)
	}
	v.Set("hash", script.Hash)
	a.DB.Exec(`UPDATE server SET external_server=1 WHERE id=1`)
	if w := do(h, "POST", "/admin/maintenance/run", v, c, nil); w.Code != 422 {
		t.Fatal(w.Code)
	}
	if dials != 0 {
		t.Fatal("校验失败不得连接")
	}
	a.DB.Exec(`UPDATE server SET external_server=0 WHERE id=1`)
	if w := do(h, "POST", "/admin/maintenance/run", v, c, nil); w.Code != http.StatusSeeOther {
		t.Fatal(w.Code, w.Body.String())
	}
	if dials != 1 {
		t.Fatal(dials)
	}
	var detail string
	a.DB.QueryRow(`SELECT detail FROM system_log WHERE category='MAINTENANCE'`).Scan(&detail)
	if !strings.Contains(detail, script.Hash) || strings.Contains(detail, "secret-token") {
		t.Fatal(detail)
	}
	headers := map[string]string{"Accept": "application/json"}
	if w := do(h, "POST", "/admin/maintenance/run", v, c, headers); w.Code != 200 || !strings.Contains(w.Body.String(), `"success":true`) {
		t.Fatal(w.Code, w.Body.String())
	}
	remote.fail = true
	if w := do(h, "POST", "/admin/maintenance/run", v, c, headers); w.Code != 502 {
		t.Fatal("失败不得返回200或重定向", w.Code)
	}

}
