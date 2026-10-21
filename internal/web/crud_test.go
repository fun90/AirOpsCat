package web

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/store"
)

func TestServerAndTagFlows(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	post := func(path string, v url.Values) *http.Response {
		v.Set("csrf", csrf)
		return do(h, "POST", path, v, c, nil).Result()
	}
	body := func(path string) string { return do(h, "GET", path, nil, c, nil).Body.String() }

	// 缺少 CSRF 被拒绝
	if w := do(h, "POST", "/admin/servers", url.Values{"ip": {"1.1.1.1"}}, c, nil); w.Code != http.StatusForbidden {
		t.Fatalf("缺 CSRF: %d", w.Code)
	}
	// 校验失败：回显输入并返回 422
	w := do(h, "POST", "/admin/servers", url.Values{"csrf": {csrf}, "ip": {""}, "ssh_port": {"99999"}, "auth_type": {"PASSWORD"}, "server_type": {"0"}, "multiple": {"0"}, "name": {"<b>x</b>"}}, c, nil)
	if w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), "请填写 IP") || strings.Contains(w.Body.String(), "<b>x</b>") {
		t.Fatalf("校验/转义不对: %d", w.Code)
	}

	good := url.Values{"name": {"hk-1"}, "ip": {"1.1.1.1"}, "ssh_port": {"22"}, "auth_type": {"PASSWORD"}, "username": {"root"}, "auth": {"s3cret-pass"},
		"server_type": {"0"}, "multiple": {"1.5"}, "price": {"352.00"}, "bandwidth": {"1000"}, "expire_date": {"2027-01-02"}, "bandwidth_date": {"2026-01-05"}}
	if r := post("/admin/servers", good); r.StatusCode != http.StatusSeeOther {
		t.Fatalf("创建: %d", r.StatusCode)
	}
	var enc string
	var price int64
	var bwd string
	h2 := a.DB
	h2.QueryRow(`SELECT auth, price, bandwidth_date FROM server WHERE id = 1`).Scan(&enc, &price, &bwd)
	if !strings.HasPrefix(enc, "v1:") || strings.Contains(enc, "s3cret") || price != 35200 || bwd != "2026-01-05 00:00:00" {
		t.Fatalf("存储不对: %q %d %q", enc, price, bwd)
	}
	if edit := body("/admin/servers/1/edit"); strings.Contains(edit, "s3cret") || strings.Contains(edit, enc) || !strings.Contains(edit, "352.00") {
		t.Fatal("编辑页不应回传凭据，应回显价格")
	}
	// 留空凭据表示不修改
	good.Set("auth", "")
	good.Set("name", "hk-renamed")
	post("/admin/servers/1", good)
	var enc2, name string
	h2.QueryRow(`SELECT auth, name FROM server WHERE id = 1`).Scan(&enc2, &name)
	if enc2 != enc || name != "hk-renamed" {
		t.Fatal("留空应保留原凭据")
	}

	// 已部署的部署位阻止删除服务器
	h2.Exec(`INSERT INTO node (id, type, protocol) VALUES (1, 0, 'vless')`)
	h2.Exec(`INSERT INTO node_server (node_id, server_id, deployed) VALUES (1, 1, 1)`)
	if r := post("/admin/servers/1/delete", url.Values{}); !strings.Contains(r.Header.Get("Location"), "err=") {
		t.Fatalf("应拒绝删除: %s", r.Header.Get("Location"))
	}
	h2.Exec(`UPDATE node_server SET deployed = 0`)
	post("/admin/servers/1/delete", url.Values{})
	var n int
	h2.QueryRow(`SELECT (SELECT count(*) FROM server) + (SELECT count(*) FROM node_server)`).Scan(&n)
	if n != 0 {
		t.Fatal("未部署的部署位应随服务器删除")
	}

	// 标签：重名拒绝、在用不可删
	post("/admin/tags", url.Values{"name": {"VIP"}, "color": {"#112233"}})
	if r := post("/admin/tags", url.Values{"name": {"vip"}}); !strings.Contains(r.Header.Get("Location"), "err=") {
		t.Fatal("标签名不区分大小写，重名应被拒绝")
	}
	h2.Exec(`INSERT INTO node_tag (node_id, tag_id) VALUES (1, 1)`)
	if r := post("/admin/tags/1/delete", url.Values{}); !strings.Contains(r.Header.Get("Location"), "err=") {
		t.Fatal("在用标签不可删除")
	}
	h2.Exec(`DELETE FROM node_tag`)
	post("/admin/tags/1/delete", url.Values{})
	if tags, _, _ := store.TagList(context.Background(), h2, "", 1, 20); len(tags) != 0 {
		t.Fatal("标签应已删除")
	}
}

