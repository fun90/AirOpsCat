package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"regexp"
	"strings"
	"unicode/utf8"

	"golang.org/x/crypto/bcrypt"
)

// 密码长度：下限与 auth.MinPasswordLen 一致；上限是 bcrypt 的 72 字节输入限制。
// store 不能 import auth（auth 依赖 store），所以哈希在此直接调用 bcrypt，cost 与 auth.BcryptCost 一致。
const (
	minPasswordLen = 6
	maxPasswordLen = 72
	bcryptCost     = 10
)

var emailRe = regexp.MustCompile(`^[^\s@]+@[^\s@]+\.[^\s@]+$`)

// UserRoles 是允许的角色。
var UserRoles = []string{"ADMIN", "PARTNER", "VIP"}

// ManagedUser 是管理后台维护的用户（不含密码和登录锁定状态）。
type ManagedUser struct {
	ID           int64
	Email        string
	NickName     string
	RemarkName   string
	Remark       string
	Role         string
	Disabled     bool
	AccountCount int // 只读，列表和详情返回
}

func (u *ManagedUser) normalize() error {
	u.Email = strings.ToLower(strings.TrimSpace(u.Email))
	u.NickName = strings.TrimSpace(u.NickName)
	u.RemarkName = strings.TrimSpace(u.RemarkName)
	u.Remark = strings.TrimSpace(u.Remark)
	u.Role = strings.ToUpper(strings.TrimSpace(u.Role))
	if u.Email == "" {
		return invalid("邮箱地址不能为空")
	}
	if len(u.Email) > 254 || !emailRe.MatchString(u.Email) {
		return invalid("请输入有效的邮箱地址")
	}
	if u.Role == "" {
		u.Role = "VIP"
	}
	if !contains(UserRoles, u.Role) {
		return invalid("角色不合法")
	}
	if utf8.RuneCountInString(u.NickName) > 64 || utf8.RuneCountInString(u.RemarkName) > 64 {
		return invalid("昵称和备注名最多 64 个字符")
	}
	if utf8.RuneCountInString(u.Remark) > 200 {
		return invalid("备注最多 200 个字符")
	}
	return nil
}

func contains(list []string, s string) bool {
	for _, v := range list {
		if v == s {
			return true
		}
	}
	return false
}

func hashPassword(pw string) (string, error) {
	if len(pw) < minPasswordLen || len(pw) > maxPasswordLen {
		return "", invalid("密码长度需为 %d–%d 个字符", minPasswordLen, maxPasswordLen)
	}
	h, err := bcrypt.GenerateFromPassword([]byte(pw), bcryptCost)
	return string(h), err
}

func emailTaken(err error) error {
	if isUnique(err) {
		return fmt.Errorf("%w：邮箱已存在", ErrConflict)
	}
	return err
}

// insertUser 校验并写入新用户（开户与用户管理共用）。
func insertUser(ctx context.Context, q dbtx, u *ManagedUser, password string) error {
	if err := u.normalize(); err != nil {
		return err
	}
	hash, err := hashPassword(password)
	if err != nil {
		return err
	}
	now := FormatTime(nowFunc())
	res, err := q.ExecContext(ctx, `INSERT INTO user (email, password, nick_name, remark_name, remark, role, disabled, create_time, update_time)
		VALUES (?,?,?,?,?,?,?,?,?)`, u.Email, hash, nz(u.NickName), nz(u.RemarkName), nz(u.Remark), u.Role, u.Disabled, now, now)
	if err != nil {
		return emailTaken(err)
	}
	u.ID, err = res.LastInsertId()
	return err
}

const managedUserCols = `u.id, u.email, coalesce(u.nick_name,''), coalesce(u.remark_name,''), coalesce(u.remark,''), u.role, u.disabled,
	(SELECT count(*) FROM account WHERE user_id = u.id)`

func scanManagedUser(row interface{ Scan(...any) error }) (*ManagedUser, error) {
	var u ManagedUser
	if err := row.Scan(&u.ID, &u.Email, &u.NickName, &u.RemarkName, &u.Remark, &u.Role, &u.Disabled, &u.AccountCount); err != nil {
		return nil, err
	}
	return &u, nil
}

