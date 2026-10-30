package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net"
	"regexp"
	"strings"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/dns"
)

const (
	DNSPending       = "PENDING_CREATE"
	DNSPendingUpdate = "PENDING_UPDATE"
	DNSPendingDelete = "PENDING_DELETE"
	DNSSynced        = "SYNCED"
	DNSSyncFailed    = "SYNC_FAILED"
)

// ---------- 服务商 ----------

type DNSProvider struct {
	ID                             int64
	Name, AccountID, Remark        string
	Enabled, HasToken              bool
	LastCheckTime, LastCheckStatus string
	LastCheckMessage               string
	Domains                        int
}

func DNSProviderList(ctx context.Context, db *sql.DB, q string, page, size int) ([]DNSProvider, int, error) {
	like := "%" + q + "%"
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM dns_provider_config WHERE display_name LIKE ? OR coalesce(remark,'') LIKE ?`, like, like).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT p.id,p.display_name,p.status='ENABLED',coalesce(p.credential_json,'')<>'',coalesce(json_extract(p.extension_json,'$.accountId'),''),
		coalesce(p.last_check_time,''),coalesce(p.last_check_status,''),coalesce(p.last_check_message,''),coalesce(p.remark,''),
		(SELECT count(*) FROM domain d WHERE d.dns_provider_config_id=p.id)
		FROM dns_provider_config p WHERE p.display_name LIKE ? OR coalesce(p.remark,'') LIKE ? ORDER BY p.id DESC LIMIT ? OFFSET ?`, like, like, size, (page-1)*size)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []DNSProvider
	for rows.Next() {
		var p DNSProvider
		if err := rows.Scan(&p.ID, &p.Name, &p.Enabled, &p.HasToken, &p.AccountID, &p.LastCheckTime, &p.LastCheckStatus, &p.LastCheckMessage, &p.Remark, &p.Domains); err != nil {
			return nil, 0, err
		}
		out = append(out, p)
	}
	return out, total, rows.Err()
}

func DNSProviderByID(ctx context.Context, db dbtx, id int64) (*DNSProvider, error) {
	var p DNSProvider
	err := db.QueryRowContext(ctx, `SELECT id,display_name,status='ENABLED',coalesce(credential_json,'')<>'',coalesce(json_extract(extension_json,'$.accountId'),''),
		coalesce(last_check_time,''),coalesce(last_check_status,''),coalesce(last_check_message,''),coalesce(remark,'') FROM dns_provider_config WHERE id=?`, id).
		Scan(&p.ID, &p.Name, &p.Enabled, &p.HasToken, &p.AccountID, &p.LastCheckTime, &p.LastCheckStatus, &p.LastCheckMessage, &p.Remark)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &p, err
}

// SaveDNSProvider 新增或编辑；token 为空表示编辑时保留原值。
func SaveDNSProvider(ctx context.Context, db *sql.DB, c *crypto.Cipher, p *DNSProvider, token string) (int64, error) {
	p.Name, p.AccountID, p.Remark = strings.TrimSpace(p.Name), strings.TrimSpace(p.AccountID), strings.TrimSpace(p.Remark)
	if p.Name == "" || len(p.Name) > 100 {
		return 0, invalid("名称不能为空且不超过 100 个字符")
	}
	if p.AccountID == "" {
		return 0, invalid("Cloudflare Account ID 不能为空")
	}
	if p.ID == 0 && token == "" {
		return 0, invalid("API Token 不能为空")
	}
	status := "DISABLED"
	if p.Enabled {
		status = "ENABLED"
	}
	ext, _ := json.Marshal(map[string]string{"accountId": p.AccountID})
	var enc any
	if token != "" {
		cred, _ := json.Marshal(map[string]string{"apiToken": token})
		e, err := c.Encrypt(string(cred))
		if err != nil {
			return 0, err
		}
		enc = e
	}
	now := FormatTime(nowFunc())
	if p.ID == 0 {
		res, err := db.ExecContext(ctx, `INSERT INTO dns_provider_config(provider_type,status,display_name,credential_json,extension_json,last_check_status,remark,create_time,update_time) VALUES('CLOUDFLARE',?,?,?,?,'NOT_CHECKED',?,?,?)`,
			status, p.Name, enc, string(ext), nz(p.Remark), now, now)
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	res, err := db.ExecContext(ctx, `UPDATE dns_provider_config SET status=?,display_name=?,extension_json=?,remark=?,update_time=?,credential_json=coalesce(?,credential_json) WHERE id=?`,
		status, p.Name, string(ext), nz(p.Remark), now, enc, p.ID)
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, sql.ErrNoRows
	}
	return p.ID, nil
}

