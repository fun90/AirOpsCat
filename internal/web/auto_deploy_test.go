package web

import (
	"context"
	"net/http"
	"net/url"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

// autoDeployFixture：服务器 1–4；代理节点 1（标签 1，服务器 1，出站落地节点 4）、代理节点 2（标签 2，服务器 2）、
// 代理节点 3（标签 3，服务器 3，一直待部署，与各操作无关）、落地节点 4（服务器 4）；账户 1 用标签 1，账户 2 用标签 2。
// 部署队列换成 fake，只记录入队的服务器，不改部署位状态。
func autoDeployFixture(t *testing.T, on bool) (*Server, http.Handler, *http.Cookie, string) {
	t.Helper()
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	s.deployQ.run = (&fakeRuns{}).run
	t.Cleanup(func() { s.ShutdownDeploys(context.Background()) })
	future := store.FormatTime(time.Now().AddDate(0, 1, 0))
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name,region,server_type) VALUES(1,'203.0.113.1','a','HK',0),(2,'203.0.113.2','b','HK',0),(3,'203.0.113.3','c','HK',0),(4,'203.0.113.4','d','US',1);
 INSERT INTO tag(id,name) VALUES(1,'t1'),(2,'t2'),(3,'t3');
 INSERT INTO node(id,name,type,port,protocol,core_type,inbound,out_id) VALUES(4,'land-1',1,9004,'vless','sing-box','{"type":"vless"}',NULL),
  (1,'n1-1',0,9001,'vless','sing-box','{"type":"vless"}',4),(2,'n2-1',0,9002,'vless','sing-box','{"type":"vless"}',NULL),(3,'n3-1',0,9003,'vless','sing-box','{"type":"vless"}',NULL);
 INSERT INTO node_tag VALUES(1,1),(2,2),(3,3);
 INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,2,2,1),(3,3,3,0),(4,4,4,1);
 INSERT INTO node_deployment(node_id,node_server_id,version,snapshot_hash,disabled) VALUES(1,1,1,'h',0),(2,2,1,'h',0),(4,4,1,'h',0);
 INSERT INTO account(id,uuid,account_no,auth_code,period_type,node_multiple,from_date,to_date,user_id,remark,bandwidth) VALUES
  (1,'00000000-0000-4000-8000-000000000001','A1','code-000000000001','MONTHLY',1,'2020-01-01 00:00:00',?,1,'甲',100),
  (2,'00000000-0000-4000-8000-000000000002','A2','code-000000000002','MONTHLY',1,'2020-01-01 00:00:00',?,1,'乙',100);
 INSERT INTO account_tag VALUES(1,1),(2,2)`, future, future); err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES(?,?,'template')`, store.AutoDeployKey, map[bool]string{true: "true", false: "false"}[on]); err != nil {
		t.Fatal(err)
	}
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	return s, h, c, a.CSRFToken(c.Value)
}

// lastJob 返回最近入队的任务服务器（升序）；没有任务返回 nil。
func lastJob(s *Server) []int64 {
	s.deployQ.mu.Lock()
	defer s.deployQ.mu.Unlock()
	if len(s.deployQ.jobs) == 0 {
		return nil
	}
	ids := slices.Clone(s.deployQ.jobs[len(s.deployQ.jobs)-1].Servers)
	slices.Sort(ids)
	return ids
}

func jobCount(s *Server) int {
	s.deployQ.mu.Lock()
	defer s.deployQ.mu.Unlock()
	return len(s.deployQ.jobs)
}

// settle 等待队列空闲，并把除无关节点 3 外的部署位恢复为已部署，隔离各步骤。
func settle(t *testing.T, s *Server) {
	t.Helper()
	if !s.deployQ.wait(5 * time.Second) {
		t.Fatal("队列未空闲")
	}
	s.DB.Exec(`UPDATE node_server SET deployed=1 WHERE id<>3`)
}

func location(w interface{ Header() http.Header }) string {
	loc, _ := url.QueryUnescape(w.Header().Get("Location"))
	return loc
}

func autoAccountForm(csrf, tag string) url.Values {
	return url.Values{"csrf": {csrf}, "user_id": {"1"}, "user_source": {"existing"}, "remark": {"甲"}, "account_no": {"A1"},
		"uuid": {"00000000-0000-4000-8000-000000000001"}, "period_type": {"MONTHLY"}, "node_multiple": {"1"}, "max_connections": {"0"},
		"max_ips": {"0"}, "bandwidth": {"100"}, "from_date": {"2020-01-01T00:00"}, "to_date": {time.Now().AddDate(0, 1, 0).In(store.Shanghai).Format(formDateLayout)}, "tags": {tag}}
}

