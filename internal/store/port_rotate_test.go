package store

import "testing"

func TestRotateRealityPorts(t *testing.T) {
	db, ctx := testDB(t)
	s1 := addServer(t, db, "s1", "HK", 0, false, false)
	s2 := addServer(t, db, "s2", "US", 0, false, false)
	mustExec(t, db, `INSERT INTO node(id,name,type,protocol,port) VALUES(1,'reality',0,'vless-reality',8000),(2,'plain',0,'vless',8001),(3,'off',0,'vless-reality',8002)`)
	mustExec(t, db, `UPDATE node SET disabled=1 WHERE id=3`)
	mustExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,?,1),(1,?,1),(2,?,1),(3,?,1)`, s1, s2, s1, s1)

	res, err := RotateRealityPorts(ctx, db, s1)
	if err != nil {
		t.Fatal(err)
	}
	if len(res.Rotations) != 1 || res.Rotations[0].NodeID != 1 || res.Rotations[0].OldPort != 8000 || res.Rotations[0].NewPort != 8003 {
		t.Fatalf("只处理已启用的 VLESS+REALITY 节点: %+v", res.Rotations)
	}
	if len(res.DeployServers) != 2 {
		t.Fatalf("节点所在的所有服务器都要重新部署: %v", res.DeployServers)
	}
	var port int
	db.QueryRow(`SELECT port FROM node WHERE id=2`).Scan(&port)
	if port != 8001 {
		t.Fatal("非 REALITY 节点端口不应变", port)
	}
	var pending int
	db.QueryRow(`SELECT count(*) FROM node_server WHERE node_id=1 AND deployed=0`).Scan(&pending)
	if pending != 2 {
		t.Fatal("节点所有部署位应待部署", pending)
	}
	blocked, _ := BlockedPorts(ctx, db, s1)
	if len(blocked) != 1 || blocked[0] != 8000 {
		t.Fatal("旧端口应只在被刷服务器封禁", blocked)
	}
	if other, _ := BlockedPorts(ctx, db, s2); len(other) != 0 {
		t.Fatal(other)
	}
	// 封禁的旧端口不再分配给新节点，也不能手动占用
	n := newNode("next", "HK", NodeProxy, 0)
	if _, err := SaveNode(ctx, db, n, nil, nil); err != nil || n.Port != 8004 {
		t.Fatal("应跳过已封禁端口", n.Port, err)
	}
	if got, err := ServerRealityNodes(ctx, db, []int64{s1, s2}); err != nil || got[s1] != 1 || got[s2] != 1 {
		t.Fatal(got, err)
	}
	if _, err := RotateRealityPorts(ctx, db, addServer(t, db, "empty", "HK", 0, false, false)); err == nil {
		t.Fatal("没有 REALITY 节点应报错")
	}
}