func DeleteDNSProvider(ctx context.Context, db *sql.DB, id int64) error {
	var n int
	db.QueryRowContext(ctx, `SELECT count(*) FROM domain WHERE dns_provider_config_id=?`, id).Scan(&n)
	if n > 0 {
		return fmt.Errorf("%w：仍有 %d 个域名使用该服务商，请先解除绑定", ErrConflict, n)
	}
	res, err := db.ExecContext(ctx, `DELETE FROM dns_provider_config WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

// DNSProviderToken 解密并返回 Token；仅供发起 API 调用，不得写日志或页面。
func DNSProviderToken(ctx context.Context, db dbtx, c *crypto.Cipher, id int64) (string, error) {
	var enc string
	if err := db.QueryRowContext(ctx, `SELECT coalesce(credential_json,'') FROM dns_provider_config WHERE id=?`, id).Scan(&enc); err != nil {
		return "", err
	}
	if enc == "" {
		return "", invalid("该服务商未配置 API Token")
	}
	plain, err := c.Decrypt(enc)
	if err != nil {
		return "", err
	}
	var cred struct {
		APIToken string `json:"apiToken"`
	}
	if err := json.Unmarshal([]byte(plain), &cred); err != nil || cred.APIToken == "" {
		return "", invalid("该服务商的凭据格式不正确，请重新保存 API Token")
	}
	return cred.APIToken, nil
}

func RecordDNSProviderCheck(ctx context.Context, db *sql.DB, id int64, ok bool, msg string) error {
	st := "FAILED"
	if ok {
		st = "SUCCESS"
	}
	if len(msg) > 200 {
		msg = msg[:200]
	}
	_, err := db.ExecContext(ctx, `UPDATE dns_provider_config SET last_check_time=?,last_check_status=?,last_check_message=? WHERE id=?`, FormatTime(nowFunc()), st, msg, id)
	return err
}

// ---------- 域名 ----------

type DNSDomain struct {
	ID                                           int64
	Domain, ZoneID, ExpireDate, Supplier, Remark string
	Price                                        *int64
	ProviderID                                   *int64
	ProviderName, SyncStatus, LastSync           string
	Records, Pending                             int
}

const domainSel = `SELECT d.id,coalesce(d.domain,''),coalesce(json_extract(d.dns_binding_extension_json,'$.zoneId'),''),coalesce(d.expire_date,''),coalesce(d.supplier,''),coalesce(d.remark,''),d.price,
	d.dns_provider_config_id,coalesce(p.display_name,''),coalesce(d.dns_sync_status,''),coalesce(d.dns_last_sync_time,''),
	(SELECT count(*) FROM domain_dns_record r WHERE r.domain_id=d.id),
	(SELECT count(*) FROM domain_dns_record r WHERE r.domain_id=d.id AND r.status LIKE 'PENDING%') FROM domain d LEFT JOIN dns_provider_config p ON p.id=d.dns_provider_config_id`

func scanDomain(row interface{ Scan(...any) error }) (*DNSDomain, error) {
	var d DNSDomain
	var price, prov sql.NullInt64
	if err := row.Scan(&d.ID, &d.Domain, &d.ZoneID, &d.ExpireDate, &d.Supplier, &d.Remark, &price, &prov, &d.ProviderName, &d.SyncStatus, &d.LastSync, &d.Records, &d.Pending); err != nil {
		return nil, err
	}
	if price.Valid {
		d.Price = &price.Int64
	}
	if prov.Valid {
		d.ProviderID = &prov.Int64
	}
	if len(d.ExpireDate) > 10 {
		d.ExpireDate = d.ExpireDate[:10]
	}
	return &d, nil
}

func DNSDomainList(ctx context.Context, db *sql.DB, q string, page, size int) ([]DNSDomain, int, error) {
	like := "%" + q + "%"
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM domain WHERE coalesce(domain,'') LIKE ? OR coalesce(remark,'') LIKE ? OR coalesce(supplier,'') LIKE ?`, like, like, like).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, domainSel+` WHERE coalesce(d.domain,'') LIKE ? OR coalesce(d.remark,'') LIKE ? OR coalesce(d.supplier,'') LIKE ? ORDER BY d.domain LIMIT ? OFFSET ?`, like, like, like, size, (page-1)*size)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []DNSDomain
	for rows.Next() {
		d, err := scanDomain(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *d)
	}
	return out, total, rows.Err()
}

