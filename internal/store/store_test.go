package store

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"testing/fstest"
)

func TestOpenPreservesFilename(t *testing.T) {
	for _, name := range []string{"question?.db", "fragment#.db", "percent%41.db", "quote'.db", "plain.db"} {
		t.Run(name, func(t *testing.T) {
			// 相对路径也必须保留原始文件名和连接选项。
			t.Chdir(t.TempDir())
			db, err := Open(context.Background(), name)
			if err != nil {
				t.Fatal(err)
			}
			defer db.Close()
			if _, err := os.Stat(name); err != nil {
				t.Fatalf("数据库应位于原始路径: %v", err)
			}
			var fk int
			if err := db.QueryRow("PRAGMA foreign_keys").Scan(&fk); err != nil || fk != 1 {
				t.Fatalf("路径编码不应丢失连接选项: foreign_keys=%d err=%v", fk, err)
			}
			if _, err := db.Exec("CREATE TABLE path_marker(value); INSERT INTO path_marker VALUES(42)"); err != nil {
				t.Fatal(err)
			}
			absolute, err := filepath.Abs(name)
			if err != nil {
				t.Fatal(err)
			}
			reopened, err := Open(context.Background(), absolute)
			if err != nil {
				t.Fatal(err)
			}
			defer reopened.Close()
			var marker int
			if err := reopened.QueryRow("SELECT value FROM path_marker").Scan(&marker); err != nil || marker != 42 {
				t.Fatalf("绝对路径应打开同一文件: marker=%d err=%v", marker, err)
			}
		})
	}
}

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
	if version != 21 {
		t.Fatalf("user_version = %d, want 21", version)
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

	var originalVersion int
	if err := db.QueryRow("PRAGMA user_version").Scan(&originalVersion); err != nil {
		t.Fatal(err)
	}
	bad := fstest.MapFS{
		"schema/0001_init.sql": {Data: []byte("SELECT 1;")},
		"schema/9999_bad.sql":  {Data: []byte("CREATE TABLE t2 (id INTEGER); SELECT * FROM missing_table;")},
	}
	if err := migrate(ctx, db, bad); err == nil {
		t.Fatal("迁移应失败")
	}
	var version, n int
	db.QueryRow("PRAGMA user_version").Scan(&version)
	db.QueryRow("SELECT count(*) FROM sqlite_master WHERE name = 't2'").Scan(&n)
	if version != originalVersion || n != 0 {
		t.Fatalf("失败的迁移未回滚: version=%d t2=%d", version, n)
	}
}

// fkOffSchema 构造一个父子级联的旧库，再用声明关闭外键的迁移重建父表。
func fkOffSchema(rebuild string) fstest.MapFS {
	return fstest.MapFS{
		"schema/0001_init.sql": {Data: []byte(`CREATE TABLE p (id INTEGER PRIMARY KEY);
CREATE TABLE c (id INTEGER PRIMARY KEY, p_id INTEGER NOT NULL REFERENCES p (id) ON DELETE CASCADE);
INSERT INTO p VALUES (1), (2);
INSERT INTO c VALUES (1, 1), (2, 2);`)},
		"schema/0002_rebuild.sql": {Data: []byte(rebuild)},
	}
}

func TestMigrateForeignKeysOffRebuildKeepsChildren(t *testing.T) {
	ctx := context.Background()
	db := openRawFK(t, filepath.Join(t.TempDir(), "fk.db"))
	db.SetMaxOpenConns(1) // 迁移与后续检查必然复用同一连接
	err := migrate(ctx, db, fkOffSchema(foreignKeysOffMarker+`
CREATE TABLE p_new (id INTEGER PRIMARY KEY AUTOINCREMENT);
INSERT INTO p_new SELECT id FROM p;
DROP TABLE p;
ALTER TABLE p_new RENAME TO p;`))
	if err != nil {
		t.Fatal(err)
	}
	var children, fk, version int
	db.QueryRow(`SELECT count(*) FROM c`).Scan(&children)
	db.QueryRow(`PRAGMA foreign_keys`).Scan(&fk)
	db.QueryRow(`PRAGMA user_version`).Scan(&version)
	if children != 2 || fk != 1 || version != 2 {
		t.Fatalf("children=%d foreign_keys=%d version=%d", children, fk, version)
	}
	if _, err := db.Exec(`DELETE FROM p WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	db.QueryRow(`SELECT count(*) FROM c`).Scan(&children)
	if children != 1 {
		t.Fatal("重建后级联删除失效", children)
	}
}

func TestMigrateForeignKeysOffRejectsDanglingAndRestores(t *testing.T) {
	for name, body := range map[string]string{
		// 删除父行留下悬空子行：foreign_key_check 必须阻止提交
		"dangling": `DELETE FROM p WHERE id=1;`,
		"sqlerror": `CREATE TABLE p_new (id INTEGER PRIMARY KEY); SELECT * FROM missing_table;`,
	} {
		t.Run(name, func(t *testing.T) {
			ctx := context.Background()
			db := openRawFK(t, filepath.Join(t.TempDir(), "fk.db"))
			db.SetMaxOpenConns(1)
			fsys := fkOffSchema(foreignKeysOffMarker + "\n" + body)
			if err := migrate(ctx, db, fsys); err == nil {
				t.Fatal("迁移应失败")
			}
			var parents, version, fk, tables int
			db.QueryRow(`SELECT count(*) FROM p`).Scan(&parents)
			db.QueryRow(`PRAGMA user_version`).Scan(&version)
			db.QueryRow(`PRAGMA foreign_keys`).Scan(&fk)
			db.QueryRow(`SELECT count(*) FROM sqlite_schema WHERE name='p_new'`).Scan(&tables)
			if parents != 2 || version != 1 || tables != 0 {
				t.Fatalf("失败的迁移未回滚: parents=%d version=%d p_new=%d", parents, version, tables)
			}
			if fk != 1 {
				t.Fatal("失败后连接未恢复外键")
			}
		})
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
