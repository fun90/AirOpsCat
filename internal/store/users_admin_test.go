package store

import (
	"database/sql"
	"errors"
	"testing"

	"golang.org/x/crypto/bcrypt"
)

func TestManagedUserCRUD(t *testing.T) {
	db, ctx := testDB(t)
	u := &ManagedUser{Email: " Admin@Example.com ", NickName: "管理员", Role: "admin", Remark: "备注"}
	if err := SaveManagedUser(ctx, db, u, "secret123"); err != nil {
		t.Fatal(err)
	}
	if u.ID == 0 || u.Email != "admin@example.com" || u.Role != "ADMIN" {
		t.Fatalf("u = %+v", u)
	}
	var hash string
	db.QueryRow(`SELECT password FROM user WHERE id = ?`, u.ID).Scan(&hash)
	if bcrypt.CompareHashAndPassword([]byte(hash), []byte("secret123")) != nil {
		t.Fatal("密码哈希不匹配")
	}

	// 更新：不改密、改备注（旧系统改不了 remark）
	edit := *u
	edit.Remark, edit.NickName = "新备注", "新昵称"
	if err := SaveManagedUser(ctx, db, &edit, ""); err != nil {
		t.Fatal(err)
	}
	got, _ := ManagedUserByID(ctx, db, u.ID)
	if got.Remark != "新备注" || got.NickName != "新昵称" {
		t.Fatalf("got = %+v", got)
	}
	var again string
	db.QueryRow(`SELECT password FROM user WHERE id = ?`, u.ID).Scan(&again)
	if again != hash {
		t.Fatal("空密码不应改密")
	}
	// 改密
	if err := SaveManagedUser(ctx, db, &edit, "newsecret1"); err != nil {
		t.Fatal(err)
	}
	db.QueryRow(`SELECT password FROM user WHERE id = ?`, u.ID).Scan(&again)
	if bcrypt.CompareHashAndPassword([]byte(again), []byte("newsecret1")) != nil {
		t.Fatal("改密未生效")
	}

	// 校验
	for name, c := range map[string]struct {
		u  ManagedUser
		pw string
	}{
		"邮箱格式":  {ManagedUser{Email: "bad"}, "secret123"},
		"邮箱为空":  {ManagedUser{}, "secret123"},
		"角色枚举":  {ManagedUser{Email: "x@example.com", Role: "ROOT"}, "secret123"},
		"新建无密码": {ManagedUser{Email: "x@example.com"}, ""},
		"密码过短":  {ManagedUser{Email: "x@example.com"}, "12345"},
		"密码过长":  {ManagedUser{Email: "x@example.com"}, string(make([]byte, 73))},
	} {
		if err := SaveManagedUser(ctx, db, &c.u, c.pw); !errors.Is(err, ErrInvalid) {
			t.Errorf("%s: err = %v", name, err)
		}
	}

	// 邮箱唯一（大小写不敏感），新建与更新都返回 ErrConflict
	dup := &ManagedUser{Email: "ADMIN@example.com"}
	if err := SaveManagedUser(ctx, db, dup, "secret123"); !errors.Is(err, ErrConflict) {
		t.Fatalf("新建重复邮箱 err = %v", err)
	}
	other := &ManagedUser{Email: "other@example.com"}
	if err := SaveManagedUser(ctx, db, other, "secret123"); err != nil {
		t.Fatal(err)
	}
	other.Email = "admin@example.com"
	if err := SaveManagedUser(ctx, db, other, ""); !errors.Is(err, ErrConflict) {
		t.Fatalf("更新重复邮箱 err = %v", err)
	}
	missing := &ManagedUser{ID: 999, Email: "m@example.com"}
	if err := SaveManagedUser(ctx, db, missing, ""); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("不存在 err = %v", err)
	}

	// 列表：搜索 + 分页，LIKE 通配符转义
	list, total, err := ManagedUserList(ctx, db, "OTHER", 1, 10)
	if err != nil || total != 1 || len(list) != 1 || list[0].Email != "other@example.com" {
		t.Fatalf("list = %v total=%d err=%v", list, total, err)
	}
	if _, total, _ := ManagedUserList(ctx, db, "%", 1, 10); total != 0 {
		t.Fatalf("%% 应按字面匹配, total=%d", total)
	}
	if list, total, _ := ManagedUserList(ctx, db, "", 1, 1); total != 2 || len(list) != 1 || list[0].Email != "other@example.com" {
		t.Fatalf("分页 total=%d list=%v", total, list)
	}
}

func TestDisableUserKeepsAccounts(t *testing.T) {
	db, ctx := testDB(t)
	r := onboard(t, db, "a@example.com", nil)
	if err := SetUserDisabled(ctx, db, r.UserID, true); err != nil {
		t.Fatal(err)
	}
	if u, _ := ManagedUserByID(ctx, db, r.UserID); !u.Disabled {
		t.Fatal("用户应被禁用")
	}
	if a, _ := AccountByID(ctx, db, r.AccountID); a.Disabled {
		t.Fatal("禁用用户不应连带停用账户")
	}
	if err := SetUserDisabled(ctx, db, 999, true); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal(err)
	}
}

func TestDeleteManagedUserGuards(t *testing.T) {
	db, ctx := testDB(t)
	admin := &ManagedUser{Email: "admin@example.com", Role: "ADMIN"}
	admin2 := &ManagedUser{Email: "admin2@example.com", Role: "ADMIN"}
	vip := &ManagedUser{Email: "vip@example.com"}
	for _, u := range []*ManagedUser{admin, admin2, vip} {
		if err := SaveManagedUser(ctx, db, u, "secret123"); err != nil {
			t.Fatal(err)
		}
	}

	if err := DeleteManagedUser(ctx, db, admin.ID, admin.ID); !errors.Is(err, ErrInvalid) {
		t.Fatalf("删除自己 err = %v", err)
	}

	// 名下有账户
	owner := onboard(t, db, "owner@example.com", nil)
	if err := DeleteManagedUser(ctx, db, owner.UserID, vip.ID); !errors.Is(err, ErrConflict) {
		t.Fatalf("有账户 err = %v", err)
	}

	// 两个管理员时可删一个，最后一个不可删
	if err := DeleteManagedUser(ctx, db, admin2.ID, vip.ID); err != nil {
		t.Fatal(err)
	}
	if err := DeleteManagedUser(ctx, db, admin.ID, vip.ID); !errors.Is(err, ErrInvalid) {
		t.Fatalf("最后管理员 err = %v", err)
	}
	if err := DeleteManagedUser(ctx, db, 999, vip.ID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("不存在 err = %v", err)
	}
	if err := DeleteManagedUser(ctx, db, vip.ID, admin.ID); err != nil {
		t.Fatal(err)
	}
	if u, _ := ManagedUserByID(ctx, db, vip.ID); u != nil {
		t.Fatal("用户应已删除")
	}
}

func TestCannotDisableOrDemoteLastEnabledAdmin(t *testing.T) {
	db, ctx := testDB(t)
	u := &ManagedUser{Email: "last@example.com", Role: "ADMIN"}
	if err := SaveManagedUser(ctx, db, u, "secret123"); err != nil {
		t.Fatal(err)
	}
	u.Disabled = true
	if err := SaveManagedUser(ctx, db, u, ""); err == nil {
		t.Fatal("不允许禁用最后启用管理员")
	}
	u.Disabled = false
	u.Role = "VIP"
	if err := SaveManagedUser(ctx, db, u, ""); err == nil {
		t.Fatal("不允许降级最后启用管理员")
	}
}