func DNSDomainByID(ctx context.Context, db dbtx, id int64) (*DNSDomain, error) {
	d, err := scanDomain(db.QueryRowContext(ctx, domainSel+` WHERE d.id=?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return d, err
}

func DNSEnabledProviders(ctx context.Context, db *sql.DB) ([]DNSProvider, error) {
	rows, err := db.QueryContext(ctx, `SELECT id,display_name FROM dns_provider_config WHERE status='ENABLED' ORDER BY display_name`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DNSProvider
	for rows.Next() {
		var p DNSProvider
		if err := rows.Scan(&p.ID, &p.Name); err != nil {
			return nil, err
		}
		out = append(out, p)
	}
	return out, rows.Err()
}

var domainNameRe = regexp.MustCompile(`^([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\.)+[a-z]{2,63}$`)

func SaveDNSDomain(ctx context.Context, db *sql.DB, d *DNSDomain, price string) (int64, error) {
	d.Domain = strings.ToLower(strings.TrimSpace(d.Domain))
	d.ZoneID, d.Supplier, d.Remark = strings.TrimSpace(d.ZoneID), strings.TrimSpace(d.Supplier), strings.TrimSpace(d.Remark)
	if !domainNameRe.MatchString(d.Domain) {
		return 0, invalid("请填写有效的根域名，例如 example.com")
	}
	var cents any
	if strings.TrimSpace(price) != "" {
		v, err := ParseCents(price)
		if err != nil || v < 0 {
			return 0, invalid("价格格式不正确")
		}
		cents = v
	}
	if d.ExpireDate != "" {
		if _, err := ParseTime(d.ExpireDate + " 00:00:00"); err != nil {
			return 0, invalid("到期日期格式应为 YYYY-MM-DD")
		}
	}
	if d.ProviderID != nil && d.ZoneID == "" {
		return 0, invalid("绑定 DNS 服务商时必须填写 Zone ID")
	}
	var dup int64
	err := db.QueryRowContext(ctx, `SELECT id FROM domain WHERE lower(domain)=? AND id<>?`, d.Domain, d.ID).Scan(&dup)
	if err == nil {
		return 0, fmt.Errorf("%w：域名 %s 已存在", ErrConflict, d.Domain)
	} else if !errors.Is(err, sql.ErrNoRows) {
		return 0, err
	}
	var prov, ptype, ext any
	if d.ProviderID != nil {
		var st string
		if err := db.QueryRowContext(ctx, `SELECT status FROM dns_provider_config WHERE id=?`, *d.ProviderID).Scan(&st); err != nil {
			return 0, invalid("请选择有效的 DNS 服务商")
		}
		prov, ptype = *d.ProviderID, "CLOUDFLARE"
		b, _ := json.Marshal(map[string]string{"zoneId": d.ZoneID})
		ext = string(b)
	}
	now := FormatTime(nowFunc())
	var expire any = nz(d.ExpireDate)
	if d.ExpireDate != "" {
		expire = d.ExpireDate
	}
	if d.ID == 0 {
		res, err := db.ExecContext(ctx, `INSERT INTO domain(domain,expire_date,price,supplier,dns_provider_config_id,dns_provider_type,dns_binding_extension_json,remark,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?)`,
			d.Domain, expire, cents, nz(d.Supplier), prov, ptype, ext, nz(d.Remark), now, now)
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	res, err := db.ExecContext(ctx, `UPDATE domain SET domain=?,expire_date=?,price=?,supplier=?,dns_provider_config_id=?,dns_provider_type=?,dns_binding_extension_json=?,remark=?,update_time=? WHERE id=?`,
		d.Domain, expire, cents, nz(d.Supplier), prov, ptype, ext, nz(d.Remark), now, d.ID)
	if err != nil {
		return 0, err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return 0, sql.ErrNoRows
	}
	return d.ID, nil
}

// DeleteDNSDomain 只删除本地登记，不会删除服务商侧记录；仅当没有记录被节点或订阅定制引用时允许，记录随域名级联删除。
func DeleteDNSDomain(ctx context.Context, db *sql.DB, id int64) error {
	var n int
	db.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record r WHERE r.domain_id=? AND (EXISTS(SELECT 1 FROM node_server WHERE access_host_id=r.id) OR EXISTS(SELECT 1 FROM account_node_subscription_domain_binding WHERE domain_dns_record_id=r.id))`, id).Scan(&n)
	if n > 0 {
		return fmt.Errorf("%w：有 %d 条记录仍被节点或订阅定制引用", ErrConflict, n)
	}
	res, err := db.ExecContext(ctx, `DELETE FROM domain WHERE id=?`, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func setDomainSync(ctx context.Context, db dbtx, id int64, status string) error {
	now := FormatTime(nowFunc())
	_, err := db.ExecContext(ctx, `UPDATE domain SET dns_sync_status=?,dns_last_sync_time=? WHERE id=?`, status, now, id)
	return err
}

func SetDNSDomainSync(ctx context.Context, db *sql.DB, id int64, status string) error {
	return setDomainSync(ctx, db, id, status)
}

// ---------- 记录 ----------

type DNSRecord struct {
	ID, DomainID                  int64
	Name, FullName, Type, Content string
	TTL                           int
	Proxied                       bool
	Status, Remark                string
	ServerID                      *int64
	ServerName                    string
	ExternalID                    string
	Refs                          int // 被节点/订阅定制引用的数量
}

const recSel = `SELECT r.id,r.domain_id,r.name,coalesce(r.full_name,''),upper(r.type),r.content,coalesce(r.ttl,1),coalesce(r.proxied,0),r.status,coalesce(r.remark,''),
	r.server_id,coalesce(nullif(s.name,''),s.ip,''),coalesce(r.external_record_id,''),
 (SELECT count(*) FROM node_server WHERE access_host_id=r.id)+(SELECT count(*) FROM account_node_subscription_domain_binding WHERE domain_dns_record_id=r.id)
	FROM domain_dns_record r LEFT JOIN server s ON s.id=r.server_id`

func scanRecord(row interface{ Scan(...any) error }) (*DNSRecord, error) {
	var r DNSRecord
	var sid sql.NullInt64
	if err := row.Scan(&r.ID, &r.DomainID, &r.Name, &r.FullName, &r.Type, &r.Content, &r.TTL, &r.Proxied, &r.Status, &r.Remark, &sid, &r.ServerName, &r.ExternalID, &r.Refs); err != nil {
		return nil, err
	}
	if sid.Valid {
		r.ServerID = &sid.Int64
	}
	return &r, nil
}

func DNSRecordList(ctx context.Context, db *sql.DB, domainID int64, q string, page, size int) ([]DNSRecord, int, error) {
	like := "%" + q + "%"
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record WHERE domain_id=? AND (coalesce(full_name,'') LIKE ? OR content LIKE ?)`, domainID, like, like).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, recSel+` WHERE r.domain_id=? AND (coalesce(r.full_name,'') LIKE ? OR r.content LIKE ?) ORDER BY r.full_name,r.type LIMIT ? OFFSET ?`, domainID, like, like, size, (page-1)*size)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var out []DNSRecord
	for rows.Next() {
		r, err := scanRecord(rows)
		if err != nil {
			return nil, 0, err
		}
		out = append(out, *r)
	}
	return out, total, rows.Err()
}

func DNSRecordByID(ctx context.Context, db dbtx, id int64) (*DNSRecord, error) {
	r, err := scanRecord(db.QueryRowContext(ctx, recSel+` WHERE r.id=?`, id))
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return r, err
}

// SaveDNSRecord 只维护 A 记录：新增为待创建；已同步记录修改后为待更新；变更不会立即推送。
func SaveDNSRecord(ctx context.Context, db *sql.DB, r *DNSRecord) (int64, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, err
	}
	defer tx.Rollback()
	d, err := DNSDomainByID(ctx, tx, r.DomainID)
	if err != nil {
		return 0, err
	}
	if d == nil {
		return 0, sql.ErrNoRows
	}
	r.Name = strings.ToLower(strings.TrimSpace(r.Name))
	r.Content = strings.TrimSpace(r.Content)
	r.Remark = strings.TrimSpace(r.Remark)
	var full string
	switch {
	case r.Name == "@" || r.Name == "":
		r.Name, full = "@", d.Domain
	case subdomainRe.MatchString(r.Name) && len(r.Name) <= 120:
		full = r.Name + "." + d.Domain
	default:
		return 0, invalid("记录名只能包含小写字母、数字、连字符和点，根域名用 @")
	}
	if ip := net.ParseIP(r.Content); ip == nil || ip.To4() == nil {
		return 0, invalid("内容必须是 IPv4 地址")
	}
	if r.TTL != 1 && (r.TTL < 60 || r.TTL > 86400) {
		return 0, invalid("TTL 取 1（自动）或 60–86400")
	}
	if r.ServerID != nil {
		var ip string
		if err := tx.QueryRowContext(ctx, `SELECT ip FROM server WHERE id=?`, *r.ServerID).Scan(&ip); err != nil {
			return 0, invalid("请选择有效的服务器")
		}
		if ip != r.Content {
			return 0, invalid("绑定服务器的记录内容必须等于服务器 IP（%s）", ip)
		}
	}
	var dup int
	if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record WHERE domain_id=? AND lower(full_name)=? AND upper(type)='A' AND status<>'PENDING_DELETE' AND id<>?`, r.DomainID, full, r.ID).Scan(&dup); err != nil {
		return 0, err
	}
	if dup > 0 {
		return 0, fmt.Errorf("%w：%s 的 A 记录已存在", ErrConflict, full)
	}
	now := FormatTime(nowFunc())
	var prov any
	if d.ProviderID != nil {
		prov = *d.ProviderID
	}
	if r.ID == 0 {
		res, err := tx.ExecContext(ctx, `INSERT INTO domain_dns_record(domain_id,server_id,dns_provider_config_id,name,full_name,type,content,ttl,proxied,status,remark,create_time,update_time) VALUES(?,?,?,?,?,'A',?,?,?,'PENDING_CREATE',?,?,?)`,
			r.DomainID, r.ServerID, prov, r.Name, full, r.Content, r.TTL, r.Proxied, nz(r.Remark), now, now)
		if err != nil {
			return 0, err
		}
		id, _ := res.LastInsertId()
		return id, tx.Commit()
	}
	cur, err := DNSRecordByID(ctx, tx, r.ID)
	if err != nil {
		return 0, err
	}
	if cur == nil || cur.DomainID != r.DomainID {
		return 0, sql.ErrNoRows
	}
	if cur.Type != "A" {
		return 0, invalid("仅支持编辑 A 记录")
	}
	if cur.Status == DNSPendingDelete {
		return 0, invalid("记录已标记待删除，请先推送或在拉取后重试")
	}
	status := cur.Status
	if status == DNSSynced || status == DNSSyncFailed {
		status = DNSPendingUpdate
	}
	if cur.Refs > 0 && cur.FullName != full {
		return 0, fmt.Errorf("%w：记录被节点或订阅定制引用，不能改名", ErrConflict)
	}
	if _, err := tx.ExecContext(ctx, `UPDATE domain_dns_record SET server_id=?,name=?,full_name=?,content=?,ttl=?,proxied=?,remark=?,status=?,update_time=?,failover_home_content=NULL WHERE id=?`,
		r.ServerID, r.Name, full, r.Content, r.TTL, r.Proxied, nz(r.Remark), status, now, r.ID); err != nil {
		return 0, err
	}
	return r.ID, tx.Commit()
}

// DeleteDNSRecord 未推送过的记录直接删除；已有服务商记录的标记待删除。
func DeleteDNSRecord(ctx context.Context, db *sql.DB, id int64) error {
	r, err := DNSRecordByID(ctx, db, id)
	if err != nil {
		return err
	}
	if r == nil {
		return sql.ErrNoRows
	}
	if r.Refs > 0 {
		return fmt.Errorf("%w：记录仍被 %d 处节点或订阅定制引用，请先解除", ErrConflict, r.Refs)
	}
	if r.Type != "A" {
		return invalid("仅支持删除 A 记录")
	}
	if r.ExternalID == "" || r.Status == DNSPending {
		_, err = db.ExecContext(ctx, `DELETE FROM domain_dns_record WHERE id=?`, id)
		return err
	}
	_, err = db.ExecContext(ctx, `UPDATE domain_dns_record SET status='PENDING_DELETE',server_id=NULL,update_time=? WHERE id=?`, FormatTime(nowFunc()), id)
	return err
}

// ApplyPull 用服务商记录更新本地：待推送的本地修改不被覆盖；本地已同步但远端已不存在的记录，
// 无人引用则删除，否则标为 SYNC_FAILED 留待人工处理。返回新增/更新/移除数量。
func ApplyDNSPull(ctx context.Context, db *sql.DB, domainID int64, recs []dns.Record) (added, updated, removed int, err error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return
	}
	defer tx.Rollback()
	d, err := DNSDomainByID(ctx, tx, domainID)
	if err != nil || d == nil {
		if err == nil {
			err = sql.ErrNoRows
		}
		return
	}
	now := FormatTime(nowFunc())
	var prov any
	if d.ProviderID != nil {
		prov = *d.ProviderID
	}
	seen := map[string]bool{}
	for _, v := range recs {
		seen[v.ID] = true
		ttl, proxied, prio := 1, false, any(nil)
		if v.TTL != nil {
			ttl = *v.TTL
		}
		if v.Proxied != nil {
			proxied = *v.Proxied
		}
		if v.Priority != nil {
			prio = *v.Priority
		}
		name := strings.ToLower(v.Name)
		label := "@"
		if name != d.Domain {
			label = strings.TrimSuffix(name, "."+d.Domain)
		}
		var id int64
		var status string
		e := tx.QueryRowContext(ctx, `SELECT id,status FROM domain_dns_record WHERE domain_id=? AND external_record_id=?`, domainID, v.ID).Scan(&id, &status)
		if errors.Is(e, sql.ErrNoRows) {
			// 同名 A 记录若本地待创建，视为用户已手工创建，拉取时关联远端 ID 并保留本地待更新内容
			if strings.EqualFold(v.Type, "A") {
				e = tx.QueryRowContext(ctx, `SELECT id,status FROM domain_dns_record WHERE domain_id=? AND lower(full_name)=? AND upper(type)='A' AND external_record_id IS NULL AND status='PENDING_CREATE'`, domainID, name).Scan(&id, &status)
				if e == nil {
					if _, err = tx.ExecContext(ctx, `UPDATE domain_dns_record SET external_record_id=?,status='PENDING_UPDATE',raw_data=?,update_time=? WHERE id=?`, v.ID, nz(v.Raw), now, id); err != nil {
						return
					}
					updated++
					continue
				}
			}
			if !errors.Is(e, sql.ErrNoRows) {
				err = e
				return
			}
			if _, err = tx.ExecContext(ctx, `INSERT INTO domain_dns_record(domain_id,dns_provider_config_id,external_record_id,name,full_name,type,content,ttl,proxied,priority,status,remark,raw_data,last_sync_time,create_time,update_time)
				VALUES(?,?,?,?,?,?,?,?,?,?,'SYNCED',?,?,?,?,?)`, domainID, prov, v.ID, label, name, strings.ToUpper(v.Type), v.Content, ttl, proxied, prio, nz(v.Comment), nz(v.Raw), now, now, now); err != nil {
				return
			}
			added++
			continue
		}
		if e != nil {
			err = e
			return
		}
		if strings.HasPrefix(status, "PENDING") {
			continue
		}
		if _, err = tx.ExecContext(ctx, `UPDATE domain_dns_record SET name=?,full_name=?,type=?,content=?,ttl=?,proxied=?,priority=?,status='SYNCED',raw_data=?,last_sync_time=?,update_time=? WHERE id=?`,
			label, name, strings.ToUpper(v.Type), v.Content, ttl, proxied, prio, nz(v.Raw), now, now, id); err != nil {
			return
		}
		updated++
	}
	rows, e := tx.QueryContext(ctx, `SELECT id,external_record_id FROM domain_dns_record WHERE domain_id=? AND external_record_id IS NOT NULL AND status IN ('SYNCED','SYNC_FAILED')`, domainID)
	if e != nil {
		err = e
		return
	}
	var gone []int64
	for rows.Next() {
		var id int64
		var ext string
		if err = rows.Scan(&id, &ext); err != nil {
			rows.Close()
			return
		}
		if !seen[ext] {
			gone = append(gone, id)
		}
	}
	rows.Close()
	for _, id := range gone {
		res, e := tx.ExecContext(ctx, `DELETE FROM domain_dns_record WHERE id=? AND NOT EXISTS(SELECT 1 FROM node_server WHERE access_host_id=?) AND NOT EXISTS(SELECT 1 FROM account_node_subscription_domain_binding WHERE domain_dns_record_id=?)`, id, id, id)
		if e != nil {
			err = e
			return
		}
		if n, _ := res.RowsAffected(); n > 0 {
			removed++
		} else if _, err = tx.ExecContext(ctx, `UPDATE domain_dns_record SET status='SYNC_FAILED',update_time=? WHERE id=?`, now, id); err != nil {
			return
		}
	}
	if err = setDomainSync(ctx, tx, domainID, DNSSynced); err != nil {
		return
	}
	err = tx.Commit()
	return
}

// DNSPush 描述一次待推送：每条变更保留记录 ID，推送成功后据此回写。
type DNSPush struct {
	Batch                        dns.Batch
	DeleteIDs, PatchIDs, PostIDs []int64
}

func (p *DNSPush) Empty() bool { return len(p.DeleteIDs)+len(p.PatchIDs)+len(p.PostIDs) == 0 }

func LoadDNSPush(ctx context.Context, db dbtx, domainID int64) (*DNSPush, error) {
	return loadDNSPush(ctx, db, `r.domain_id=?`, domainID)
}

// LoadDNSRecordPush 只加载指定记录，避免订阅定制保存时推送其他待处理变更。
func LoadDNSRecordPush(ctx context.Context, db dbtx, recordID int64) (*DNSPush, error) {
	return loadDNSPush(ctx, db, `r.id=?`, recordID)
}

func loadDNSPush(ctx context.Context, db dbtx, where string, id int64) (*DNSPush, error) {
	rows, err := db.QueryContext(ctx, recSel+` WHERE `+where+` AND r.status IN ('PENDING_CREATE','PENDING_UPDATE','PENDING_DELETE') ORDER BY r.id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	p := &DNSPush{}
	for rows.Next() {
		r, err := scanRecord(rows)
		if err != nil {
			return nil, err
		}
		ch := dns.Change{ID: r.ExternalID, Type: r.Type, Name: r.FullName, Content: r.Content, TTL: r.TTL, Proxied: r.Proxied, Comment: r.Remark}
		switch {
		case r.Status == DNSPendingDelete && r.ExternalID != "":
			p.Batch.Deletes, p.DeleteIDs = append(p.Batch.Deletes, ch), append(p.DeleteIDs, r.ID)
		case r.Status == DNSPendingDelete:
			p.DeleteIDs = append(p.DeleteIDs, r.ID) // 无远端记录，仅回写本地删除
		case r.Status == DNSPendingUpdate && r.ExternalID != "":
			p.Batch.Patches, p.PatchIDs = append(p.Batch.Patches, ch), append(p.PatchIDs, r.ID)
		default:
			p.Batch.Posts, p.PostIDs = append(p.Batch.Posts, ch), append(p.PostIDs, r.ID)
		}
	}
	return p, rows.Err()
}

// ApplyDNSPush 推送成功后回写：删除待删除、待更新转已同步、待创建写入外部 ID。
// 仅更新仍处于推送时状态的记录，推送期间被再次编辑的记录保持待推送。
func ApplyDNSPush(ctx context.Context, db *sql.DB, domainID int64, p *DNSPush, newIDs []string) error {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	now := FormatTime(nowFunc())
	for _, id := range p.DeleteIDs {
		if _, err := tx.ExecContext(ctx, `DELETE FROM domain_dns_record WHERE id=? AND status='PENDING_DELETE'`, id); err != nil {
			return err
		}
	}
	for _, id := range p.PatchIDs {
		if _, err := tx.ExecContext(ctx, `UPDATE domain_dns_record SET status='SYNCED',last_sync_time=? WHERE id=? AND status='PENDING_UPDATE'`, now, id); err != nil {
			return err
		}
	}
	for i, id := range p.PostIDs {
		if _, err := tx.ExecContext(ctx, `UPDATE domain_dns_record SET status='SYNCED',external_record_id=?,last_sync_time=? WHERE id=? AND status='PENDING_CREATE'`, newIDs[i], now, id); err != nil {
			return err
		}
	}
	if err := setDomainSync(ctx, tx, domainID, DNSSynced); err != nil {
		return err
	}
	return tx.Commit()
}

type DNSServerOption struct {
	ID       int64
	Name, IP string
}

func DNSServerOptions(ctx context.Context, db *sql.DB) ([]DNSServerOption, error) {
	rows, err := db.QueryContext(ctx, `SELECT id,coalesce(name,''),ip FROM server ORDER BY name,ip`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []DNSServerOption
	for rows.Next() {
		var o DNSServerOption
		if err := rows.Scan(&o.ID, &o.Name, &o.IP); err != nil {
			return nil, err
		}
		out = append(out, o)
	}
	return out, rows.Err()
}
