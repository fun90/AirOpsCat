package store

import (
	"errors"
	"strings"
	"testing"
	"time"
)

// TestExpireAccountsLeavesDisabledAlone 到期任务只识别新进入到期的账户并置待部署，不改 disabled。
func TestExpireAccountsLeavesDisabledAlone(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "exp")
	nid := deployedNode(t, db, "exp", tag)
	now := time.Now()
	past := now.AddDate(0, 0, -1)
	expired := onboard(t, db, "expired@example.test", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
		in.Account.FromDate, in.Account.ToDate = ptr(past.AddDate(0, -1, 0)), &past
	})
	banned := onboard(t, db, "banned@example.test", func(in *OnboardInput) {
		in.Account.FromDate, in.Account.ToDate, in.Account.Disabled = ptr(past.AddDate(0, -1, 0)), &past, true
	})
	onboard(t, db, "future@example.test", func(in *OnboardInput) { in.Account.ToDate = ptr(now.AddDate(0, 1, 0)) })
	onboard(t, db, "forever@example.test", nil)
	resetDeployed(db, nid)

	ids, err := ExpireAccounts(ctx, db, now)
	if err != nil || len(ids) != 1 || ids[0] != expired.AccountID {
		t.Fatalf("只应返回新到期且未禁用的账户: %v %v", ids, err)
	}
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("到期账户的节点应置待部署")
	}
	for id, want := range map[int64]bool{expired.AccountID: false, banned.AccountID: true} {
		a, _ := AccountByID(ctx, db, id)
		if a.Disabled != want {
			t.Fatalf("账户 %d disabled=%v，到期不应改动禁用状态", id, a.Disabled)
		}
	}
	var handled int
	db.QueryRow(`SELECT count(*) FROM account WHERE expire_handled_to = to_date`).Scan(&handled)
	if handled != 2 {
		t.Fatalf("两个到期账户都应记为已处理: %d", handled)
	}
	resetDeployed(db, nid)
	if ids, err = ExpireAccounts(ctx, db, now); err != nil || len(ids) != 0 || nodeDeployed(t, db, nid) != Deployed {
		t.Fatalf("已处理的到期不应重复部署: %v %v", ids, err)
	}
	// 续期后再次到期，需重新处理
	if err := RenewAccount(ctx, db, expired.AccountID, now.Add(time.Hour), nil, ""); err != nil {
		t.Fatal(err)
	}
	if ids, err = ExpireAccounts(ctx, db, now.AddDate(0, 0, 1)); err != nil || len(ids) != 1 || ids[0] != expired.AccountID {
		t.Fatalf("续期后再到期应重新处理: %v %v", ids, err)
	}
}

// TestRenewKeepsManualDisable 续期不解除手动禁用；禁用账户续期不改变节点配置，无需部署。
func TestRenewKeepsManualDisable(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "renew")
	nid := deployedNode(t, db, "renew", tag)
	past := time.Now().Add(-time.Hour)
	r := onboard(t, db, "banned@example.test", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
		in.Account.FromDate, in.Account.ToDate, in.Account.Disabled = ptr(past.AddDate(0, -1, 0)), &past, true
	})
	resetDeployed(db, nid)
	if err := RenewAccount(ctx, db, r.AccountID, time.Now().AddDate(0, 1, 0), nil, ""); err != nil {
		t.Fatal(err)
	}
	a, _ := AccountByID(ctx, db, r.AccountID)
	if !a.Disabled || !a.ToDate.After(time.Now()) {
		t.Fatalf("续期应只改到期时间: %+v", a)
	}
	if nodeDeployed(t, db, nid) != Deployed {
		t.Fatal("禁用账户续期不应置待部署")
	}
}

// TestRenewRejectsPermanentAccount 长期有效账户续期会悄悄变成有到期日，必须拒绝。
func TestRenewRejectsPermanentAccount(t *testing.T) {
	db, ctx := testDB(t)
	r := onboard(t, db, "forever@example.test", nil)
	amount := int64(100)
	err := RenewAccount(ctx, db, r.AccountID, time.Now().AddDate(0, 1, 0), &amount, "AliPay")
	if !errors.Is(err, ErrInvalid) || !strings.Contains(err.Error(), "该账户长期有效，如需设置到期日请编辑账户") {
		t.Fatalf("err = %v", err)
	}
	a, _ := AccountByID(ctx, db, r.AccountID)
	var n int
	db.QueryRow(`SELECT count(*) FROM transactions WHERE business_table='account' AND business_id=?`, r.AccountID).Scan(&n)
	if a.ToDate != nil || n != 0 {
		t.Fatalf("拒绝续期不得写入: to=%v 流水=%d", a.ToDate, n)
	}
}

