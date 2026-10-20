// airopscat-import 把 Java 版 MySQL 数据一次性导入全新的 SQLite 库，规则见 docs/rewrite/migration.md。
package main

import (
	"context"
	"crypto/rand"
	"database/sql"
	"encoding/hex"
	"flag"
	"fmt"
	"net/url"
	"os"
	"sort"
	"strings"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
	_ "github.com/go-sql-driver/mysql"
	_ "modernc.org/sqlite"
)

type table struct {
	name  string
	where string // 源库过滤条件
}

// 按外键依赖排序；自引用和环形引用靠导入时关闭外键、结束后 foreign_key_check 保证。
var tables = []table{
	{"user", ""}, {"tag", ""}, {"dns_provider_config", ""}, {"domain", ""}, {"server", ""},
	{"node", ""}, {"account", ""}, {"domain_dns_record", ""}, {"node_server", ""},
	{"account_tag", ""}, {"node_tag", ""}, {"route_rule", ""}, {"route_rule_server", ""},
	{"account_node_subscription_domain_binding", ""}, {"account_node_subscription_placement", ""},
	{"account_traffic_stats", ""},
	{"account_online_ip", "last_online_time >= NOW() - INTERVAL 30 DAY"}, // 决策 8：只保留最近 30 天
	{"node_deployment", ""}, {"system_config", ""}, {"system_log", ""}, {"transactions", ""}, {"alert_state", ""},
}

var moneyCols = map[string]bool{"server.price": true, "domain.price": true, "transactions.amount": true}
var encryptedCols = map[string]bool{"server.auth": true, "dns_provider_config.credential_json": true}

// system_config 中加密存储的 key（SystemConfigService storageEncrypted）
var encryptedKeys = map[string]bool{
	"airopscat.bark.device-key": true, "airopscat.bark.encrypt-key": true, "airopscat.bark.encrypt-iv": true,
	"airopscat.api.token": true, "airopscat.apple.id": true, "airopscat.apple.pwd": true,
	"airopscat.sing-box.clash-api.secret": true,
}

// 新版不再使用的 system_config key（architecture.md 第 3 节）
func droppedKey(k string) bool {
	if strings.HasSuffix(k, ".cron") {
		return true
	}
	for _, p := range []string{"airopscat.request-log.", "airopscat.backup.mysqldump", "airopscat.sing-box.clash-api.", "airopscat.node.online-account.", "airopscat.node.deployment.history.", "airopscat.account.multiplier", "airopscat.config.templates.dir"} {
		if strings.HasPrefix(k, p) {
			return true
		}
	}
	return false
}

// querier 由源库的一致性快照事务实现；预检、导出与对账都经它读取。
// 同一事务连接上同一时刻只能有一个未关闭的结果集。
type querier interface {
	QueryContext(ctx context.Context, q string, args ...any) (*sql.Rows, error)
	QueryRowContext(ctx context.Context, q string, args ...any) *sql.Row
}

type importer struct {
	src    querier
	dst    *sql.DB
	legacy string
	tmp    string
	regen  map[string]bool // 需要重新生成 auth_code 的账户 id
	field  *crypto.Cipher
	report []string
}

func (im *importer) note(f string, a ...any) { im.report = append(im.report, fmt.Sprintf(f, a...)) }

func main() {
	dsn := flag.String("mysql-dsn", "", "MySQL DSN，如 user:pass@tcp(127.0.0.1:3306)/airopscat")
	oldKey := flag.String("old-secret-key", "AirOpsCatDefaultSecretKey2024", "Java 版 airopscat.crypto.secret-key")
	out := flag.String("out", "", "输出 SQLite 文件（必须不存在）")
	dropSpeed := flag.Bool("drop-speed", false, "确认丢弃 account.speed（决策 24 D1/25）")
	flag.Parse()
	if err := run(*dsn, *oldKey, *out, *dropSpeed); err != nil {
		fmt.Fprintln(os.Stderr, "导入失败:", err)
		os.Exit(1)
	}
}

