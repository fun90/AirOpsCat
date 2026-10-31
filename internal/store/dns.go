package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"regexp"
	"strings"
)

const (
	DNSPending       = "PENDING_CREATE"
	DNSPendingUpdate = "PENDING_UPDATE"
	DNSPendingDelete = "PENDING_DELETE"
	DNSSynced        = "SYNCED"
	DNSSyncFailed    = "SYNC_FAILED"
)

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
	expire := nz(d.ExpireDate)
	if d.ID == 0 {
		res, err := db.ExecContext(ctx, `INSERT INTO domain(domain,expire_date,price,supplier,dns_provider_config_id,dns_provider_type,dns_binding_extension_json,remark,create_time,update_time) VALUES(?,?,?,?,?,?,?,?,?,?)`,
			d.Domain, expire, cents, nz(d.Supplier), prov, ptype, ext, nz(d.Remark), now, now)
		if err != nil {
			return 0, err
		}
		return res.LastInsertId()
	}
	return d.ID, execOne(db.ExecContext(ctx, `UPDATE domain SET domain=?,expire_date=?,price=?,supplier=?,dns_provider_config_id=?,dns_provider_type=?,dns_binding_extension_json=?,remark=?,update_time=? WHERE id=?`,
		d.Domain, expire, cents, nz(d.Supplier), prov, ptype, ext, nz(d.Remark), now, d.ID))
}

// DeleteDNSDomain 只删除本地登记，不会删除服务商侧记录；仅当没有记录被节点或订阅定制引用时允许，记录随域名级联删除。
func DeleteDNSDomain(ctx context.Context, db *sql.DB, id int64) error {
	var n int
	db.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record r WHERE r.domain_id=? AND (EXISTS(SELECT 1 FROM node_server WHERE access_host_id=r.id) OR EXISTS(SELECT 1 FROM account_node_subscription_domain_binding WHERE domain_dns_record_id=r.id))`, id).Scan(&n)
	if n > 0 {
		return fmt.Errorf("%w：有 %d 条记录仍被节点或订阅定制引用", ErrConflict, n)
	}
	return execOne(db.ExecContext(ctx, `DELETE FROM domain WHERE id=?`, id))
}

func setDomainSync(ctx context.Context, db dbtx, id int64, status string) error {
	now := FormatTime(nowFunc())
	_, err := db.ExecContext(ctx, `UPDATE domain SET dns_sync_status=?,dns_last_sync_time=? WHERE id=?`, status, now, id)
	return err
}

func SetDNSDomainSync(ctx context.Context, db *sql.DB, id int64, status string) error {
	return setDomainSync(ctx, db, id, status)
}

type DNSServerOption struct {
	ID       int64
	Name, IP string
}
