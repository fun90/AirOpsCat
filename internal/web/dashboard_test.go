package web

import (
	"context"
	"html"
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/store"
)

func TestDashboardPage(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	if body := do(h, "GET", "/admin", nil, c, nil).Body.String(); !strings.Contains(body, "暂无待处理事项") || !strings.Contains(body, "暂无在线上报") {
		t.Fatal("空库概览应显示空状态")
	} else if strings.Contains(body, "服务器健康") || strings.Contains(body, "最近事件") {
		t.Fatal("概览不应再包含服务器健康和最近事件")
	}
	now := time.Now()
	local := now.In(store.Shanghai)
	for _, q := range []struct {
		sql  string
		args []any
	}{
		{`INSERT INTO server(id,name,ip,region) VALUES(1,'香港机','203.0.113.1','香港'),(2,'故障机','203.0.113.2','美国')`, nil},
		{`INSERT INTO domain(id,domain,expire_date) VALUES(1,'renew.example',?)`, []any{local.AddDate(0, 0, 1).Format("2006-01-02")}},
		{`INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status,server_id) VALUES(10,1,'hk1','hk1.renew.example','A','203.0.113.1','SYNCED',1)`, nil},
		{`INSERT INTO node(id,name,type,protocol,port) VALUES(1,'香港',0,'vless-reality',443),(2,'离线节点',0,'vless-reality',8443)`, nil},
		{`INSERT INTO node_server(id,node_id,server_id,access_host_id,exposed,priority,deployed) VALUES(1,1,1,10,1,1,1),(2,2,2,NULL,1,1,1)`, nil},
		{`INSERT INTO alert_state(alert_type,resource_type,resource_id,fingerprint,status,summary,last_triggered_time) VALUES(?,'server',2,'x','ACTIVE','故障机不可达',?),('server-expiring','server',1,'e','ACTIVE','到期重复',?)`, []any{alertUnreachable, store.FormatTime(now), store.FormatTime(now)}},
		{`INSERT INTO account(account_no,uuid,auth_code,period_type,to_date,remark) VALUES('a1','u1','c1','MONTHLY',?,'将到期账户'),('a2','u2','c2','MONTHLY',?,'在线账户2')`, []any{store.FormatTime(now.Add(5 * time.Hour)), store.FormatTime(now.Add(48 * time.Hour))}},
		{`INSERT INTO transactions(transaction_date,amount,type) VALUES(?,10000,0),(?,2500,1),('2000-01-01 00:00:00',99900,0)`, []any{store.FormatTime(now), store.FormatTime(now)}},
	} {
		if _, err := a.DB.Exec(q.sql, q.args...); err != nil {
			t.Fatal(err)
		}
	}

	s, err := New(a.DB, a, nil)
	if err != nil {
		t.Fatal(err)
	}
	g := guard.New(a.DB)
	conn3, conn2 := int64(3), int64(2)
	if err := g.Report(context.Background(), "203.0.113.1", []guard.AccountReport{
		{AccountNo: "a1", Connections: 3, IPs: []string{"114.114.114.114"}},
		{AccountNo: "a2", Connections: 2, IPs: []string{"8.8.8.8"}},
	}); err != nil {
		t.Fatal(err)
	}
	if err := g.ReportTags(context.Background(), "203.0.113.1", []store.GuardOnlineAccount{
		{AccountNo: "a1", NodeTag: "node_1", ConnectionCount: &conn3, ClientIPs: []string{"114.114.114.114"}},
		{AccountNo: "a2", NodeTag: "node_1", ConnectionCount: &conn2, ClientIPs: []string{"8.8.8.8"}},
	}); err != nil {
		t.Fatal(err)
	}
	s.Guard = g
	h = s.Handler()
	c = login(t, h, "admin@example.com")

	body := do(h, "GET", "/admin", nil, c, nil).Body.String()
	unescaped := html.UnescapeString(body)
	for _, want := range []string{
		"运行情况", "1 部署位 · 1 服务器", "中国香港", "香港-1", "hk1.renew.example", "香港机", "203.0.113.1",
		`"from":"CN","to":"HK"`, `"from":"US","to":"HK"`,
		"将到期账户", "4 小时后", "renew.example", "1 天后", "故障机不可达", "/admin/servers/2/edit", ">75.00<", "100.00", "25.00",
	} {
		if !strings.Contains(unescaped, want) {
			t.Errorf("概览缺少 %q", want)
		}
	}
	for _, forbid := range []string{"服务器健康", "最近事件", "到期重复", "离线节点"} {
		if strings.Contains(body, forbid) {
			t.Errorf("概览不应包含 %q", forbid)
		}
	}

	if err := store.EnsureSettings(context.Background(), a.DB); err != nil {
		t.Fatal(err)
	}
	if err := store.SaveSettings(context.Background(), a.DB, nil, map[string]string{store.StatsMultiplierKey: "10"}); err != nil {
		t.Fatal(err)
	}
	scaled := html.UnescapeString(do(h, "GET", "/admin", nil, c, nil).Body.String())
	for _, want := range []string{
		`20 <span class="dash-kpi-unit">账户</span>`, "50 连接", `>20</a> <span class="dash-kpi-unit">/ 20</span>`,
		`>750.00<`, "1000.00", "250.00",
		`"from":"CN","to":"HK","accounts":10,"connections":30`, `title="20 在线账户"`, `title="50 连接"`,
		"1 部署位 · 1 服务器", "1 台上报",
	} {
		if !strings.Contains(scaled, want) {
			t.Errorf("倍数概览缺少 %q", want)
		}
	}
	ledger := do(h, "GET", "/admin/transactions", nil, c, nil).Body.String()
	for _, want := range []string{
		`text-green">1000.00<`, `text-red">250.00<`, `<div class="h3 ledger-total text-break mb-0">750.00<`,
		"+100.00", "-25.00", // 流水明细保持原值
	} {
		if !strings.Contains(html.UnescapeString(ledger), want) {
			t.Errorf("倍数收支页缺少 %q", want)
		}
	}
}