func TestNodeFlows(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	post := func(path string, v url.Values) *httptest.ResponseRecorder {
		v.Set("csrf", csrf)
		return do(h, "POST", path, v, c, nil)
	}
	a.DB.Exec(`INSERT INTO server (name, ip, region, server_type) VALUES ('hk-1', '1.1.1.1', 'HK', 0)`)
	form := func(name, port string) url.Values {
		return url.Values{"name": {name}, "type": {"0"}, "protocol": {"vless"}, "port": {port}, "region": {"HK"}, "inbound": {`{"type":"vless"}`}}
	}
	// 非法入站：回显错误
	bad := form("n1", "443")
	bad.Set("inbound", `{"listen":"::"}`)
	if w := post("/admin/nodes", bad); w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), "不能包含 listen") {
		t.Fatalf("应回显校验错误: %d", w.Code)
	}
	if w := post("/admin/nodes", form("n1", "443")); w.Code != http.StatusSeeOther {
		t.Fatalf("创建: %d %s", w.Code, w.Body.String())
	}
	// 同服务器同端口冲突
	if w := post("/admin/nodes", form("n2", "443")); w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), "已被节点") {
		t.Fatalf("端口冲突应回显: %d", w.Code)
	}
	list := do(h, "GET", "/admin/nodes?status=0", nil, c, nil).Body.String()
	if !strings.Contains(list, "n1-1") || !strings.Contains(list, "待部署") {
		t.Fatal("列表应显示待部署的 n1-1")
	}
	if strings.Contains(do(h, "GET", "/admin/nodes?status=1", nil, c, nil).Body.String(), "n1-1") {
		t.Fatal("状态筛选应排除")
	}
	if edit := do(h, "GET", "/admin/nodes/1/edit", nil, c, nil).Body.String(); !strings.Contains(edit, "hk-1") || !strings.Contains(edit, `name="p1_priority"`) {
		t.Fatal("编辑页应显示部署位")
	}
	// 新增服务器后自动同步部署位
	post("/admin/servers", url.Values{"name": {"hk-2"}, "ip": {"2.2.2.2"}, "ssh_port": {"22"}, "auth_type": {"PASSWORD"}, "server_type": {"0"}, "multiple": {"1"}, "region": {"HK"}})
	var n int
	a.DB.QueryRow(`SELECT count(*) FROM node_server WHERE node_id = 1`).Scan(&n)
	if n != 2 {
		t.Fatalf("新服务器应自动关联部署位: %d", n)
	}
	post("/admin/nodes/1/toggle", url.Values{"disabled": {"1"}})
	var dis int
	a.DB.QueryRow(`SELECT disabled FROM node WHERE id = 1`).Scan(&dis)
	if dis != 1 {
		t.Fatal("应已禁用")
	}
	post("/admin/nodes/1/delete", url.Values{})
	a.DB.QueryRow(`SELECT count(*) FROM node`).Scan(&n)
	if n != 0 {
		t.Fatal("未部署的节点应直接删除")
	}
}

func TestInboundFormattedForEditAndCompactedOnSave(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	a.DB.Exec(`INSERT INTO server (name, ip, region, server_type) VALUES ('hk', '1.1.1.1', 'HK', 0)`)
	v := url.Values{"csrf": {a.CSRFToken(c.Value)}, "name": {"n"}, "type": {"0"}, "protocol": {"vless"}, "port": {"443"}, "region": {"HK"},
		"inbound": {"{\n  \"type\": \"vless\",\n  \"b\": {\"z\": 1, \"a\": 2}\n}"}}
	if w := do(h, "POST", "/admin/nodes", v, c, nil); w.Code != http.StatusSeeOther {
		t.Fatal(w.Code)
	}
	var stored string
	a.DB.QueryRow(`SELECT inbound FROM node WHERE id = 1`).Scan(&stored)
	if stored != `{"type":"vless","b":{"z":1,"a":2}}` {
		t.Fatalf("应存为紧凑形式并保持键顺序: %s", stored)
	}
	page := do(h, "GET", "/admin/nodes/1/edit", nil, c, nil).Body.String()
	if !strings.Contains(page, "{\n  &#34;type&#34;: &#34;vless&#34;,\n  &#34;b&#34;: {\n    &#34;z&#34;: 1,") {
		t.Fatalf("编辑页应展示缩进后的 JSON")
	}
}

