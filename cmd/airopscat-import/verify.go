package main

import (
	"context"
	"database/sql"
	"fmt"
	"sort"
	"strings"

	"github.com/fun90/airopscat/internal/crypto"
)

func (im *importer) verify(ctx context.Context) error {
	// 外键检查需要开启外键后的新连接
	chk, err := sql.Open("sqlite", "file:"+im.tmp+"?_pragma=foreign_keys(1)")
	if err != nil {
		return err
	}
	defer chk.Close()
	rows, err := chk.QueryContext(ctx, "PRAGMA foreign_key_check")
	if err != nil {
		return err
	}
	var bad []string
	for rows.Next() {
		var tbl, parent sql.NullString
		var rowid, fk sql.NullInt64
		rows.Scan(&tbl, &rowid, &parent, &fk)
		bad = append(bad, fmt.Sprintf("%s#%d→%s", tbl.String, rowid.Int64, parent.String))
		if len(bad) >= 10 {
			break
		}
	}
	rows.Close()
	if len(bad) > 0 {
		return fmt.Errorf("foreign_key_check 失败: %s", strings.Join(bad, ", "))
	}
	var res string
	if err := chk.QueryRowContext(ctx, "PRAGMA integrity_check").Scan(&res); err != nil || res != "ok" {
		return fmt.Errorf("integrity_check: %s %v", res, err)
	}
	// 行数对账
	for _, t := range tables {
		if t.name == "system_config" {
			continue // 有丢弃项，已在报告中列出
		}
		q := "select count(*) from `" + t.name + "`"
		if t.where != "" {
			q += " where " + t.where
		}
		want, err := im.scalar(ctx, q)
		if err != nil {
			return err
		}
		var got int
		chk.QueryRowContext(ctx, "SELECT count(*) FROM "+t.name).Scan(&got)
		if want != got {
			return fmt.Errorf("%s 行数不一致: 源 %d 目标 %d", t.name, want, got)
		}
		im.note("%s: %d 行", t.name, got)
	}
	// 关键字段逐行对账
	rows2, err := im.src.QueryContext(ctx, `select id, account_no, uuid, to_date, bandwidth, download_mbps, upload_mbps from account`)
	if err != nil {
		return err
	}
	defer rows2.Close()
	diff := 0
	for rows2.Next() {
		var id int64
		var no, uuid string
		var to sql.NullString
		var bw, dl, ul sql.NullInt64
		if err := rows2.Scan(&id, &no, &uuid, &to, &bw, &dl, &ul); err != nil {
			// 时间列需要 parseTime；退化为不比较时间
			return fmt.Errorf("读取账户对账数据: %w", err)
		}
		var gno, guuid string
		var gto sql.NullString
		var gbw, gdl, gul sql.NullInt64
		chk.QueryRowContext(ctx, `SELECT account_no, uuid, to_date, bandwidth, download_mbps, upload_mbps FROM account WHERE id = ?`, id).Scan(&gno, &guuid, &gto, &gbw, &gdl, &gul)
		wantTo := ""
		if to.Valid {
			wantTo = to.String
			if len(wantTo) > 19 {
				wantTo = wantTo[:19]
			}
		}
		if gno != no || guuid != uuid || gto.String != wantTo || gbw != bw || gdl != dl || gul != ul {
			diff++
		}
	}
	rows2.Close()
	if diff > 0 {
		return fmt.Errorf("account 关键字段有 %d 行与源库不一致", diff)
	}
	if err := im.verifyAuthCodes(ctx, chk); err != nil {
		return err
	}
	if err := im.verifyEncrypted(ctx, chk); err != nil {
		return err
	}
	return im.verifyMoney(ctx, chk)
}

// pairs 把两列查询结果读成 map[第一列]第二列。
func pairs(ctx context.Context, db querier, q string) (map[string]string, error) {
	rows, err := db.QueryContext(ctx, q)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	m := map[string]string{}
	for rows.Next() {
		var k, v string
		if err := rows.Scan(&k, &v); err != nil {
			return nil, err
		}
		m[k] = v
	}
	return m, rows.Err()
}

// verifyAuthCodes：保留项与源库相同，重新生成项与全部原值不同，且目标库全表唯一。
func (im *importer) verifyAuthCodes(ctx context.Context, chk *sql.DB) error {
	src, err := pairs(ctx, im.src, `select id, auth_code from account`)
	if err != nil {
		return err
	}
	dst, err := pairs(ctx, chk, `SELECT id, auth_code FROM account`)
	if err != nil {
		return err
	}
	if err := checkAuthCodes(src, dst, im.regen); err != nil {
		return err
	}
	im.note("auth_code 对账通过（重新生成 %d 个）", len(im.regen))
	return nil
}