func TestDashboardLeftText(t *testing.T) {
	now := time.Date(2026, 10, 20, 4, 0, 0, 0, time.UTC) // 上海中午
	for expires, want := range map[string]dashExpiry{
		store.FormatTime(now.Add(30 * time.Minute)): {"1 小时内", true},
		store.FormatTime(now.Add(23 * time.Hour)):   {"23 小时后", true},
		store.FormatTime(now.Add(50 * time.Hour)):   {"2 天后", false},
		"bad": {},
	} {
		if got := accountLeft(expires, now); got != want {
			t.Errorf("accountLeft(%s) = %+v, want %+v", expires, got, want)
		}
	}
	for expire, want := range map[string]dashExpiry{
		"2026-10-20": {"今天", true}, "2026-10-21": {"1 天后", false}, "2026-10-27": {"7 天后", false},
	} {
		if got := renewalLeft(expire, now); got != want {
			t.Errorf("renewalLeft(%s) = %+v, want %+v", expire, got, want)
		}
	}
}

func TestPageVersionDisplay(t *testing.T) {
	_, a := newTestServer(t)
	s, err := New(a.DB, a, nil)
	if err != nil {
		t.Fatal(err)
	}
	s.Version = "v4.0.82"
	h := s.Handler()
	if body := do(h, "GET", "/login", nil, nil, nil).Body.String(); !strings.Contains(body, "v4.0.82") {
		t.Fatal("登录页应显示版本号")
	}
	c := login(t, h, "admin@example.com")
	if body := do(h, "GET", "/admin", nil, c, nil).Body.String(); !strings.Contains(body, ">v4.0.82</footer>") {
		t.Fatal("管理页底部应显示版本号")
	}
}
