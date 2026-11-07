package store

import "testing"

func TestUnblockPortQueuesRemoteRemovalAndReuse(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", NodeProxy, false, false)
	mustExec(t, db, `INSERT INTO server_blocked_port(server_id,port,create_time) VALUES(?,?,?)`, sid, 8000, "2026-01-01 00:00:00")
	if err := UnblockServerPort(ctx, db, sid, 8000); err != nil {
		t.Fatal(err)
	}
	if count(t, db, `SELECT count(*) FROM server_blocked_port WHERE server_id=?`, sid) != 0 || count(t, db, `SELECT count(*) FROM server_unblocked_port WHERE server_id=? AND port=8000`, sid) != 1 {
		t.Fatal("解除后需保留远端待执行动作")
	}
	pending, err := PendingDeployServers(ctx, db)
	if err != nil || len(pending) != 1 || pending[0] != sid {
		t.Fatalf("待解封应触发自动部署，即使没有节点部署位: %v %v", pending, err)
	}
	if port, err := availableNodePort(ctx, db); err != nil || port == 8000 {
		t.Fatalf("远端尚未解封不能重用端口: %d %v", port, err)
	}
	if err := UnblockServerPort(ctx, db, sid, 8000); err == nil {
		t.Fatal("重复解除应提示")
	}
	mustExec(t, db, `INSERT INTO server_blocked_port(server_id,port,create_time) VALUES(?,?,?)`, sid, 8000, "2026-01-01 00:00:00")
	if count(t, db, `SELECT count(*) FROM server_unblocked_port WHERE server_id=?`, sid) != 1 {
		t.Fatal("测试前提")
	}
}
