package store

import (
	"strings"
	"testing"
	"time"
)

func shTime(t *testing.T, v string) time.Time {
	t.Helper()
	at, err := time.ParseInLocation("2006-01-02 15:04", v, Shanghai)
	if err != nil {
		t.Fatal(err)
	}
	return at
}

func TestAddMonthsClampsMonthEnd(t *testing.T) {
	for _, c := range []struct {
		from string
		n    int
		want string
	}{
		{"2026-01-31 10:30", 1, "2026-02-28 10:30"},
		{"2028-01-31 10:30", 1, "2028-02-29 10:30"},
		{"2026-03-31 00:00", 1, "2026-04-30 00:00"},
		{"2026-08-31 23:59", 6, "2027-02-28 23:59"},
		{"2026-12-15 08:00", 1, "2027-01-15 08:00"},
		{"2026-05-31 08:00", 12, "2027-05-31 08:00"},
		{"2026-03-31 08:00", -1, "2026-02-28 08:00"},
	} {
		// 以 UTC 传入也按上海日历计算
		if got := AddMonths(shTime(t, c.from).UTC(), c.n).Format("2006-01-02 15:04"); got != c.want {
			t.Errorf("%s +%d = %s, want %s", c.from, c.n, got, c.want)
		}
	}
}

func TestBatchRenewAccounts(t *testing.T) {
	db, ctx := testDB(t)
	now := shTime(t, "2026-01-31 12:00")
	old := nowFunc
	nowFunc = func() time.Time { return now }
	t.Cleanup(func() { nowFunc = old })
	tag := mustTag(t, db, "batch")
	nid := deployedNode(t, db, "batch", tag)
	future, past := shTime(t, "2026-03-31 09:00"), shTime(t, "2026-01-01 09:00")
	mk := func(email string, to *time.Time) int64 {
		from := shTime(t, "2025-12-01 09:00")
		return onboard(t, db, email, func(in *OnboardInput) {
			in.Account.TagIDs, in.Account.FromDate, in.Account.ToDate = []int64{tag}, &from, to
		}).AccountID
	}
	active, expired, forever := mk("a@example.test", &future), mk("b@example.test", &past), mk("c@example.test", nil)
	resetDeployed(db, nid)

	amount := int64(3000)
	if _, _, err := BatchRenewAccounts(ctx, db, []int64{active}, 1, &amount, ""); err == nil {
		t.Fatal("记账缺付款方式应拒绝")
	}
	renewed, skipped, err := BatchRenewAccounts(ctx, db, []int64{active, expired, forever}, 1, &amount, "WeChat")
	if err != nil || renewed != 2 || skipped != 1 {
		t.Fatal(renewed, skipped, err)
	}
	to := func(id int64) string {
		a, _ := AccountByID(ctx, db, id)
		if a.ToDate == nil {
			return ""
		}
		return a.ToDate.In(Shanghai).Format("2006-01-02 15:04")
	}
	// 未到期从原到期起算并月末钳制；已到期从当前起算（1/31 + 1 月 = 2 月末）
	if to(active) != "2026-04-30 09:00" || to(expired) != "2026-02-28 12:00" || to(forever) != "" {
		t.Fatal(to(active), to(expired), to(forever))
	}
	// 已到期账户恢复有效需部署；每户记一笔同金额流水
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("恢复有效的账户应把相关节点置为待部署")
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM transactions WHERE business_table='account' AND amount=3000 AND payment_method='WeChat' AND description='账号：1月'`).Scan(&n)
	if n != 2 {
		t.Fatal("每户应记一笔流水", n)
	}
	incomes, err := AccountLastIncomes(ctx, db, []int64{active, forever})
	if err != nil || incomes[active] != (LastIncome{Cents: 3000, Method: "WeChat", Months: 1}) {
		t.Fatal(incomes, err)
	}
	// 任一账户失败整体回滚
	if _, _, err := BatchRenewAccounts(ctx, db, []int64{active, 9999}, 1, nil, ""); err == nil {
		t.Fatal("不存在的账户应整体失败")
	}
	if to(active) != "2026-04-30 09:00" {
		t.Fatal("失败应回滚", to(active))
	}
}

func TestBatchAccountToggleDeleteAndRemoveTags(t *testing.T) {
	db, ctx := testDB(t)
	t1, t2 := mustTag(t, db, "t1"), mustTag(t, db, "t2")
	nid := deployedNode(t, db, "n", t1)
	a := onboard(t, db, "a@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{t1, t2} }).AccountID
	b := onboard(t, db, "b@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{t2} }).AccountID
	resetDeployed(db, nid)

	updated, unchanged, err := BatchAccountTags(ctx, db, []int64{a, b}, []int64{t1}, "remove")
	if err != nil || updated != 1 || unchanged != 1 {
		t.Fatal(updated, unchanged, err)
	}
	if acc, _ := AccountByID(ctx, db, a); len(acc.TagIDs) != 1 || acc.TagIDs[0] != t2 {
		t.Fatal("应只移除所选标签", acc.TagIDs)
	}
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("移除标签应按旧标签置待部署")
	}
	if _, _, err := BatchAccountTags(ctx, db, []int64{a}, nil, "REMOVE"); err == nil {
		t.Fatal("移除模式须选择标签")
	}

	changed, err := BatchSetAccountsDisabled(ctx, db, []int64{a, b}, true)
	if err != nil || changed != 2 {
		t.Fatal(changed, err)
	}
	if changed, _ = BatchSetAccountsDisabled(ctx, db, []int64{a, b}, true); changed != 0 {
		t.Fatal("重复禁用不应计为变化")
	}
	if _, err := BatchSetAccountsDisabled(ctx, db, []int64{a, 9999}, false); err == nil {
		t.Fatal("不存在的账户应整体失败")
	}
	if acc, _ := AccountByID(ctx, db, a); !acc.Disabled {
		t.Fatal("失败应回滚")
	}
	if n, err := BatchDeleteAccounts(ctx, db, []int64{a, b}); err != nil || n != 2 {
		t.Fatal(n, err)
	}
	if acc, _ := AccountByID(ctx, db, a); acc != nil {
		t.Fatal("应已删除")
	}
}

func TestAccountPeriodQuotaAndReset(t *testing.T) {
	db, ctx := testDB(t)
	at := shTime(t, "2026-02-10 12:00")
	id := onboard(t, db, "q@example.test", func(in *OnboardInput) {
		from := shTime(t, "2026-01-05 00:00")
		in.Account.FromDate, in.Account.Bandwidth = &from, 100
	}).AccountID
	db.Exec(`UPDATE account SET traffic_anchor='2026-01-05 00:00:00' WHERE id=?`, id)
	// 当期行不存在：按周期创建并只改快照配额
	quota, err := AddAccountPeriodQuota(ctx, db, id, 50, at)
	if err != nil || quota != 150 {
		t.Fatal(quota, err)
	}
	u, _ := AccountUsage(ctx, db, id, at)
	if u.QuotaGB != 150 || u.PeriodStart.Format("2006-01-02") != "2026-02-05" {
		t.Fatalf("%+v", u)
	}
	if acc, _ := AccountByID(ctx, db, id); acc.Bandwidth != 100 {
		t.Fatal("不应改账户配额", acc.Bandwidth)
	}
	db.Exec(`UPDATE account_traffic_stats SET upload_bytes=?, download_bytes=? WHERE account_id=?`, int64(100)<<30, int64(60)<<30, id)
	limits, _ := EffectiveLimitOverrides(ctx, db, at)
	if quota, _ = AddAccountPeriodQuota(ctx, db, id, 20, at); quota != 170 {
		t.Fatal(quota)
	}
	after, _ := EffectiveLimitOverrides(ctx, db, at)
	acc, _ := AccountByID(ctx, db, id)
	if !limits[acc.AccountNo].OverQuota || after[acc.AccountNo].OverQuota {
		t.Fatal("加量后应解除超额", limits[acc.AccountNo], after[acc.AccountNo])
	}
	used, err := ResetAccountPeriodUsage(ctx, db, id, at)
	if err != nil || used != int64(160)<<30 {
		t.Fatal(used, err)
	}
	if u, _ = AccountUsage(ctx, db, id, at); u.UsedBytes != 0 || u.QuotaGB != 170 {
		t.Fatalf("%+v", u)
	}
	var logs int
	db.QueryRow(`SELECT count(*) FROM system_log WHERE category='ACCOUNT' AND resource_id=?`, id).Scan(&logs)
	if logs != 3 {
		t.Fatal("加量与重置应写系统日志", logs)
	}
	db.Exec(`UPDATE account SET bandwidth=0 WHERE id=?`, id)
	db.Exec(`DELETE FROM account_traffic_stats WHERE account_id=?`, id)
	if _, err := AddAccountPeriodQuota(ctx, db, id, 10, at); err == nil || !strings.Contains(err.Error(), "不限量") {
		t.Fatal("不限量账户不应加量", err)
	}
}
