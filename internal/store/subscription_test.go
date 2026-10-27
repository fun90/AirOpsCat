package store

import (
	"encoding/json"
	"testing"
)

func TestSubscriptionUsesSnapshotAndBindings(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "sub")
	nid := deployedNode(t, db, "香港", tag)
	r := onboard(t, db, "sub@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	a, _ := AccountByID(ctx, db, r.AccountID)
	// 开户标记了待部署；模拟已有成功快照。
	var pid, sid int64
	db.QueryRow(`SELECT id,server_id FROM node_server WHERE node_id=?`, nid).Scan(&pid, &sid)
	snapshot := map[string]any{"nodeId": nid, "nodeServerId": pid, "serverId": sid, "port": 443, "protocol": "vless", "coreType": "sing-box", "type": 0, "disabled": 0, "inbound": map[string]any{"type": "vless"}}
	raw, _ := json.Marshal(snapshot)
	mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json) VALUES(?,?,?,1,'x',?)`, nid, pid, sid, string(raw))
	mustExec(t, db, `UPDATE node_server SET deployed=1 WHERE id=?`, pid)
	mustExec(t, db, `UPDATE node SET port=9999,inbound='{"type":"hysteria2"}' WHERE id=?`, nid)
	nodes, err := SubscriptionNodes(ctx, db, a, nil)
	if err != nil || len(nodes) != 1 {
		t.Fatalf("%v %v", nodes, err)
	}
	if nodes[0]["port"] != json.Number("443") || nodes[0]["protocol"] != "vless" {
		t.Fatal("泄露未部署修改", nodes)
	}
	multiple := 2
	nodes, err = SubscriptionNodes(ctx, db, a, &multiple)
	if err != nil || len(nodes) != 2 {
		t.Fatal(nodes, err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=0 WHERE id=?`, pid)
	nodes, err = SubscriptionNodes(ctx, db, a, nil)
	if err != nil || len(nodes) != 0 {
		t.Fatal("待部署不能输出", nodes, err)
	}
}

func TestSubscriptionBindingKeepsAllPlacements(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "multi")
	nid := deployedNode(t, db, "东京", tag)
	sid2 := addServer(t, db, "东京2", "JP", 0, false, false)
	sid3 := addServer(t, db, "东京3", "JP", 0, false, false)
	mustExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(?,?,1),(?,?,1)`, nid, sid2, nid, sid3)
	rows, _ := db.Query(`SELECT id,server_id FROM node_server WHERE node_id=? ORDER BY id`, nid)
	var pids []int64
	for rows.Next() {
		var pid, sid int64
		rows.Scan(&pid, &sid)
		raw, _ := json.Marshal(map[string]any{"type": 0, "disabled": 0, "protocol": "vless"})
		pids = append(pids, pid)
		mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json) VALUES(?,?,?,1,'x',?)`, nid, pid, sid, string(raw))
	}
	rows.Close()
	r := onboard(t, db, "multi@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	a, _ := AccountByID(ctx, db, r.AccountID)
	mustExec(t, db, `INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,enabled) VALUES(1,?,?,1)`, a.ID, nid)
	mustExec(t, db, `INSERT INTO account_node_subscription_placement VALUES(1,?),(1,?)`, pids[0], pids[1])
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	nodes, err := SubscriptionNodes(ctx, db, a, nil)
	if err != nil || len(nodes) != 2 {
		t.Fatal(len(nodes), err)
	}
}

func TestSubscriptionSnapshotBoundaryFilters(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "snapshot-filter")
	nid := deployedNode(t, db, "快照过滤", tag)
	r := onboard(t, db, "snapshot-filter@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	a, _ := AccountByID(ctx, db, r.AccountID)
	var pid, sid int64
	if err := db.QueryRow(`SELECT id,server_id FROM node_server WHERE node_id=?`, nid).Scan(&pid, &sid); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	for _, tc := range []struct {
		name     string
		snapshot map[string]any
		want     int
	}{
		{"可输出", map[string]any{"type": 0, "disabled": 0, "protocol": "vless"}, 1},
		{"快照禁用", map[string]any{"type": 0, "disabled": 1, "protocol": "vless"}, 0},
		{"快照落地类型", map[string]any{"type": 1, "disabled": 0, "protocol": "vless"}, 0},
	} {
		raw, _ := json.Marshal(tc.snapshot)
		mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json) VALUES(?,?,?,1,'x',?) ON CONFLICT(node_server_id) DO UPDATE SET snapshot_json=excluded.snapshot_json`, nid, pid, sid, string(raw))
		nodes, err := SubscriptionNodes(ctx, db, a, nil)
		if err != nil || len(nodes) != tc.want {
			t.Fatal(tc.name, len(nodes), err)
		}
	}
}

func TestPlacementSubscriptionNames(t *testing.T) {
	pls := []*Placement{
		{ID: 3, Exposed: true, Deployed: 1},
		{ID: 1, Exposed: true, Deployed: 0},
		{ID: 2, Exposed: false, Deployed: 1},
		{ID: 4, Exposed: true, Deployed: 2},
		{ID: 5, Exposed: true, Deployed: 1},
	}
	got := PlacementSubscriptionNames("HK", pls)
	want := map[int64]string{1: "HK-1", 3: "HK-2", 5: "HK-3"}
	if len(got) != len(want) {
		t.Fatalf("got %v", got)
	}
	for id, name := range want {
		if got[id] != name {
			t.Fatalf("got %v, want %v", got, want)
		}
	}
}
