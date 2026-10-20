package store

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"errors"
	"fmt"
	"regexp"
	"slices"
	"strings"
	"time"
	"unicode/utf8"
)

// 周期类型
const (
	PeriodMonthly = "MONTHLY"
	PeriodYearly  = "YEARLY"
)

// PaymentMethods 是记账允许的付款方式（区分大小写，沿用旧系统取值）。
var PaymentMethods = []string{"WeChat", "AliPay", "BankCard"}

const maxCents = 10_000_000_000 // 1 亿元，防止误输入

var (
	uuidRe      = regexp.MustCompile(`^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$`)
	accountNoRe = regexp.MustCompile(`^[A-Za-z0-9_-]{1,32}$`)
	authCodeRe  = regexp.MustCompile(`^[A-Za-z0-9_-]{16,64}$`)
)

// Account 既是账户的读取结果，也是开户和更新的输入。
// 可空整数用 0 表示不限/未设置，库中存 NULL。
type Account struct {
	ID             int64
	UUID           string
	AccountNo      string
	AuthCode       string
	NodeMultiple   int
	NodePrefix     string
	FromDate       *time.Time
	ToDate         *time.Time // nil 表示永不过期
	PeriodType     string
	MaxConnections int
	MaxIps         int
	Bandwidth      int // GB
	DownloadMbps   int
	UploadMbps     int
	Disabled       bool
	Remark         string
	UserID         int64 // 0 表示无所属用户
	// TagIDs 开户时可空；更新时 nil 表示不改标签，空切片表示清空。
	TagIDs []int64
}

// AccountRow 是列表行。
type AccountRow struct {
	Account
	UserEmail   string
	UserNick    string
	Tags        []*Tag
	UsedBytes   int64 // 当期上传+下载
	QuotaGB     int   // 有效配额，0 表示不限量
	OnlineConns int
	LastOnline  *time.Time
}

// AccountFilter 是列表筛选；Status 取 active|expired|disabled|expiring，Online 取 online|offline，其他值忽略。
type AccountFilter struct {
	Search       string
	UserID       int64
	Status       string
	Online       string
	OnlineWithin time.Duration // 在线判定窗口，默认 10 分钟
	Sort         string        // create_time（默认）或 to_date
	Asc          bool
}

// RecentNode 是账户最近使用过的节点。
type RecentNode struct {
	NodeID     int64 // 0 表示上报时未能映射到节点
	NodeName   string
	NodeIP     string
	LastOnline time.Time
}

// OnboardInput 是开户请求：UserID 与 NewUser 二选一；AmountCents 非 nil 时记一笔首期收入。
type OnboardInput struct {
	UserID        int64
	NewUser       *ManagedUser
	Password      string
	Account       Account
	AmountCents   *int64
	PaymentMethod string
}

type OnboardResult struct {
	UserID, AccountID, TransactionID int64
}

func randHex(n int) string {
	b := make([]byte, n)
	rand.Read(b)
	return hex.EncodeToString(b)
}

func newUUID() string {
	b := make([]byte, 16)
	rand.Read(b)
	b[6] = b[6]&0x0f | 0x40
	b[8] = b[8]&0x3f | 0x80
	h := hex.EncodeToString(b)
	return h[:8] + "-" + h[8:12] + "-" + h[12:16] + "-" + h[16:20] + "-" + h[20:]
}

