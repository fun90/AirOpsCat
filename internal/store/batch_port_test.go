package store

import "testing"

func TestReallocateNodePortsAtomicAndBlock(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "proxy", "HK", NodeProxy, false, false)
	a, err := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 8000), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	b, err := SaveNode(ctx, db, newNode("b", "HK", NodeProxy, 8001), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	result, err := ReallocateNodePorts(ctx, db, []int64{b, a}, true)
	if err != nil || len(result.Rotations) != 2 {
		t.Fatalf("结果 %+v: %v", result, err)
	}
	if count(t, db, `SELECT count(*) FROM node WHERE id IN (?,?) AND port IN (8000,8001)`, a, b) != 0 {
		t.Fatal("不能重用同批旧端口")
	}
	if count(t, db, `SELECT count(*) FROM server_blocked_port WHERE server_id=? AND port IN (8000,8001)`, sid) != 2 {
		t.Fatal("需封禁旧端口")
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE server_id=? AND deployed=0`, sid) != 2 {
		t.Fatal("变更应标待部署")
	}
	if _, err := ReallocateNodePorts(ctx, db, []int64{a, 999}, false); err == nil {
		t.Fatal("非法节点应整体回滚")
	}
}
