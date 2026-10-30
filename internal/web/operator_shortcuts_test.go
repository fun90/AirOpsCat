package web

import (
	"net/url"
	"strings"
	"testing"
)

// 运营快捷操作：开户后跳详情、详情页部署回详情、节点保存并部署、服务器初始化预选、地区候选。
func TestOperatorShortcuts(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	a.DB.Exec(`INSERT INTO server(id,name,ip,region,server_type) VALUES (7,'hk-1','203.0.113.7','HK',0)`)
	a.DB.Exec(`INSERT INTO tag(id,name) VALUES (5,'基础')`)
	a.DB.Exec(`INSERT INTO node(id,name,type,region) VALUES (8,'hk',0,'HK')`)
	a.DB.Exec(`INSERT INTO node_tag VALUES (8,5)`)
	a.DB.Exec(`INSERT INTO node_server(node_id,server_id,deployed) VALUES (8,7,1)`)

	w := do(h, "POST", "/admin/accounts", url.Values{"csrf": {csrf}, "user_source": {"new"}, "email": {"quick@example.com"}, "new_password": {"pw-123456"}, "remark": {"快捷开户"}, "period_type": {"MONTHLY"}, "node_multiple": {"2"}, "max_connections": {"100"}, "max_ips": {"2"}, "bandwidth": {"500"}}, c, nil)
	var id string
	a.DB.QueryRow(`SELECT id FROM account WHERE remark='快捷开户'`).Scan(&id)
	edit := "/admin/accounts/" + id + "/edit"
	if w.Code != 303 || !strings.HasPrefix(w.Header().Get("Location"), edit+"?msg=") {
		t.Fatal("开户后应跳到账户详情", w.Code, w.Header().Get("Location"))
	}
	body := do(h, "GET", edit, nil, c, nil).Body.String()
	if !strings.Contains(body, `action="/admin/accounts/`+id+`/deploy"`) || !strings.Contains(body, `data-node-ids="8"`) || !strings.Contains(body, "data-tag-nodes") {
		t.Fatal("详情页缺少部署按钮或标签节点数据")
	}
	if !strings.Contains(do(h, "GET", "/admin/accounts/new", nil, c, nil).Body.String(), `data-term-months="3"`) {
		t.Fatal("开户页缺少快捷时长")
	}
	// 账户没有标签：不连 SSH，直接回到详情页
	w = do(h, "POST", "/admin/accounts/"+id+"/deploy", url.Values{"csrf": {csrf}, "from": {"edit"}}, c, nil)
	if !strings.HasPrefix(w.Header().Get("Location"), edit+"?msg=") {
		t.Fatal("详情页部署应回到详情页", w.Header().Get("Location"))
	}

	w = do(h, "POST", "/admin/nodes", url.Values{"csrf": {csrf}, "name": {"无部署位"}, "type": {"0"}, "protocol": {"vless"}, "region": {"US"}, "inbound": {`{"type":"vless"}`}, "deploy": {"1"}}, c, nil)
	if loc, _ := url.QueryUnescape(w.Header().Get("Location")); w.Code != 303 || !strings.Contains(loc, "没有待部署的部署位") {
		t.Fatal("保存并部署应走部署分支", w.Code, loc)
	}

	if !strings.Contains(do(h, "GET", "/admin/servers", nil, c, nil).Body.String(), `href="/admin/maintenance?server_id=7"`) {
		t.Fatal("服务器列表缺少初始化入口")
	}
	body = do(h, "GET", "/admin/maintenance?server_id=7", nil, c, nil).Body.String()
	if !strings.Contains(body, "data-maintenance-init") || !strings.Contains(body, `<option value="7" selected>`) {
		t.Fatal("维护页未预选服务器")
	}
	if !strings.Contains(do(h, "GET", "/admin/servers/new", nil, c, nil).Body.String(), `<option value="HK">`) {
		t.Fatal("服务器表单缺少地区候选")
	}
}