// TestUpdateAccountRequiresUser 编辑时所属用户为空不得把 user_id 写成 NULL。
func TestUpdateAccountRequiresUser(t *testing.T) {
	db, ctx := testDB(t)
	r := onboard(t, db, "owner@example.test", nil)
	a, _ := AccountByID(ctx, db, r.AccountID)
	edit := *a
	edit.UserID = 0
	if err := UpdateAccount(ctx, db, a.ID, &edit); !errors.Is(err, ErrInvalid) || !strings.Contains(err.Error(), "请选择所属用户") {
		t.Fatalf("err = %v", err)
	}
	if got, _ := AccountByID(ctx, db, a.ID); got.UserID != r.UserID {
		t.Fatalf("user_id 被改为 %d", got.UserID)
	}
}

// TestMaxConnectionsChangeKeepsDeployment 最大连接数只供 guard 实时读取，不进节点配置。
func TestMaxConnectionsChangeKeepsDeployment(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "conn")
	nid := deployedNode(t, db, "conn", tag)
	r := onboard(t, db, "conn@example.test", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
		in.Account.MaxConnections = 10
	})
	resetDeployed(db, nid)
	a, _ := AccountByID(ctx, db, r.AccountID)
	edit := *a
	edit.MaxConnections = 20
	if err := UpdateAccount(ctx, db, a.ID, &edit); err != nil {
		t.Fatal(err)
	}
	if nodeDeployed(t, db, nid) != Deployed {
		t.Fatal("修改最大连接数不应置待部署")
	}
	if got, _ := AccountByID(ctx, db, a.ID); got.MaxConnections != 20 {
		t.Fatal("最大连接数未保存")
	}
}

// TestEnableExpiredAccountKeepsDeployment 启用已到期账户不改变节点配置；有效账户启停仍置待部署。
func TestEnableExpiredAccountKeepsDeployment(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "toggle")
	nid := deployedNode(t, db, "toggle", tag)
	past := time.Now().AddDate(0, 0, -1)
	r := onboard(t, db, "toggle@example.test", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
		in.Account.FromDate, in.Account.ToDate, in.Account.Disabled = ptr(past.AddDate(0, -1, 0)), &past, true
	})
	resetDeployed(db, nid)
	if err := SetAccountDisabled(ctx, db, r.AccountID, false); err != nil {
		t.Fatal(err)
	}
	if a, _ := AccountByID(ctx, db, r.AccountID); a.Disabled {
		t.Fatal("未启用")
	}
	if nodeDeployed(t, db, nid) != Deployed {
		t.Fatal("启用已到期账户不应置待部署")
	}
}

