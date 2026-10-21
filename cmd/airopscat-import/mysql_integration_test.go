package main

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"strings"
	"testing"

	_ "github.com/go-sql-driver/mysql"
)

func TestMySQLRepeatableReadSnapshotIntegration(t *testing.T) {
	dsn := os.Getenv("AIROPSCAT_DRILL_DSN")
	if dsn == "" {
		t.Skip("设置 AIROPSCAT_DRILL_DSN 后运行 MySQL 集成演练")
	}
	ctx := context.Background()
	src, err := sql.Open("mysql", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	snap, err := src.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	if err != nil {
		t.Fatal(err)
	}
	defer snap.Rollback()
	var before int
	if err := snap.QueryRowContext(ctx, "select count(*) from account").Scan(&before); err != nil {
		t.Fatal(err)
	}
	other, err := sql.Open("mysql", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer other.Close()
	if _, err := other.ExecContext(ctx, "insert into account(id,user_id,account_no,uuid,auth_code,remark,period_type,from_date,to_date,bandwidth,max_connections,max_ips,disabled,node_multiple,create_time,update_time) values(99,1,'A99','99999999-9999-4999-8999-999999999999','auth-new-0000000000000000','new','MONTHLY',now(),date_add(now(),interval 1 month),1,1,1,b'0',1,now(),now())"); err != nil {
		t.Fatal(err)
	}
	defer other.ExecContext(ctx, "delete from account where id=99")
	var after int
	if err := snap.QueryRowContext(ctx, "select count(*) from account").Scan(&after); err != nil {
		t.Fatal(err)
	}
	if after != before {
		t.Fatalf("快照读取到并发写入: %d -> %d", before, after)
	}
	var current int
	if err := other.QueryRowContext(ctx, "select count(*) from account").Scan(&current); err != nil || current != before+1 {
		t.Fatal(current, err)
	}

	t.Setenv("AIROPSCAT_SECRET_KEY", strings.Repeat("s", 40))
	out := filepath.Join(t.TempDir(), "drill.db")
	err = run(dsn, "AirOpsCatDefaultSecretKey2024", out, false)
	if err != nil {
		t.Fatal(err)
	}
	got, err := sql.Open("sqlite", "file:"+out)
	if err != nil {
		t.Fatal(err)
	}
	defer got.Close()
	var imported int
	if err := got.QueryRow("select count(*) from account").Scan(&imported); err != nil {
		t.Fatal(err)
	}
	if imported != current {
		t.Fatalf("独立导入应包含提交后的当前数据: %d != %d", imported, current)
	}
}

func TestMySQLImportFailurePublishesNoOutputIntegration(t *testing.T) {
	dsn := os.Getenv("AIROPSCAT_DRILL_DSN")
	if dsn == "" {
		t.Skip("设置 AIROPSCAT_DRILL_DSN 后运行 MySQL 集成演练")
	}
	src, err := sql.Open("mysql", dsn)
	if err != nil {
		t.Fatal(err)
	}
	defer src.Close()
	if _, err := src.Exec("update server set price='1.234' where id=1"); err != nil {
		t.Fatal(err)
	}
	defer src.Exec("update server set price=12.34 where id=1")
	t.Setenv("AIROPSCAT_SECRET_KEY", strings.Repeat("s", 40))
	out := filepath.Join(t.TempDir(), "must-not-exist.db")
	err = run(dsn, "AirOpsCatDefaultSecretKey2024", out, false)
	if err == nil {
		t.Fatal("异常金额应使导入失败")
	}
	for _, p := range []string{out, out + ".tmp", out + ".tmp-wal", out + ".tmp-shm"} {
		if _, err := os.Stat(p); err == nil {
			t.Fatalf("失败后残留输出: %s", p)
		}
	}
}
