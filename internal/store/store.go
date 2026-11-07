// Package store 负责打开 SQLite、执行 schema 迁移和各表 SQL。
package store

import (
	"context"
	"database/sql"
	"database/sql/driver"
	"embed"
	"errors"
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

// dsn 生成连接串；_pragma 由驱动在每个新连接上执行，保证连接池里每个连接都开启外键。
func dsn(path string) string {
	q := url.Values{}
	q.Add("_pragma", "foreign_keys(1)")
	q.Add("_pragma", "busy_timeout(5000)")
	q.Add("_pragma", "journal_mode(WAL)")
	q.Add("_pragma", "synchronous(NORMAL)")
	q.Set("_txlock", "immediate")
	return (&url.URL{Scheme: "file", OmitHost: true, Path: path, RawQuery: q.Encode()}).String()
}

// Open 打开数据库并执行未应用的 schema 迁移。
func Open(ctx context.Context, path string) (*sql.DB, error) {
	db, err := sql.Open("sqlite", dsn(path))
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

// foreignKeysOffMarker 写在迁移文件首行，表示该迁移需要在关闭外键的连接上执行（重建被引用的表）。
// 外键开启时 DROP TABLE 会隐式 DELETE 并触发子表 ON DELETE CASCADE。
const foreignKeysOffMarker = "-- airopscat:foreign-keys-off"

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
		if strings.HasPrefix(string(body), foreignKeysOffMarker) {
			err = migrateForeignKeysOff(ctx, db, name, string(body), version)
		} else {
			err = migrateFile(ctx, db, name, string(body), version)
		}
		if err != nil {
			return err
		}
		current = version
	}
	return nil
}

func migrateFile(ctx context.Context, db *sql.DB, name, body string, version int) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	if _, err := tx.ExecContext(ctx, body); err != nil {
		tx.Rollback()
		return fmt.Errorf("执行 %s 失败: %w", name, err)
	}
	if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", version)); err != nil {
		tx.Rollback()
		return err
	}
	return tx.Commit()
}

// migrateForeignKeysOff 按 SQLite 12 步重建法执行：固定一个连接，事务外关闭外键，事务内执行迁移，
// foreign_key_check 无结果才提交；结束后恢复外键，无法确认恢复时丢弃该连接，不让它回到连接池。
func migrateForeignKeysOff(ctx context.Context, db *sql.DB, name, body string, version int) (err error) {
	conn, err := db.Conn(ctx)
	if err != nil {
		return err
	}
	defer conn.Close()
	restored := false
	defer func() {
		if !restored {
			// 返回 ErrBadConn 让 database/sql 关闭该底层连接
			conn.Raw(func(any) error { return driver.ErrBadConn })
			if err == nil {
				err = fmt.Errorf("执行 %s 后无法恢复外键约束", name)
			}
		}
	}()
	pragma := func(want int) error {
		if _, err := conn.ExecContext(ctx, fmt.Sprintf("PRAGMA foreign_keys = %d", want)); err != nil {
			return err
		}
		var got int
		if err := conn.QueryRowContext(ctx, "PRAGMA foreign_keys").Scan(&got); err != nil {
			return err
		}
		if got != want {
			return fmt.Errorf("PRAGMA foreign_keys 期望 %d，实际 %d", want, got)
		}
		return nil
	}
	if err := pragma(0); err != nil {
		return fmt.Errorf("执行 %s 前关闭外键失败: %w", name, err)
	}
	err = func() error {
		tx, err := conn.BeginTx(ctx, nil)
		if err != nil {
			return err
		}
		defer tx.Rollback()
		if _, err := tx.ExecContext(ctx, body); err != nil {
			return fmt.Errorf("执行 %s 失败: %w", name, err)
		}
		if err := foreignKeyViolation(ctx, tx); err != nil {
			return fmt.Errorf("执行 %s 后外键检查失败: %w", name, err)
		}
		if _, err := tx.ExecContext(ctx, fmt.Sprintf("PRAGMA user_version = %d", version)); err != nil {
			return err
		}
		return tx.Commit()
	}()
	if e := pragma(1); e == nil {
		restored = true
	}
	return err
}

// foreignKeyViolation 在事务内运行 PRAGMA foreign_key_check，有任何结果即报错。
func foreignKeyViolation(ctx context.Context, tx *sql.Tx) error {
	rows, err := tx.QueryContext(ctx, "PRAGMA foreign_key_check")
	if err != nil {
		return err
	}
	defer rows.Close()
	var bad []string
	for rows.Next() {
		var table, parent string
		var rowid sql.NullInt64
		var fkid int
		if err := rows.Scan(&table, &rowid, &parent, &fkid); err != nil {
			return err
		}
		if len(bad) < 5 {
			bad = append(bad, fmt.Sprintf("%s(rowid=%d)→%s", table, rowid.Int64, parent))
		}
	}
	if err := rows.Err(); err != nil {
		return err
	}
	if len(bad) > 0 {
		return errors.New("存在悬空外键：" + strings.Join(bad, "，"))
	}
	return nil
}
