package store

import (
	"context"
	"database/sql"
	"fmt"
	"io/fs"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
	"testing/fstest"
)

// openRawFK 用与 Open 相同的连接参数（开启外键）打开库，但不执行迁移，复现生产升级时的连接状态。
func openRawFK(t *testing.T, path string) *sql.DB {
	t.Helper()
	db, err := sql.Open("sqlite", dsn(path))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

// schemaBefore 返回编号小于 prefix 的迁移文件，用于构造旧版本库。
func schemaBefore(t *testing.T, prefix string) fstest.MapFS {
	t.Helper()
	old := fstest.MapFS{}
	files, err := fs.Glob(schemaFS, "schema/*.sql")
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		if file >= prefix {
			continue
		}
		body, err := schemaFS.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		old[file] = &fstest.MapFile{Data: body}
	}
	return old
}

// seedV20 在 0020 版本库中写入覆盖 account/server/domain 全部子表的数据，ID 刻意不连续。
func seedV20(t *testing.T, db *sql.DB) {
	t.Helper()
	for _, q := range []string{
		`INSERT INTO user(id,email,password) VALUES(1,'u@example.test','x')`,
		`INSERT INTO account(id,uuid,account_no,auth_code,period_type,from_date,to_date,disabled,user_id,max_connections,bandwidth) VALUES
			(3,'u3','A3','c3','MONTHLY','2020-01-01 00:00:00','2020-02-01 00:00:00',1,1,10,5),
			(5,'u5','A5','c5','MONTHLY','2020-01-01 00:00:00','2020-03-01 00:00:00',0,1,10,5),
			(7,'u7','A7','c7','YEARLY','2020-01-01 00:00:00','2099-01-01 00:00:00',1,1,10,5),
			(9,'u9','A9','c9','MONTHLY','2020-01-01 00:00:00',NULL,0,1,10,5)`,
		`INSERT INTO tag(id,name) VALUES(1,'t')`,
		`INSERT INTO account_tag VALUES(3,1),(5,1),(7,1),(9,1)`,
		`INSERT INTO server(id,ip,name,host_key,price_months,traffic_offset,traffic_offset_period,bandwidth_unit,supplier_url) VALUES
			(2,'203.0.113.2','s2','SHA256:k',1,123,'2026-01','GB','https://supplier.example.test'),
			(4,'203.0.113.4','s4',NULL,12,0,NULL,'GiB',NULL)`,
		`INSERT INTO node(id,name,type,port,protocol) VALUES(1,'n1',0,443,'vless')`,
		`INSERT INTO node_tag VALUES(1,1)`,
		`INSERT INTO dns_provider_config(id,provider_type,status,display_name) VALUES(1,'CLOUDFLARE','ENABLED','cf')`,
		`INSERT INTO domain(id,domain,dns_provider_config_id) VALUES(6,'example.test',1)`,
		`INSERT INTO domain_dns_record(id,domain_id,server_id,name,type,content,status) VALUES(1,6,2,'a','A','203.0.113.2','ACTIVE')`,
		`INSERT INTO node_server(id,node_id,server_id,access_host_id,deployed) VALUES(1,1,2,1,1),(2,1,4,NULL,1)`,
		`INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,domain_dns_record_id) VALUES(1,3,1,1)`,
		`INSERT INTO account_node_subscription_placement VALUES(1,1)`,
		`INSERT INTO account_traffic_stats(account_id,period_start,period_end,upload_bytes,download_bytes) VALUES(3,'2020-01-01 00:00:00','2020-02-01 00:00:00',1,2),(5,'2020-01-01 00:00:00','2020-02-01 00:00:00',1,2)`,
		`INSERT INTO account_traffic_counter VALUES(2,'A3','uplink',10,'2020-01-01 00:00:00')`,
		`INSERT INTO route_rule(id,name,rule_type,rule_value,outbound_node_id) VALUES(1,'r','domain','[]',1)`,
		`INSERT INTO route_rule_server VALUES(1,2)`,
		`INSERT INTO server_deployment(server_id,success) VALUES(2,1)`,
		`INSERT INTO server_metrics(server_id,kind,sample_time) VALUES(2,'monitor','2020-01-01 00:00:00')`,
		`INSERT INTO monitor_agent(server_id,token_hash) VALUES(2,'h')`,
		`INSERT INTO placement_probe(placement_id,probe_server_id,ok,msg,checked_time) VALUES(1,4,1,'ok','2020-01-01 00:00:00')`,
		`INSERT INTO server_blocked_port VALUES(2,8443,'2020-01-01 00:00:00')`,
		`INSERT INTO dns_auto_operation VALUES(1,'old','new',NULL,'test','2020-01-01 00:00:00')`,
		// 已删除记录留下的流水：迁移后新 ID 不得复用这些 business_id
		`INSERT INTO transactions(transaction_date,amount,type,business_table,business_id) VALUES
			('2020-01-01',1,0,'account',50),('2020-01-01',1,1,'server',60),('2020-01-01',1,1,'domain',70),('2020-01-01',1,0,'account',3)`,
	} {
		mustExec(t, db, q)
	}
}

