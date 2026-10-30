package store

import (
	"context"
	"database/sql"
	"errors"
	"strings"
	"testing"
	"time"
)

func baseAccount() Account {
	return Account{NodeMultiple: 2, Remark: "客户A", Bandwidth: 500, MaxIps: 2}
}

func mustTag(t *testing.T, db *sql.DB, name string) int64 {
	t.Helper()
	tag := &Tag{Name: name}
	if err := SaveTag(context.Background(), db, tag); err != nil {
		t.Fatal(err)
	}
	return tag.ID
}

// deployedNode 建一个绑定了标签、已部署的节点，返回节点 ID。
func deployedNode(t *testing.T, db *sql.DB, name string, tagID int64) int64 {
	t.Helper()
	res, err := db.Exec(`INSERT INTO node (name, type, protocol, port) VALUES (?, 0, 'vless', 443)`, name)
	if err != nil {
		t.Fatal(err)
	}
	nid, _ := res.LastInsertId()
	sid := addServer(t, db, name, "HK", 0, false, false)
	db.Exec(`INSERT INTO node_server (node_id, server_id, deployed) VALUES (?, ?, 1)`, nid, sid)
	db.Exec(`INSERT INTO node_tag (node_id, tag_id) VALUES (?, ?)`, nid, tagID)
	return nid
}

func nodeDeployed(t *testing.T, db *sql.DB, nid int64) int {
	t.Helper()
	var d int
	if err := db.QueryRow(`SELECT deployed FROM node_server WHERE node_id = ?`, nid).Scan(&d); err != nil {
		t.Fatal(err)
	}
	return d
}

func resetDeployed(db *sql.DB, nid int64) {
	db.Exec(`UPDATE node_server SET deployed = 1 WHERE node_id = ?`, nid)
}

func onboard(t *testing.T, db *sql.DB, email string, mut func(*OnboardInput)) *OnboardResult {
	t.Helper()
	in := OnboardInput{NewUser: &ManagedUser{Email: email}, Password: "secret123", Account: baseAccount()}
	if mut != nil {
		mut(&in)
	}
	r, err := Onboard(context.Background(), db, in)
	if err != nil {
		t.Fatal(err)
	}
	return r
}

func TestOnboardCreatesUserAccountTagsAndTransaction(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "hk")
	nid := deployedNode(t, db, "n1", tag)
	amount := int64(3500)
	from := time.Now()
	to := from.AddDate(0, 3, 0)
	r := onboard(t, db, " A@Example.com ", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag, tag}
		in.Account.FromDate, in.Account.ToDate = &from, &to
		in.AmountCents, in.PaymentMethod = &amount, "WeChat"
	})
	a, err := AccountByID(ctx, db, r.AccountID)
	if err != nil || a == nil {
		t.Fatal(a, err)
	}
	if len(a.AccountNo) != 12 || len(a.AuthCode) != 32 || !uuidRe.MatchString(a.UUID) || a.PeriodType != PeriodMonthly {
		t.Fatalf("自动生成值不对: %+v", a)
	}
	if len(a.TagIDs) != 1 || a.UserID != r.UserID {
		t.Fatalf("标签/用户关联不对: %+v", a)
	}
	u, _ := ManagedUserByID(ctx, db, r.UserID)
	if u.Email != "a@example.com" || u.Role != "VIP" || u.AccountCount != 1 {
		t.Fatalf("用户 = %+v", u)
	}
	var hash string
	db.QueryRow(`SELECT password FROM user WHERE id = ?`, r.UserID).Scan(&hash)
	if !strings.HasPrefix(hash, "$2") {
		t.Fatalf("密码未做 bcrypt: %s", hash)
	}
	var desc string
	var cents, bid int64
	db.QueryRow(`SELECT description, amount, business_id FROM transactions WHERE id = ?`, r.TransactionID).Scan(&desc, &cents, &bid)
	if desc != "账号首期：3月" || cents != 3500 || bid != r.AccountID {
		t.Fatalf("流水 = %q %d %d", desc, cents, bid)
	}
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("开户带标签后关联节点应置待部署")
	}
}

