package dbbackup

import (
	"context"
	"database/sql"
	"os"
	"path/filepath"
	"sync"
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

func TestCreatePreservesSpecialDirectory(t *testing.T) {
	for _, dir := range []string{"question?", "fragment#", "percent%41", "quote'"} {
		t.Run(dir, func(t *testing.T) {
			m := newMgr(t)
			m.Dir = filepath.Join(m.Dir, dir)
			name, err := m.Create(context.Background())
			if err != nil {
				t.Fatal(err)
			}
			if _, err := os.Stat(filepath.Join(m.Dir, name)); err != nil {
				t.Fatal(err)
			}
		})
	}
}

func TestCreateDoesNotRemoveAnotherTemporaryFile(t *testing.T) {
	m := newMgr(t)
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	m.Now = func() time.Time { return now }
	if err := os.MkdirAll(m.Dir, 0o700); err != nil {
		t.Fatal(err)
	}
	// 模拟同秒的另一项备份已经在写入临时文件。
	other := filepath.Join(m.Dir, ".airopscat-20260101-000000.db.tmp")
	if err := os.WriteFile(other, []byte("another backup in progress"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := m.Create(context.Background()); err != nil {
		t.Fatal(err)
	}
	if body, err := os.ReadFile(other); err != nil || string(body) != "another backup in progress" {
		t.Fatalf("不应删除或修改另一项备份的临时文件: %v", err)
	}
}

func TestConcurrentCreatePublishesOneBackup(t *testing.T) {
	m := newMgr(t)
	m.DB.SetMaxOpenConns(1)
	now := time.Date(2026, 1, 1, 0, 0, 0, 0, time.UTC)
	m.Now = func() time.Time { return now }
	ctx := context.Background()
	start := make(chan struct{})
	results := make(chan error, 8)
	var wg sync.WaitGroup
	for range cap(results) {
		wg.Add(1)
		go func() {
			defer wg.Done()
			<-start
			// 不同 Manager 共用目录，覆盖进程内互斥无法保护的情况。
			other := &Manager{DB: m.DB, Dir: m.Dir, Now: m.Now}
			_, err := other.Create(ctx)
			results <- err
		}()
	}
	close(start)
	wg.Wait()
	close(results)
	succeeded := 0
	for err := range results {
		if err == nil {
			succeeded++
		}
	}
	if succeeded != 1 {
		t.Fatalf("同秒备份应且仅应发布一次，成功次数=%d", succeeded)
	}
	files, err := m.List()
	if err != nil || len(files) != 1 {
		t.Fatalf("备份列表: %v %v", files, err)
	}
	if err := check(ctx, filepath.Join(m.Dir, files[0].Name)); err != nil {
		t.Fatalf("发布的备份应完整: %v", err)
	}
	entries, err := os.ReadDir(m.Dir)
	if err != nil || len(entries) != 1 {
		t.Fatalf("不应残留临时文件: %v %v", entries, err)
	}
}

func TestListAndCleanupIgnoreTemporaryFiles(t *testing.T) {
	m := newMgr(t)
	if err := os.MkdirAll(m.Dir, 0o700); err != nil {
		t.Fatal(err)
	}
	name := ".airopscat-20260101-000000.db-other.tmp"
	path := filepath.Join(m.Dir, name)
	if err := os.WriteFile(path, []byte("in progress"), 0o600); err != nil {
		t.Fatal(err)
	}
	if files, err := m.List(); err != nil || len(files) != 0 {
		t.Fatalf("列表不应包含临时文件: %v %v", files, err)
	}
	if _, err := m.Path(name); !os.IsNotExist(err) {
		t.Fatalf("临时文件不应可下载或删除: %v", err)
	}
	if n, err := m.Cleanup(-time.Second); err != nil || n != 0 {
		t.Fatalf("清理不应移除临时文件: n=%d err=%v", n, err)
	}
	if _, err := os.Stat(path); err != nil {
		t.Fatal(err)
	}
}

func TestCreateFailureRemovesTemporaryFile(t *testing.T) {
	m := newMgr(t)
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	if _, err := m.Create(ctx); err == nil {
		t.Fatal("已取消的备份应失败")
	}
	if entries, err := os.ReadDir(m.Dir); err != nil || len(entries) != 0 {
		t.Fatalf("失败后不应残留临时文件: %v %v", entries, err)
	}
}

func TestCreateListDeleteCleanup(t *testing.T) {
	m := newMgr(t)
	now := time.Now()
	m.Now = func() time.Time { return now }
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
