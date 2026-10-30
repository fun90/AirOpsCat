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
	"time"

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
		"server_type": {"0"}, "multiple": {"1.5"}, "price": {"352.00"}, "price_months": {"12"}, "bandwidth": {"1000"}, "expire_date": {"2027-01-02"}, "bandwidth_date": {"2026-01-05"}}
	if r := post("/admin/servers", good); r.StatusCode != http.StatusSeeOther {
		t.Fatalf("创建: %d", r.StatusCode)
	}
	if idle := body("/admin/servers/idle"); !strings.Contains(idle, "hk-1") || !strings.Contains(idle, "29.33") {
		t.Fatal("下线建议应列出服务器并按年价折算月成本")
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
	good.Set("price_months", "1")
	post("/admin/servers/1", good)
	var enc2, name string
	var months int
	h2.QueryRow(`SELECT auth, name, price_months FROM server WHERE id = 1`).Scan(&enc2, &name, &months)
	if enc2 != enc || name != "hk-renamed" || months != 1 {
		t.Fatal("留空应保留原凭据")
	}

	// 已部署的部署位阻止删除服务器
	h2.Exec(`INSERT INTO node (id, type, protocol) VALUES (1, 0, 'vless')`)
	h2.Exec(`INSERT INTO node_server (node_id, server_id, deployed) VALUES (1, 1, 1)`)
	if r := post("/admin/servers/1/failover", url.Values{"on": {"1"}}); !strings.Contains(r.Header.Get("Location"), "msg=") || !strings.Contains(body("/admin/servers"), "已切走") {
		t.Fatal("手动切走未生效", r.Header.Get("Location"))
	}
	if post("/admin/servers/1/failover", url.Values{"on": {"0"}}); strings.Contains(body("/admin/servers"), "已切走") {
		t.Fatal("恢复未生效")
	}
	// 单部署位节点没有可切的目标，批量切换应跳过
	if r := post("/admin/nodes/batch-failover", url.Values{"node_ids": {"1"}}); !strings.Contains(r.Header.Get("Location"), url.QueryEscape("跳过 1")) {
		t.Fatal("单部署位节点应跳过", r.Header.Get("Location"))
	}
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
	if b := body("/admin/tags?edit=1"); !strings.Contains(b, `action="/admin/tags/1"`) || !strings.Contains(b, `name="name" value="VIP"`) {
		t.Fatal("点击标签应在新增表单中预填修改")
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
	if page := do(h, "GET", "/admin/nodes/new", nil, c, nil).Body.String(); !strings.Contains(page, `data-region="HK" data-proxy-ok`) {
		t.Fatal("新建页应提供可添加的部署位服务器")
	} else if !strings.Contains(page, `&#34;private_key&#34;`) || !strings.Contains(page, `data-regenerate-inbound`) {
		t.Fatal("新建页应预填 vless-reality 入站并提供重新生成按钮")
	}
	create := func(name, port string) url.Values { v := form(name, port); v.Set("add_server", "1"); return v }
	if w := post("/admin/nodes", create("n1", "443")); w.Code != http.StatusSeeOther {
		t.Fatalf("创建: %d %s", w.Code, w.Body.String())
	}
	// 同服务器同端口冲突
	if w := post("/admin/nodes", create("n2", "443")); w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), "已被节点") {
		t.Fatalf("端口冲突应回显: %d", w.Code)
	}
	list := do(h, "GET", "/admin/nodes?status=0", nil, c, nil).Body.String()
	if !strings.Contains(list, "n1-1") || !strings.Contains(list, "待部署") {
		t.Fatal("列表应显示待部署的 n1-1")
	}
	if strings.Contains(do(h, "GET", "/admin/nodes?status=1", nil, c, nil).Body.String(), "n1-1") {
		t.Fatal("状态筛选应排除")
	}
	if edit := do(h, "GET", "/admin/nodes/1/edit", nil, c, nil).Body.String(); !strings.Contains(edit, "hk-1") || !strings.Contains(edit, `name="p1_priority"`) || !strings.Contains(edit, "订阅名 n1-1") {
		t.Fatal("编辑页应显示部署位及订阅名")
	}
	a.DB.Exec(`UPDATE node_server SET failover = 1 WHERE node_id = 1`)
	if edit := do(h, "GET", "/admin/nodes/1/edit", nil, c, nil).Body.String(); !strings.Contains(edit, `name="restore"`) || !strings.Contains(edit, "全部回切") {
		t.Fatal("已切走的部署位应提供取消切走和全部回切")
	}
	if list := do(h, "GET", "/admin/nodes", nil, c, nil).Body.String(); !strings.Contains(list, `badge bg-orange-lt">已切走 hk-1`) {
		t.Fatal("列表页应标注已切走的部署位")
	}
	var fpid int64
	a.DB.QueryRow(`SELECT id FROM node_server WHERE node_id = 1`).Scan(&fpid)
	post("/admin/nodes/1/switch", url.Values{"restore": {strconv.FormatInt(fpid, 10)}})
	var fo int
	a.DB.QueryRow(`SELECT failover FROM node_server WHERE id = ?`, fpid).Scan(&fo)
	if fo != 0 {
		t.Fatal("应已取消该部署位的切走")
	}
	// 新增服务器不再自动关联，需在节点中手动添加部署位（不限地区）
	post("/admin/servers", url.Values{"name": {"jp-2"}, "ip": {"2.2.2.2"}, "ssh_port": {"22"}, "auth_type": {"PASSWORD"}, "server_type": {"0"}, "multiple": {"1"}, "price_months": {"1"}, "region": {"JP"}})
	var n int
	a.DB.QueryRow(`SELECT count(*) FROM node_server WHERE node_id = 1`).Scan(&n)
	if n != 1 {
		t.Fatalf("新服务器不应自动关联部署位: %d", n)
	}
	if edit := do(h, "GET", "/admin/nodes/1/edit", nil, c, nil).Body.String(); !strings.Contains(edit, "jp-2（2.2.2.2）") || !strings.Contains(edit, `id="placement-delete"`) {
		t.Fatal("编辑页应提供不限地区的添加部署位和删除模态框")
	}
	// 增删只随节点保存提交；节点校验失败时不落库并回显暂存项
	save := func(extra url.Values) *httptest.ResponseRecorder {
		v := form("n1-1", "443")
		for k, vs := range extra {
			v[k] = vs
		}
		return post("/admin/nodes/1", v)
	}
	a.DB.Exec(`INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	bad = url.Values{"add_server": {"2"}, "p2_new_name": {"jp9"}, "p2_new_domain_id": {"1"}, "inbound": {`{"listen":"::"}`}}
	if w := save(bad); w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), `data-add-item="2" data-server="2" data-new-name="jp9" data-new-domain="1"`) {
		t.Fatalf("校验失败应回显暂存添加和新增域名: %d", w.Code)
	}
	a.DB.QueryRow(`SELECT count(*) FROM node_server WHERE node_id = 1`).Scan(&n)
	if n != 1 {
		t.Fatalf("保存失败不应添加部署位: %d", n)
	}
	if w := save(url.Values{"add_server": {"2"}, "p2_new_name": {"jp9"}, "p2_new_domain_id": {"1"}}); w.Code != http.StatusSeeOther {
		t.Fatalf("保存时添加部署位: %d %s", w.Code, w.Body.String())
	}
	var host string
	a.DB.QueryRow(`SELECT r.full_name FROM node_server ns JOIN domain_dns_record r ON r.id = ns.access_host_id WHERE ns.node_id = 1 AND ns.server_id = 2`).Scan(&host)
	if host != "jp9.example.test" {
		t.Fatalf("待添加部署位应带新增的访问域名: %q", host)
	}
	a.DB.QueryRow(`SELECT count(*) FROM node_server WHERE node_id = 1`).Scan(&n)
	if n != 2 {
		t.Fatalf("应已添加部署位: %d", n)
	}
	var pid int64
	a.DB.QueryRow(`SELECT id FROM node_server WHERE node_id = 1 AND server_id = 2`).Scan(&pid)
	p := strconv.FormatInt(pid, 10)
	if w := save(url.Values{"del_placement": {p}, "del_target_" + p: {""}}); w.Code != http.StatusSeeOther {
		t.Fatalf("保存时删除部署位: %d", w.Code)
	}
	a.DB.QueryRow(`SELECT count(*) FROM node_server WHERE node_id = 1`).Scan(&n)
	if n != 1 {
		t.Fatalf("未部署的部署位应直接删除: %d", n)
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
	for _, path := range []string{"/admin/accounts", "/admin/accounts/new", "/admin/accounts/" + strconv.FormatInt(id, 10) + "/edit", "/admin/accounts/" + strconv.FormatInt(id, 10) + "/bindings", "/admin/users"} {
		if w := do(h, "GET", path, nil, c, nil); w.Code != 200 {
			t.Fatalf("%s: %d", path, w.Code)
		}
	}
	for _, query := range []string{"online=online", "user_id=999999"} {
		filtered := do(h, "GET", "/admin/accounts?"+query, nil, c, nil)
		if filtered.Code != 200 || !strings.Contains(filtered.Body.String(), "暂无账户") {
			t.Fatal("账户筛选未生效", query, filtered.Code)
		}
	}
	filtered := do(h, "GET", "/admin/accounts?online=offline&user_id="+strconv.FormatInt(acc.UserID, 10), nil, c, nil)
	if filtered.Code != 200 || !strings.Contains(filtered.Body.String(), "修改备注") {
		t.Fatal("离线与用户组合筛选错误")
	}
	// 概览在 {{with .Form}} 内，必须经 $ 取页面数据；无上报时应明确显示“暂无上报”。
	if body := do(h, "GET", "/admin/accounts/"+strconv.FormatInt(id, 10)+"/edit", nil, c, nil).Body.String(); !strings.Contains(body, `data-online-summary>暂无上报</div>`) || !strings.Contains(body, `id="account-online" role="tabpanel">`) || !strings.Contains(body, "保存续期") {
		t.Fatal("账户编辑页在线面板为空")
	}
	for _, query := range []string{
		`INSERT INTO server(id,name,ip) VALUES (990,'缺少上报的入口','203.0.113.99')`,
		`INSERT INTO node(id,name,type) VALUES (990,'入口',0)`,
		`INSERT INTO node_server(node_id,server_id,deployed) VALUES (990,990,1)`,
	} {
		if _, err := a.DB.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	for _, path := range []string{"/admin/accounts", "/admin/accounts/" + strconv.FormatInt(id, 10) + "/edit"} {
		w := do(h, "GET", path, nil, c, nil)
		if w.Code != 200 || !strings.Contains(w.Body.String(), "在线统计不完整：缺少上报的入口") || !strings.Contains(w.Body.String(), "当前连接数可能偏少") {
			t.Fatal("账户页缺少统计覆盖告警", path, w.Code)
		}
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

func TestAdminUnlockUser(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	id, err := store.CreateUser(context.Background(), a.DB, "locked@example.test", "unused", "VIP", "锁定用户")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`UPDATE user SET failed_attempts=8,lock_time=? WHERE id=?`, store.FormatTime(time.Now()), id); err != nil {
		t.Fatal(err)
	}
	path := "/admin/users/" + strconv.FormatInt(id, 10)
	if got := do(h, "GET", path+"/edit", nil, c, nil); got.Code != 200 || !strings.Contains(got.Body.String(), "解除登录锁定") {
		t.Fatal("解锁按钮缺失", got.Code)
	}
	if got := do(h, "POST", path+"/unlock", nil, nil, nil); got.Code != http.StatusUnauthorized {
		t.Fatal("未登录请求应拒绝", got.Code)
	}
	if got := do(h, "POST", path+"/unlock", url.Values{}, c, nil); got.Code != 403 {
		t.Fatal("解锁缺失CSRF防护", got.Code)
	}
	vipID, err := store.CreateUser(context.Background(), a.DB, "vip-unlock@example.test", "unused", "VIP", "普通用户")
	if err != nil {
		t.Fatal(err)
	}
	vip, err := store.UserByEmail(context.Background(), a.DB, "vip-unlock@example.test")
	if err != nil || vip.ID != vipID {
		t.Fatal(err)
	}
	token := a.IssueToken(vip, time.Hour)
	vipCookie := &http.Cookie{Name: c.Name, Value: token}
	if got := do(h, "POST", path+"/unlock", url.Values{"csrf": {a.CSRFToken(token)}}, vipCookie, nil); got.Code != http.StatusForbidden {
		t.Fatal("非管理员可解锁", got.Code)
	}
	locked, err := store.UserByEmail(context.Background(), a.DB, "locked@example.test")
	if err != nil || locked.LockTime == nil || locked.FailedAttempts != 8 {
		t.Fatal("拒绝请求改变了锁定状态", err)
	}
	if got := do(h, "POST", path+"/unlock", url.Values{"csrf": {a.CSRFToken(c.Value)}}, c, nil); got.Code != 303 {
		t.Fatal(got.Code)
	}
	u, err := store.UserByEmail(context.Background(), a.DB, "locked@example.test")
	if err != nil || u.LockTime != nil || u.FailedAttempts != 0 {
		t.Fatal("未解除锁定", err)
	}
}

func TestBatchNodeTagsHTTP(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	if _, err := a.DB.Exec(`INSERT INTO node(id,name,type,protocol,port) VALUES(901,'批量节点',0,'vless',443);INSERT INTO tag(id,name) VALUES(901,'批量标签')`); err != nil {
		t.Fatal(err)
	}
	form := url.Values{"node_ids": {"901"}, "tag_ids": {"901"}, "mode": {"APPEND"}}
	if got := do(h, "POST", "/admin/nodes/batch-tags", form, c, nil); got.Code != 403 {
		t.Fatal("CSRF 未保护", got.Code)
	}
	form.Set("csrf", a.CSRFToken(c.Value))
	if got := do(h, "POST", "/admin/nodes/batch-tags", form, c, nil); got.Code != 303 {
		t.Fatal(got.Code)
	}
	var count int
	if err := a.DB.QueryRow(`SELECT count(*) FROM node_tag WHERE node_id=901 AND tag_id=901`).Scan(&count); err != nil || count != 1 {
		t.Fatal(count, err)
	}
	form.Set("node_ids", "invalid")
	if got := do(h, "POST", "/admin/nodes/batch-tags", form, c, nil); got.Code != 400 {
		t.Fatal("无效 ID 未拒绝", got.Code)
	}
	if got := do(h, "GET", "/admin/nodes", nil, c, nil); got.Code != 200 || !strings.Contains(got.Body.String(), `data-node-select`) {
		t.Fatal("批量选择未渲染", got.Code)
	}
}

func TestNodeAutomaticPortHTTP(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	form := url.Values{"csrf": {a.CSRFToken(c.Value)}, "name": {"自动端口"}, "type": {"0"}, "protocol": {"vless"}, "region": {"HK"}, "inbound": {`{"type":"vless"}`}}
	for _, port := range []string{"0", "invalid", "65536", "999999999999999999999999"} {
		form.Set("port", port)
		if got := do(h, "POST", "/admin/nodes", form, c, nil); got.Code != 422 {
			t.Fatal(port, got.Code)
		}
	}
	form.Del("port")
	if got := do(h, "POST", "/admin/nodes", form, c, nil); got.Code != 303 {
		t.Fatal(got.Code, got.Body.String())
	}
	var port int
	if err := a.DB.QueryRow(`SELECT port FROM node WHERE name='自动端口-1'`).Scan(&port); err != nil || port != 8000 {
		t.Fatal(port, err)
	}
}

func TestHysteriaDefaultInboundHTTP(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,region) VALUES(901,'203.0.113.10','HK')`); err != nil {
		t.Fatal(err)
	}
	got := do(h, "GET", "/admin/nodes/default-inbound?protocol=hysteria2&type=0&region=HK&node_id=0", nil, c, nil)
	if got.Code != 200 || !strings.Contains(got.Body.String(), "https://203.0.113.10/") {
		t.Fatal(got.Code, got.Body.String())
	}
	got = do(h, "GET", "/admin/nodes/default-inbound?protocol=anytls&type=0&region=HK&node_id=0", nil, c, nil)
	if got.Code != 200 || !strings.Contains(got.Body.String(), `"server_name": "203.0.113.10"`) {
		t.Fatal(got.Code, got.Body.String())
	}
	// 新建时选定的部署位优先于地区匹配。
	got = do(h, "GET", "/admin/nodes/default-inbound?protocol=anytls&type=0&region=JP&node_id=0&server_id=901", nil, c, nil)
	if got.Code != 200 || !strings.Contains(got.Body.String(), `"server_name": "203.0.113.10"`) {
		t.Fatal(got.Code, got.Body.String())
	}
	for _, query := range []string{"type=0&region=HK&server_id=bad", "type=0&region=HK&server_id=99999", "type=0&region=JP", "type=bad&region=HK", "type=0&region=HK&node_id=99999"} {
		if got := do(h, "GET", "/admin/nodes/default-inbound?protocol=hysteria2&"+query, nil, c, nil); got.Code != 400 {
			t.Fatal(query, got.Code)
		}
	}
}
