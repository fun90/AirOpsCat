// Package dbbackup 用 SQLite VACUUM INTO 生成一致性快照，不复制 WAL。
package dbbackup

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"sort"
	"time"
)

var namePattern = regexp.MustCompile(`^airopscat-\d{8}-\d{6}\.db$`)

type Manager struct {
	DB  *sql.DB
	Dir string
	Now func() time.Time
}

type File struct {
	Name    string
	Size    int64
	ModTime time.Time
}

func (m *Manager) now() time.Time {
	if m.Now != nil {
		return m.Now()
	}
	return time.Now()
}

// Create 先写独占临时文件，通过完整性检查后原子发布；失败不留半成品。
func (m *Manager) Create(ctx context.Context) (string, error) {
	if err := os.MkdirAll(m.Dir, 0o700); err != nil {
		return "", err
	}
	name := "airopscat-" + m.now().Format("20060102-150405") + ".db"
	final := filepath.Join(m.Dir, name)
	if _, err := os.Lstat(final); err == nil {
		return "", errors.New("同一秒内已存在备份，请稍后重试")
	} else if !errors.Is(err, os.ErrNotExist) {
		return "", err
	}
	// 每次调用独占临时文件，且从创建起即为 0600，避免并发备份互相删除。
	f, err := os.CreateTemp(m.Dir, "."+name+"-*.tmp")
	if err != nil {
		return "", err
	}
	tmp := f.Name()
	defer os.Remove(tmp)
	if err := f.Close(); err != nil {
		return "", err
	}
	if _, err := m.DB.ExecContext(ctx, "VACUUM INTO ?", tmp); err != nil {
		return "", fmt.Errorf("生成备份: %w", err)
	}
	if err := check(ctx, tmp); err != nil {
		return "", err
	}
	// Link 原子发布且拒绝覆盖，保护不同 Manager 或进程同时生成的同名备份。
	if err := os.Link(tmp, final); errors.Is(err, os.ErrExist) {
		return "", errors.New("同一秒内已存在备份，请稍后重试")
	} else if err != nil {
		return "", fmt.Errorf("发布备份: %w", err)
	}
	return name, nil
}

func check(ctx context.Context, path string) error {
	dsn := (&url.URL{Scheme: "file", OmitHost: true, Path: path, RawQuery: "mode=ro"}).String()
	db, err := sql.Open("sqlite", dsn)
	if err != nil {
		return err
	}
	defer db.Close()
	var res string
	if err := db.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&res); err != nil {
		return err
	}
	if res != "ok" {
		return fmt.Errorf("备份完整性检查失败: %s", res)
	}
	return nil
}

func (m *Manager) List() ([]File, error) {
	entries, err := os.ReadDir(m.Dir)
	if errors.Is(err, os.ErrNotExist) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	var out []File
	for _, e := range entries {
		if !namePattern.MatchString(e.Name()) || !e.Type().IsRegular() {
			continue
		}
		info, err := e.Info()
		if err != nil {
			continue
		}
		out = append(out, File{e.Name(), info.Size(), info.ModTime()})
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name > out[j].Name })
	return out, nil
}

// Path 只接受本目录内符合命名规则的常规文件。
func (m *Manager) Path(name string) (string, error) {
	if !namePattern.MatchString(name) {
		return "", os.ErrNotExist
	}
	p := filepath.Join(m.Dir, name)
	if info, err := os.Lstat(p); err != nil || !info.Mode().IsRegular() {
		return "", os.ErrNotExist
	}
	return p, nil
}

func (m *Manager) Delete(name string) error {
	p, err := m.Path(name)
	if err != nil {
		return err
	}
	return os.Remove(p)
}

// Cleanup 删除超过 keep 的备份，返回删除数量。
func (m *Manager) Cleanup(keep time.Duration) (int, error) {
	files, err := m.List()
	if err != nil {
		return 0, err
	}
	n := 0
	for _, f := range files {
		if m.now().Sub(f.ModTime) > keep {
			if err := os.Remove(filepath.Join(m.Dir, f.Name)); err == nil {
				n++
			}
		}
	}
	return n, nil
}
