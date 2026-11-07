package store

import "testing"

// 节点列表的待部署口径与 placementNeedsDeploySQL 一致：禁用且服务器上未运行的部署位不算待部署。
func TestNodeListPendingMatchesNeedsDeploy(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	db.Exec(`INSERT INTO node (id, name, type, protocol, port, disabled) VALUES (1, 'off-synced', 0, 'vless', 1001, 1), (2, 'off-running', 0, 'vless', 1002, 1), (3, 'on-pending', 0, 'vless', 1003, 0)`)
	db.Exec(`INSERT INTO node_server (id, node_id, server_id, deployed) VALUES (1, 1, ?, 0), (2, 2, ?, 0), (3, 3, ?, 0)`, sid, sid, sid)
	// 2 号节点最近一次下发的快照仍为启用，服务器上还在运行，需要部署移除
	db.Exec(`INSERT INTO node_deployment (node_id, node_server_id, version, snapshot_hash, disabled) VALUES (2, 2, 1, 'h', 0)`)
	list, _, err := NodeList(ctx, db, NodeFilter{Type: -1, Status: -2}, 1, 10)
	if err != nil {
		t.Fatal(err)
	}
	got := map[string]int{}
	for _, n := range list {
		got[n.Name] = n.DeployStatus()
	}
	if got["off-synced"] != Deployed || got["off-running"] != PendingDeploy || got["on-pending"] != PendingDeploy {
		t.Fatal(got)
	}
	counts, err := NodeStatusCounts(ctx, db, NodeFilter{Type: -1})
	if err != nil || counts[PendingDeploy] != 2 || counts[Deployed] != 1 {
		t.Fatal(counts, err)
	}
	pending, _ := PendingDeployServers(ctx, db)
	if len(pending) != 1 {
		t.Fatal(pending)
	}
}
