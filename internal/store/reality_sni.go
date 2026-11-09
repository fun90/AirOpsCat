package store

import (
	"context"
	"database/sql"
	"fmt"
	"net"
	"strings"

	"golang.org/x/net/idna"
)

type RealitySNI struct {
	ID           int64
	Domain, Note string
	Enabled      bool
}

// RealitySNIs 返回候选池；节点只保存域名值，不随候选池修改而变化。
func RealitySNIs(ctx context.Context, db *sql.DB, enabledOnly bool) ([]RealitySNI, error) {
	rows, err := db.QueryContext(ctx, `SELECT id, domain, note, enabled FROM reality_sni WHERE (? = 0 OR enabled = 1) ORDER BY domain`, enabledOnly)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	list := []RealitySNI{}
	for rows.Next() {
		var v RealitySNI
		if err := rows.Scan(&v.ID, &v.Domain, &v.Note, &v.Enabled); err != nil {
			return nil, err
		}
		list = append(list, v)
	}
	return list, rows.Err()
}

func normalizeSNIDomain(domain string) (string, error) {
	domain, err := idna.Lookup.ToASCII(strings.TrimSpace(domain))
	domain = strings.ToLower(strings.TrimSuffix(domain, "."))
	if err != nil || len(domain) > 253 || !strings.Contains(domain, ".") || net.ParseIP(domain) != nil {
		return "", invalid("请填写完整域名，不含协议、端口或路径")
	}
	for _, label := range strings.Split(domain, ".") {
		if len(label) == 0 || len(label) > 63 || label[0] == '-' || label[len(label)-1] == '-' {
			return "", invalid("域名格式不正确")
		}
		for _, c := range label {
			if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-') {
				return "", invalid("域名格式不正确")
			}
		}
	}
	return domain, nil
}

func SaveRealitySNI(ctx context.Context, db *sql.DB, v *RealitySNI) error {
	domain, err := normalizeSNIDomain(v.Domain)
	if err != nil {
		return err
	}
	if len([]rune(v.Note)) > 200 {
		return invalid("备注最长 200 个字符")
	}
	v.Domain = domain
	var res sql.Result
	if v.ID == 0 {
		res, err = db.ExecContext(ctx, `INSERT INTO reality_sni(domain,note,enabled) VALUES(?,?,?)`, v.Domain, v.Note, v.Enabled)
	} else {
		res, err = db.ExecContext(ctx, `UPDATE reality_sni SET domain=?,note=?,enabled=? WHERE id=?`, v.Domain, v.Note, v.Enabled, v.ID)
	}
	if isUnique(err) {
		return fmt.Errorf("%w：域名已存在", ErrConflict)
	}
	if err != nil {
		return err
	}
	if v.ID == 0 {
		v.ID, err = res.LastInsertId()
		return err
	}
	return execOne(res, nil)
}

func DeleteRealitySNI(ctx context.Context, db *sql.DB, id int64) error {
	return execOne(db.ExecContext(ctx, `DELETE FROM reality_sni WHERE id=?`, id))
}

func ToggleRealitySNI(ctx context.Context, db *sql.DB, id int64, enabled bool) error {
	return execOne(db.ExecContext(ctx, `UPDATE reality_sni SET enabled=? WHERE id=?`, enabled, id))
}
