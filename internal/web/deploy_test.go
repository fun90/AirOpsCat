package web

import (
	"context"
	"net/url"
	"strings"
	"testing"
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
	var state int
	db.QueryRowContext(context.Background(), `SELECT deployed FROM node_server WHERE node_id=99`).Scan(&state)
	if state != 1 {
		t.Fatal("外部部署未记录成功")
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