func tableCounts(t *testing.T, db *sql.DB) map[string]int {
	t.Helper()
	rows, err := db.Query(`SELECT name FROM sqlite_schema WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name`)
	if err != nil {
		t.Fatal(err)
	}
	var names []string
	for rows.Next() {
		var n string
		rows.Scan(&n)
		names = append(names, n)
	}
	rows.Close()
	counts := map[string]int{}
	for _, n := range names {
		var c int
		if err := db.QueryRow(`SELECT count(*) FROM "` + n + `"`).Scan(&c); err != nil {
			t.Fatal(err)
		}
		counts[n] = c
	}
	return counts
}

// tableShape 返回表的列定义（不含新增列）、显式索引与依赖它的触发器，用于核对重建前后一致。
func tableShape(t *testing.T, db *sql.DB, table string, skipCols ...string) (cols []string, objects []string) {
	t.Helper()
	rows, err := db.Query(`SELECT name,type,"notnull",coalesce(dflt_value,''),pk,hidden FROM pragma_table_xinfo(?) ORDER BY cid`, table)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var name, typ, dflt string
		var notnull, pk, hidden int
		rows.Scan(&name, &typ, &notnull, &dflt, &pk, &hidden)
		skip := false
		for _, s := range skipCols {
			skip = skip || s == name
		}
		if !skip {
			cols = append(cols, fmt.Sprint(name, "|", typ, "|", notnull, "|", dflt, "|", pk, "|", hidden))
		}
	}
	rows.Close()
	// 索引（含 UNIQUE 自动索引的列与排序规则）
	rows, err = db.Query(`SELECT il.name, il."unique", il.origin, group_concat(ii.name||' '||coalesce(ii.coll,''), ',')
		FROM pragma_index_list(?) il JOIN pragma_index_xinfo(il.name) ii ON ii.key=1 GROUP BY il.name ORDER BY il.origin, il.name`, table)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var name, origin, cols string
		var unique int
		rows.Scan(&name, &unique, &origin, &cols)
		if origin == "u" { // 自动索引名与序号有关，只比较内容
			name = "autoindex"
		}
		objects = append(objects, fmt.Sprint("index|", name, "|", unique, "|", origin, "|", cols))
	}
	rows.Close()
	rows, err = db.Query(`SELECT type||'|'||name||'|'||tbl_name||'|'||sql FROM sqlite_schema
		WHERE type='trigger' AND (tbl_name=?1 OR sql LIKE '% '||?1||' %' OR sql LIKE '% '||?1||char(10)||'%') ORDER BY name`, table)
	if err != nil {
		t.Fatal(err)
	}
	for rows.Next() {
		var s string
		rows.Scan(&s)
		objects = append(objects, s)
	}
	rows.Close()
	return cols, objects
}

func foreignKeyList(t *testing.T, db *sql.DB) []string {
	t.Helper()
	rows, err := db.Query(`SELECT m.name||'|'||f."table"||'|'||f."from"||'|'||coalesce(f."to",'')||'|'||f.on_delete
		FROM sqlite_schema m JOIN pragma_foreign_key_list(m.name) f WHERE m.type='table' ORDER BY 1`)
	if err != nil {
		t.Fatal(err)
	}
	defer rows.Close()
	var out []string
	for rows.Next() {
		var s string
		rows.Scan(&s)
		out = append(out, s)
	}
	return out
}

