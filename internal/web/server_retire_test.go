package web

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestServerMigrateAndRetireFlows(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	post := func(path string, v url.Values) *http.Response {
		v.Set("csrf", csrf)
		return do(h, "POST", path, v, c, nil).Result()
	}
	body := func(path string) string { return do(h, "GET", path, nil, c, nil).Body.String() }
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := a.DB.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	count := func(q string, args ...any) int {
		t.Helper()
		var n int
		if err := a.DB.QueryRow(q, args...).Scan(&n); err != nil {
			t.Fatal(err)
		}
		return n
	}
	exec(`INSERT INTO server(id,name,ip) VALUES(1,'src','1.1.1.1'),(2,'dst','2.2.2.2'),(3,'other','3.3.3.3')`)
	exec(`INSERT INTO node(id,name,type,protocol,port,inbound) VALUES(1,'n-1',0,'vless',443,'{}'),(2,'m-1',0,'vless',444,'{}')`)
	exec(`INSERT INTO node_server(id,node_id,server_id,deployed,priority) VALUES(1,1,1,1,1),(2,2,3,1,1)`)
	exec(`INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	exec(`INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES(1,1,1,'a','a.example.test','A','1.1.1.1','SYNCED'),(2,1,3,'m','m.example.test','A','3.3.3.3','SYNCED')`)
	exec(`UPDATE node_server SET access_host_id=id`)

	if w := do(h, "POST", "/admin/servers/1/retire", url.Values{}, c, nil); w.Code != http.StatusForbidden {
		t.Fatalf("缺 CSRF 应拒绝: %d", w.Code)
	}
	edit := body("/admin/servers/1/edit")
	for _, want := range []string{`action="/admin/servers/1/migrate"`, `<option value="2">dst（2.2.2.2）</option>`, `action="/admin/servers/1/retire"`, "报废并强制移除"} {
		if !strings.Contains(edit, want) {
			t.Fatalf("编辑页缺少 %s", want)
		}
	}
	if strings.Contains(edit, "src（1.1.1.1）") {
		t.Fatal("迁移目标不应包含自身")
	}
	if r := post("/admin/servers/1/migrate", url.Values{"target": {"2"}}); !strings.Contains(r.Header.Get("Location"), "msg=") || count(`SELECT count(*) FROM node_server WHERE node_id=1 AND server_id=2 AND deployed=0`) != 1 {
		t.Fatalf("迁移第一步未生效: %s", r.Header.Get("Location"))
	}
	edit = body("/admin/servers/1/edit")
	if !strings.Contains(edit, `action="/admin/servers/2/deploy"`) || !strings.Contains(edit, `name="target" value="2" disabled`) {
		t.Fatal("目标未部署时应提示部署目标服务器，完成迁移不可点")
	}
	if r := post("/admin/servers/1/migrate-finish", url.Values{"target": {"2"}}); !strings.Contains(r.Header.Get("Location"), "err=") {
		t.Fatalf("目标未部署时应拒绝完成迁移: %s", r.Header.Get("Location"))
	}
	exec(`UPDATE node_server SET deployed=1 WHERE server_id=2`)
	if r := post("/admin/servers/1/migrate-finish", url.Values{"target": {"2"}}); !strings.Contains(r.Header.Get("Location"), "msg=") {
		t.Fatalf("完成迁移失败: %s", r.Header.Get("Location"))
	}
	if count(`SELECT count(*) FROM node_server WHERE id=1 AND deployed=2`) != 1 || count(`SELECT count(*) FROM domain_dns_record WHERE id=1 AND server_id=2 AND content='2.2.2.2'`) != 1 {
		t.Fatal("源部署位应待删除，域名转移到目标")
	}

	// 失联服务器：待删除部署位无法通过部署清理，报废后强制移除并删除服务器
	if r := post("/admin/servers/1/delete", url.Values{}); !strings.Contains(r.Header.Get("Location"), "err=") {
		t.Fatal("普通删除应因已部署过的部署位被拒绝")
	}
	if r := post("/admin/servers/1/retire", url.Values{"delete_server": {"1"}}); !strings.HasPrefix(r.Header.Get("Location"), "/admin/servers?msg=") {
		t.Fatalf("报废并删除应回到列表: %s", r.Header.Get("Location"))
	}
	if count(`SELECT count(*) FROM server WHERE id=1`) != 0 || count(`SELECT count(*) FROM node_server WHERE server_id=1`) != 0 {
		t.Fatal("报废后服务器与部署位应已删除")
	}
	// 节点只在本服务器且有访问域名：拒绝并列出，服务器保持不变
	if r := post("/admin/servers/3/retire", url.Values{}); !strings.Contains(r.Header.Get("Location"), "err=") || !strings.Contains(r.Header.Get("Location"), url.QueryEscape("m-1")) {
		t.Fatalf("无处承接的域名应阻止报废: %s", r.Header.Get("Location"))
	}
	if count(`SELECT count(*) FROM server WHERE id=3 AND disabled=0`) != 1 {
		t.Fatal("拒绝时不应禁用服务器")
	}
	exec(`UPDATE node_server SET access_host_id=NULL WHERE id=2`)
	if r := post("/admin/servers/3/retire", url.Values{}); !strings.Contains(r.Header.Get("Location"), "msg=") || count(`SELECT count(*) FROM server WHERE id=3 AND disabled=1`) != 1 {
		t.Fatalf("报废保留服务器应置为禁用: %s", r.Header.Get("Location"))
	}
	if w := do(h, "POST", "/admin/servers/99/retire", url.Values{"csrf": {csrf}}, c, nil); w.Code != http.StatusNotFound {
		t.Fatalf("不存在的服务器应 404: %d", w.Code)
	}
	if count(`SELECT count(*) FROM system_log WHERE category='SERVER'`) != 4 {
		t.Fatal("迁移两步与两次报废都应写系统日志")
	}
}
