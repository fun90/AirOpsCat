package web

import (
	"context"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

// 节点 1：s1 有已同步域名，s2、s3 在订阅中直连 IP；节点 2 只有 s3 一个部署位，无从切走。
func TestFailoverIPOnlyWarnAndAlert(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := a.DB.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`INSERT INTO server(id,ip,name)VALUES(1,'1.1.1.1','s1'),(2,'2.2.2.2','s2'),(3,'3.3.3.3','s3')`)
	exec(`INSERT INTO node(id,name,type,protocol,core_type,port)VALUES(1,'n',0,'vless','sing-box',443),(2,'single',0,'vless','sing-box',444)`)
	exec(`INSERT INTO domain(id,domain)VALUES(1,'example.com')`)
	exec(`INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status,server_id)VALUES(1,1,'a','a.example.com','A','1.1.1.1','SYNCED',1)`)
	exec(`INSERT INTO node_server(id,node_id,server_id,deployed,exposed,access_host_id,priority)VALUES(1,1,1,1,1,1,1),(2,1,2,1,1,NULL,2),(3,1,3,1,1,NULL,3),(4,2,3,1,1,NULL,1)`)
	exec(`INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json)SELECT node_id,id,server_id,1,'x','{}' FROM node_server`)

	body := do(h, "GET", "/admin/servers", nil, c, nil).Body.String()
	if !strings.Contains(body, "切走后仍会连到本服务器：n\"") || strings.Contains(body, "single") {
		t.Fatal("服务器切走确认框应只列出可切走的直连 IP 节点")
	}
	body = do(h, "GET", "/admin/nodes/1/edit", nil, c, nil).Body.String()
	if !strings.Contains(body, `切换后仍会连原服务器：s3`) || !strings.Contains(body, `切换后仍会连原服务器：s2`) {
		t.Fatal("节点切到此处确认框应列出其余直连 IP 部署位")
	}
	if body := do(h, "GET", "/admin/nodes", nil, c, nil).Body.String(); strings.Contains(body, "data-ip-only") {
		t.Fatal("当前指向有域名时批量切换不应提示")
	}
	// 已切到 s3：再次批量切换时 s3 的订阅条目直连 IP
	exec(`UPDATE domain_dns_record SET content='3.3.3.3', failover_home_content='1.1.1.1' WHERE id=1`)
	if body := do(h, "GET", "/admin/nodes", nil, c, nil).Body.String(); !strings.Contains(body, `data-ip-only="s3"`) {
		t.Fatal("当前指向直连 IP 时批量切换应提示")
	}

	field, _ := crypto.New(crypto.DeriveKey("test-key", "field"))
	s, _ := New(a.DB, a, field)
	s.NotifyDisabled = true
	ctx := context.Background()
	active := func() (n int, summary string) {
		a.DB.QueryRow(`SELECT count(*), coalesce(max(summary),'') FROM alert_state WHERE alert_type='dns-ip-only' AND resource_id=1 AND status<>'RECOVERED'`).Scan(&n, &summary)
		return
	}
	// s1 被自动切走，但 s1 上没有直连 IP 的部署位
	if err := s.alertIPOnly(ctx); err != nil {
		t.Fatal(err)
	}
	if n, _ := active(); n != 0 {
		t.Fatal("原服务器没有直连 IP 部署位时不应告警")
	}
	exec(`UPDATE domain_dns_record SET full_name='' WHERE id=1`)
	if err := s.alertIPOnly(ctx); err != nil {
		t.Fatal(err)
	}
	if n, summary := active(); n != 1 || !strings.Contains(summary, "s1 已自动切走，但 1 个部署位") {
		t.Fatal("自动切走后应告警直连 IP 部署位", n, summary)
	}
	// 手动切走不告警，已有告警恢复
	if err := store.SetServerFailover(ctx, a.DB, 1, true); err != nil {
		t.Fatal(err)
	}
	if err := s.alertIPOnly(ctx); err != nil {
		t.Fatal(err)
	}
	if n, _ := active(); n != 0 {
		t.Fatal("手动切走不应告警")
	}
}