func checkAuthCodes(src, dst map[string]string, regen map[string]bool) error {
	orig := map[string]bool{}
	for _, c := range src {
		orig[c] = true
	}
	seen := map[string]bool{}
	var bad []string
	for id, got := range dst {
		switch {
		case seen[got]:
			bad = append(bad, "account#"+id+"(重复)")
		case regen[id] && orig[got]:
			bad = append(bad, "account#"+id+"(未重新生成)")
		case !regen[id] && got != src[id]:
			bad = append(bad, "account#"+id+"(保留项被改动)")
		}
		seen[got] = true
	}
	if len(bad) > 0 {
		sort.Strings(bad)
		return fmt.Errorf("auth_code 对账失败: %s", strings.Join(bad[:min(len(bad), 10)], ", "))
	}
	return nil
}

// verifyEncrypted 解密目标字段并与源字段转换前的明文比较；报告只含字段定位。
func (im *importer) verifyEncrypted(ctx context.Context, chk *sql.DB) error {
	type field struct{ tbl, col, key, where string }
	fields := []field{{"server", "auth", "id", ""}, {"dns_provider_config", "credential_json", "id", ""}}
	for k := range encryptedKeys {
		fields = append(fields, field{"system_config", "config_value", "config_key", "config_key='" + k + "'"})
	}
	var bad []string
	checked := 0
	for _, f := range fields {
		q := fmt.Sprintf("select `%s`, `%s` from `%s`", f.key, f.col, f.tbl)
		if f.where != "" {
			q += " where " + f.where
		}
		rows, err := im.src.QueryContext(ctx, q)
		if err != nil {
			return err
		}
		want := map[string]string{}
		for rows.Next() {
			var k string
			var v sql.NullString
			if err := rows.Scan(&k, &v); err != nil {
				rows.Close()
				return err
			}
			if v.Valid {
				want[k] = v.String
			}
		}
		rows.Close()
		if err := rows.Err(); err != nil {
			return err
		}
		for k, raw := range want {
			plain := raw
			if p, err := crypto.LegacyDecrypt(im.legacy, raw); err == nil && strings.TrimSpace(raw) != "" {
				plain = p
			}
			var got sql.NullString
			if err := chk.QueryRowContext(ctx, fmt.Sprintf("SELECT %s FROM %s WHERE %s = ?", f.col, f.tbl, f.key), k).Scan(&got); err != nil {
				bad = append(bad, fmt.Sprintf("%s.%s[%s]", f.tbl, f.col, k))
				continue
			}
			dec := got.String
			if strings.TrimSpace(raw) != "" {
				if dec, err = im.field.Decrypt(got.String); err != nil {
					dec = "\x00decrypt-failed"
				}
			}
			if dec != plain {
				bad = append(bad, fmt.Sprintf("%s.%s[%s]", f.tbl, f.col, k))
			}
			checked++
		}
	}
	if len(bad) > 0 {
		sort.Strings(bad)
		return fmt.Errorf("敏感字段对账失败 %d 处: %s", len(bad), strings.Join(bad[:min(len(bad), 10)], ", "))
	}
	im.note("敏感字段解密对账通过（%d 个字段）", checked)
	return nil
}

// verifyMoney 按流水类型及服务器、域名价格汇总源库金额（转为整数分），与目标库比较。
func (im *importer) verifyMoney(ctx context.Context, chk *sql.DB) error {
	want, err := pairs(ctx, im.src, `select cast(type as unsigned), cast(sum(amount) as char) from transactions group by cast(type as unsigned)`)
	if err != nil {
		return err
	}
	for typ, sum := range want {
		if want[typ], err = toCents(sum); err != nil {
			return fmt.Errorf("transactions 汇总: %w", err)
		}
	}
	got, err := pairs(ctx, chk, `SELECT type, sum(amount) FROM transactions GROUP BY type`)
	if err != nil {
		return err
	}
	if len(want) != len(got) {
		return fmt.Errorf("流水类型数量不一致: 源 %d 目标 %d", len(want), len(got))
	}
	for typ, w := range want {
		if got[typ] != w {
			return fmt.Errorf("流水类型 %s 金额汇总不一致: 源 %s 分 目标 %s 分", typ, w, got[typ])
		}
	}
	for _, tbl := range []string{"server", "domain"} {
		var raw sql.NullString
		if err := im.src.QueryRowContext(ctx, "select cast(sum(price) as char) from `"+tbl+"`").Scan(&raw); err != nil {
			return err
		}
		w := "0"
		if raw.Valid {
			if w, err = toCents(raw.String); err != nil {
				return fmt.Errorf("%s.price 汇总: %w", tbl, err)
			}
		}
		var g sql.NullInt64
		if err := chk.QueryRowContext(ctx, "SELECT sum(price) FROM "+tbl).Scan(&g); err != nil {
			return err
		}
		if fmt.Sprint(g.Int64) != w {
			return fmt.Errorf("%s.price 汇总不一致: 源 %s 分 目标 %d 分", tbl, w, g.Int64)
		}
	}
	im.note("金额汇总对账通过")
	return nil
}
