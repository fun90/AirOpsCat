package store

import (
	"testing"
	"time"
)

// 改周期类型立即开始新周期：当期行截断到修改时刻保留为历史，新周期用量从 0 计、配额取账户配额。
func TestPeriodTypeChangeStartsNewPeriod(t *testing.T) {
	db, ctx := testDB(t)
	now := shTime(t, "2026-05-20 15:30")
	old := nowFunc
	nowFunc = func() time.Time { return now }
	t.Cleanup(func() { nowFunc = old })
	id := onboard(t, db, "period@example.test", func(in *OnboardInput) {
		from := shTime(t, "2026-01-05 00:00")
		in.Account.FromDate, in.Account.Bandwidth, in.Account.PeriodType = &from, 1000, PeriodYearly
	}).AccountID
	db.Exec(`UPDATE account SET traffic_anchor='2026-01-05 00:00:00' WHERE id=?`, id)
	db.Exec(`INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes)
		VALUES(?,'2026-01-05 00:00:00','2027-01-05 00:00:00',1200,?,?)`, id, int64(300)<<30, int64(500)<<30)

	a, _ := AccountByID(ctx, db, id)
	edit := *a
	edit.PeriodType = PeriodMonthly
	edit.Bandwidth = 100
	if err := UpdateAccount(ctx, db, id, &edit); err != nil {
		t.Fatal(err)
	}
	u, err := AccountUsage(ctx, db, id, now)
	if err != nil {
		t.Fatal(err)
	}
	if u.UsedBytes != 0 || u.QuotaGB != 100 || !u.PeriodStart.Equal(now) || u.PeriodEnd.Format("2006-01-02 15:04") != "2026-06-20 15:30" {
		t.Fatalf("应立即开始新的月度周期：%+v", u)
	}
	var end string
	var up int64
	db.QueryRow(`SELECT period_end, upload_bytes FROM account_traffic_stats WHERE account_id=? AND period_start='2026-01-05 00:00:00'`, id).Scan(&end, &up)
	if end != "2026-05-20 15:30:00" || up != int64(300)<<30 {
		t.Fatal("旧周期应截断到修改时刻并保留用量", end, up)
	}
	// 一个月后进入下一个月度周期
	next, _ := AccountUsage(ctx, db, id, shTime(t, "2026-06-21 00:00"))
	if next.PeriodStart.Format("2006-01-02 15:04") != "2026-06-20 15:30" {
		t.Fatalf("%+v", next)
	}
	var logs int
	db.QueryRow(`SELECT count(*) FROM system_log WHERE category='ACCOUNT' AND resource_id=? AND message LIKE '%周期%'`, id).Scan(&logs)
	if logs != 1 {
		t.Fatal("应记录周期变更日志", logs)
	}

	// 周期类型不变的保存不影响周期
	again, _ := AccountByID(ctx, db, id)
	again.Remark = "改备注"
	nowFunc = func() time.Time { return now.Add(time.Hour) }
	if err := UpdateAccount(ctx, db, id, again); err != nil {
		t.Fatal(err)
	}
	if u2, _ := AccountUsage(ctx, db, id, now.Add(time.Hour)); !u2.PeriodStart.Equal(now) {
		t.Fatalf("未改周期类型不应移动周期：%+v", u2)
	}

	// 同一秒内改回，零长度的周期行被删除，不与新周期冲突
	nowFunc = func() time.Time { return now.Add(2 * time.Hour) }
	db.Exec(`INSERT INTO account_traffic_stats(account_id,period_start,period_end,upload_bytes,download_bytes) VALUES(?,?,?,0,0)`,
		id, FormatTime(now.Add(2*time.Hour)), FormatTime(now.Add(2*time.Hour).AddDate(0, 1, 0)))
	back, _ := AccountByID(ctx, db, id)
	back.PeriodType = PeriodYearly
	if err := UpdateAccount(ctx, db, id, back); err != nil {
		t.Fatal(err)
	}
	if u3, _ := AccountUsage(ctx, db, id, now.Add(2*time.Hour)); u3.PeriodEnd.Format("2006-01-02") != "2027-05-20" || u3.UsedBytes != 0 {
		t.Fatalf("改回年度应开始新的年度周期：%+v", u3)
	}
}
