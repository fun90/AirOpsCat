package web

import (
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func TestDashboardPage(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	if body := do(h, "GET", "/admin", nil, c, nil).Body.String(); !strings.Contains(body, "暂无待处理事项") || !strings.Contains(body, "暂无服务器") {
		t.Fatal("空库概览应显示空状态")
	}
	now := time.Now()
	local := now.In(store.Shanghai)
	for _, q := range []struct {
		sql  string
		args []any
	}{
		{`INSERT INTO server(id,name,ip) VALUES(1,'正常机','203.0.113.1'),(2,'故障机','203.0.113.2')`, nil},
		{`INSERT INTO server_metrics(server_id,kind,sample_time,cpu_usage,memory_usage) VALUES(1,'monitor',?,0.1,0.2),(2,'monitor',?,0.1,0.2)`, []any{store.FormatTime(now), store.FormatTime(now)}},
		{`INSERT INTO alert_state(alert_type,resource_type,resource_id,fingerprint,status,summary,last_triggered_time) VALUES(?,'server',2,'x','ACTIVE','故障机不可达',?),('server-expiring','server',1,'e','ACTIVE','到期重复',?)`, []any{alertUnreachable, store.FormatTime(now), store.FormatTime(now)}},
		{`INSERT INTO account(account_no,uuid,auth_code,period_type,to_date,remark) VALUES('a1','u1','c1','MONTHLY',?,'将到期账户')`, []any{store.FormatTime(now.Add(5 * time.Hour))}},
		{`INSERT INTO domain(id,domain,expire_date) VALUES(1,'renew.example',?)`, []any{local.AddDate(0, 0, 1).Format("2006-01-02")}},
		{`INSERT INTO transactions(transaction_date,amount,type) VALUES(?,10000,0),(?,2500,1),('2000-01-01 00:00:00',99900,0)`, []any{store.FormatTime(now), store.FormatTime(now)}},
	} {
		if _, err := a.DB.Exec(q.sql, q.args...); err != nil {
			t.Fatal(err)
		}
	}
	body := do(h, "GET", "/admin", nil, c, nil).Body.String()
	for _, want := range []string{"将到期账户", "4 小时后", "renew.example", "1 天后", "故障机不可达", "/admin/servers/2/edit", ">75.00<", "100.00", "25.00"} {
		if !strings.Contains(body, want) {
			t.Errorf("概览缺少 %q", want)
		}
	}
	if strings.Contains(body, "到期重复") && strings.Index(body, "到期重复") < strings.Index(body, "最近事件") {
		t.Error("到期类告警不应进入需要处理")
	}
	if bad, ok := strings.Index(body, ">故障机<"), strings.Index(body, ">正常机<"); bad < 0 || ok < 0 || bad > ok {
		t.Error("有进行中告警的服务器应排在健康列表前面")
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
