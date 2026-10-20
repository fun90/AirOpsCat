package store

import "testing"

func TestDeleteDuringDeploymentKeepsSubmittedSnapshot(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	nid := addNode(t, db, "n", "vless", 0, 443, `{}`, nil)
	pid := place(t, db, nid, sid, 0, 1)
	c, err := CaptureDeployment(ctx, db, sid, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE id=?`, pid)
	if err := RecordDeploymentSnapshots(ctx, db, sid, "old", true, "ok", c.Revision, c.Snapshots); err != nil {
		t.Fatal(err)
	}
	var n int
	if err := db.QueryRow(`SELECT deployed FROM node_server WHERE id=?`, pid).Scan(&n); err != nil || n != 2 {
		t.Fatalf("待删除状态丢失: %d %v", n, err)
	}
	if err := db.QueryRow(`SELECT count(*) FROM node_deployment WHERE node_server_id=?`, pid).Scan(&n); err != nil || n != 1 {
		t.Fatalf("本次下发快照丢失: %d %v", n, err)
	}
	c, err = CaptureDeployment(ctx, db, sid, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(c.Snapshots) != 0 {
		t.Fatal("待删除节点不应出现在下次配置")
	}
	if err := RecordDeploymentSnapshots(ctx, db, sid, "new", true, "ok", c.Revision, c.Snapshots); err != nil {
		t.Fatal(err)
	}
	if err := db.QueryRow(`SELECT count(*) FROM node_deployment WHERE node_server_id=?`, pid).Scan(&n); err != nil || n != 0 {
		t.Fatalf("删除后快照残留: %d %v", n, err)
	}
}

func TestSnapshotWriteRollsBackDeployment(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	nid := addNode(t, db, "n", "vless", 0, 443, `{}`, nil)
	pid := place(t, db, nid, sid, 0, 0)
	c, err := CaptureDeployment(ctx, db, sid, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	bad := c.Snapshots[0]
	bad.ServerID++
	c.Snapshots = append(c.Snapshots, bad)
	if err := RecordDeploymentSnapshots(ctx, db, sid, "hash", true, "ok", c.Revision, c.Snapshots); err == nil {
		t.Fatal("错误快照应回滚")
	}
	for _, table := range []string{"node_deployment", "server_deployment"} {
		var n int
		if err := db.QueryRow(`SELECT count(*) FROM ` + table).Scan(&n); err != nil {
			t.Fatal(err)
		}
		if n != 0 {
			t.Fatal("结果没有整体回滚", table)
		}
	}
	var state int
	if err := db.QueryRow(`SELECT deployed FROM node_server WHERE id=?`, pid).Scan(&state); err != nil {
		t.Fatal(err)
	}
	if state != 0 {
		t.Fatal("回滚后部署位状态被修改")
	}
}
