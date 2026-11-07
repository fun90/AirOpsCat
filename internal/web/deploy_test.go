package web

import (
	"context"
	"net/url"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/store"
)

func TestDeploymentHTTPExternal(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	db := a.DB
	_, err := db.Exec(`INSERT INTO server(id,ip,name,external_server) VALUES(99,'203.0.113.99','external',1);
 INSERT INTO node(id,name,type,protocol,port,inbound) VALUES(99,'test',0,'vless',10001,'{"type":"vless"}');
 INSERT INTO node_server(node_id,server_id,deployed) VALUES(99,99,0)`)
	if err != nil {
		t.Fatal(err)
	}
	w := do(h, "POST", "/admin/nodes/99/deploy", url.Values{"csrf": {csrf}}, c, nil)
	if w.Code != 303 {
		t.Fatal(w.Code, w.Body.String())
	}
	eventually(t, "外部部署记录成功", func() bool {
		var state int
		db.QueryRowContext(context.Background(), `SELECT deployed FROM node_server WHERE node_id=99`).Scan(&state)
		return state == 1
	})
	if edit := do(h, "GET", "/admin/servers/99/edit", nil, c, nil).Body.String(); !strings.Contains(edit, `action="/admin/servers/99/deploy"`) {
		t.Fatal("服务器编辑页应提供整机部署按钮")
	}
	w = do(h, "POST", "/admin/servers/99/deploy", url.Values{"csrf": {csrf}}, c, nil)
	if w.Code != 303 || !strings.Contains(w.Header().Get("Location"), "/admin/servers/99/edit") {
		t.Fatal("整机部署应回到编辑页", w.Code, w.Header().Get("Location"))
	}
	w = do(h, "GET", "/admin/servers/99/config", nil, c, nil)
	if w.Code != 200 || !strings.Contains(w.Body.String(), "node_99") {
		t.Fatal(w.Code, w.Body.String())
	}
	w = do(h, "POST", "/admin/nodes/99/deploy", nil, c, nil)
	if w.Code == 303 {
		t.Fatal("部署必须校验CSRF")
	}
}

func TestServerRebootHTTP(t *testing.T) {
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("test-master-key", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name) VALUES(1,'192.0.2.1','本机');
 INSERT INTO server(id,ip,name,external_server) VALUES(2,'192.0.2.2','外部',1)`); err != nil {
		t.Fatal(err)
	}
	remote := &fakeRemote{}
	s.Deployer.Dial = func(context.Context, *store.SSHTarget) (deploy.Remote, error) { return remote, nil }
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	csrf := url.Values{"csrf": {a.CSRFToken(c.Value)}}

	if list := do(h, "GET", "/admin/servers", nil, c, nil).Body.String(); !strings.Contains(list, `action="/admin/servers/1/reboot"`) || strings.Contains(list, `action="/admin/servers/2/reboot"`) {
		t.Fatal("服务器列表应只为非外部服务器提供重启按钮")
	}
	if w := do(h, "POST", "/admin/servers/1/reboot", url.Values{}, c, nil); w.Code != 403 || len(remote.cmds) != 0 {
		t.Fatal("重启必须校验 CSRF", w.Code)
	}
	w := do(h, "POST", "/admin/servers/1/reboot", csrf, c, nil)
	if w.Code != 303 || !strings.Contains(w.Header().Get("Location"), "msg=") || len(remote.cmds) != 1 || remote.cmds[0] != deploy.RebootCommand {
		t.Fatal(w.Code, w.Header().Get("Location"), remote.cmds)
	}
	w = do(h, "POST", "/admin/servers/1/reboot", csrf, c, map[string]string{"Accept": "application/json"})
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"success":true`) {
		t.Fatal(w.Code, w.Body.String())
	}
	w = do(h, "POST", "/admin/servers/2/reboot", csrf, c, map[string]string{"Accept": "application/json"})
	if w.Code != 422 || len(remote.cmds) != 2 {
		t.Fatal("外部服务器应拒绝重启", w.Code, remote.cmds)
	}
	if w := do(h, "POST", "/admin/servers/404/reboot", csrf, c, nil); w.Code != 404 {
		t.Fatal(w.Code)
	}
}
