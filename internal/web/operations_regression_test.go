package web

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestServerMigrationCancelAndUnblockHTTP(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := a.DB.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`INSERT INTO server(id,name,ip) VALUES(1,'src','192.0.2.1'),(2,'dst','192.0.2.2')`)
	exec(`INSERT INTO node(id,name,type,protocol,port,inbound) VALUES(1,'node',0,'vless',8443,'{}')`)
	exec(`INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,1,1)`)
	exec(`INSERT INTO server_blocked_port(server_id,port,create_time) VALUES(1,8000,'2026-01-01 00:00:00')`)
	page := do(h, "GET", "/admin/servers/1/edit", nil, c, nil).Body.String()
	if strings.Contains(page, "migrate-cancel") || !strings.Contains(page, "封禁端口") {
		t.Fatal("未迁移时无取消按钮，封禁端口应展示")
	}
	post := func(path string, v url.Values) *http.Response {
		v.Set("csrf", csrf)
		return do(h, "POST", path, v, c, nil).Result()
	}
	if loc := post("/admin/servers/1/migrate", url.Values{"target": {"2"}}).Header.Get("Location"); !strings.Contains(loc, "msg=") {
		t.Fatal(loc)
	}
	page = do(h, "GET", "/admin/servers/1/edit", nil, c, nil).Body.String()
	if !strings.Contains(page, `action="/admin/servers/1/migrate-cancel"`) {
		t.Fatal("迁移后应可取消")
	}
	if loc := post("/admin/servers/1/migrate-cancel", url.Values{"target": {"2"}}).Header.Get("Location"); !strings.Contains(loc, "msg=") {
		t.Fatal(loc)
	}
	if loc := post("/admin/servers/1/unblock-port", url.Values{"port": {"8000"}}).Header.Get("Location"); !strings.Contains(loc, "msg=") {
		t.Fatal(loc)
	}
	var n int
	if err := a.DB.QueryRow(`SELECT count(*) FROM server_unblocked_port WHERE server_id=1 AND port=8000`).Scan(&n); err != nil || n != 1 {
		t.Fatalf("待解封: %d %v", n, err)
	}
}

func TestNodeBatchDeleteLandingWithProxyHTTP(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	for _, q := range []string{`INSERT INTO server(id,name,ip) VALUES(1,'p','192.0.2.1'),(2,'l','192.0.2.2')`, `INSERT INTO node(id,name,type,protocol,port,inbound,out_id) VALUES(1,'landing',1,'shadowsocks',8300,'{}',NULL),(2,'proxy',0,'vless',8301,'{}',1)`, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,2,1),(2,1,1)`} {
		if _, err := a.DB.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	v := url.Values{"csrf": {csrf}, "node_ids": {"1", "2"}}
	if loc := do(h, "POST", "/admin/nodes/batch-delete", v, c, nil).Result().Header.Get("Location"); !strings.Contains(loc, "msg=") {
		t.Fatal(loc)
	}
}
