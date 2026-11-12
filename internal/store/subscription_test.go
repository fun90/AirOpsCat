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
	if err != nil || len(nodes) != 1 || nodes[0]["port"] != json.Number("443") {
		t.Fatal("待部署但已有快照的部署位应继续按旧快照输出", nodes, err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE id=?`, pid)
	nodes, err = SubscriptionNodes(ctx, db, a, nil)
	if err != nil || len(nodes) != 0 {
		t.Fatal("待删除不能输出", nodes, err)
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

// SNI 不依赖入站 server_name：域名访问地址优先，其次服务器证书域名（与证书脚本同口径），最后兼容旧入站值。
func TestSubscriptionSNI(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "sni")
	nid := deployedNode(t, db, "SNI", tag)
	r := onboard(t, db, "sni@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	a, _ := AccountByID(ctx, db, r.AccountID)
	var pid, sid int64
	var ip string
	db.QueryRow(`SELECT ns.id,ns.server_id,s.ip FROM node_server ns JOIN server s ON s.id=ns.server_id WHERE ns.node_id=?`, nid).Scan(&pid, &sid, &ip)
	mustExec(t, db, `UPDATE node_server SET deployed=1 WHERE id=?`, pid)
	setInbound := func(name string) {
		raw, _ := json.Marshal(map[string]any{"type": 0, "disabled": 0, "protocol": "hysteria2", "inbound": map[string]any{"tls": map[string]any{"server_name": name}}})
		mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json) VALUES(?,?,?,1,'x',?) ON CONFLICT(node_server_id) DO UPDATE SET snapshot_json=excluded.snapshot_json`, nid, pid, sid, string(raw))
	}
	sni := func() any {
		t.Helper()
		nodes, err := SubscriptionNodes(ctx, db, a, nil)
		if err != nil || len(nodes) != 1 {
			t.Fatal(nodes, err)
		}
		return nodes[0]["sni"]
	}
	setInbound("legacy.example.test")
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES
		(1,1,?,'*','*.example.test','A',?,'SYNCED'),
		(2,1,?,'cert','cert.example.test','A',?,'SYNCED'),
		(3,1,NULL,'hk','hk.example.test','A',?,'SYNCED')`, sid, ip, sid, ip, ip)
	if got := sni(); got != "cert.example.test" {
		t.Fatal("IP 访问时应取服务器证书域名（跳过通配）", got)
	}
	mustExec(t, db, `UPDATE node_server SET access_host_id=3 WHERE id=?`, pid)
	if got := sni(); got != "hk.example.test" {
		t.Fatal("域名访问时应取访问域名", got)
	}
	mustExec(t, db, `UPDATE node_server SET access_host_id=NULL WHERE id=?`, pid)
	mustExec(t, db, `UPDATE domain_dns_record SET status='PENDING_UPDATE' WHERE id=2`)
	if got := sni(); got != "legacy.example.test" {
		t.Fatal("服务器无证书域名时兼容旧入站值", got)
	}
	setInbound(ip)
	if got := sni(); got != ip {
		t.Fatal("没有任何域名时退回访问地址", got)
	}
}

// 普通 vless 的入站 tls.server_name 是域名时优先作为 SNI；其他协议不受影响。
func TestSubscriptionSNIVlessExplicit(t *testing.T) {
	inbound := map[string]any{"tls": map[string]any{"server_name": "cert.example.test"}}
	snap := func(proto string) map[string]any { return map[string]any{"protocol": proto, "inbound": inbound} }
	if got := subscriptionSNI("edge.example.test", "", snap("vless")); got != "cert.example.test" {
		t.Fatal("vless 应使用入站 server_name", got)
	}
	if got := subscriptionSNI("edge.example.test", "", snap("hysteria2")); got != "edge.example.test" {
		t.Fatal("其他协议仍用访问域名", got)
	}
	if got := subscriptionSNI("edge.example.test", "", map[string]any{"protocol": "vless", "inbound": map[string]any{}}); got != "edge.example.test" {
		t.Fatal("未指定时保持原行为", got)
	}
}