// ManagedUserByID 按 ID 查用户，不存在返回 nil。
func ManagedUserByID(ctx context.Context, db *sql.DB, id int64) (*ManagedUser, error) {
	u, err := scanManagedUser(db.QueryRowContext(ctx, `SELECT `+managedUserCols+` FROM user u WHERE u.id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return u, err
}

// ManagedUserList 按邮箱或昵称搜索，id 倒序分页；size 上限 100。
func ManagedUserList(ctx context.Context, db *sql.DB, search string, page, size int) ([]*ManagedUser, int, error) {
	limit, offset := pageArgs(page, size)
	like := "%" + likeEscape(strings.TrimSpace(search)) + "%"
	const where = ` WHERE u.email LIKE ? ESCAPE '\' OR u.nick_name LIKE ? ESCAPE '\'`
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM user u`+where, like, like).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT `+managedUserCols+` FROM user u`+where+` ORDER BY u.id DESC LIMIT ? OFFSET ?`, like, like, limit, offset)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*ManagedUser
	for rows.Next() {
		u, err := scanManagedUser(rows)
		if err != nil {
			return nil, 0, err
		}
		list = append(list, u)
	}
	return list, total, rows.Err()
}

// SaveManagedUser 新建（ID=0，password 必填）或更新用户（password 为空表示不改密）。
// 邮箱重复返回 ErrConflict；更新不存在的用户返回 sql.ErrNoRows。
func SaveManagedUser(ctx context.Context, db *sql.DB, u *ManagedUser, password string) error {
	if u.ID == 0 {
		return insertUser(ctx, db, u, password)
	}
	if err := u.normalize(); err != nil {
		return err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var oldRole string
	var oldDisabled bool
	if err := tx.QueryRowContext(ctx, `SELECT role,disabled FROM user WHERE id=?`, u.ID).Scan(&oldRole, &oldDisabled); err != nil {
		return err
	}
	if oldRole == "ADMIN" && !oldDisabled && (u.Role != "ADMIN" || u.Disabled) {
		var count int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM user WHERE role='ADMIN' AND disabled=0`).Scan(&count); err != nil {
			return err
		}
		if count <= 1 {
			return invalid("必须保留至少一个启用的管理员")
		}
	}
	now := FormatTime(nowFunc())
	var res sql.Result
	if password == "" {
		res, err = tx.ExecContext(ctx, `UPDATE user SET email=?, nick_name=?, remark_name=?, remark=?, role=?, disabled=?, update_time=? WHERE id=?`,
			u.Email, nz(u.NickName), nz(u.RemarkName), nz(u.Remark), u.Role, u.Disabled, now, u.ID)
	} else {
		var hash string
		if hash, err = hashPassword(password); err != nil {
			return err
		}
		res, err = tx.ExecContext(ctx, `UPDATE user SET email=?, nick_name=?, remark_name=?, remark=?, role=?, disabled=?, password=?, update_time=? WHERE id=?`,
			u.Email, nz(u.NickName), nz(u.RemarkName), nz(u.Remark), u.Role, u.Disabled, hash, now, u.ID)
	}
	if err != nil {
		return emailTaken(err)
	}
	if c, _ := res.RowsAffected(); c == 0 {
		return sql.ErrNoRows
	}
	return tx.Commit()
}

// DeleteManagedUser 删除用户。禁止删除自己、最后一个 ADMIN 和名下仍有账户的用户。
func DeleteManagedUser(ctx context.Context, db *sql.DB, id, currentUserID int64) error {
	if id == currentUserID {
		return invalid("不能删除当前登录的用户")
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var role string
	var accounts, admins int
	err = tx.QueryRowContext(ctx, `SELECT role, (SELECT count(*) FROM account WHERE user_id = ?),
		(SELECT count(*) FROM user WHERE role = 'ADMIN' AND disabled=0) FROM user WHERE id = ?`, id, id).Scan(&role, &accounts, &admins)
	if err != nil {
		return err // 含 sql.ErrNoRows
	}
	if accounts > 0 {
		return fmt.Errorf("%w：该用户名下还有 %d 个账户，请先删除或转移", ErrConflict, accounts)
	}
	if role == "ADMIN" && admins <= 1 {
		return invalid("不能删除最后一个管理员")
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM user WHERE id = ?`, id); err != nil {
		return err
	}
	return tx.Commit()
}

// AccountUserOptions 是账户表单的用户选项，不复用分页列表的 100 行上限。
func AccountUserOptions(ctx context.Context, db *sql.DB) ([]*ManagedUser, int, error) {
	rows, err := db.QueryContext(ctx, `SELECT id,email,coalesce(nick_name,'') FROM user ORDER BY email`)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*ManagedUser
	for rows.Next() {
		var u ManagedUser
		if err := rows.Scan(&u.ID, &u.Email, &u.NickName); err != nil {
			return nil, 0, err
		}
		list = append(list, &u)
	}
	return list, len(list), rows.Err()
}