func TestOnboardRollsBackAndValidates(t *testing.T) {
	db, ctx := testDB(t)
	// 标签不存在：用户和账户都不应留下
	_, err := Onboard(ctx, db, OnboardInput{NewUser: &ManagedUser{Email: "x@example.com"}, Password: "secret123",
		Account: Account{NodeMultiple: 1, TagIDs: []int64{999}}})
	if !errors.Is(err, ErrInvalid) {
		t.Fatalf("err = %v", err)
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM user`).Scan(&n)
	if n != 0 {
		t.Fatal("失败后不应保留用户")
	}

	zero := int64(0)
	cents := int64(100)
	cases := map[string]OnboardInput{
		"两种用户来源":   {UserID: 1, NewUser: &ManagedUser{Email: "a@b.co"}, Account: baseAccount()},
		"没有用户来源":   {Account: baseAccount()},
		"节点倍数":     {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: Account{NodeMultiple: 11}},
		"金额为0":     {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: baseAccount(), AmountCents: &zero, PaymentMethod: "WeChat"},
		"缺付款方式":    {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: baseAccount(), AmountCents: &cents},
		"付款方式非法":   {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: baseAccount(), AmountCents: &cents, PaymentMethod: "wechat"},
		"密码太短":     {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "123", Account: baseAccount()},
		"UUID非法":   {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: Account{NodeMultiple: 1, UUID: "nope"}},
		"到期早于开始":   {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: Account{NodeMultiple: 1, FromDate: ptr(time.Now()), ToDate: ptr(time.Now().Add(-time.Hour))}},
		"周期枚举":     {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: Account{NodeMultiple: 1, PeriodType: "WEEKLY"}},
		"负数限速":     {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: Account{NodeMultiple: 1, DownloadMbps: -1}},
		"账户编号字符非法": {NewUser: &ManagedUser{Email: "a@b.co"}, Password: "secret123", Account: Account{NodeMultiple: 1, AccountNo: "a b"}},
	}
	for name, in := range cases {
		if _, err := Onboard(ctx, db, in); !errors.Is(err, ErrInvalid) {
			t.Errorf("%s: err = %v, 期望 ErrInvalid", name, err)
		}
	}
	if _, err := Onboard(ctx, db, OnboardInput{UserID: 999, Account: baseAccount()}); !errors.Is(err, ErrInvalid) {
		t.Errorf("不存在的用户: %v", err)
	}
}

func TestOnboardUniqueConflicts(t *testing.T) {
	db, ctx := testDB(t)
	r := onboard(t, db, "a@example.com", func(in *OnboardInput) { in.Account.AccountNo = "ACC1" })
	a, _ := AccountByID(ctx, db, r.AccountID)

	for name, mut := range map[string]func(*Account){
		"accountNo 大小写": func(x *Account) { x.AccountNo = "acc1" },
		"uuid":          func(x *Account) { x.UUID = a.UUID },
		"authCode":      func(x *Account) { x.AuthCode = a.AuthCode },
	} {
		acc := baseAccount()
		mut(&acc)
		_, err := Onboard(ctx, db, OnboardInput{UserID: r.UserID, Account: acc})
		if !errors.Is(err, ErrConflict) {
			t.Errorf("%s: err = %v", name, err)
		}
	}
	// 邮箱重复
	_, err := Onboard(ctx, db, OnboardInput{NewUser: &ManagedUser{Email: "A@example.com"}, Password: "secret123", Account: baseAccount()})
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("邮箱重复 err = %v", err)
	}
	// 复用已有用户
	if _, err := Onboard(ctx, db, OnboardInput{UserID: r.UserID, Account: baseAccount()}); err != nil {
		t.Fatal(err)
	}
}

func TestUpdateAccount(t *testing.T) {
	db, ctx := testDB(t)
	t1, t2 := mustTag(t, db, "t1"), mustTag(t, db, "t2")
	n1, n2 := deployedNode(t, db, "n1", t1), deployedNode(t, db, "n2", t2)
	r := onboard(t, db, "a@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{t1} })
	a, _ := AccountByID(ctx, db, r.AccountID)
	resetDeployed(db, n1)

	// 当期统计行：修改配额应同步
	db.Exec(`INSERT INTO account_traffic_stats (account_id, period_start, period_end, bandwidth_quota) VALUES (?, ?, ?, 500)`,
		a.ID, FormatTime(time.Now().Add(-time.Hour)), FormatTime(time.Now().Add(time.Hour)))

	// 改配额：更新当期配额并置待部署；nil 标签不动
	edit := *a
	edit.Remark, edit.TagIDs, edit.Bandwidth = "新备注", nil, 800
	if err := UpdateAccount(ctx, db, a.ID, &edit); err != nil {
		t.Fatal(err)
	}
	if nodeDeployed(t, db, n1) != PendingDeploy {
		t.Fatal("改配额应置待部署")
	}
	got, _ := AccountByID(ctx, db, a.ID)
	if got.Remark != "新备注" || len(got.TagIDs) != 1 || got.AccountNo != a.AccountNo || got.AuthCode != a.AuthCode {
		t.Fatalf("got = %+v", got)
	}
	var quota int
	db.QueryRow(`SELECT bandwidth_quota FROM account_traffic_stats WHERE account_id = ?`, a.ID).Scan(&quota)
	if quota != 800 {
		t.Fatalf("当期配额 = %d, want 800", quota)
	}

	// 换标签 t1→t2：新旧标签的节点都置待部署
	resetDeployed(db, n1)
	resetDeployed(db, n2)
	edit.TagIDs = []int64{t2}
	if err := UpdateAccount(ctx, db, a.ID, &edit); err != nil {
		t.Fatal(err)
	}
	if nodeDeployed(t, db, n1) != PendingDeploy || nodeDeployed(t, db, n2) != PendingDeploy {
		t.Fatal("换标签后新旧节点都应置待部署")
	}

	// accountNo / authCode 禁止修改，原值可以
	bad := edit
	bad.AccountNo = "other"
	if err := UpdateAccount(ctx, db, a.ID, &bad); !errors.Is(err, ErrInvalid) {
		t.Fatalf("accountNo err = %v", err)
	}
	bad = edit
	bad.AuthCode = "abcdefghijklmnop"
	if err := UpdateAccount(ctx, db, a.ID, &bad); !errors.Is(err, ErrInvalid) {
		t.Fatalf("authCode err = %v", err)
	}
	same := edit
	same.AccountNo, same.AuthCode = a.AccountNo, a.AuthCode
	if err := UpdateAccount(ctx, db, a.ID, &same); err != nil {
		t.Fatal(err)
	}

	// UUID 冲突与清空标签
	r2 := onboard(t, db, "b@example.com", nil)
	b, _ := AccountByID(ctx, db, r2.AccountID)
	dup := *b
	dup.UUID = a.UUID
	if err := UpdateAccount(ctx, db, b.ID, &dup); !errors.Is(err, ErrConflict) {
		t.Fatalf("uuid err = %v", err)
	}
	edit.TagIDs = []int64{}
	if err := UpdateAccount(ctx, db, a.ID, &edit); err != nil {
		t.Fatal(err)
	}
	if got, _ := AccountByID(ctx, db, a.ID); len(got.TagIDs) != 0 {
		t.Fatal("空切片应清空标签")
	}
	if err := UpdateAccount(ctx, db, 999, &edit); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("不存在 err = %v", err)
	}
}

func TestRenewToggleResetAndDelete(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "hk")
	nid := deployedNode(t, db, "n1", tag)
	past := time.Now().Add(-24 * time.Hour)
	r := onboard(t, db, "a@example.com", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
		in.Account.FromDate, in.Account.ToDate, in.Account.Disabled = ptr(past.AddDate(0, -1, 0)), &past, true
	})
	resetDeployed(db, nid)

	if err := RenewAccount(ctx, db, r.AccountID, time.Now().Add(-time.Hour), nil, ""); !errors.Is(err, ErrInvalid) {
		t.Fatalf("过去的到期时间 err = %v", err)
	}
	amount := int64(1000)
	if err := RenewAccount(ctx, db, r.AccountID, time.Now().Add(-time.Hour), &amount, "AliPay"); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	// 20 天内日历月差至多为 1，月末运行也稳定得到「1月」
	if err := RenewAccount(ctx, db, r.AccountID, time.Now().AddDate(0, 0, 20), &amount, "AliPay"); err != nil {
		t.Fatal(err)
	}
	a, _ := AccountByID(ctx, db, r.AccountID)
	if a.Disabled || a.ToDate.Before(time.Now()) {
		t.Fatalf("续期后应启用且到期时间更新: %+v", a)
	}
	var desc string
	db.QueryRow(`SELECT description FROM transactions WHERE business_id = ? ORDER BY id DESC LIMIT 1`, r.AccountID).Scan(&desc)
	if desc != "账号：1月" {
		t.Fatalf("续期流水描述 = %q", desc)
	}
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("续期应置待部署")
	}

	for _, to := range []time.Time{*a.ToDate, a.ToDate.Add(-time.Hour)} {
		if err := RenewAccount(ctx, db, r.AccountID, to, &amount, "AliPay"); !errors.Is(err, ErrInvalid) {
			t.Fatalf("续期不能保持或缩短到期时间: %v", err)
		}
	}

	resetDeployed(db, nid)
	if err := SetAccountDisabled(ctx, db, r.AccountID, true); err != nil {
		t.Fatal(err)
	}
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("停用应置待部署")
	}
	if err := SetAccountDisabled(ctx, db, 999, true); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal(err)
	}

	resetDeployed(db, nid)
	code, err := ResetAuthCode(ctx, db, r.AccountID)
	if err != nil || len(code) != 32 || code == a.AuthCode {
		t.Fatal(code, err)
	}
	if nodeDeployed(t, db, nid) != Deployed {
		t.Fatal("重置订阅码不需要部署")
	}

	// 删除：绑定、告警、在线记录清理，流水保留，节点置待部署
	db.Exec(`INSERT INTO alert_state (alert_type, resource_type, resource_id, fingerprint, status) VALUES ('x','account',?, 'f','ACTIVE')`, r.AccountID)
	db.Exec(`INSERT INTO alert_state (alert_type, resource_type, resource_id, fingerprint, status) VALUES ('x','server',?, 'f','ACTIVE')`, r.AccountID)
	db.Exec(`INSERT INTO account_online_ip (account_no, client_ip, node_ip, last_online_time) VALUES (?, '1.1.1.1', '2.2.2.2', ?)`, a.AccountNo, FormatTime(time.Now()))
	var nsID int64
	db.QueryRow(`SELECT id FROM node_server WHERE node_id = ?`, nid).Scan(&nsID)
	res, _ := db.Exec(`INSERT INTO account_node_subscription_domain_binding (account_id, node_id) VALUES (?, ?)`, r.AccountID, nid)
	bid, _ := res.LastInsertId()
	db.Exec(`INSERT INTO account_node_subscription_placement (binding_id, node_server_id) VALUES (?, ?)`, bid, nsID)
	resetDeployed(db, nid)

	if err := DeleteAccount(ctx, db, r.AccountID); err != nil {
		t.Fatal(err)
	}
	count := func(q string) (n int) { db.QueryRow(q).Scan(&n); return }
	if count(`SELECT count(*) FROM account`) != 0 || count(`SELECT count(*) FROM account_node_subscription_domain_binding`) != 0 ||
		count(`SELECT count(*) FROM account_online_ip`) != 0 || count(`SELECT count(*) FROM account_tag`) != 0 {
		t.Fatal("账户及其关联数据应被清理")
	}
	if count(`SELECT count(*) FROM alert_state WHERE resource_type='account'`) != 0 || count(`SELECT count(*) FROM alert_state WHERE resource_type='server'`) != 1 {
		t.Fatal("只应删除该账户的告警状态")
	}
	if count(`SELECT count(*) FROM transactions`) != 1 {
		t.Fatal("流水应保留")
	}
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("删除应置待部署")
	}
	if err := DeleteAccount(ctx, db, r.AccountID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal(err)
	}
}

func TestAccountListFilters(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "hk")
	now := time.Now()
	mk := func(email, remark string, mut func(*Account)) *OnboardResult {
		return onboard(t, db, email, func(in *OnboardInput) {
			in.Account.Remark = remark
			in.Account.TagIDs = []int64{tag}
			if mut != nil {
				mut(&in.Account)
			}
		})
	}
	active := mk("alice@example.com", "活跃", func(a *Account) { a.FromDate, a.ToDate = ptr(now.Add(-time.Hour)), ptr(now.AddDate(0, 1, 0)) })
	forever := mk("bob@example.com", "永久", nil)
	expiring := mk("carol@example.com", "快到期", func(a *Account) { a.FromDate, a.ToDate = ptr(now.Add(-time.Hour)), ptr(now.AddDate(0, 0, 3)) })
	expired := mk("dave@example.com", "已过期", func(a *Account) { a.FromDate, a.ToDate = ptr(now.AddDate(0, -2, 0)), ptr(now.Add(-time.Hour)) })
	disabled := mk("erin@example.com", "禁用", func(a *Account) { a.Disabled = true })

	ids := func(f AccountFilter) map[int64]bool {
		rows, total, err := AccountList(ctx, db, f, 1, 50)
		if err != nil {
			t.Fatal(err)
		}
		if total != len(rows) {
			t.Fatalf("total %d != rows %d", total, len(rows))
		}
		m := map[int64]bool{}
		for _, r := range rows {
			m[r.ID] = true
		}
		return m
	}
	check := func(name string, f AccountFilter, want ...*OnboardResult) {
		t.Helper()
		got := ids(f)
		if len(got) != len(want) {
			t.Errorf("%s: 得到 %d 条, 期望 %d", name, len(got), len(want))
		}
		for _, w := range want {
			if !got[w.AccountID] {
				t.Errorf("%s: 缺少账户 %d", name, w.AccountID)
			}
		}
	}
	check("全部", AccountFilter{}, active, forever, expiring, expired, disabled)
	check("active", AccountFilter{Status: "active"}, active, forever, expiring)
	check("expired", AccountFilter{Status: "expired"}, expired)
	check("disabled", AccountFilter{Status: "DISABLED"}, disabled)
	check("expiring", AccountFilter{Status: "expiring"}, expiring)
	check("备注搜索", AccountFilter{Search: "快到"}, expiring)
	check("邮箱前缀", AccountFilter{Search: "ali"}, active)
	check("用户", AccountFilter{UserID: forever.UserID}, forever)
	check("LIKE 通配符被转义", AccountFilter{Search: "%"})

	a, _ := AccountByID(ctx, db, active.AccountID)
	check("authCode", AccountFilter{Search: a.AuthCode}, active)
	check("uuid 前缀", AccountFilter{Search: a.UUID[:8]}, active)

	// 在线筛选与行内统计
	db.Exec(`INSERT INTO account_online_ip (account_no, client_ip, node_ip, last_online_time) VALUES (?, '1.1.1.1', '2.2.2.2', ?)`, a.AccountNo, FormatTime(now.Add(-time.Minute)))
	check("online", AccountFilter{Online: "online"}, active)
	check("offline", AccountFilter{Online: "offline"}, forever, expiring, expired, disabled)
	db.Exec(`INSERT INTO account_traffic_stats (account_id, period_start, period_end, bandwidth_quota, upload_bytes, download_bytes) VALUES (?, ?, ?, 100, 10, 20)`,
		a.ID, FormatTime(now.Add(-time.Hour)), FormatTime(now.Add(time.Hour)))
	// 重叠的旧周期行不计入，与订阅用量口径一致。
	db.Exec(`INSERT INTO account_traffic_stats (account_id, period_start, period_end, bandwidth_quota, upload_bytes, download_bytes) VALUES (?, ?, ?, 5, 1000, 0)`,
		a.ID, FormatTime(now.Add(-2*time.Hour)), FormatTime(now.Add(2*time.Hour)))
	rows, _, _ := AccountList(ctx, db, AccountFilter{Search: a.AccountNo}, 1, 10)
	r := rows[0]
	if r.UserEmail != "alice@example.com" || r.UsedBytes != 30 || r.QuotaGB != 100 || r.OnlineConns != 1 || r.LastOnline == nil || len(r.Tags) != 1 {
		t.Fatalf("行统计 = %+v", r)
	}

	// 按最后在线排序：有记录的在前，无记录的无论升降序都在最后。
	fa, _ := AccountByID(ctx, db, forever.AccountID)
	db.Exec(`INSERT INTO account_online_ip (account_no, client_ip, node_ip, last_online_time) VALUES (?, '1.1.1.1', '2.2.2.2', ?)`, fa.AccountNo, FormatTime(now.Add(-time.Hour)))
	firstLast := func(asc bool) []int64 {
		rows, _, err := AccountList(ctx, db, AccountFilter{Sort: "last_online", Asc: asc}, 1, 50)
		if err != nil {
			t.Fatal(err)
		}
		var out []int64
		for _, r := range rows {
			out = append(out, r.ID)
		}
		return out
	}
	desc, asc := firstLast(false), firstLast(true)
	if desc[0] != active.AccountID || desc[1] != forever.AccountID || asc[0] != forever.AccountID || asc[1] != active.AccountID {
		t.Fatalf("最后在线排序: desc=%v asc=%v", desc, asc)
	}
	if len(desc) != 5 || len(asc) != 5 {
		t.Fatalf("排序不应丢行: %v %v", desc, asc)
	}

	// 排序与分页
	rows, total, _ := AccountList(ctx, db, AccountFilter{Sort: "to_date", Asc: true}, 1, 2)
	if total != 5 || len(rows) != 2 || rows[0].ToDate == nil {
		t.Fatalf("to_date 升序首行应是最早到期，永不过期排最后: total=%d rows=%d", total, len(rows))
	}
	rows, _, _ = AccountList(ctx, db, AccountFilter{Sort: "to_date"}, 1, 50)
	if rows[0].ToDate != nil || rows[len(rows)-1].ToDate == nil {
		t.Fatal("to_date 降序应永不过期在前、最早到期在后")
	}
	byEnd, _, _ := AccountList(ctx, db, AccountFilter{Sort: "to_date", Asc: true}, 1, 50)
	if byEnd[len(byEnd)-1].ToDate != nil {
		t.Fatal("to_date 升序永不过期应在最后")
	}
	if rows, _, _ := AccountList(ctx, db, AccountFilter{}, 1, 1000); len(rows) != 5 {
		t.Fatal("size 过大应夹到上限")
	}
}

func TestAccountRecentNodes(t *testing.T) {
	db, ctx := testDB(t)
	r := onboard(t, db, "a@example.com", nil)
	a, _ := AccountByID(ctx, db, r.AccountID)
	res, _ := db.Exec(`INSERT INTO node (name, type, protocol, port) VALUES ('hk-1', 0, 'vless', 443)`)
	nid, _ := res.LastInsertId()
	now := time.Now()
	ins := func(ip string, node any, conn string, ago time.Duration) {
		db.Exec(`INSERT INTO account_online_ip (account_no, client_ip, connection_id, node_ip, node_id, last_online_time) VALUES (?, '9.9.9.9', ?, ?, ?, ?)`,
			a.AccountNo, conn, ip, node, FormatTime(now.Add(-ago)))
	}
	ins("2.2.2.2", nid, "c1", 48*time.Hour)
	ins("2.2.2.2", nid, "c2", 2*time.Hour) // 同节点取最近
	ins("3.3.3.3", nil, "c3", time.Hour)   // 未映射节点
	ins("4.4.4.4", nid, "c4", 40*24*time.Hour)

	list, err := AccountRecentNodes(ctx, db, a.ID, now.AddDate(0, 0, -30), 0)
	if err != nil {
		t.Fatal(err)
	}
	if len(list) != 2 || list[0].NodeIP != "3.3.3.3" || list[0].NodeID != 0 || list[1].NodeName != "hk-1" {
		t.Fatalf("list = %+v", list[0])
	}
	if d := now.Add(-2 * time.Hour).Sub(list[1].LastOnline); d > time.Minute || d < -time.Minute {
		t.Fatalf("应取同节点最近一次: %v", list[1].LastOnline)
	}
}
