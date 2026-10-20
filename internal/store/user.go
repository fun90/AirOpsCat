package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

type User struct {
	ID             int64
	Email          string
	NickName       string
	Password       string
	Role           string
	Disabled       bool
	FailedAttempts int
	LockTime       *time.Time
}

const userColumns = `id, email, coalesce(nick_name, ''), password, role, disabled, failed_attempts, lock_time`

func scanUser(row interface{ Scan(...any) error }) (*User, error) {
	var u User
	var lock sql.NullString
	if err := row.Scan(&u.ID, &u.Email, &u.NickName, &u.Password, &u.Role, &u.Disabled, &u.FailedAttempts, &lock); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			return nil, nil
		}
		return nil, err
	}
	if lock.Valid {
		t, err := ParseTime(lock.String)
		if err != nil {
			return nil, err
		}
		u.LockTime = &t
	}
	return &u, nil
}

// UserByEmail 按邮箱查找用户（不区分大小写），不存在返回 nil。
func UserByEmail(ctx context.Context, db *sql.DB, email string) (*User, error) {
	return scanUser(db.QueryRowContext(ctx, `SELECT `+userColumns+` FROM user WHERE email = ?`, email))
}

// RecordLoginFailure 原子地累加失败次数，达到 maxAttempts 时写入锁定时间。
func RecordLoginFailure(ctx context.Context, db *sql.DB, id int64, maxAttempts int, now time.Time) error {
	_, err := db.ExecContext(ctx, `UPDATE user SET failed_attempts = failed_attempts + 1,
		lock_time = CASE WHEN failed_attempts + 1 >= ? THEN ? ELSE lock_time END
		WHERE id = ?`, maxAttempts, FormatTime(now), id)
	return err
}

// ResetLoginFailures 清零失败次数并解除锁定。
func ResetLoginFailures(ctx context.Context, db *sql.DB, id int64) error {
	_, err := db.ExecContext(ctx, `UPDATE user SET failed_attempts = 0, lock_time = NULL WHERE id = ?`, id)
	return err
}

// CountUsers 返回用户总数和管理员数。
func CountUsers(ctx context.Context, db *sql.DB) (total, admins int, err error) {
	err = db.QueryRowContext(ctx, `SELECT count(*), count(*) FILTER (WHERE role = 'ADMIN') FROM user`).Scan(&total, &admins)
	return
}

// CreateUser 新建用户，password 为 bcrypt 哈希。
func CreateUser(ctx context.Context, db *sql.DB, email, passwordHash, role, nickName string) (int64, error) {
	now := FormatTime(time.Now())
	res, err := db.ExecContext(ctx, `INSERT INTO user (email, password, role, nick_name, create_time, update_time)
		VALUES (?, ?, ?, ?, ?, ?)`, email, passwordHash, role, nickName, now, now)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}
