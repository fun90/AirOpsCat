// Package store 负责打开 SQLite、执行 schema 迁移和各表 SQL。
package store

import (
	"context"
	"database/sql"
	"embed"
	"fmt"
	"io/fs"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"time"
	_ "time/tzdata"

	_ "modernc.org/sqlite"
)

//go:embed schema/*.sql
var schemaFS embed.FS

// TimeLayout 是库中时间列的文本格式，统一按上海本地时间解释。
const TimeLayout = "2006-01-02 15:04:05"

// nowFunc 便于测试替换当前时间。
var nowFunc = time.Now

// Shanghai 是所有时间列使用的时区。
var Shanghai = mustLoadLocation("Asia/Shanghai")

func mustLoadLocation(name string) *time.Location {
	loc, err := time.LoadLocation(name)
	if err != nil {
		panic(err)
	}
	return loc
}

// FormatTime 把时间转成库中存储的文本。
func FormatTime(t time.Time) string {
	return t.In(Shanghai).Format(TimeLayout)
}

// ParseTime 解析库中的时间文本。
func ParseTime(s string) (time.Time, error) {
	return time.ParseInLocation(TimeLayout, s, Shanghai)
}

// Open 打开数据库并执行未应用的 schema 迁移。
func Open(ctx context.Context, path string) (*sql.DB, error) {
	// _pragma 由驱动在每个新连接上执行，保证连接池里每个连接都开启外键
	q := url.Values{}
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "synchronous(NORMAL)")
	q.Set("_txlock", "immediate")
	db, err := sql.Open("sqlite", "file:"+path+"?"+q.Encode())
	if err != nil {
		return nil, err
	}
	db.SetMaxOpenConns(4)
	if err := migrate(ctx, db, schemaFS); err != nil {
		db.Close()
		return nil, err
	}
	return db, nil
}

// migrate 依次执行编号大于 user_version 的 schema 文件，每个文件与版本号更新在同一事务内。
func migrate(ctx context.Context, db *sql.DB, fsys fs.FS) error {
	files, err := fs.Glob(fsys, "schema/*.sql")
	if err != nil {
		return err
	}
	sort.Strings(files)
	var current int
	if err := db.QueryRowContext(ctx, "PRAGMA user_version").Scan(&current); err != nil {
		return err
	}
	for _, f := range files {
		name := f[strings.LastIndex(f, "/")+1:]
		version, err := strconv.Atoi(strings.SplitN(name, "_", 2)[0])
		if err != nil {
			return fmt.Errorf("schema 文件名缺少编号: %s", name)
		}
		if version <= current {
			continue
		}
		body, err := fs.ReadFile(fsys, f)
		if err != nil {
			return err
		}
		tx, err := db.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		if _, err := tx.ExecContext(ctx, string(body)); err != nil {
			tx.Rollback()
			return fmt.Errorf("执行 %s 失败: %w", name, err)
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", version)); err != nil {
			tx.Rollback()
			return err
		}
		if err := tx.Commit(); err != nil {
			return err
		}
		current = version
	}
	return nil
}