// TestMigration21RebuildKeepsChildrenAndConstraints 用开启外键的连接从 0020 升级：
// 子表数据不得被 DROP TABLE 级联删除，列、索引、外键、触发器保持原样。
func TestMigration21RebuildKeepsChildrenAndConstraints(t *testing.T) {
	ctx := context.Background()
	db := openRawFK(t, filepath.Join(t.TempDir(), "old.db"))
	if err := migrate(ctx, db, schemaBefore(t, "schema/0021")); err != nil {
		t.Fatal(err)
	}
	seedV20(t, db)
	before := tableCounts(t, db)
	type shape struct{ cols, objects []string }
	shapes := map[string]shape{}
	for _, table := range []string{"account", "server", "domain"} {
		c, o := tableShape(t, db, table)
		shapes[table] = shape{c, o}
	}
	fks := foreignKeyList(t, db)

	// 这里只核对 0021 重建表；0022 会有意收窄账户 UPDATE 触发器。
	if err := migrate(ctx, db, schemaBefore(t, "schema/0022")); err != nil {
		t.Fatal(err)
	}
	var version, fk int
	db.QueryRow(`PRAGMA user_version`).Scan(&version)
	if version < 21 {
		t.Fatalf("user_version=%d", version)
	}
	// 连接池中的连接都必须恢复外键约束
	db.SetMaxOpenConns(1)
	if err := db.QueryRow(`PRAGMA foreign_keys`).Scan(&fk); err != nil || fk != 1 {
		t.Fatalf("迁移后外键未恢复: %d %v", fk, err)
	}
	after := tableCounts(t, db)
	if !reflect.DeepEqual(before, after) {
		t.Fatalf("迁移改变了行数:\nbefore=%v\nafter=%v", before, after)
	}
	for table, want := range shapes {
		c, o := tableShape(t, db, table, "expire_handled_to")
		if !reflect.DeepEqual(c, want.cols) {
			t.Fatalf("%s 列定义变化:\n%v\n%v", table, want.cols, c)
		}
		if !reflect.DeepEqual(o, want.objects) {
			t.Fatalf("%s 索引/触发器变化:\n%v\n%v", table, want.objects, o)
		}
		var create string
		db.QueryRow(`SELECT sql FROM sqlite_schema WHERE type='table' AND name=?`, table).Scan(&create)
		if !strings.Contains(create, "AUTOINCREMENT") {
			t.Fatalf("%s 未改为 AUTOINCREMENT: %s", table, create)
		}
	}
	if got := foreignKeyList(t, db); !reflect.DeepEqual(got, fks) {
		t.Fatalf("外键定义变化:\n%v\n%v", fks, got)
	}
	var check int
	db.QueryRow(`SELECT count(*) FROM pragma_foreign_key_check`).Scan(&check)
	if check != 0 {
		t.Fatal("存在悬空外键", check)
	}
	// 后续 ALTER 加的列数据完整保留
	var hostKey, unit, supplierURL, offsetPeriod string
	var months, offset int
	db.QueryRow(`SELECT host_key,price_months,traffic_offset,traffic_offset_period,bandwidth_unit,supplier_url FROM server WHERE id=2`).
		Scan(&hostKey, &months, &offset, &offsetPeriod, &unit, &supplierURL)
	if hostKey != "SHA256:k" || months != 1 || offset != 123 || offsetPeriod != "2026-01" || unit != "GB" || supplierURL != "https://supplier.example.test" {
		t.Fatal("服务器列数据丢失", hostKey, months, offset, offsetPeriod, unit, supplierURL)
	}
	var anchor string
	db.QueryRow(`SELECT traffic_anchor FROM account WHERE id=3`).Scan(&anchor)
	if anchor == "" {
		t.Fatal("traffic_anchor 丢失")
	}

	// CHECK / UNIQUE / COLLATE 仍生效
	for _, q := range []string{
		`INSERT INTO server(ip,price_months) VALUES('203.0.113.9',3)`,
		`INSERT INTO server(ip,bandwidth_unit) VALUES('203.0.113.9','TB')`,
		`INSERT INTO account(uuid,account_no,auth_code,period_type) VALUES('x1','X1','x1','DAILY')`,
		`INSERT INTO account(uuid,account_no,auth_code,period_type) VALUES('x2','a3','x2','MONTHLY')`,
		`INSERT INTO account(uuid,account_no,auth_code,period_type) VALUES('u3','X3','x3','MONTHLY')`,
		`INSERT INTO domain(domain,dns_binding_extension_json) VALUES('bad.test','{')`,
		`INSERT INTO account(uuid,account_no,auth_code,period_type,user_id) VALUES('x4','X4','x4','MONTHLY',999)`,
		`INSERT INTO account_tag VALUES(999,1)`,
	} {
		if _, err := db.Exec(q); err == nil {
			t.Fatalf("约束未生效: %s", q)
		}
	}
	// RESTRICT：仍被部署位引用的服务器、仍有账户的用户不可删
	if _, err := db.Exec(`DELETE FROM server WHERE id=4`); err == nil {
		t.Fatal("node_server RESTRICT 未生效")
	}
	if _, err := db.Exec(`DELETE FROM user WHERE id=1`); err == nil {
		t.Fatal("account.user_id RESTRICT 未生效")
	}
	// 触发器：DNS 自动操作保护、部署修订号、流量锚点
	if _, err := db.Exec(`UPDATE domain SET remark='x' WHERE id=6`); err == nil || !strings.Contains(err.Error(), "DNS 自动操作尚待对账") {
		t.Fatal("domain 触发器未生效", err)
	}
	rev := func() (n int64) { db.QueryRow(`SELECT revision FROM deploy_revision`).Scan(&n); return }
	r0 := rev()
	mustExec(t, db, `UPDATE server SET ip='203.0.113.5' WHERE id=4`)
	r1 := rev()
	mustExec(t, db, `UPDATE account SET remark='x' WHERE id=9`)
	if r1 <= r0 || rev() <= r1 {
		t.Fatal("deploy_revision 触发器未生效", r0, r1, rev())
	}
	newID := mustExec(t, db, `INSERT INTO account(uuid,account_no,auth_code,period_type,from_date) VALUES('n1','N1','n1','MONTHLY','2021-05-06 07:08:09')`)
	db.QueryRow(`SELECT coalesce(traffic_anchor,'') FROM account WHERE id=?`, newID).Scan(&anchor)
	if anchor != "2021-05-06 07:08:09" {
		t.Fatal("流量锚点触发器未生效", anchor)
	}
	// CASCADE：删账户级联标签、绑定、流量统计与累计读数；删服务器级联其附属表
	mustExec(t, db, `DELETE FROM account WHERE id=3`)
	for _, q := range []string{
		`SELECT count(*) FROM account_tag WHERE account_id=3`,
		`SELECT count(*) FROM account_node_subscription_domain_binding WHERE account_id=3`,
		`SELECT count(*) FROM account_traffic_stats WHERE account_id=3`,
		`SELECT count(*) FROM account_traffic_counter WHERE account_no='A3'`,
	} {
		var n int
		db.QueryRow(q).Scan(&n)
		if n != 0 {
			t.Fatalf("级联删除未生效: %s", q)
		}
	}
}

