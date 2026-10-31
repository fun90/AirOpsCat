package store

import (
	"context"
	"database/sql"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
)

func testSettingsDB(t *testing.T) (*sql.DB, context.Context) {
	t.Helper()
	ctx := context.Background()
	db, err := Open(ctx, filepath.Join(t.TempDir(), "settings.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db, ctx
}

func TestSettingsSensitivePreservedAndValidated(t *testing.T) {
	db, ctx := testSettingsDB(t)
	c, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	db.ExecContext(ctx, `INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.api.token','v1:old','open'),('airopscat.subscription.url','https://old.example/subscribe','open'),('airopscat.ratelimit.enabled','false','account'),('airopscat.account.guard.ttl-seconds','30','monitor')`)
	before := ""
	db.QueryRow(`SELECT config_value FROM system_config WHERE config_key='airopscat.api.token'`).Scan(&before)
	settings, err := Settings(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	for _, v := range settings {
		if v.Key == "airopscat.api.token" && (!v.Sensitive || !v.HasValue || v.Value != "") {
			t.Fatalf("敏感配置泄露或未标记：%+v", v)
		}
	}
	if err := SaveSettings(ctx, db, c, map[string]string{"airopscat.api.token": ""}); err != nil {
		t.Fatal(err)
	}
	var got string
	db.QueryRow(`SELECT config_value FROM system_config WHERE config_key='airopscat.api.token'`).Scan(&got)
	if got != before {
		t.Fatal("敏感空提交应保留原值")
	}
	if err := SaveSettings(ctx, db, c, map[string]string{"airopscat.api.token": "next-token"}); err != nil {
		t.Fatal(err)
	}
	db.QueryRow(`SELECT config_value FROM system_config WHERE config_key='airopscat.api.token'`).Scan(&got)
	plain, err := c.Decrypt(got)
	if err != nil || plain != "next-token" || !strings.HasPrefix(got, "v1:") {
		t.Fatalf("敏感配置应加密：%q %v", got, err)
	}
	for _, tc := range []struct{ key, value string }{{"missing.key", "x"}, {"airopscat.subscription.url", "ftp://bad"}, {"airopscat.ratelimit.enabled", "yes"}, {"airopscat.account.guard.ttl-seconds", "4"}} {
		if err := SaveSettings(ctx, db, c, map[string]string{tc.key: tc.value}); err == nil {
			t.Fatalf("应拒绝 %#v", tc)
		}
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM system_config WHERE config_key='missing.key'`).Scan(&n)
	if n != 0 {
		t.Fatal("未知键不得创建")
	}
}

func TestLogsPaginationFilteringAndRedaction(t *testing.T) {
	db, ctx := testSettingsDB(t)
	for i := 0; i < 22; i++ {
		category := "DEPLOY"
		if i == 0 {
			category = "ALERT"
		}
		if err := Log(ctx, db, category, "INFO", nil, "node", "token=abc <script>", `{"password":"secret","Authorization":"Bearer abc"}`); err != nil {
			t.Fatal(err)
		}
	}
	list, total, err := Logs(ctx, db, LogFilter{Category: "DEPLOY", Q: "隐藏"}, 1)
	if err != nil || total != 21 || len(list) != 20 {
		t.Fatalf("筛选分页：首页 %d %d %v", total, len(list), err)
	}
	second, total, err := Logs(ctx, db, LogFilter{Category: "DEPLOY"}, 2)
	if err != nil || total != 21 || len(second) != 1 {
		t.Fatalf("筛选分页：第二页 %d %d %v", total, len(second), err)
	}
	if strings.Contains(list[0].Message, "abc") || strings.Contains(list[0].Detail, "secret") {
		t.Fatalf("日志秘密未脱敏：%+v", list[0])
	}
	list, total, err = Logs(context.Background(), db, LogFilter{Category: "ALERT"}, 1)
	if err != nil || total != 1 || len(list) != 1 {
		t.Fatalf("分类筛选：%d %d %v", total, len(list), err)
	}
	today := time.Now().In(Shanghai).Format("2006-01-02")
	if _, total, _ = Logs(ctx, db, LogFilter{From: today, To: today}, 1); total != 22 {
		t.Fatalf("时间范围含当天：%d", total)
	}
	if _, total, _ = Logs(ctx, db, LogFilter{To: "2000-01-01"}, 1); total != 0 {
		t.Fatalf("时间范围排除：%d", total)
	}
}

func TestSettingsRuntimeAndLogCleanup(t *testing.T) {
	db, ctx := testSettingsDB(t)
	db.Exec(`INSERT INTO system_config(config_key,config_value,group_key)VALUES('airopscat.backup.dir','/old','backup'),('airopscat.docs.url','https://docs.example','open')`)
	if err := EnsureSettings(ctx, db); err != nil {
		t.Fatal(err)
	}
	items, err := Settings(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	if len(items) != len(SettingDefaults) {
		t.Fatal(len(items), len(SettingDefaults))
	}
	for _, v := range items {
		if v.Key == "airopscat.backup.dir" {
			t.Fatal("环境控制项不应可编辑")
		}
		if v.Key == "airopscat.docs.url" && v.Value != "https://docs.example" {
			t.Fatal("已有值被覆盖")
		}
	}
	if err := SaveSettings(ctx, db, nil, map[string]string{"airopscat.backup.dir": "/new"}); err == nil {
		t.Fatal("失效项不应写入")
	}
	now := time.Now()
	db.Exec(`INSERT INTO system_log(category,level,message,create_time)VALUES('TEST','INFO','old',?),('TEST','INFO','new',?)`, FormatTime(now.AddDate(0, 0, -100)), FormatTime(now))
	if err := CleanupSystemLogs(ctx, db, 90, now); err != nil {
		t.Fatal(err)
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM system_log`).Scan(&n)
	if n != 1 {
		t.Fatal(n)
	}
}