func TestInlineTagMaintenance(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	post := func(v url.Values) *httptest.ResponseRecorder {
		v.Set("csrf", a.CSRFToken(c.Value))
		return do(h, "POST", "/admin/tag-picker", v, c, nil)
	}
	w := post(url.Values{"name": {"新标签"}, "color": {"#123456"}})
	if w.Code != 200 {
		t.Fatalf("新增失败: %d %s", w.Code, w.Body.String())
	}
	var tag struct {
		ID int64 `json:"id"`
	}
	if err := json.Unmarshal(w.Body.Bytes(), &tag); err != nil || tag.ID == 0 {
		t.Fatal("新增应返回真实 ID")
	}
	a.DB.Exec(`UPDATE tag SET description='保留说明' WHERE id=?`, tag.ID)
	w = post(url.Values{"id": {strconv.FormatInt(tag.ID, 10)}, "name": {"修改标签"}, "color": {"#654321"}})
	if w.Code != 200 {
		t.Fatal(w.Body.String())
	}
	saved, _ := store.TagByID(context.Background(), a.DB, tag.ID)
	if saved.Name != "修改标签" || saved.Description != "保留说明" {
		t.Fatalf("编辑破坏已有字段: %+v", saved)
	}
	if w = post(url.Values{"name": {"修改标签"}, "color": {"#123456"}}); w.Code != 409 {
		t.Fatal("重复名称应返回409")
	}
	if w = post(url.Values{"name": {""}, "color": {"#123456"}}); w.Code != 422 {
		t.Fatal("空名称应被拒绝")
	}
	if w = do(h, "POST", "/admin/tag-picker", url.Values{"name": {"x"}}, c, nil); w.Code != 403 {
		t.Fatal("无CSRF应被拒绝")
	}
}

func TestAccountHTTPFlow(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	form := url.Values{"csrf": {csrf}, "user_source": {"new"}, "email": {"new-customer@example.com"}, "new_password": {"secret123"}, "nick_name": {"客户"}, "remark": {"测试开户"}, "period_type": {"MONTHLY"}, "node_multiple": {"2"}, "max_connections": {"100"}, "max_ips": {"2"}, "bandwidth": {"500"}, "download_mbps": {"12"}, "upload_mbps": {"8"}, "amount": {"25.00"}, "payment_method": {"WeChat"}}
	w := do(h, "POST", "/admin/accounts", form, c, nil)
	if w.Code != 303 {
		t.Fatalf("开户: %d %s", w.Code, w.Body.String())
	}
	var id int64
	if err := a.DB.QueryRow(`SELECT id FROM account WHERE remark='测试开户'`).Scan(&id); err != nil {
		t.Fatal(err)
	}
	acc, err := store.AccountByID(context.Background(), a.DB, id)
	if err != nil {
		t.Fatal(err)
	}
	if acc.FromDate == nil || acc.ToDate == nil || !acc.ToDate.Equal(acc.FromDate.AddDate(0, 1, 0)) {
		t.Fatal("缺省开户日期应与前端一个月默认一致")
	}
	var count int
	a.DB.QueryRow(`SELECT count(*) FROM transactions WHERE business_id=? AND amount=2500`, id).Scan(&count)
	if count != 1 {
		t.Fatal("开户流水缺失")
	}
	form.Set("account_no", acc.AccountNo)
	form.Set("uuid", acc.UUID)
	form.Set("user_id", strconv.FormatInt(acc.UserID, 10))
	form.Set("remark", "修改备注")
	form.Set("amount", "")
	w = do(h, "POST", "/admin/accounts/"+strconv.FormatInt(id, 10), form, c, nil)
	if w.Code != 303 {
		t.Fatalf("编辑: %d %s", w.Code, w.Body.String())
	}
	for _, path := range []string{"/admin/accounts", "/admin/accounts/" + strconv.FormatInt(id, 10) + "/edit", "/admin/accounts/" + strconv.FormatInt(id, 10) + "/bindings", "/admin/users"} {
		if w := do(h, "GET", path, nil, c, nil); w.Code != 200 {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
	// 在线面板在 {{with .Form}} 内，必须经 $ 取页面数据；无上报时应明确显示“暂无上报”。
	if body := do(h, "GET", "/admin/accounts/"+strconv.FormatInt(id, 10)+"/edit", nil, c, nil).Body.String(); !strings.Contains(body, `id="account-online" role="tabpanel"><div class="mb-2">暂无上报</div>`) {
		t.Fatal("账户编辑页在线面板为空")
	}
	w = do(h, "POST", "/admin/accounts/"+strconv.FormatInt(id, 10)+"/auth-code", url.Values{"csrf": {csrf}}, c, nil)
	if w.Code != 303 {
		t.Fatal(w.Code)
	}
	updated, _ := store.AccountByID(context.Background(), a.DB, id)
	if updated.AuthCode == acc.AuthCode {
		t.Fatal("授权码未重置")
	}
}