func run(dsn, oldKey, out string, dropSpeed bool) error {
	secret := os.Getenv("AIROPSCAT_SECRET_KEY")
	if dsn == "" || out == "" || len(secret) < crypto.MinSecretLen {
		return fmt.Errorf("需要 --mysql-dsn、--out，以及至少 %d 字符的环境变量 AIROPSCAT_SECRET_KEY", crypto.MinSecretLen)
	}
	if _, err := os.Stat(out); err == nil {
		return fmt.Errorf("%s 已存在，拒绝覆盖", out)
	}
	ctx := context.Background()
	src, err := sql.Open("mysql", dsn)
	if err != nil {
		return err
	}
	defer src.Close()
	// 源端整个导入共用一份 REPEATABLE READ 快照；立即读取业务表以建立 InnoDB 快照点。
	snap, err := src.BeginTx(ctx, &sql.TxOptions{Isolation: sql.LevelRepeatableRead, ReadOnly: true})
	if err != nil {
		return err
	}
	defer snap.Rollback()
	var n int
	if err := snap.QueryRowContext(ctx, "select count(*) from account").Scan(&n); err != nil {
		return err
	}
	tmp := out + ".tmp"
	os.Remove(tmp)
	// 先用主程序同一套 schema 建表
	db, err := store.Open(ctx, tmp)
	if err != nil {
		return err
	}
	db.Close()

	q := url.Values{}
	q.Add("_pragma", "foreign_keys(0)")
	dst, err := sql.Open("sqlite", "file:"+tmp+"?"+q.Encode())
	if err != nil {
		return err
	}
	dst.SetMaxOpenConns(1)
	field, _ := crypto.New(crypto.DeriveKey(secret, "field"))
	im := &importer{src: snap, dst: dst, legacy: oldKey, field: field, tmp: tmp, regen: map[string]bool{}}

	ok := false
	defer func() {
		dst.Close()
		os.Remove(tmp + "-wal")
		os.Remove(tmp + "-shm")
		if !ok {
			os.Remove(tmp)
		}
	}()
	if err := im.precheck(ctx, dropSpeed); err != nil {
		return err
	}
	tx, err := dst.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	for _, t := range tables {
		if err := im.copyTable(ctx, tx, t); err != nil {
			return fmt.Errorf("%s: %w", t.name, err)
		}
	}
	if err := im.fixups(ctx, tx); err != nil {
		return err
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	if err := im.verify(ctx); err != nil {
		return err
	}
	dst.Close()
	if err := os.Rename(tmp, out); err != nil {
		return err
	}
	ok = true
	fmt.Println("导入完成:", out)
	for _, l := range im.report {
		fmt.Println(" -", l)
	}
	return nil
}

func (im *importer) scalar(ctx context.Context, q string) (int, error) {
	var n sql.NullInt64
	err := im.src.QueryRowContext(ctx, q).Scan(&n)
	return int(n.Int64), err
}

func (im *importer) precheck(ctx context.Context, dropSpeed bool) error {
	if n, err := im.scalar(ctx, "select sum(speed > 0) from account"); err == nil && n > 0 {
		if !dropSpeed {
			return fmt.Errorf("account.speed 有 %d 行大于 0，确认丢弃请加 --drop-speed", n)
		}
		im.note("丢弃 account.speed（%d 行大于 0）", n)
	}
	// auth_code 去重：保留未禁用且较早的一个，其余重新生成
	rows, err := im.src.QueryContext(ctx, `select a.id, a.auth_code from account a join (select auth_code from account group by 1 having count(*)>1) d on d.auth_code = a.auth_code order by a.auth_code, coalesce(a.disabled,0), a.id`)
	if err != nil {
		return err
	}
	prev := ""
	for rows.Next() {
		var id, code string
		rows.Scan(&id, &code)
		if code == prev {
			im.regen[id] = true
			im.note("账户 %s 的 auth_code 与他人重复，已重新生成", id)
		}
		prev = code
	}
	rows.Close()
	for _, c := range []struct{ msg, q string }{
		{"user.email 小写后重复", "select count(*) from (select lower(email) from user group by 1 having count(*)>1) t"},
		{"tag.name 小写后重复", "select count(*) from (select lower(name) from tag group by 1 having count(*)>1) t"},
		{"account.account_no 小写后重复", "select count(*) from (select lower(account_no) from account group by 1 having count(*)>1) t"},
		{"account.uuid 重复", "select count(*) from (select uuid from account group by 1 having count(*)>1) t"},
		{"account_traffic_stats 同账户同周期重复", "select count(*) from (select account_id, period_start from account_traffic_stats group by 1,2 having count(*)>1) t"},
	} {
		if n, err := im.scalar(ctx, c.q); err != nil {
			return err
		} else if n > 0 {
			return fmt.Errorf("前置检查失败：%s（%d 组）", c.msg, n)
		}
	}
	return nil
}

type colInfo struct{ name, srcType string }

func (im *importer) srcColumns(ctx context.Context, tbl string) (map[string]string, error) {
	rows, err := im.src.QueryContext(ctx, `select column_name, data_type from information_schema.columns where table_schema = database() and table_name = ?`, tbl)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	m := map[string]string{}
	for rows.Next() {
		var n, t string
		rows.Scan(&n, &t)
		m[strings.ToLower(n)] = strings.ToLower(t)
	}
	return m, rows.Err()
}

func (im *importer) dstColumns(ctx context.Context, tx *sql.Tx, tbl string) ([]string, error) {
	rows, err := tx.QueryContext(ctx, fmt.Sprintf("PRAGMA table_info(%s)", tbl))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var cols []string
	for rows.Next() {
		var cid, notnull, pk int
		var name, typ string
		var dflt sql.NullString
		rows.Scan(&cid, &name, &typ, &notnull, &dflt, &pk)
		cols = append(cols, name)
	}
	return cols, rows.Err()
}

func (im *importer) copyTable(ctx context.Context, tx *sql.Tx, t table) error {
	srcCols, err := im.srcColumns(ctx, t.name)
	if err != nil {
		return err
	}
	if len(srcCols) == 0 {
		return fmt.Errorf("源库没有该表")
	}
	dstCols, err := im.dstColumns(ctx, tx, t.name)
	if err != nil {
		return err
	}
	var cols []string
	for _, c := range dstCols {
		if _, ok := srcCols[c]; ok {
			cols = append(cols, c)
		}
	}
	q := "select `" + strings.Join(cols, "`,`") + "` from `" + t.name + "`"
	if t.where != "" {
		q += " where " + t.where
	}
	rows, err := im.src.QueryContext(ctx, q)
	if err != nil {
		return err
	}
	defer rows.Close()
	count, plain := 0, 0
	for rows.Next() {
		vals := make([]sql.NullString, len(cols))
		ptrs := make([]any, len(cols))
		for i := range vals {
			ptrs[i] = &vals[i]
		}
		if err := rows.Scan(ptrs...); err != nil {
			return err
		}
		var useCols []string
		var args []any
		var key, id string
		for i, c := range cols {
			if c == "id" {
				id = vals[i].String
			}
			if c == "config_key" {
				key = vals[i].String
			}
		}
		if t.name == "system_config" && droppedKey(key) {
			im.note("丢弃配置 %s", key)
			continue
		}
		for i, c := range cols {
			if !vals[i].Valid {
				continue // 交给列默认值
			}
			v := vals[i].String
			full := t.name + "." + c
			switch {
			case full == "account.auth_code" && im.regen[id]:
				v = randHex()
			case srcCols[c] == "bit":
				if v == "\x01" || v == "1" {
					v = "1"
				} else {
					v = "0"
				}
			case srcCols[c] == "datetime" || srcCols[c] == "timestamp":
				if len(v) > 19 {
					v = v[:19]
				}
			case moneyCols[full]:
				cents, err := toCents(v)
				if err != nil {
					return fmt.Errorf("%s=%q: %w", full, v, err)
				}
				v = cents
			case encryptedCols[full] || (t.name == "system_config" && c == "config_value" && encryptedKeys[key]):
				enc, wasPlain, err := im.reencrypt(v)
				if err != nil {
					return err
				}
				if wasPlain {
					plain++
				}
				v = enc
			}
			useCols = append(useCols, c)
			args = append(args, v)
		}
		ins := fmt.Sprintf("INSERT INTO %s (%s) VALUES (%s)", t.name, strings.Join(useCols, ","), strings.TrimSuffix(strings.Repeat("?,", len(useCols)), ","))
		if _, err := tx.ExecContext(ctx, ins, args...); err != nil {
			return fmt.Errorf("%w (行 %d)", err, count+1)
		}
		count++
	}
	if plain > 0 {
		im.note("%s: %d 个加密字段原为明文，已按新格式加密", t.name, plain)
	}
	return rows.Err()
}

func (im *importer) reencrypt(v string) (string, bool, error) {
	if strings.TrimSpace(v) == "" {
		return v, false, nil
	}
	plain, err := crypto.LegacyDecrypt(im.legacy, v)
	wasPlain := err != nil // 旧版「解密失败返回原文」兼容的历史明文
	if wasPlain {
		plain = v
	}
	enc, err := im.field.Encrypt(plain)
	return enc, wasPlain, err
}

// toCents 把十进制金额字符串转为整数分，不经浮点；小数超过 2 位报错。
func toCents(s string) (string, error) {
	neg := strings.HasPrefix(s, "-")
	s = strings.TrimPrefix(s, "-")
	whole, frac, _ := strings.Cut(s, ".")
	frac = strings.TrimRight(frac, "0")
	if len(frac) > 2 {
		return "", fmt.Errorf("小数超过 2 位")
	}
	frac += strings.Repeat("0", 2-len(frac))
	out := strings.TrimLeft(whole+frac, "0")
	if out == "" {
		out = "0"
	}
	if neg && out != "0" {
		out = "-" + out
	}
	return out, nil
}

func randHex() string {
	b := make([]byte, 16)
	rand.Read(b)
	return hex.EncodeToString(b)
}

// fixups 处理需要跨表的转换：server_host 并回 server.host、account.auth_code 去重。
func (im *importer) fixups(ctx context.Context, tx *sql.Tx) error {
	// 服务器域名字段已删除（决策 30）：导出旧 host，未绑定 DNS 记录的列入报告，由人工确认
	hosts, err := im.src.QueryContext(ctx, `select server_id, host from server_host union select id, host from server where host is not null and host <> ''`)
	if err != nil {
		return err
	}
	defer hosts.Close()
	var legacy []string
	for hosts.Next() {
		var sid int64
		var host string
		hosts.Scan(&sid, &host)
		host = strings.TrimSpace(host)
		var n int
		tx.QueryRowContext(ctx, `SELECT count(*) FROM domain_dns_record WHERE full_name = ?`, host).Scan(&n)
		if n == 0 && host != "" {
			legacy = append(legacy, fmt.Sprintf("server %d: %s", sid, host))
		}
	}
	if len(legacy) > 0 {
		im.note("旧服务器域名（未绑定 DNS 记录，需人工确认是否补建）: %s", strings.Join(legacy, "; "))
	}
	return nil
}

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

// verifyAuthCodes：保留项与源库相同，重新生成项与全部原值不同，且目标库全表唯一。
func (im *importer) verifyAuthCodes(ctx context.Context, chk *sql.DB) error {
	rows, err := im.src.QueryContext(ctx, `select id, auth_code from account`)
	if err != nil {
		return err
	}
	src := map[string]string{}
	for rows.Next() {
		var id, code string
		if err := rows.Scan(&id, &code); err != nil {
			rows.Close()
			return err
		}
		src[id] = code
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	drows, err := chk.QueryContext(ctx, `SELECT id, auth_code FROM account`)
	if err != nil {
		return err
	}
	defer drows.Close()
	dst := map[string]string{}
	for drows.Next() {
		var id, code string
		if err := drows.Scan(&id, &code); err != nil {
			return err
		}
		dst[id] = code
	}
	if err := drows.Err(); err != nil {
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
	rows, err := im.src.QueryContext(ctx, `select cast(type as unsigned), cast(sum(amount) as char) from transactions group by cast(type as unsigned)`)
	if err != nil {
		return err
	}
	want := map[string]string{}
	for rows.Next() {
		var typ, sum string
		if err := rows.Scan(&typ, &sum); err != nil {
			rows.Close()
			return err
		}
		if want[typ], err = toCents(sum); err != nil {
			rows.Close()
			return fmt.Errorf("transactions 汇总: %w", err)
		}
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		return err
	}
	got := map[string]string{}
	drows, err := chk.QueryContext(ctx, `SELECT type, sum(amount) FROM transactions GROUP BY type`)
	if err != nil {
		return err
	}
	defer drows.Close()
	for drows.Next() {
		var typ, sum string
		if err := drows.Scan(&typ, &sum); err != nil {
			return err
		}
		got[typ] = sum
	}
	if err := drows.Err(); err != nil {
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
