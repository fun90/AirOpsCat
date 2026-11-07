package store

import (
	"errors"
	"testing"
)

func TestMigrationRecordCancelOnlyNewPlacements(t *testing.T) {
	db, ctx := testDB(t)
	src := addServer(t, db, "src", "HK", NodeProxy, false, false)
	dst := addServer(t, db, "dst", "JP", NodeProxy, false, false)
	a, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 8443), nil, nil)
	b, _ := SaveNode(ctx, db, newNode("b", "HK", NodeProxy, 8444), nil, nil)
	mustExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(?,?,1)`, a, dst)
	if targets, err := MigrationTargets(ctx, db, src); err != nil || len(targets) != 0 {
		t.Fatalf("未迁移不应出现目标: %+v %v", targets, err)
	}
	if _, err := FinishServerMigration(ctx, db, src, dst); !errors.Is(err, ErrInvalid) {
		t.Fatalf("未迁移不能完成: %v", err)
	}
	if added, err := MigrateServerPlacements(ctx, db, src, dst); err != nil || added != 1 {
		t.Fatalf("迁移: %d %v", added, err)
	}
	if count(t, db, `SELECT count(*) FROM server_migration WHERE source_server_id=? AND target_server_id=?`, src, dst) != 1 {
		t.Fatal("未记录迁移")
	}
	if again, err := MigrateServerPlacements(ctx, db, src, dst); err != nil || again != 0 || count(t, db, `SELECT count(*) FROM server_migration_placement`) != 2 {
		t.Fatalf("重复迁移未合并: %d %v", again, err)
	}
	if err := CancelServerMigration(ctx, db, src, dst); err != nil {
		t.Fatal(err)
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE node_id=? AND server_id=?`, a, dst) != 1 || count(t, db, `SELECT count(*) FROM node_server WHERE node_id=? AND server_id=?`, b, dst) != 0 || count(t, db, `SELECT count(*) FROM server_migration`) != 0 {
		t.Fatal("取消必须保留旧部署位、移除新部署位和记录")
	}
}

func TestMigrationCancelDeployedAndForeignKeys(t *testing.T) {
	db, ctx := testDB(t)
	src := addServer(t, db, "src", "HK", NodeProxy, false, false)
	dst := addServer(t, db, "dst", "JP", NodeProxy, false, false)
	n, err := SaveNode(ctx, db, newNode("node", "HK", NodeProxy, 8450), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := MigrateServerPlacements(ctx, db, src, dst); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1 WHERE server_id=?`, dst)
	if err := CancelServerMigration(ctx, db, src, dst); err != nil {
		t.Fatal(err)
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE node_id=? AND server_id=? AND deployed=2`, n, dst) != 1 {
		t.Fatal("已部署目标应待删除")
	}
	if count(t, db, `SELECT count(*) FROM server_migration`) != 0 {
		t.Fatal("取消后清理记录")
	}
	if _, err := MigrateServerPlacements(ctx, db, src, dst); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `DELETE FROM node_server WHERE server_id=?`, src)
	mustExec(t, db, `DELETE FROM server WHERE id=?`, src)
	if count(t, db, `SELECT count(*) FROM server_migration`) != 0 || count(t, db, `SELECT count(*) FROM server_migration_placement`) != 0 {
		t.Fatal("服务器删除应级联清理迁移")
	}
}

func TestRouteRuleOutboundValidationOnlyOnChange(t *testing.T) {
	db, ctx := testDB(t)
	ls := addServer(t, db, "landing-host", "HK", NodeLanding, false, false)
	l, err := SaveNode(ctx, db, newNode("landing", "HK", NodeLanding, 8300), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE node_id=? AND server_id=?`, l, ls) != 1 {
		t.Fatal("落地测试部署位缺失")
	}
	r := &RouteRule{Name: "r", RuleType: "domain", RuleValue: `"example.test"`, OutboundNodeID: l, Enabled: true}
	if err := SaveRouteRule(ctx, db, r); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE node_id=?`, l)
	if err := SaveRouteRule(ctx, db, &RouteRule{Name: "new", RuleType: "domain", RuleValue: `"example.test"`, OutboundNodeID: l}); !errors.Is(err, ErrInvalid) {
		t.Fatalf("新建必须拒绝不可用出站: %v", err)
	}
	r.Remark = "只改备注"
	if err := SaveRouteRule(ctx, db, r); err != nil {
		t.Fatalf("原出站的无关编辑应允许: %v", err)
	}
}

func TestBatchDeleteRejectsOutsideReferences(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "landing", "HK", NodeLanding, false, false)
	l, err := SaveNode(ctx, db, newNode("landing", "HK", NodeLanding, 8300), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	p := newNode("outside", "HK", NodeProxy, 8301)
	p.OutID = &l
	if _, err := SaveNode(ctx, db, p, nil, nil); err != nil {
		t.Fatal(err)
	}
	if _, err := BatchDeleteNodes(ctx, db, []int64{l}); !errors.Is(err, ErrConflict) || count(t, db, `SELECT count(*) FROM node_server WHERE node_id=? AND deployed<>2`, l) == 0 {
		t.Fatalf("批次外代理引用应整体拒绝: %v", err)
	}
}

func TestBatchDeleteLandingAndProxy(t *testing.T) {
	db, ctx := testDB(t)
	ls := addServer(t, db, "landing-host", "HK", NodeLanding, false, false)
	addServer(t, db, "proxy-host", "HK", NodeProxy, false, false)
	l, err := SaveNode(ctx, db, newNode("landing", "HK", NodeLanding, 8300), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	p := newNode("proxy", "HK", NodeProxy, 8301)
	p.OutID = &l
	id, err := SaveNode(ctx, db, p, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	total := count(t, db, `SELECT count(*) FROM node_server WHERE node_id IN (?,?)`, l, id)
	if _, err := BatchDeleteNodes(ctx, db, []int64{l, id}); err != nil {
		t.Fatal(err)
	}
	if n := count(t, db, `SELECT count(*) FROM node_server WHERE node_id IN (?,?) AND deployed=2`, l, id); n != total {
		t.Fatalf("两种节点均应待删除: %d/%d", n, total)
	}
	for _, sid := range []int64{ls, ls + 1} {
		if err := recordDeployment(ctx, db, sid, "", true, "", nil, nil); err != nil {
			t.Fatal(err)
		}
	}
	if count(t, db, `SELECT count(*) FROM node WHERE id IN (?,?)`, l, id) != 0 {
		t.Fatal("部署后代理与落地均应删除")
	}
}
