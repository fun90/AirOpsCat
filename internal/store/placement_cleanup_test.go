package store

import (
	"context"
	"fmt"
	"path/filepath"
	"testing"
)

func TestDeleteDisabledPlacement(t *testing.T) {
	for _, disabled := range []bool{false, true} {
		for _, state := range []int{PendingDeploy, Deployed, PendingDelete} {
			t.Run(fmt.Sprintf("disabled=%v/state=%d", disabled, state), func(t *testing.T) {
				db, ctx := testDB(t)
				sid := addServer(t, db, "source", "HK", NodeProxy, false, disabled)
				mustExec(t, db, `INSERT INTO node(id,name,type) VALUES(1,'n',0)`)
				mustExec(t, db, `INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,?,?)`, sid, state)
				mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,version,snapshot_hash,disabled) VALUES(1,1,1,'h',0)`)
				if err := removeOrMarkDeleted(ctx, db, &Placement{ID: 1, ServerID: sid, Deployed: state}); err != nil {
					t.Fatal(err)
				}
				want := 1
				if disabled {
					want = 0
				}
				if got := count(t, db, `SELECT count(*) FROM node_server WHERE id=1 AND deployed=2`); got != want {
					t.Fatalf("待删除记录=%d，期望%d", got, want)
				}
				if disabled && count(t, db, `SELECT count(*) FROM node_deployment`) != 0 {
					t.Fatal("快照未级联删除")
				}
			})
		}
	}
}

func TestDisableServerCleansPendingDeletion(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "source", "HK", NodeProxy, false, false)
	mustExec(t, db, `INSERT INTO node(id,name,type) VALUES(1,'pending',0),(2,'keep',0),(3,'landing',1)`)
	mustExec(t, db, `UPDATE node SET out_id=3 WHERE id=1`)
	mustExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,?,2),(2,?,1)`, sid, sid)
	s, err := ServerByID(ctx, db, sid)
	if err != nil {
		t.Fatal(err)
	}
	s.Disabled = true
	if _, err := SaveServer(ctx, db, nil, s, ""); err != nil {
		t.Fatal(err)
	}
	if count(t, db, `SELECT count(*) FROM node WHERE id IN (1,3)`) != 0 || count(t, db, `SELECT count(*) FROM node_server WHERE node_id=2`) != 1 {
		t.Fatal("应只清理待删除节点，保留其他部署位")
	}
}

func TestMigration24CleansDisabledPendingPlacements(t *testing.T) {
	ctx := context.Background()
	db := openRawFK(t, filepath.Join(t.TempDir(), "old.db"))
	if err := migrate(ctx, db, schemaBefore(t, "schema/0024")); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO server(id,ip,disabled) VALUES(1,'192.0.2.1',1),(2,'192.0.2.2',0)`)
	mustExec(t, db, `INSERT INTO node(id,name,type) VALUES(34,'affected',0),(35,'keep',0),(36,'orphan',0),(37,'landing',1),(38,'referenced',1),(39,'proxy',0)`)
	mustExec(t, db, `UPDATE node SET out_id=37 WHERE id=36`)
	mustExec(t, db, `UPDATE node SET out_id=38 WHERE id IN (35,39)`)
	mustExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(39,1,2)`)
	mustExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(34,1,2),(34,2,1),(35,2,2),(36,1,2)`)
	mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,version,snapshot_hash,disabled) SELECT node_id,id,1,'h',0 FROM node_server WHERE server_id=1`)
	mustExec(t, db, `INSERT INTO server_migration(id,source_server_id,target_server_id,create_time) VALUES(1,1,2,'2026-01-01')`)
	mustExec(t, db, `INSERT INTO server_migration_placement(migration_id,source_placement_id,target_placement_id,created) SELECT 1,a.id,b.id,0 FROM node_server a JOIN node_server b ON a.node_id=b.node_id WHERE a.server_id=1 AND b.server_id=2`)
	if err := migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
	if count(t, db, `SELECT count(*) FROM node_deployment`) != 0 || count(t, db, `SELECT count(*) FROM server_migration_placement`) != 0 {
		t.Fatal("迁移未级联清理部署快照或迁移映射")
	}
	if count(t, db, `PRAGMA foreign_keys`) != 1 {
		t.Fatal("迁移后外键应保持启用")
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE server_id=1`) != 0 || count(t, db, `SELECT count(*) FROM node_server WHERE server_id=2`) != 2 {
		t.Fatal("只应移除禁用服务器上的待删除位")
	}
	if count(t, db, `SELECT count(*) FROM node WHERE id IN (36,37,39)`) != 0 || count(t, db, `SELECT count(*) FROM node WHERE id IN (34,38)`) != 2 {
		t.Fatal("节点清理错误")
	}
	if err := migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
}
