package store

import (
	"database/sql"
	"testing"
	"time"
)

func TestAlertStateMachine(t *testing.T) {
	db, ctx := testDB(t)
	k := AlertKey{Type: "server-cpu", ResourceType: "server", ResourceID: 1, ResourceKey: "HK", Fingerprint: "cpu"}
	t0 := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	iv := time.Hour
	id, notify, err := AlertTrigger(ctx, db, k, "warning", 0.95, 0.9, "CPU 高", iv, t0)
	if err != nil || !notify {
		t.Fatalf("首次应通知: %v %v", notify, err)
	}
	// 发送失败未标记通知：下一轮继续通知
	if _, notify, _ = AlertTrigger(ctx, db, k, "warning", 0.96, 0.9, "CPU 高", iv, t0.Add(time.Minute)); !notify {
		t.Fatal("未成功通知过应继续通知")
	}
	AlertMarkNotified(ctx, db, id, t0.Add(time.Minute))
	if _, notify, _ = AlertTrigger(ctx, db, k, "warning", 0.96, 0.9, "x", iv, t0.Add(30*time.Minute)); notify {
		t.Fatal("节流间隔内不应通知")
	}
	if _, notify, _ = AlertTrigger(ctx, db, k, "warning", 0.96, 0.9, "x", iv, t0.Add(61*time.Minute)); !notify {
		t.Fatal("超过间隔应再次通知")
	}
	// 确认后持续静默，即便超过间隔
	if err := AlertAcknowledge(ctx, db, id, "admin", t0.Add(2*time.Hour)); err != nil {
		t.Fatal(err)
	}
	if _, notify, _ = AlertTrigger(ctx, db, k, "warning", 0.97, 0.9, "x", iv, t0.Add(5*time.Hour)); notify {
		t.Fatal("已确认的告警不应通知")
	}
	if AlertAcknowledge(ctx, db, id+99, "admin", t0) == nil {
		t.Fatal("不存在的告警不能确认")
	}
	// 恢复（不通知）后重新触发：重置为 ACTIVE 并通知
	res, err := AlertResolve(ctx, db, "server-cpu", "server", 1, t0.Add(6*time.Hour))
	if err != nil || len(res) != 1 || res[0].Status != AlertAcknowledged {
		t.Fatalf("%v %+v", err, res)
	}
	if again, _ := AlertResolve(ctx, db, "server-cpu", "server", 1, t0); len(again) != 0 {
		t.Fatal("已恢复的不应重复返回")
	}
	if _, notify, _ = AlertTrigger(ctx, db, k, "warning", 0.99, 0.9, "again", iv, t0.Add(7*time.Hour)); !notify {
		t.Fatal("恢复后再次触发应通知")
	}
	list, total, _ := AlertList(ctx, db, AlertActive, "", 1, 20)
	if total != 1 || list[0].Count != 1 || list[0].AckBy != "" || list[0].Summary != "again" {
		t.Fatalf("重置不完整: %+v", list)
	}
	if alertActiveCount(db) != 1 {
		t.Fatal("进行中计数")
	}
	if err := AlertRecoverManual(ctx, db, id, t0); err != nil {
		t.Fatal(err)
	}
	if AlertRecoverManual(ctx, db, id, t0) == nil {
		t.Fatal("重复恢复应报不存在")
	}
}

func TestResolveMissingAndSources(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	for _, id := range []int64{1, 2} {
		AlertTrigger(ctx, db, AlertKey{Type: "x", ResourceType: "account", ResourceID: id, Fingerprint: "f"}, "", 1, 1, "s", time.Hour, now)
	}
	out, err := AlertResolveMissing(ctx, db, "x", "account", map[int64]bool{1: true}, now)
	if err != nil || len(out) != 1 || out[0].ResourceID != 2 {
		t.Fatalf("%v %+v", err, out)
	}
	mustExec(t, db, `INSERT INTO server(id,ip,name,expire_date) VALUES(1,'1.1.1.1','HK','2026-10-20 00:00:00'),(2,'2.2.2.2','US','2027-01-01'),(3,'3.3.3.3','OLD','2026-10-01'),(4,'4.4.4.4','OFF','2026-10-20')`)
	mustExec(t, db, `UPDATE server SET disabled=1 WHERE id=4`)
	mustExec(t, db, `INSERT INTO domain(id,domain,expire_date) VALUES(1,'a.com','2026-10-20'),(2,'b.com','2026-10-22')`)
	items, err := ExpiringResources(ctx, db, now)
	if err != nil || len(items) != 2 {
		t.Fatalf("只应返回当天到期且启用的资源: %v %+v", err, items)
	}
	names := map[string]bool{}
	for _, it := range items {
		names[it.Name] = true
	}
	if !names["HK"] || !names["a.com"] {
		t.Fatalf("%v", names)
	}
}

func TestOverQuotaAccounts(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	mustExec(t, db, `INSERT INTO account(id,account_no,remark,uuid,auth_code,period_type,bandwidth) VALUES(1,'a1','over','u1','c1','MONTHLY',1),(2,'a2','under','u2','c2','MONTHLY',10),(3,'a3','unlimited','u3','c3','MONTHLY',0),(4,'a4','off','u4','c4','MONTHLY',1)`)
	mustExec(t, db, `UPDATE account SET disabled=1 WHERE id=4`)
	for _, id := range []int{1, 2, 3, 4} {
		mustExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes) SELECT id,'2026-10-01 00:00:00','2026-11-01 00:00:00',bandwidth,1073741824,1073741824 FROM account WHERE id=?`, id)
	}
	got, err := OverQuotaAccounts(ctx, db, now)
	if err != nil || len(got) != 1 || got[0].AccountNo != "a1" || got[0].UsedGB != 2 || got[0].QuotaGB != 1 {
		t.Fatalf("%v %+v", err, got)
	}
}

func alertActiveCount(db *sql.DB) (n int) {
	db.QueryRow(`SELECT count(*) FROM alert_state WHERE status='ACTIVE'`).Scan(&n)
	return
}