func TestAutoDeployEnqueuesOnlyAffectedServers(t *testing.T) {
	s, h, c, csrf := autoDeployFixture(t, true)
	steps := []struct {
		name, method, path string
		form               url.Values
		want               []int64
	}{
		// 换标签：旧标签（服务器 1）与新标签（服务器 2）都要部署，无关的待部署服务器 3 不动
		{"账户保存换标签", "POST", "/admin/accounts/1", autoAccountForm(csrf, "2"), []int64{1, 2}},
		{"账户删除", "POST", "/admin/accounts/2/delete", url.Values{"csrf": {csrf}}, []int64{2}},
		{"节点启停", "POST", "/admin/nodes/2/toggle", url.Values{"csrf": {csrf}, "disabled": {"1"}}, []int64{2}},
		{"节点保存", "POST", "/admin/nodes/1", url.Values{"csrf": {csrf}, "name": {"n1"}, "type": {"0"}, "protocol": {"vless"}, "port": {"9011"}, "region": {"HK"}, "inbound": {`{"type":"vless"}`}, "out_id": {"4"}, "tags": {"1"}}, []int64{1}},
		// 落地所在服务器改 IP：引用它的代理节点所在服务器 1 需要部署，服务器 4 自身不需要
		{"服务器改 IP", "POST", "/admin/servers/4", url.Values{"csrf": {csrf}, "name": {"d"}, "ip": {"203.0.113.44"}, "ssh_port": {"22"}, "auth_type": {"PASSWORD"}, "username": {"root"}, "region": {"US"}, "server_type": {"1"}, "price_months": {"1"}, "multiple": {"1"}, "bandwidth": {"0"}, "cpu_cores": {"1"}, "price": {"0"}}, []int64{1}},
		// 路由规则不标记待部署，直接部署规则关联的服务器（启用且有部署位）
		{"路由规则新增", "POST", "/admin/routes", url.Values{"csrf": {csrf}, "name": {"r"}, "rule_type": {"domain"}, "rule_value": {`["example.com"]`}, "outbound_node_id": {"4"}, "enabled": {"1"}, "servers": {"2"}}, []int64{2}},
	}
	for _, st := range steps {
		before := jobCount(s)
		w := do(h, st.method, st.path, st.form, c, nil)
		loc := location(w)
		if w.Code != http.StatusSeeOther {
			t.Fatalf("%s：%d %s", st.name, w.Code, w.Body.String())
		}
		if jobCount(s) != before+1 || !slices.Equal(lastJob(s), st.want) {
			t.Fatalf("%s：入队 %v，期望 %v（%s）", st.name, lastJob(s), st.want, loc)
		}
		if !strings.Contains(loc, "已提交后台部署") {
			t.Fatalf("%s：提示应包含任务信息：%s", st.name, loc)
		}
		settle(t, s)
	}
	// 编辑路由规则：改选服务器 1 后规则前后关联的服务器 1、2 都部署
	w := do(h, "POST", "/admin/routes/1", url.Values{"csrf": {csrf}, "name": {"r"}, "rule_type": {"domain"}, "rule_value": {`["example.com"]`}, "outbound_node_id": {"4"}, "enabled": {"1"}, "servers": {"1"}}, c, nil)
	if !slices.Equal(lastJob(s), []int64{1, 2}) {
		t.Fatal("路由规则编辑应部署前后关联的服务器", lastJob(s), location(w))
	}
	settle(t, s)
	// 到期任务：只部署到期账户相关、需要部署的服务器
	s.DB.Exec(`UPDATE account SET to_date=? WHERE id=1`, store.FormatTime(time.Now().Add(-time.Minute)))
	if err := s.expireAccounts(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(lastJob(s), []int64{2}) { // 账户 1 已换到标签 2
		t.Fatal("到期任务入队", lastJob(s))
	}
	settle(t, s)
	// 没有需要部署的服务器时不入队，也不提示需部署
	before := jobCount(s)
	w = do(h, "POST", "/admin/accounts/1/toggle", url.Values{"csrf": {csrf}, "disabled": {"1"}}, c, nil) // 已到期，启停不改配置
	if jobCount(s) != before || strings.Contains(location(w), "需重新部署") {
		t.Fatal("无需部署时不应入队", location(w))
	}
}

func TestAutoDeployScopeIgnoresUnrelatedNewPendingServer(t *testing.T) {
	s, _, _, _ := autoDeployFixture(t, true)
	sc := s.beginDeploy(context.Background())
	sc.node(1)
	// 另一个操作把服务器 2 置为待部署时，本次操作的候选并集不包含它。
	if _, err := s.DB.Exec(`UPDATE node_server SET deployed=0 WHERE id=2`); err != nil {
		t.Fatal(err)
	}
	if note := sc.finish("保存节点 #1", ""); note != "" || jobCount(s) != 0 {
		t.Fatal("无关服务器不应入队", note, lastJob(s))
	}
}

func TestAutoDeployOffKeepsManualHints(t *testing.T) {
	s, h, c, csrf := autoDeployFixture(t, false)
	// 到期移除不受开关影响
	s.DB.Exec(`UPDATE account SET to_date=? WHERE id=1`, store.FormatTime(time.Now().Add(-time.Minute)))
	if err := s.expireAccounts(context.Background()); err != nil {
		t.Fatal(err)
	}
	if !slices.Equal(lastJob(s), []int64{1}) {
		t.Fatal("到期任务应入队", lastJob(s))
	}
	settle(t, s)
	s.DB.Exec(`UPDATE account SET to_date=? WHERE id=1`, store.FormatTime(time.Now().AddDate(0, 1, 0)))
	jobs := jobCount(s)
	if w := do(h, "POST", "/admin/accounts/1", autoAccountForm(csrf, "2"), c, nil); !strings.Contains(location(w), "台服务器待部署") {
		t.Fatal(location(w))
	}
	if w := do(h, "POST", "/admin/accounts/2/delete", url.Values{"csrf": {csrf}}, c, nil); !strings.Contains(location(w), "相关节点需重新部署") {
		t.Fatal(location(w))
	}
	if w := do(h, "POST", "/admin/nodes/2/toggle", url.Values{"csrf": {csrf}, "disabled": {"1"}}, c, nil); !strings.Contains(location(w), "需重新部署后生效") {
		t.Fatal(location(w))
	}
	w := do(h, "POST", "/admin/routes", url.Values{"csrf": {csrf}, "name": {"r"}, "rule_type": {"domain"}, "rule_value": {`["example.com"]`}, "outbound_node_id": {"4"}, "enabled": {"1"}, "servers": {"2"}}, c, nil)
	if !strings.Contains(location(w), "强制部署相关服务器后生效") {
		t.Fatal(location(w))
	}
	if page := do(h, "GET", "/admin/routes", nil, c, nil).Body.String(); !strings.Contains(page, "变更需强制部署相关服务器后生效") {
		t.Fatal("关闭时路由规则页应提示手动强制部署")
	}
	if jobCount(s) != jobs {
		t.Fatal("关闭时不应入队", jobCount(s))
	}
}

func TestAccountBatchHandlersAndRenewModal(t *testing.T) {
	s, h, c, csrf := autoDeployFixture(t, true)
	page := do(h, "GET", "/admin/accounts?tag=1", nil, c, nil).Body.String()
	for _, want := range []string{`id="accountRenewModal"`, `data-renew-id="1"`, `action="/admin/accounts/batch-renew"`, `action="/admin/accounts/batch-delete"`, `value="REMOVE"`, `name="back" value="/admin/accounts?tag=1"`} {
		if !strings.Contains(page, want) {
			t.Fatal("账户列表缺少", want)
		}
	}
	// 列表弹窗续期：停留在带筛选参数的列表
	to := time.Now().AddDate(0, 2, 0).In(store.Shanghai).Format(formDateLayout)
	w := do(h, "POST", "/admin/accounts/1/renew", url.Values{"csrf": {csrf}, "to_date": {to}, "from": {"list"}, "back": {"/admin/accounts?tag=1"}, "amount": {"30"}, "payment_method": {"WeChat"}}, c, nil)
	if loc := location(w); !strings.HasPrefix(loc, "/admin/accounts?tag=1&msg=续期已保存") {
		t.Fatal(loc)
	}
	// 请求中的返回地址只能指向账户列表，防止表单提交诱导外部跳转。
	w = do(h, "POST", "/admin/accounts/1/renew", url.Values{"csrf": {csrf}, "to_date": {time.Now().AddDate(0, 3, 0).In(store.Shanghai).Format(formDateLayout)}, "from": {"list"}, "back": {"//example.invalid/other"}}, c, nil)
	if loc := location(w); !strings.HasPrefix(loc, "/admin/accounts?msg=") {
		t.Fatal("续期不应跳转到表单中的其它地址", loc)
	}
	if page = do(h, "GET", "/admin/accounts", nil, c, nil).Body.String(); !strings.Contains(page, `data-renew-cents="3000" data-renew-method="WeChat" data-renew-months-last="1"`) {
		t.Fatal("续期弹窗应带出最近一笔流水")
	}
	// 批量续期：记账需金额与付款方式
	w = do(h, "POST", "/admin/accounts/batch-renew", url.Values{"csrf": {csrf}, "account_ids": {"1", "2"}, "months": {"1"}, "record": {"same"}, "amount": {"20"}}, c, nil)
	if !strings.Contains(location(w), "err=") {
		t.Fatal("缺付款方式应拒绝", location(w))
	}
	w = do(h, "POST", "/admin/accounts/batch-renew", url.Values{"csrf": {csrf}, "account_ids": {"1", "2"}, "months": {"1"}, "record": {"same"}, "amount": {"20"}, "payment_method": {"AliPay"}}, c, nil)
	if loc := location(w); !strings.Contains(loc, "已续期 2 个账户（+1 月），每户记账 20.00 元") {
		t.Fatal(loc)
	}
	settle(t, s)
	// 批量禁用：两个有效账户的服务器 1、2 入队，无关服务器 3 不动
	before := jobCount(s)
	w = do(h, "POST", "/admin/accounts/batch-toggle", url.Values{"csrf": {csrf}, "account_ids": {"1", "2"}, "disabled": {"1"}}, c, nil)
	if !strings.Contains(location(w), "已禁用 2 个账户") || jobCount(s) != before+1 || !slices.Equal(lastJob(s), []int64{1, 2}) {
		t.Fatal(location(w), lastJob(s))
	}
	settle(t, s)
	// 批量移除标签
	w = do(h, "POST", "/admin/accounts/batch-tags", url.Values{"csrf": {csrf}, "account_ids": {"1"}, "tag_ids": {"1"}, "mode": {"REMOVE"}}, c, nil)
	if !strings.Contains(location(w), "标签已更新 1 个账户") {
		t.Fatal(location(w))
	}
	settle(t, s)
	w = do(h, "POST", "/admin/accounts/batch-delete", url.Values{"csrf": {csrf}, "account_ids": {"1", "2"}}, c, nil)
	var n int
	s.DB.QueryRow(`SELECT count(*) FROM account`).Scan(&n)
	if !strings.Contains(location(w), "已删除 2 个账户") || n != 0 {
		t.Fatal(location(w), n)
	}
}

func TestAccountPeriodHandlers(t *testing.T) {
	s, h, c, csrf := autoDeployFixture(t, true)
	if page := do(h, "GET", "/admin/accounts/1/edit", nil, c, nil).Body.String(); !strings.Contains(page, `action="/admin/accounts/1/period-quota"`) || !strings.Contains(page, `action="/admin/accounts/1/period-reset"`) {
		t.Fatal("编辑页应提供本期加量与重置")
	}
	w := do(h, "POST", "/admin/accounts/1/period-quota", url.Values{"csrf": {csrf}, "gb": {"50"}}, c, nil)
	if loc := location(w); !strings.HasPrefix(loc, "/admin/accounts/1/edit?msg=本期已加量 50 GB，本期配额 150 GB") {
		t.Fatal(loc)
	}
	if w = do(h, "POST", "/admin/accounts/1/period-quota", url.Values{"csrf": {csrf}, "gb": {"x"}}, c, nil); !strings.Contains(location(w), "err=") {
		t.Fatal(location(w))
	}
	s.DB.Exec(`UPDATE account_traffic_stats SET upload_bytes=? WHERE account_id=1`, int64(3)<<30)
	if w = do(h, "POST", "/admin/accounts/1/period-reset", url.Values{"csrf": {csrf}}, c, nil); !strings.Contains(location(w), "本期用量已清零") {
		t.Fatal(location(w))
	}
	if w = do(h, "POST", "/admin/accounts/99/period-reset", url.Values{"csrf": {csrf}}, c, nil); w.Code != http.StatusNotFound {
		t.Fatal(w.Code)
	}
	if jobCount(s) != 0 {
		t.Fatal("加量与重置不需要部署")
	}
}