func likeEscape(s string) string {
	return strings.NewReplacer(`\`, `\\`, `%`, `\%`, `_`, `\_`).Replace(s)
}

// pageArgs 把页码和页大小夹到合法范围（size 上限 100）。
func pageArgs(page, size int) (limit, offset int) {
	if page < 1 {
		page = 1
	}
	if size < 1 {
		size = 10
	}
	if size > 100 {
		size = 100
	}
	return size, (page - 1) * size
}

func dateArg(t *time.Time) any {
	if t == nil {
		return nil
	}
	return FormatTime(*t)
}

func nzInt(n int) any {
	if n == 0 {
		return nil
	}
	return n
}

func (a *Account) normalize(create bool) error {
	a.UUID = strings.ToLower(strings.TrimSpace(a.UUID))
	a.AccountNo = strings.TrimSpace(a.AccountNo)
	a.AuthCode = strings.TrimSpace(a.AuthCode)
	a.NodePrefix = strings.TrimSpace(a.NodePrefix)
	a.Remark = strings.TrimSpace(a.Remark)
	a.PeriodType = strings.ToUpper(strings.TrimSpace(a.PeriodType))
	switch {
	case a.UUID == "" && create:
		a.UUID = newUUID()
	case a.UUID == "":
		return invalid("UUID 不能为空")
	case !uuidRe.MatchString(a.UUID):
		return invalid("UUID 格式不合法")
	}
	if a.AccountNo != "" && !accountNoRe.MatchString(a.AccountNo) {
		return invalid("账户编号只能包含字母、数字、下划线和连字符，最长 32 位")
	}
	if a.AuthCode != "" && !authCodeRe.MatchString(a.AuthCode) {
		return invalid("订阅码只能包含字母、数字、下划线和连字符，长度 16–64 位")
	}
	if a.NodeMultiple < 1 || a.NodeMultiple > 10 {
		return invalid("节点倍数必须在 1 到 10 之间")
	}
	if utf8.RuneCountInString(a.NodePrefix) > 32 {
		return invalid("节点前缀最多 32 个字符")
	}
	if utf8.RuneCountInString(a.Remark) > 200 {
		return invalid("备注最多 200 个字符")
	}
	if a.PeriodType == "" {
		a.PeriodType = PeriodMonthly
	}
	if a.PeriodType != PeriodMonthly && a.PeriodType != PeriodYearly {
		return invalid("周期类型不合法")
	}
	for _, c := range []struct {
		name string
		v    int
		max  int
	}{{"最大连接数", a.MaxConnections, 1_000_000}, {"最大 IP 数", a.MaxIps, 1_000_000}, {"流量配额", a.Bandwidth, 1_000_000},
		{"下行限速", a.DownloadMbps, 100_000}, {"上行限速", a.UploadMbps, 100_000}} {
		if c.v < 0 || c.v > c.max {
			return invalid("%s必须在 0 到 %d 之间（0 表示不限）", c.name, c.max)
		}
	}
	if create && a.FromDate == nil {
		now := nowFunc()
		a.FromDate = &now
	}
	if a.FromDate != nil && a.ToDate != nil && !a.ToDate.After(*a.FromDate) {
		return invalid("到期时间必须晚于开始时间")
	}
	if a.TagIDs != nil {
		ids := normalizeTagIDs(a.TagIDs)
		if ids == nil {
			ids = []int64{}
		}
		if slices.ContainsFunc(ids, func(id int64) bool { return id <= 0 }) {
			return invalid("标签不存在")
		}
		a.TagIDs = ids
	}
	return nil
}

func validatePayment(amount *int64, method string) error {
	if amount == nil {
		return nil
	}
	if *amount <= 0 || *amount > maxCents {
		return invalid("请输入有效金额")
	}
	if method == "" {
		return invalid("填写金额时必须选择付款方式")
	}
	if !slices.Contains(PaymentMethods, method) {
		return invalid("付款方式不合法")
	}
	return nil
}

// column 只接受内部常量，不拼接用户输入。
func taken(ctx context.Context, q dbtx, column, val string, exceptID int64) (bool, error) {
	var n int
	err := q.QueryRowContext(ctx, `SELECT count(*) FROM account WHERE `+column+` = ? AND id <> ?`, val, exceptID).Scan(&n)
	return n > 0, err
}

func checkUnique(ctx context.Context, q dbtx, column, label, val string, exceptID int64) error {
	t, err := taken(ctx, q, column, val, exceptID)
	if err != nil {
		return err
	}
	if t {
		return fmt.Errorf("%w：%s已存在", ErrConflict, label)
	}
	return nil
}

// freshValue 生成库中不存在的随机值。
func freshValue(ctx context.Context, q dbtx, column string, gen func() string) (string, error) {
	for range 10 {
		v := gen()
		if t, err := taken(ctx, q, column, v, 0); err != nil || !t {
			return v, err
		}
	}
	return "", errors.New("生成唯一值失败")
}

func userExists(ctx context.Context, q dbtx, id int64) error {
	var n int
	if err := q.QueryRowContext(ctx, `SELECT count(*) FROM user WHERE id = ?`, id).Scan(&n); err != nil {
		return err
	}
	if n == 0 {
		return invalid("用户不存在")
	}
	return nil
}

func setAccountTags(ctx context.Context, q dbtx, accountID int64, tagIDs []int64) error {
	if _, err := q.ExecContext(ctx, `DELETE FROM account_tag WHERE account_id = ?`, accountID); err != nil {
		return err
	}
	for _, id := range tagIDs {
		if _, err := q.ExecContext(ctx, `INSERT INTO account_tag (account_id, tag_id) VALUES (?, ?)`, accountID, id); err != nil {
			return invalid("标签不存在")
		}
	}
	return nil
}

// markAccountPending 把与账户共享标签的节点的部署位置为待部署（决策 24 D2）。
func markAccountPending(ctx context.Context, q dbtx, accountID int64) error {
	_, err := q.ExecContext(ctx, `UPDATE node_server SET deployed = 0, update_time = ? WHERE deployed <> 2 AND node_id IN
		(SELECT nt.node_id FROM account_tag at JOIN node_tag nt ON nt.tag_id = at.tag_id WHERE at.account_id = ?)`,
		FormatTime(nowFunc()), accountID)
	return err
}

// calendarMonths 按日历月差计算流水描述里的月数，最小 1。
func calendarMonths(from, to time.Time) int {
	from, to = from.In(Shanghai), to.In(Shanghai)
	if !to.After(from) {
		return 1
	}
	return max(1, (to.Year()-from.Year())*12+int(to.Month())-int(from.Month()))
}

func insertAccountIncome(ctx context.Context, q dbtx, accountID int64, cents int64, method, desc, remark string) (int64, error) {
	now := FormatTime(nowFunc())
	res, err := q.ExecContext(ctx, `INSERT INTO transactions (transaction_date, amount, type, business_table, business_id, description, payment_method, remark, create_time, update_time)
		VALUES (?, ?, 0, 'account', ?, ?, ?, ?, ?, ?)`, now, cents, accountID, desc, method, nz(remark), now, now)
	if err != nil {
		return 0, err
	}
	return res.LastInsertId()
}

const accountCols = `a.id, coalesce(a.node_multiple,0), coalesce(a.node_prefix,''), a.from_date, a.to_date, a.period_type, a.uuid, a.account_no, a.auth_code,
	coalesce(a.max_connections,0), coalesce(a.max_ips,0), coalesce(a.bandwidth,0), coalesce(a.download_mbps,0), coalesce(a.upload_mbps,0),
	a.disabled, coalesce(a.remark,''), coalesce(a.user_id,0)`

func scanAccount(row interface{ Scan(...any) error }, extra ...any) (*Account, error) {
	var a Account
	var from, to sql.NullString
	dest := []any{&a.ID, &a.NodeMultiple, &a.NodePrefix, &from, &to, &a.PeriodType, &a.UUID, &a.AccountNo, &a.AuthCode,
		&a.MaxConnections, &a.MaxIps, &a.Bandwidth, &a.DownloadMbps, &a.UploadMbps, &a.Disabled, &a.Remark, &a.UserID}
	if err := row.Scan(append(dest, extra...)...); err != nil {
		return nil, err
	}
	for _, d := range []struct {
		s   sql.NullString
		dst **time.Time
	}{{from, &a.FromDate}, {to, &a.ToDate}} {
		if d.s.Valid {
			t, err := ParseTime(d.s.String)
			if err != nil {
				return nil, err
			}
			*d.dst = &t
		}
	}
	return &a, nil
}

func loadAccount(ctx context.Context, q dbtx, id int64) (*Account, error) {
	a, err := scanAccount(q.QueryRowContext(ctx, `SELECT `+accountCols+` FROM account a WHERE a.id = ?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	if err != nil {
		return nil, err
	}
	rows, err := q.QueryContext(ctx, `SELECT tag_id FROM account_tag WHERE account_id = ? ORDER BY tag_id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	a.TagIDs = []int64{}
	for rows.Next() {
		var t int64
		if err := rows.Scan(&t); err != nil {
			return nil, err
		}
		a.TagIDs = append(a.TagIDs, t)
	}
	return a, rows.Err()
}

// AccountByID 按 ID 查账户（含标签 ID），不存在返回 nil。
func AccountByID(ctx context.Context, db *sql.DB, id int64) (*Account, error) {
	return loadAccount(ctx, db, id)
}

func (f AccountFilter) where(now time.Time) (string, []any) {
	within := f.OnlineWithin
	if within <= 0 {
		within = 10 * time.Minute
	}
	args := []any{sql.Named("now", FormatTime(now)), sql.Named("soon", FormatTime(now.AddDate(0, 0, 7))),
		sql.Named("cutoff", FormatTime(now.Add(-within)))}
	var conds []string
	if s := strings.TrimSpace(f.Search); s != "" {
		esc := likeEscape(s)
		args = append(args, sql.Named("pre", esc+"%"), sql.Named("con", "%"+esc+"%"), sql.Named("raw", s))
		conds = append(conds, `(a.account_no LIKE @pre ESCAPE '\' OR a.remark LIKE @con ESCAPE '\' OR a.uuid LIKE @pre ESCAPE '\' OR a.auth_code = @raw
			OR a.user_id IN (SELECT id FROM user WHERE email LIKE @pre ESCAPE '\' OR nick_name LIKE @con ESCAPE '\'))`)
	}
	if f.UserID != 0 {
		args = append(args, sql.Named("uid", f.UserID))
		conds = append(conds, `a.user_id = @uid`)
	}
	switch strings.ToLower(f.Status) {
	case "active":
		conds = append(conds, `a.disabled = 0 AND (a.to_date IS NULL OR a.to_date >= @now)`)
	case "expired":
		conds = append(conds, `a.disabled = 0 AND a.to_date IS NOT NULL AND a.to_date < @now`)
	case "disabled":
		conds = append(conds, `a.disabled = 1`)
	case "expiring":
		conds = append(conds, `a.disabled = 0 AND a.to_date > @now AND a.to_date <= @soon`)
	}
	switch strings.ToLower(f.Online) {
	case "online":
		conds = append(conds, `a.account_no IN (SELECT account_no FROM account_online_ip WHERE last_online_time > @cutoff)`)
	case "offline":
		conds = append(conds, `a.account_no NOT IN (SELECT account_no FROM account_online_ip WHERE last_online_time > @cutoff)`)
	}
	if len(conds) == 0 {
		return "", args
	}
	return " WHERE " + strings.Join(conds, " AND "), args
}

// 当期统计行：period_start <= now < period_end，重叠时取最近开始的一行。
const currentStats = `FROM account_traffic_stats s WHERE s.account_id = a.id AND s.period_start <= @now AND s.period_end > @now`

// AccountList 按筛选分页返回账户；size 上限 100，默认 create_time 倒序。
func AccountList(ctx context.Context, db *sql.DB, f AccountFilter, page, size int) ([]*AccountRow, int, error) {
	where, args := f.where(nowFunc())
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM account a`+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	order := "a.create_time"
	if f.Sort == "to_date" {
		order = "a.to_date"
	}
	dir := "DESC"
	if f.Asc {
		dir = "ASC"
	}
	limit, offset := pageArgs(page, size)
	args = append(args, sql.Named("limit", limit), sql.Named("offset", offset))
	rows, err := db.QueryContext(ctx, `SELECT `+accountCols+`, coalesce(u.email,''), coalesce(u.nick_name,''),
		(SELECT coalesce(sum(s.upload_bytes + s.download_bytes),0) `+currentStats+`),
		coalesce((SELECT s.bandwidth_quota `+currentStats+` ORDER BY s.period_start DESC LIMIT 1), a.bandwidth, 0),
		(SELECT count(*) FROM account_online_ip o WHERE o.account_no = a.account_no AND o.last_online_time > @cutoff),
		(SELECT max(o.last_online_time) FROM account_online_ip o WHERE o.account_no = a.account_no)
		FROM account a LEFT JOIN user u ON u.id = a.user_id`+where+
		` ORDER BY `+order+` `+dir+`, a.id `+dir+` LIMIT @limit OFFSET @offset`, args...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*AccountRow
	byID := map[int64]*AccountRow{}
	var ids []any
	for rows.Next() {
		var r AccountRow
		var last sql.NullString
		a, err := scanAccount(rows, &r.UserEmail, &r.UserNick, &r.UsedBytes, &r.QuotaGB, &r.OnlineConns, &last)
		if err != nil {
			return nil, 0, err
		}
		r.Account = *a
		r.TagIDs = []int64{}
		if last.Valid {
			t, err := ParseTime(last.String)
			if err != nil {
				return nil, 0, err
			}
			r.LastOnline = &t
		}
		list = append(list, &r)
		byID[r.ID] = &r
		ids = append(ids, r.ID)
	}
	if err := rows.Err(); err != nil {
		return nil, 0, err
	}
	rows.Close()
	if len(ids) == 0 {
		return list, total, nil
	}
	trows, err := db.QueryContext(ctx, `SELECT at.account_id, t.id, t.name, coalesce(t.color,'') FROM account_tag at JOIN tag t ON t.id = at.tag_id
		WHERE at.account_id IN (?`+strings.Repeat(",?", len(ids)-1)+`) ORDER BY t.name`, ids...)
	if err != nil {
		return nil, 0, err
	}
	defer trows.Close()
	for trows.Next() {
		var aid int64
		var t Tag
		if err := trows.Scan(&aid, &t.ID, &t.Name, &t.Color); err != nil {
			return nil, 0, err
		}
		r := byID[aid]
		r.Tags = append(r.Tags, &t)
		r.TagIDs = append(r.TagIDs, t.ID)
	}
	return list, total, trows.Err()
}

func insertAccount(ctx context.Context, q dbtx, a *Account) error {
	var err error
	if a.AccountNo == "" {
		if a.AccountNo, err = freshValue(ctx, q, "account_no", func() string { return randHex(6) }); err != nil {
			return err
		}
	} else if err = checkUnique(ctx, q, "account_no", "账户编号", a.AccountNo, 0); err != nil {
		return err
	}
	if a.AuthCode == "" {
		if a.AuthCode, err = freshValue(ctx, q, "auth_code", func() string { return randHex(16) }); err != nil {
			return err
		}
	} else if err = checkUnique(ctx, q, "auth_code", "订阅码", a.AuthCode, 0); err != nil {
		return err
	}
	if err = checkUnique(ctx, q, "uuid", "UUID", a.UUID, 0); err != nil {
		return err
	}
	now := FormatTime(nowFunc())
	res, err := q.ExecContext(ctx, `INSERT INTO account (node_multiple, node_prefix, from_date, to_date, period_type, uuid, account_no, auth_code,
		max_connections, max_ips, bandwidth, download_mbps, upload_mbps, disabled, remark, user_id, create_time, update_time)
		VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)`,
		a.NodeMultiple, nz(a.NodePrefix), dateArg(a.FromDate), dateArg(a.ToDate), a.PeriodType, a.UUID, a.AccountNo, a.AuthCode,
		nzInt(a.MaxConnections), nzInt(a.MaxIps), nzInt(a.Bandwidth), nzInt(a.DownloadMbps), nzInt(a.UploadMbps), a.Disabled, nz(a.Remark),
		nzInt(int(a.UserID)), now, now)
	if err != nil {
		if isUnique(err) {
			return fmt.Errorf("%w：账户编号、订阅码或 UUID 已存在", ErrConflict)
		}
		return err
	}
	a.ID, err = res.LastInsertId()
	return err
}

// Onboard 在一个事务内完成开户：可选新建用户、账户、标签关联和首期收入流水。
// 新账户的标签关联节点会置为待部署。
func Onboard(ctx context.Context, db *sql.DB, in OnboardInput) (*OnboardResult, error) {
	if (in.UserID != 0) == (in.NewUser != nil) {
		return nil, invalid("请选择且仅选择一种用户来源")
	}
	a := in.Account
	if err := a.normalize(true); err != nil {
		return nil, err
	}
	if err := validatePayment(in.AmountCents, in.PaymentMethod); err != nil {
		return nil, err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	res := &OnboardResult{}
	if in.NewUser != nil {
		u := *in.NewUser
		if err := insertUser(ctx, tx, &u, in.Password); err != nil {
			return nil, err
		}
		res.UserID = u.ID
	} else {
		if err := userExists(ctx, tx, in.UserID); err != nil {
			return nil, err
		}
		res.UserID = in.UserID
	}
	a.UserID = res.UserID
	if err := insertAccount(ctx, tx, &a); err != nil {
		return nil, err
	}
	res.AccountID = a.ID
	if err := setAccountTags(ctx, tx, a.ID, a.TagIDs); err != nil {
		return nil, err
	}
	if err := markAccountPending(ctx, tx, a.ID); err != nil {
		return nil, err
	}
	if in.AmountCents != nil {
		months := 1
		if a.ToDate != nil {
			months = calendarMonths(*a.FromDate, *a.ToDate)
		}
		if res.TransactionID, err = insertAccountIncome(ctx, tx, a.ID, *in.AmountCents, in.PaymentMethod, fmt.Sprintf("账号首期：%d月", months), a.Remark); err != nil {
			return nil, err
		}
	}
	return res, tx.Commit()
}

func sameIDs(a, b []int64) bool {
	x, y := slices.Clone(a), slices.Clone(b)
	slices.Sort(x)
	slices.Sort(y)
	return slices.Equal(x, y)
}

func sameTime(a, b *time.Time) bool {
	if a == nil || b == nil {
		return a == b
	}
	return FormatTime(*a) == FormatTime(*b)
}

// UpdateAccount 全量覆盖账户字段。AccountNo、AuthCode 不可修改（传空或原值），重置订阅码用 ResetAuthCode；
// TagIDs 为 nil 时不改标签。UUID、启停、到期时间、标签变化会把相关节点置为待部署；
// 流量配额变化同步到当期统计行。
func UpdateAccount(ctx context.Context, db *sql.DB, id int64, a *Account) error {
	in := *a
	if err := in.normalize(false); err != nil {
		return err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	old, err := loadAccount(ctx, tx, id)
	if err != nil {
		return err
	}
	if old == nil {
		return sql.ErrNoRows
	}
	if in.AccountNo != "" && !strings.EqualFold(in.AccountNo, old.AccountNo) {
		return invalid("账户编号不可修改")
	}
	if in.AuthCode != "" && in.AuthCode != old.AuthCode {
		return invalid("订阅码不可直接修改，请使用重置")
	}
	if err := checkUnique(ctx, tx, "uuid", "UUID", in.UUID, id); err != nil {
		return err
	}
	if in.UserID != 0 && in.UserID != old.UserID {
		if err := userExists(ctx, tx, in.UserID); err != nil {
			return err
		}
	}
	tagsChanged := in.TagIDs != nil && !sameIDs(in.TagIDs, old.TagIDs)
	affectsNodes := in.MaxConnections != old.MaxConnections || in.MaxIps != old.MaxIps || in.DownloadMbps != old.DownloadMbps || in.UploadMbps != old.UploadMbps || in.Bandwidth != old.Bandwidth || tagsChanged || in.UUID != old.UUID || in.Disabled != old.Disabled || !sameTime(in.ToDate, old.ToDate)
	if affectsNodes { // 先按旧标签置位，覆盖被移除的标签
		if err := markAccountPending(ctx, tx, id); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `UPDATE account SET node_multiple=?, node_prefix=?, from_date=?, to_date=?, period_type=?, uuid=?,
		max_connections=?, max_ips=?, bandwidth=?, download_mbps=?, upload_mbps=?, disabled=?, remark=?, user_id=?, update_time=? WHERE id=?`,
		in.NodeMultiple, nz(in.NodePrefix), dateArg(in.FromDate), dateArg(in.ToDate), in.PeriodType, in.UUID,
		nzInt(in.MaxConnections), nzInt(in.MaxIps), nzInt(in.Bandwidth), nzInt(in.DownloadMbps), nzInt(in.UploadMbps), in.Disabled, nz(in.Remark),
		nzInt(int(in.UserID)), FormatTime(nowFunc()), id); err != nil {
		if isUnique(err) {
			return fmt.Errorf("%w：UUID 已存在", ErrConflict)
		}
		return err
	}
	if tagsChanged {
		if err := setAccountTags(ctx, tx, id, in.TagIDs); err != nil {
			return err
		}
		if err := cleanUnauthorizedBindings(ctx, tx, id); err != nil {
			return err
		}
	}
	if affectsNodes {
		if err := markAccountPending(ctx, tx, id); err != nil {
			return err
		}
	}
	if in.Bandwidth != old.Bandwidth { // 本轮周期锚点未单独存储：直接改当期行配额
		now := FormatTime(nowFunc())
		if _, err := tx.ExecContext(ctx, `UPDATE account_traffic_stats SET bandwidth_quota = ?, update_time = ? WHERE account_id = ? AND period_start <= ? AND period_end > ?`,
			nzInt(in.Bandwidth), now, id, now, now); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// RenewAccount 设置新的到期时间（须晚于当前时刻），自动启用被禁用的账户，
// amountCents 非 nil 时按「起点取 max(旧到期, 当前)」记一笔收入。相关节点置为待部署。
func RenewAccount(ctx context.Context, db *sql.DB, id int64, newToDate time.Time, amountCents *int64, paymentMethod string) error {
	now := nowFunc()
	if !newToDate.After(now) {
		return invalid("新的到期时间必须晚于当前时间")
	}
	if err := validatePayment(amountCents, paymentMethod); err != nil {
		return err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	old, err := loadAccount(ctx, tx, id)
	if err != nil {
		return err
	}
	if old == nil {
		return sql.ErrNoRows
	}
	if _, err := tx.ExecContext(ctx, `UPDATE account SET to_date = ?, disabled = 0, update_time = ? WHERE id = ?`,
		FormatTime(newToDate), FormatTime(now), id); err != nil {
		return err
	}
	if err := markAccountPending(ctx, tx, id); err != nil {
		return err
	}
	if amountCents != nil {
		start := now
		if old.ToDate != nil && old.ToDate.After(now) {
			start = *old.ToDate
		}
		desc := fmt.Sprintf("账号：%d月", calendarMonths(start, newToDate))
		if _, err := insertAccountIncome(ctx, tx, id, *amountCents, paymentMethod, desc, old.Remark); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// SetAccountDisabled 启停账户，相关节点置为待部署。
func SetAccountDisabled(ctx context.Context, db *sql.DB, id int64, disabled bool) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	res, err := tx.ExecContext(ctx, `UPDATE account SET disabled = ?, update_time = ? WHERE id = ?`, disabled, FormatTime(nowFunc()), id)
	if err != nil {
		return err
	}
	if c, _ := res.RowsAffected(); c == 0 {
		return sql.ErrNoRows
	}
	if err := markAccountPending(ctx, tx, id); err != nil {
		return err
	}
	return tx.Commit()
}

// ResetAuthCode 重新生成订阅码并返回；订阅码不进节点配置，不需要部署。
func ResetAuthCode(ctx context.Context, db *sql.DB, id int64) (string, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return "", err
	}
	defer tx.Rollback()
	code, err := freshValue(ctx, tx, "auth_code", func() string { return randHex(16) })
	if err != nil {
		return "", err
	}
	res, err := tx.ExecContext(ctx, `UPDATE account SET auth_code = ?, update_time = ? WHERE id = ?`, code, FormatTime(nowFunc()), id)
	if err != nil {
		return "", err
	}
	if c, _ := res.RowsAffected(); c == 0 {
		return "", sql.ErrNoRows
	}
	return code, tx.Commit()
}

// DeleteAccount 删除账户及其订阅定制绑定、告警状态、在线记录和流量统计，收支流水保留。
// 相关节点置为待部署。
func DeleteAccount(ctx context.Context, db *sql.DB, id int64) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	a, err := loadAccount(ctx, tx, id)
	if err != nil {
		return err
	}
	if a == nil {
		return sql.ErrNoRows
	}
	if err := markAccountPending(ctx, tx, id); err != nil {
		return err
	}
	var records []int64
	rows, err := tx.QueryContext(ctx, `SELECT domain_dns_record_id FROM account_node_subscription_domain_binding WHERE account_id = ? AND domain_dns_record_id IS NOT NULL`, id)
	if err != nil {
		return err
	}
	for rows.Next() {
		var r int64
		if err := rows.Scan(&r); err != nil {
			rows.Close()
			return err
		}
		records = append(records, r)
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	for _, stmt := range []struct {
		sql string
		arg any
	}{
		{`DELETE FROM alert_state WHERE resource_type = 'account' AND resource_id = ?`, id},
		{`DELETE FROM account_online_ip WHERE account_no = ?`, a.AccountNo},
		{`DELETE FROM account WHERE id = ?`, id}, // 绑定、标签关联、流量统计随外键级联删除
	} {
		if _, err := tx.ExecContext(ctx, stmt.sql, stmt.arg); err != nil {
			return err
		}
	}
	for _, r := range records {
		if err := releaseBindingRecord(ctx, tx, &r); err != nil {
			return err
		}
	}
	return tx.Commit()
}

// AccountRecentNodes 返回账户自 since 以来用过的节点，按最近使用倒序；limit<=0 取 20。
func AccountRecentNodes(ctx context.Context, db *sql.DB, accountID int64, since time.Time, limit int) ([]*RecentNode, error) {
	if limit <= 0 {
		limit = 20
	}
	rows, err := db.QueryContext(ctx, `SELECT coalesce(o.node_id,0), coalesce(n.name,''), o.node_ip, max(o.last_online_time) AS last
		FROM account a JOIN account_online_ip o ON o.account_no = a.account_no LEFT JOIN node n ON n.id = o.node_id
		WHERE a.id = ? AND o.last_online_time >= ?
		GROUP BY coalesce(o.node_id,0), o.node_ip ORDER BY last DESC LIMIT ?`, accountID, FormatTime(since), limit)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*RecentNode
	for rows.Next() {
		var r RecentNode
		var last string
		if err := rows.Scan(&r.NodeID, &r.NodeName, &r.NodeIP, &last); err != nil {
			return nil, err
		}
		if r.LastOnline, err = ParseTime(last); err != nil {
			return nil, err
		}
		list = append(list, &r)
	}
	return list, rows.Err()
}

// ConfigValue 读取 system_config 中的明文配置项；不存在返回空串。
func ConfigValue(ctx context.Context, db *sql.DB, key string) string {
	var v sql.NullString
	db.QueryRowContext(ctx, `SELECT config_value FROM system_config WHERE config_key = ?`, key).Scan(&v)
	return strings.TrimRight(strings.TrimSpace(v.String), "/")
}
