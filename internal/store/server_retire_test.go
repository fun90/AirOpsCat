package store

import (
	"database/sql"
	"errors"
	"strings"
	"testing"
)

func count(t *testing.T, db *sql.DB, q string, args ...any) int {
	t.Helper()
	var n int
	if err := db.QueryRow(q, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestRetireServer(t *testing.T) {
	db, ctx := testDB(t)
	x := addServer(t, db, "x", "HK", NodeProxy, false, false)
	y := addServer(t, db, "y", "JP", NodeProxy, false, false)
	setServerIP(t, db, y, "2.2.2.2")
	z := addServer(t, db, "z", "SG", NodeProxy, false, false)
	setServerIP(t, db, z, "3.3.3.3")
	mk := func(name string, typ, port int) int64 {
		id, err := SaveNode(ctx, db, newNode(name, "HK", typ, port), nil, nil)
		if err != nil {
			t.Fatal(err)
		}
		return id
	}
	l := mk("l", NodeLanding, 8388)
	if err := addPl(ctx, db, l, y); err != nil {
		t.Fatal(err)
	}
	a := newNode("a", "HK", NodeProxy, 443)
	a.OutID = &l
	aid, err := SaveNode(ctx, db, a, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if err := addPl(ctx, db, aid, y); err != nil {
		t.Fatal(err)
	}
	b := mk("b", NodeProxy, 444) // 待删除，报废后清理
	c := mk("c", NodeProxy, 445) // 只在 x 上、无域名：保留节点
	d := mk("d", NodeProxy, 446) // 只在 x 上且有访问域名：阻止
	m := mk("m", NodeLanding, 8389)
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE node_id=?`, b)
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES
		(1,1,?,'a','a.example.test','A','1.1.1.1','SYNCED'),(2,1,?,'d','d.example.test','A','1.1.1.1','SYNCED'),
		(3,1,?,'z','z.example.test','A','1.1.1.1','SYNCED')`, x, x, z)
	mustExec(t, db, `UPDATE domain_dns_record SET failover_home_content='3.3.3.3' WHERE id=3`)
	mustExec(t, db, `UPDATE node_server SET access_host_id=1 WHERE node_id=? AND server_id=?`, aid, x)
	mustExec(t, db, `UPDATE node_server SET access_host_id=2 WHERE node_id=?`, d)
	mustExec(t, db, `INSERT INTO route_rule(id,name,rule_type,rule_value,outbound_node_id,enabled) VALUES(1,'rr','domain','["a.test"]',?,1)`, m)
	before := count(t, db, `SELECT count(*) FROM node_server`)

	_, err = RetireServer(ctx, db, x, false)
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("有无法承接的域名时应拒绝: %v", err)
	}
	for _, want := range []string{"节点 d", "z.example.test", "落地节点 m"} {
		if !strings.Contains(err.Error(), want) {
			t.Fatalf("应列出 %s: %v", want, err)
		}
	}
	if count(t, db, `SELECT count(*) FROM node_server`) != before || count(t, db, `SELECT count(*) FROM server WHERE disabled=1`) != 0 ||
		count(t, db, `SELECT count(*) FROM domain_dns_record WHERE id=1 AND server_id=?`, x) != 1 {
		t.Fatal("拒绝时应整体回滚")
	}

	mustExec(t, db, `UPDATE node_server SET access_host_id=NULL WHERE node_id=?`, d)
	mustExec(t, db, `UPDATE domain_dns_record SET content='3.3.3.3', failover_home_content=NULL WHERE id=3`)
	mustExec(t, db, `DELETE FROM route_rule`)
	res, err := RetireServer(ctx, db, x, false)
	if err != nil {
		t.Fatal(err)
	}
	if res.Removed != 6 || res.Transferred != 1 || res.DeletedNodes != 1 || res.ServerDeleted {
		t.Fatalf("报废结果不对: %+v", res)
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE server_id=?`, x) != 0 || count(t, db, `SELECT count(*) FROM server WHERE id=? AND disabled=1`, x) != 1 {
		t.Fatal("应移除全部部署位并保留为禁用")
	}
	var sid int64
	var content, status string
	db.QueryRow(`SELECT server_id,content,status FROM domain_dns_record WHERE id=1`).Scan(&sid, &content, &status)
	if sid != y || content != "2.2.2.2" || status != DNSPendingUpdate {
		t.Fatalf("访问域名应转移到同节点其他部署位并待同步: %d %s %s", sid, content, status)
	}
	if p := placementsOf(t, db, aid)["y"]; p.AccessHostID == nil || *p.AccessHostID != 1 || p.Deployed != PendingDeploy {
		t.Fatalf("承接部署位应获得访问域名，且因落地变化待部署: %+v", p)
	}
	if count(t, db, `SELECT count(*) FROM node WHERE id=?`, b) != 0 || count(t, db, `SELECT count(*) FROM node WHERE id IN (?,?,?)`, c, d, m) != 3 {
		t.Fatal("只清理原为待删除且无剩余部署位的节点")
	}
	if count(t, db, `SELECT count(*) FROM system_log WHERE category='SERVER' AND resource_id=? AND message LIKE '服务器报废%'`, x) != 1 {
		t.Fatal("应写系统日志")
	}
	// 报废后可一并删除服务器记录
	if res, err := RetireServer(ctx, db, x, true); err != nil || !res.ServerDeleted || count(t, db, `SELECT count(*) FROM server WHERE id=?`, x) != 0 {
		t.Fatalf("删除服务器记录失败: %+v %v", res, err)
	}
	if _, err := RetireServer(ctx, db, x, true); !errors.Is(err, sql.ErrNoRows) {
		t.Fatalf("不存在的服务器: %v", err)
	}
}

func TestMigrateServerPlacements(t *testing.T) {
	db, ctx := testDB(t)
	a := addServer(t, db, "a", "HK", NodeProxy, false, false)
	b := addServer(t, db, "b", "JP", NodeProxy, false, false)
	setServerIP(t, db, b, "2.2.2.2")
	c := addServer(t, db, "c", "SG", NodeProxy, false, false)
	setServerIP(t, db, c, "3.3.3.3")
	l, err := SaveNode(ctx, db, newNode("l", "HK", NodeLanding, 8388), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	p1 := newNode("p1", "HK", NodeProxy, 443)
	p1.OutID = &l
	id1, err := SaveNode(ctx, db, p1, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	id2, err := SaveNode(ctx, db, newNode("p2", "HK", NodeProxy, 444), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := SaveNode(ctx, db, newNode("q", "SG", NodeProxy, 443), nil, nil); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	mustExec(t, db, `UPDATE node_server SET priority=5, exposed=0 WHERE node_id=?`, id2)
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES(1,1,?,'a','a.example.test','A','1.1.1.1','SYNCED')`, a)
	mustExec(t, db, `UPDATE node_server SET access_host_id=1 WHERE node_id=?`, id1)

	if _, err := MigrateServerPlacements(ctx, db, a, c); !errors.Is(err, ErrConflict) || !strings.Contains(err.Error(), "p1") {
		t.Fatalf("端口冲突应整体回滚并列出: %v", err)
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE server_id=?`, c) != 1 {
		t.Fatal("冲突时不应添加任何部署位")
	}
	if _, err := MigrateServerPlacements(ctx, db, a, a); !errors.Is(err, ErrInvalid) {
		t.Fatalf("不能迁移到自身: %v", err)
	}
	added, err := MigrateServerPlacements(ctx, db, a, b)
	if err != nil || added != 3 {
		t.Fatalf("迁移第一步: %d %v", added, err)
	}
	if p := placementsOf(t, db, id2)["b"]; p.Priority != 5 || p.Exposed || p.Deployed != PendingDeploy {
		t.Fatalf("应继承优先级与订阅显示: %+v", p)
	}
	if again, err := MigrateServerPlacements(ctx, db, a, b); err != nil || again != 0 {
		t.Fatalf("重复执行应跳过已添加的: %d %v", again, err)
	}
	targets, err := MigrationTargets(ctx, db, a)
	if err != nil || len(targets) != 1 || targets[0].ID != b || targets[0].Pending != 3 {
		t.Fatalf("迁移目标: %+v %v", targets, err)
	}
	if _, err := FinishServerMigration(ctx, db, a, b); !errors.Is(err, ErrConflict) || !strings.Contains(err.Error(), "尚未部署运行") {
		t.Fatalf("目标未部署时应拒绝: %v", err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1 WHERE server_id=?`, b)
	moved, err := FinishServerMigration(ctx, db, a, b)
	if err != nil || moved != 3 {
		t.Fatalf("完成迁移: %d %v", moved, err)
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE server_id=? AND deployed<>2`, a) != 0 {
		t.Fatal("源部署位应全部待删除")
	}
	var sid int64
	var content string
	db.QueryRow(`SELECT server_id,content FROM domain_dns_record WHERE id=1`).Scan(&sid, &content)
	if p := placementsOf(t, db, id1)["b"]; p.AccessHostID == nil || *p.AccessHostID != 1 || sid != b || content != "2.2.2.2" {
		t.Fatalf("访问域名应转移到目标部署位: %+v %d %s", p, sid, content)
	}
	if placementsOf(t, db, id1)["b"].Deployed != PendingDeploy {
		t.Fatal("落地节点生效部署位变化后，引用它的代理节点应待部署")
	}
	if targets, _ := MigrationTargets(ctx, db, a); len(targets) != 0 {
		t.Fatal("完成后不再有迁移目标")
	}
	if count(t, db, `SELECT count(*) FROM system_log WHERE category='SERVER' AND resource_id=?`, a) != 2 {
		t.Fatal("两步都应写系统日志")
	}
}
