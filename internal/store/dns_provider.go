package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"strings"

	"github.com/fun90/airopscat/internal/crypto"
)

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
	return p.ID, execOne(db.ExecContext(ctx, `UPDATE dns_provider_config SET status=?,display_name=?,extension_json=?,remark=?,update_time=?,credential_json=coalesce(?,credential_json) WHERE id=?`,
		status, p.Name, string(ext), nz(p.Remark), now, enc, p.ID))
}

func DeleteDNSProvider(ctx context.Context, db *sql.DB, id int64) error {
	var n int
	db.QueryRowContext(ctx, `SELECT count(*) FROM domain WHERE dns_provider_config_id=?`, id).Scan(&n)
	if n > 0 {
		return fmt.Errorf("%w：仍有 %d 个域名使用该服务商，请先解除绑定", ErrConflict, n)
	}
	return execOne(db.ExecContext(ctx, `DELETE FROM dns_provider_config WHERE id=?`, id))
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
