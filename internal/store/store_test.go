package store

import (
	"context"
	"path/filepath"
	"testing"
	"testing/fstest"
)

func TestOpenMigratesOnceAndEnforcesForeignKeys(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "a.db")
	db, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	db.Close()

	// 重复打开不应重复执行迁移
	db, err = Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	var version int
	db.QueryRow("PRAGMA user_version").Scan(&version)
	if version != 13 {
		t.Fatalf("user_version = %d, want 13", version)
	}

	// 连接池中多个连接都必须开启外键
	conns := make([]interface{ Close() error }, 0, 3)
	for range 3 {
		c, err := db.Conn(ctx)
		if err != nil {
			t.Fatal(err)
		}
		conns = append(conns, c)
		var fk int
		c.QueryRowContext(ctx, "PRAGMA foreign_keys").Scan(&fk)
		if fk != 1 {
			t.Fatal("连接未开启 foreign_keys")
		}
	}
	for _, c := range conns {
		c.Close()
	}

	_, err = db.Exec(`INSERT INTO account_tag (account_id, tag_id) VALUES (999, 999)`)
	if err == nil {
		t.Fatal("非法外键引用应被拒绝")
	}
}

func TestMigrateRollsBackFailedFile(t *testing.T) {
	ctx := context.Background()
	db, err := Open(ctx, filepath.Join(t.TempDir(), "b.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()

	bad := fstest.MapFS{
		"schema/0001_init.sql": {Data: []byte("SELECT 1;")},
		"schema/0014_bad.sql":  {Data: []byte("CREATE TABLE t2 (id INTEGER); SELECT * FROM missing_table;")},
	}
	if err := migrate(ctx, db, bad); err == nil {
		t.Fatal("迁移应失败")
	}
	var version, n int
	db.QueryRow("PRAGMA user_version").Scan(&version)
	db.QueryRow("SELECT count(*) FROM sqlite_master WHERE name = 't2'").Scan(&n)
	if version != 13 || n != 0 {
		t.Fatalf("失败的迁移未回滚: version=%d t2=%d", version, n)
	}
}

func TestTimeRoundTrip(t *testing.T) {
	ts, err := ParseTime("2026-10-19 08:30:00")
	if err != nil {
		t.Fatal(err)
	}
	if got := FormatTime(ts); got != "2026-10-19 08:30:00" {
		t.Fatal(got)
	}
	if ts.UTC().Hour() != 0 {
		t.Fatal("应按上海时间解析")
	}
}

func TestTrustHostKeyOnlyFirstWins(t *testing.T) {
	ctx := context.Background()
	db, err := Open(ctx, filepath.Join(t.TempDir(), "h.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	res, _ := db.Exec(`INSERT INTO server (ip) VALUES ('203.0.113.1')`)
	id, _ := res.LastInsertId()
	if cur, _ := TrustHostKey(ctx, db, id, "SHA256:a"); cur != "SHA256:a" {
		t.Fatal(cur)
	}
	if cur, _ := TrustHostKey(ctx, db, id, "SHA256:b"); cur != "SHA256:a" {
		t.Fatal("已信任的指纹不应被覆盖", cur)
	}
	ClearHostKey(ctx, db, id)
	if cur, _ := TrustHostKey(ctx, db, id, "SHA256:b"); cur != "SHA256:b" {
		t.Fatal(cur)
	}
}