// TestMigration21NoIDReuse 删除最大 ID 后新行不复用，也不复用流水里出现过的已删除记录 ID。
func TestMigration21NoIDReuse(t *testing.T) {
	ctx := context.Background()
	db := openRawFK(t, filepath.Join(t.TempDir(), "old.db"))
	if err := migrate(ctx, db, schemaBefore(t, "schema/0021")); err != nil {
		t.Fatal(err)
	}
	seedV20(t, db)
	if err := migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
	// 流水中的已删除 ID 高于现存最大 ID
	if id := mustExec(t, db, `INSERT INTO account(uuid,account_no,auth_code,period_type) VALUES('n1','N1','n1','MONTHLY')`); id <= 50 {
		t.Fatalf("account 复用了流水中的 ID: %d", id)
	}
	if id := mustExec(t, db, `INSERT INTO server(ip) VALUES('203.0.113.8')`); id <= 60 {
		t.Fatalf("server 复用了流水中的 ID: %d", id)
	}
	if id := mustExec(t, db, `INSERT INTO domain(domain) VALUES('new.test')`); id <= 70 {
		t.Fatalf("domain 复用了流水中的 ID: %d", id)
	}
	// 删除最大 ID 后再插入
	for _, table := range []string{"account", "server", "domain"} {
		var maxID int64
		db.QueryRow(`SELECT max(id) FROM ` + table).Scan(&maxID)
		mustExec(t, db, `DELETE FROM `+table+` WHERE id=?`, maxID)
		var id int64
		switch table {
		case "account":
			id = mustExec(t, db, `INSERT INTO account(uuid,account_no,auth_code,period_type) VALUES('n2','N2','n2','MONTHLY')`)
		case "server":
			id = mustExec(t, db, `INSERT INTO server(ip) VALUES('203.0.113.7')`)
		default:
			id = mustExec(t, db, `INSERT INTO domain(domain) VALUES('new2.test')`)
		}
		if id <= maxID {
			t.Fatalf("%s 复用了已删除的最大 ID: %d <= %d", table, id, maxID)
		}
	}
	// 全新库同样不复用
	fresh, ctx := testDB(t)
	a := mustExec(t, fresh, `INSERT INTO server(ip) VALUES('203.0.113.1')`)
	mustExec(t, fresh, `DELETE FROM server WHERE id=?`, a)
	if b := mustExec(t, fresh, `INSERT INTO server(ip) VALUES('203.0.113.1')`); b <= a {
		t.Fatalf("全新库复用了 ID: %d <= %d", b, a)
	}
	_ = ctx
}

