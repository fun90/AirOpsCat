package store

import (
	"database/sql"
	"errors"
	"strings"
	"testing"
)

func TestRealitySNIPool(t *testing.T) {
	db, ctx := testDB(t)
	list, err := RealitySNIs(ctx, db, true)
	if err != nil || len(list) != 15 {
		t.Fatalf("预置候选: %d %v", len(list), err)
	}
	v := &RealitySNI{Domain: "  TLS.Example.Test. ", Note: "自定义", Enabled: true}
	if err := SaveRealitySNI(ctx, db, v); err != nil || v.Domain != "tls.example.test" {
		t.Fatalf("规范化: %+v %v", v, err)
	}
	if err := SaveRealitySNI(ctx, db, &RealitySNI{Domain: "TLS.EXAMPLE.TEST"}); !errors.Is(err, ErrConflict) {
		t.Fatalf("重复: %v", err)
	}
	for _, domain := range []string{"", "localhost", "127.0.0.1", "::1", "https://example.test", "example.test:443", "example.test/path", "*.example.test", "bad..test", "-bad.test", strings.Repeat("a", 64) + ".test"} {
		if err := SaveRealitySNI(ctx, db, &RealitySNI{Domain: domain}); !errors.Is(err, ErrInvalid) {
			t.Errorf("非法域名 %q: %v", domain, err)
		}
	}
	unicode := &RealitySNI{Domain: "例子.测试"}
	if err := SaveRealitySNI(ctx, db, unicode); err != nil || unicode.Domain != "xn--fsqu00a.xn--0zwm56d" {
		t.Fatalf("IDNA: %+v %v", unicode, err)
	}
	v.Note, v.Enabled = "修改", false
	if err := SaveRealitySNI(ctx, db, v); err != nil {
		t.Fatal(err)
	}
	enabled, err := RealitySNIs(ctx, db, true)
	if err != nil || len(enabled) != 15 {
		t.Fatalf("停用候选不应可选: %d %v", len(enabled), err)
	}
	if err := ToggleRealitySNI(ctx, db, v.ID, true); err != nil {
		t.Fatal(err)
	}
	if err := DeleteRealitySNI(ctx, db, list[0].ID); err != nil {
		t.Fatal(err)
	}
	if err := migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
	all, err := RealitySNIs(ctx, db, false)
	if err != nil || len(all) != 16 {
		t.Fatalf("迁移不应恢复删除项: %d %v", len(all), err)
	}
	for _, x := range all {
		if x.Domain == list[0].Domain {
			t.Fatal("已删除种子复活")
		}
	}
	for _, err := range []error{DeleteRealitySNI(ctx, db, 9999), ToggleRealitySNI(ctx, db, 9999, true), SaveRealitySNI(ctx, db, &RealitySNI{ID: 9999, Domain: "absent.test"})} {
		if !errors.Is(err, sql.ErrNoRows) {
			t.Fatalf("不存在记录: %v", err)
		}
	}
}
