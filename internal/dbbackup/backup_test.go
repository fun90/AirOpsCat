package dbbackup

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"testing"
	"time"

	_ "modernc.org/sqlite"
)

func newMgr(t *testing.T) *Manager {
	t.Helper()
	dir := t.TempDir()
	db, err := sql.Open("sqlite", filepath.Join(dir, "a.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	if _, err := db.Exec("CREATE TABLE t(x); INSERT INTO t VALUES (1)"); err != nil {
		t.Fatal(err)
	}
	return &Manager{DB: db, Dir: filepath.Join(dir, "bk")}
}

func TestCreateListDeleteCleanup(t *testing.T) {
	m := newMgr(t)
	name, err := m.Create(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	info, _ := os.Stat(filepath.Join(m.Dir, name))
	if info.Mode().Perm() != 0o600 {
		t.Fatalf("权限 %v", info.Mode())
	}
	if l, _ := m.List(); len(l) != 1 || l[0].Name != name {
		t.Fatalf("list %v", l)
	}
	if _, err := m.Create(context.Background()); err == nil {
		t.Fatal("同秒重复应拒绝")
	}
	for _, bad := range []string{"../a.db", "a.db", "airopscat-20200101-000000.db/../../x", ""} {
		if _, err := m.Path(bad); err == nil {
			t.Fatalf("应拒绝 %q", bad)
		}
	}
	m.Now = func() time.Time { return time.Now().Add(16 * 24 * time.Hour) }
	if n, _ := m.Cleanup(15 * 24 * time.Hour); n != 1 {
		t.Fatalf("清理 %d", n)
	}
	if l, _ := m.List(); len(l) != 0 {
		t.Fatal("应已清空")
	}
}
