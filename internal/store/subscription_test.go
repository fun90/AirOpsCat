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
