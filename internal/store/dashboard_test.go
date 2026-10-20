package store

import (
	"fmt"
	"reflect"
	"testing"
	"time"
)

func TestDashboardLoad(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Date(2026, 10, 20, 4, 0, 0, 0, time.UTC) // 上海中午
	d, err := DashboardLoad(ctx, db, now)
	if err != nil || !reflect.DeepEqual(d, &Dashboard{}) {
		t.Fatalf("空库: %+v, %v", d, err)
	}
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.ExecContext(ctx, q, args...); err != nil {
			t.Fatal(err)
		}
	}
	// 长期有效、恰好到期、未来、已过期，以及禁用且过期的账户。
	for i, a := range []struct {
		date     any
		disabled int
	}{
		{nil, 0}, {FormatTime(now), 0}, {FormatTime(now.Add(time.Second)), 0},
		{FormatTime(now.Add(-time.Second)), 0}, {FormatTime(now.Add(-time.Hour)), 1},
	} {
		key := fmt.Sprint(i)
		exec(`INSERT INTO account(account_no,uuid,auth_code,period_type,to_date,disabled) VALUES(?,?,?,'MONTHLY',?,?)`, key, key, key, a.date, a.disabled)
	}
	// 日期窗口包含今天和第七天全天；禁用/外部、已过期、窗口外和无日期不计到期。
	for _, s := range []struct {
		date               any
		disabled, external int
	}{
		{"2026-10-20", 0, 0}, {"2026-10-27 23:59:59", 0, 0},
		{"2026-10-28", 0, 0}, {"2026-10-19", 0, 0}, {nil, 0, 0}, {"", 0, 0},
		{"2026-10-21", 1, 0}, {"2026-10-21", 0, 1}, {"2026-10-21", 1, 1},
	} {
		exec(`INSERT INTO server(ip,expire_date,disabled,external_server) VALUES('203.0.113.1',?,?,?)`, s.date, s.disabled, s.external)
	}
	exec(`INSERT INTO node(id,disabled) VALUES(1,0),(2,1),(3,0)`)
	exec(`INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,1,0),(1,2,0),(2,1,1),(3,1,2)`)
	for i, status := range []string{AlertActive, AlertAcknowledged, AlertRecovered, AlertActive, AlertAcknowledged, AlertRecovered} {
		exec(`INSERT INTO alert_state(alert_type,resource_type,resource_id,fingerprint,status,last_triggered_time)
			VALUES('server-expiring','server',?,'expiry',?,?)`, i+1, status, FormatTime(now.Add(time.Duration(i/2)*time.Minute)))
	}
	d, err = DashboardLoad(ctx, db, now)
	if err != nil {
		t.Fatal(err)
	}
	want := &Dashboard{
		AccountsTotal: 5, AccountsActive: 3, AccountsDisabled: 1, AccountsExpired: 1,
		NodesEnabled: 2, NodesDisabled: 1, PendingPlacements: 2,
		ServersEnabled: 7, ServersExternal: 2, ServersExpiring: 2,
		AlertsActive: 2, AlertsAcknowledged: 2,
	}
	if len(d.Expiring24Hours) != 2 || d.Expiring24Hours[0].AccountNo != "1" {
		t.Fatalf("未来24小时内到期账户: %+v", d.Expiring24Hours)
	}
	d.Expiring24Hours = nil
	alerts := d.RecentAlerts
	d.RecentAlerts = nil
	if !reflect.DeepEqual(d, want) {
		t.Fatalf("统计: %+v, want %+v", d, want)
	}
	if len(alerts) != 5 {
		t.Fatalf("近期告警数量: %d", len(alerts))
	}
	for i, a := range alerts {
		if a.ID != int64(6-i) {
			t.Fatalf("近期告警排序: 第 %d 条 ID=%d", i, a.ID)
		}
	}
}

func TestDashboardLoadReturnsErrors(t *testing.T) {
	db, ctx := testDB(t)
	if err := db.Close(); err != nil {
		t.Fatal(err)
	}
	if d, err := DashboardLoad(ctx, db, time.Now()); err == nil || d != nil {
		t.Fatalf("数据库错误不可当作零统计: %+v, %v", d, err)
	}
}

func TestDashboardRolling24Hours(t *testing.T) {
	db, ctx := testDB(t)
	for i, date := range []string{"2026-10-20 12:00:00", "2026-10-21 11:59:59", "2026-10-20 11:59:59", "2026-10-21 12:00:00"} {
		k := fmt.Sprint(i)
		if _, err := db.Exec(`INSERT INTO account(account_no,uuid,auth_code,period_type,to_date)VALUES(?,?,?,'MONTHLY',?)`, k, k, k, date); err != nil {
			t.Fatal(err)
		}
	}
	d, err := DashboardLoad(ctx, db, time.Date(2026, 10, 20, 4, 0, 0, 0, time.UTC))
	if err != nil || len(d.Expiring24Hours) != 2 || d.Expiring24Hours[0].AccountNo != "0" || d.Expiring24Hours[1].AccountNo != "1" {
		t.Fatal(d, err)
	}
}