// TestMigration21SchemaMatchesFresh 带数据升级后的 schema 与全新库完全一致。
func TestMigration21SchemaMatchesFresh(t *testing.T) {
	ctx := context.Background()
	old := openRawFK(t, filepath.Join(t.TempDir(), "old.db"))
	if err := migrate(ctx, old, schemaBefore(t, "schema/0021")); err != nil {
		t.Fatal(err)
	}
	seedV20(t, old)
	if err := migrate(ctx, old, schemaFS); err != nil {
		t.Fatal(err)
	}
	fresh, _ := testDB(t)
	dump := func(db *sql.DB) []string {
		rows, err := db.Query(`SELECT type||'|'||name||'|'||tbl_name||'|'||coalesce(sql,'') FROM sqlite_schema ORDER BY type,name`)
		if err != nil {
			t.Fatal(err)
		}
		defer rows.Close()
		var out []string
		for rows.Next() {
			var s string
			rows.Scan(&s)
			out = append(out, s)
		}
		return out
	}
	if a, b := dump(old), dump(fresh); !reflect.DeepEqual(a, b) {
		t.Fatalf("升级库与全新库 schema 不一致:\n%s\n----\n%s", strings.Join(a, "\n"), strings.Join(b, "\n"))
	}
}

// TestMigration21SeparatesExpiryFromDisabled 存量「已到期且禁用」视为旧到期任务所致：恢复 disabled=0 并记为已处理；
// 未到期的手动禁用保持不变；已到期但未处理的账户留给到期任务。
func TestMigration21SeparatesExpiryFromDisabled(t *testing.T) {
	ctx := context.Background()
	db := openRawFK(t, filepath.Join(t.TempDir(), "old.db"))
	if err := migrate(ctx, db, schemaBefore(t, "schema/0021")); err != nil {
		t.Fatal(err)
	}
	seedV20(t, db)
	if err := migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct {
		id       int64
		disabled int
		handled  string
	}{{3, 0, "2020-02-01 00:00:00"}, {5, 0, ""}, {7, 1, ""}, {9, 0, ""}} {
		var disabled int
		var handled string
		if err := db.QueryRow(`SELECT disabled,coalesce(expire_handled_to,'') FROM account WHERE id=?`, c.id).Scan(&disabled, &handled); err != nil {
			t.Fatal(err)
		}
		if disabled != c.disabled || handled != c.handled {
			t.Fatalf("账户 %d: disabled=%d handled=%q, want %d %q", c.id, disabled, handled, c.disabled, c.handled)
		}
	}
}