// TestAccountExpiredAndDisabledAreIndependent 「已到期」与「已禁用」各自独立筛选和统计。
func TestAccountExpiredAndDisabledAreIndependent(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Now()
	past := now.AddDate(0, 0, -1)
	expired := onboard(t, db, "expired@example.test", func(in *OnboardInput) {
		in.Account.FromDate, in.Account.ToDate = ptr(past.AddDate(0, -1, 0)), &past
	})
	both := onboard(t, db, "both@example.test", func(in *OnboardInput) {
		in.Account.FromDate, in.Account.ToDate, in.Account.Disabled = ptr(past.AddDate(0, -1, 0)), &past, true
	})
	banned := onboard(t, db, "banned@example.test", func(in *OnboardInput) {
		in.Account.ToDate, in.Account.Disabled = ptr(now.AddDate(0, 1, 0)), true
	})
	active := onboard(t, db, "active@example.test", nil)
	ids := func(status string) map[int64]bool {
		list, _, err := AccountList(ctx, db, AccountFilter{Status: status}, 1, 50)
		if err != nil {
			t.Fatal(err)
		}
		m := map[int64]bool{}
		for _, a := range list {
			m[a.ID] = true
		}
		return m
	}
	if got := ids("expired"); len(got) != 2 || !got[expired.AccountID] || !got[both.AccountID] {
		t.Fatalf("已到期筛选: %v", got)
	}
	if got := ids("disabled"); len(got) != 2 || !got[both.AccountID] || !got[banned.AccountID] {
		t.Fatalf("已禁用筛选: %v", got)
	}
	if got := ids("active"); len(got) != 1 || !got[active.AccountID] {
		t.Fatalf("有效筛选: %v", got)
	}
	d, err := DashboardLoad(ctx, db, now, 1)
	if err != nil {
		t.Fatal(err)
	}
	if d.AccountsTotal != 4 || d.AccountsActive != 1 || d.AccountsDisabled != 2 || d.AccountsExpired != 2 {
		t.Fatalf("概览统计: total=%d active=%d disabled=%d expired=%d", d.AccountsTotal, d.AccountsActive, d.AccountsDisabled, d.AccountsExpired)
	}
}

func TestAccountAndServerNoonGracePeriod(t *testing.T) {
	shTime := func(s string) time.Time {
		t.Helper()
		tm, err := ParseTime(s)
		if err != nil {
			t.Fatal(err)
		}
		return tm
	}

	// 服务器：当天 12:00 前仍可用，12:00 及之后不再可用
	if got := ServerUsableMinDay(shTime("2026-11-24 00:00:00")); got != "2026-11-24" {
		t.Fatalf("00:00 应含当天: %s", got)
	}
	if got := ServerUsableMinDay(shTime("2026-11-24 11:59:59")); got != "2026-11-24" {
		t.Fatalf("11:59 应含当天: %s", got)
	}
	if got := ServerUsableMinDay(shTime("2026-11-24 12:00:00")); got != "2026-11-25" {
		t.Fatalf("12:00 应排除当天: %s", got)
	}

	// 账户：当天 00:00~12:00 到期的账户在 12:00 前继续可用，12:00 统一处理；12:00 后到期的按实际时刻生效
	t0 := shTime("2026-11-24 00:00:00")
	t9 := shTime("2026-11-24 09:00:00")
	t12 := shTime("2026-11-24 12:00:00")
	t15 := shTime("2026-11-24 15:00:00")
	yest := shTime("2026-11-23 23:59:59")

	at08 := shTime("2026-11-24 08:00:00")
	at12 := shTime("2026-11-24 12:00:00")
	at16 := shTime("2026-11-24 16:00:00")

	if !IsAccountExpired(&yest, at08) {
		t.Fatal("昨日到期的账户 08:00 应已过期")
	}
	if IsAccountExpired(&t0, at08) || IsAccountExpired(&t9, at08) || IsAccountExpired(&t12, at08) {
		t.Fatal("0~12 点到期的账户在 08:00 不应过期")
	}
	if !IsAccountExpired(&t0, at12) || !IsAccountExpired(&t9, at12) || !IsAccountExpired(&t12, at12) {
		t.Fatal("0~12 点到期的账户在 12:00 应过期")
	}
	if IsAccountExpired(&t15, at12) {
		t.Fatal("15:00 到期的账户在 12:00 不应过期")
	}
	if !IsAccountExpired(&t15, at16) {
		t.Fatal("15:00 到期的账户在 16:00 应过期")
	}

	db, ctx := testDB(t)
	tag := mustTag(t, db, "grace")
	nid := deployedNode(t, db, "grace", tag)
	onboard(t, db, "grace9@example.test", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
		in.Account.FromDate, in.Account.ToDate = ptr(yest.AddDate(0, -1, 0)), &t9
	})
	resetDeployed(db, nid)

	if ids, err := ExpireAccounts(ctx, db, at08); err != nil || len(ids) != 0 {
		t.Fatalf("12 点前不应处理当天 0~12 点到期账户: %v %v", ids, err)
	}
	if ids, err := ExpireAccounts(ctx, db, at12); err != nil || len(ids) != 1 {
		t.Fatalf("12 点应处理当天 0~12 点到期账户: %v %v", ids, err)
	}
}
