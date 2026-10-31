package store

import (
	"context"
	"database/sql"
	"errors"
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
	Sort         string        // create_time（默认）、to_date、last_online 或 used
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

// lastOnlineSQL 取账户在线记录（保留 30 天）中的最近时间；无记录为 NULL。
const lastOnlineSQL = `(SELECT max(o.last_online_time) FROM account_online_ip o WHERE o.account_no = a.account_no)`

// 当期统计行：period_start <= now < period_end，重叠时取最近开始的一行。
// currentStats 与 accountUsage 同口径：周期行重叠时只取最新一行。
const currentStats = `FROM account_traffic_stats s WHERE s.account_id = a.id AND s.period_start <= @now AND s.period_end > @now ORDER BY s.period_start DESC, s.id DESC LIMIT 1`

const usedSQL = `coalesce((SELECT s.upload_bytes + s.download_bytes ` + currentStats + `), 0)`

// AccountList 按筛选分页返回账户；size 上限 100，默认 create_time 倒序。
func AccountList(ctx context.Context, db *sql.DB, f AccountFilter, page, size int) ([]*AccountRow, int, error) {
	where, args := f.where(nowFunc())
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM account a`+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	dir := "DESC"
	if f.Asc {
		dir = "ASC"
	}
	order := "a.create_time " + dir
	switch f.Sort {
	case "to_date":
		// 长期有效（NULL）视为最晚到期：降序在最前，升序在最后。
		order = "a.to_date IS NULL " + dir + ", a.to_date " + dir
	case "used":
		order = usedSQL + " " + dir
	case "last_online":
		// 没有在线记录的账户无论升降序都排最后。
		order = lastOnlineSQL + " IS NULL, " + lastOnlineSQL + " " + dir
	}
	limit, offset := pageArgs(page, size)
	args = append(args, sql.Named("limit", limit), sql.Named("offset", offset))
	rows, err := db.QueryContext(ctx, `SELECT `+accountCols+`, coalesce(u.email,''), coalesce(u.nick_name,''),
		`+usedSQL+`,
		coalesce((SELECT s.bandwidth_quota `+currentStats+`), a.bandwidth, 0),
		(SELECT count(*) FROM account_online_ip o WHERE o.account_no = a.account_no AND o.last_online_time > @cutoff),
		`+lastOnlineSQL+`
		FROM account a LEFT JOIN user u ON u.id = a.user_id`+where+
		` ORDER BY `+order+`, a.id `+dir+` LIMIT @limit OFFSET @offset`, args...)
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
