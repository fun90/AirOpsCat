package store

import (
	"context"
	"database/sql"
	"errors"
	"path/filepath"
	"sync"
	"testing"
)

func testDB(t *testing.T) (*sql.DB, context.Context) {
	t.Helper()
	ctx := context.Background()
	db, err := Open(ctx, filepath.Join(t.TempDir(), "n.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db, ctx
}

func addServer(t *testing.T, db *sql.DB, name, region string, typ int, external, disabled bool) int64 {
	t.Helper()
	res, err := db.Exec(`INSERT INTO server (name, ip, region, server_type, external_server, disabled) VALUES (?, '1.1.1.1', ?, ?, ?, ?)`, name, region, typ, external, disabled)
	if err != nil {
		t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

func newNode(name, region string, typ, port int) *Node {
	n := &Node{Name: name, Region: region, Type: typ, Port: port, Inbound: `{"type":"vless"}`, Protocol: "vless"}
	if typ == NodeLanding {
		n.Protocol = "shadowsocks"
	}
	return n
}

func placementsOf(t *testing.T, db *sql.DB, id int64) map[string]*Placement {
	t.Helper()
	pls, err := Placements(context.Background(), db, id)
	if err != nil {
		t.Fatal(err)
	}
	m := map[string]*Placement{}
	for _, p := range pls {
		m[p.ServerName] = p
	}
	return m
}

func TestPlacementSyncRules(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "hk-proxy", "HK", 0, false, false)
	addServer(t, db, "hk-ext", "HK", 0, true, false)
	addServer(t, db, "hk-land", "HK", 1, false, false)
	addServer(t, db, "hk-off", "HK", 0, false, true)
	addServer(t, db, "jp-proxy", "JP", 0, false, false)
	addServer(t, db, "hk-lower", "hk", 0, false, false) // 地区区分大小写

	pid, err := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	pm := placementsOf(t, db, pid)
	if len(pm) != 1 || pm["hk-proxy"] == nil || !pm["hk-proxy"].Exposed || pm["hk-proxy"].Priority != 1 || pm["hk-proxy"].Deployed != PendingDeploy {
		t.Fatalf("代理节点应只落在启用的非外部代理服务器: %+v", pm)
	}

	lid, err := SaveNode(ctx, db, newNode("l", "HK", NodeLanding, 8388), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	lm := placementsOf(t, db, lid)
	if len(lm) != 3 || lm["hk-ext"] == nil || lm["hk-land"] == nil || lm["hk-proxy"] == nil || lm["hk-proxy"].Exposed {
		t.Fatalf("落地节点可落在任何匹配服务器且默认不进订阅: %+v", lm)
	}
	if n, _ := NodeByID(ctx, db, pid); n.Name != "a-1" {
		t.Fatalf("名称应补编号: %s", n.Name)
	}
}

func TestPortConflictAndRollback(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "s1", "HK", 0, false, false)
	if _, err := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil); err != nil {
		t.Fatal(err)
	}
	_, err := SaveNode(ctx, db, newNode("b", "HK", NodeProxy, 443), nil, nil)
	if !errors.Is(err, ErrConflict) {
		t.Fatalf("同服务器同端口应冲突: %v", err)
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM node`).Scan(&n)
	if n != 1 {
		t.Fatal("冲突后新节点应回滚")
	}
	// 服务器变更触发的全量同步遇到冲突只跳过
	addServer(t, db, "s2", "HK", 0, false, false)
	if _, err := SaveNode(ctx, db, newNode("c", "HK", NodeProxy, 8443), nil, nil); err != nil {
		t.Fatal(err)
	}
	if err := SyncAll(ctx, db); err != nil {
		t.Fatal(err)
	}
}

func TestValidation(t *testing.T) {
	db, ctx := testDB(t)
	cases := map[string]func(*Node){
		"协议与类型不匹配":   func(n *Node) { n.Protocol = "socks" },
		"入站含 listen": func(n *Node) { n.Inbound = `{"listen":"::"}` },
		"入站不是对象":     func(n *Node) { n.Inbound = `[1]` },
		"端口越界":       func(n *Node) { n.Port = 70000 },
		"地区为空":       func(n *Node) { n.Region = " " },
	}
	for name, mut := range cases {
		n := newNode("x", "HK", NodeProxy, 443)
		mut(n)
		if _, err := SaveNode(ctx, db, n, nil, nil); !errors.Is(err, ErrInvalid) {
			t.Errorf("%s: 应拒绝，得到 %v", name, err)
		}
	}
}

func setDeployed(t *testing.T, db *sql.DB, nodeID int64, v int) {
	t.Helper()
	db.Exec(`UPDATE node_server SET deployed = ? WHERE node_id = ?`, v, nodeID)
}

func TestPendingDeploySignature(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "s1", "HK", 0, false, false)
	db.Exec(`INSERT INTO tag (name) VALUES ('t1')`)
	id, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)

	status := func() int { return placementsOf(t, db, id)["s1"].Deployed }
	edit := func(mut func(*Node), tags []int64) {
		n, _ := NodeByID(ctx, db, id)
		mut(n)
		if _, err := SaveNode(ctx, db, n, tags, nil); err != nil {
			t.Fatal(err)
		}
	}
	setDeployed(t, db, id, Deployed)
	edit(func(n *Node) { n.Remark = "只改备注" }, nil)
	if status() != Deployed {
		t.Fatal("备注不是实质变更，不应置待部署")
	}
	edit(func(n *Node) { n.Inbound = `{"b":1,"type":"vless"}` }, nil)
	if status() != PendingDeploy {
		t.Fatal("入站变化应置待部署")
	}
	setDeployed(t, db, id, Deployed)
	edit(func(n *Node) { n.Inbound = `{"type": "vless", "b": 1}` }, nil)
	if status() != Deployed {
		t.Fatal("JSON 仅格式或键顺序不同不应算变更")
	}
	edit(func(n *Node) {}, []int64{1})
	if status() != PendingDeploy {
		t.Fatal("标签变化应置待部署")
	}
}

func TestRegionChangeAndDelete(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "hk", "HK", 0, false, false)
	addServer(t, db, "jp", "JP", 0, false, false)
	id, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)
	setDeployed(t, db, id, Deployed)

	n, _ := NodeByID(ctx, db, id)
	n.Region = "JP"
	if _, err := SaveNode(ctx, db, n, nil, nil); err != nil {
		t.Fatal(err)
	}
	pm := placementsOf(t, db, id)
	if pm["hk"].Deployed != PendingDelete || pm["jp"] == nil || pm["jp"].Deployed != PendingDeploy {
		t.Fatalf("曾部署的应标待删除，新地区应新建: %+v", pm)
	}
	// 改回地区：待删除恢复为待部署
	n.Region = "HK"
	SaveNode(ctx, db, n, nil, nil)
	pm = placementsOf(t, db, id)
	if pm["hk"].Deployed != PendingDeploy || pm["jp"] != nil {
		t.Fatalf("应恢复 hk 并移除从未部署的 jp: %+v", pm)
	}

	// 删除：已部署 → 待删除，取消后恢复
	setDeployed(t, db, id, Deployed)
	gone, err := DeleteNode(ctx, db, id)
	if err != nil || gone {
		t.Fatalf("部署过的节点应标记待删除: %v %v", gone, err)
	}
	if placementsOf(t, db, id)["hk"].Deployed != PendingDelete {
		t.Fatal("应为待删除")
	}
	if err := CancelDeleteNode(ctx, db, id); err != nil || placementsOf(t, db, id)["hk"].Deployed != PendingDeploy {
		t.Fatalf("取消删除失败: %v", err)
	}
	// 从未部署：直接物理删除
	setDeployed(t, db, id, PendingDeploy)
	if gone, err := DeleteNode(ctx, db, id); err != nil || !gone {
		t.Fatalf("未部署的节点应直接删除: %v %v", gone, err)
	}
}

func TestLandingOutboundReferences(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "hk", "HK", 0, false, false)
	lid, _ := SaveNode(ctx, db, newNode("l", "HK", NodeLanding, 8388), nil, nil)
	p := newNode("p", "HK", NodeProxy, 443)
	p.OutID = &lid
	pid, err := SaveNode(ctx, db, p, nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	// 出站必须是落地节点
	bad := newNode("q", "HK", NodeProxy, 444)
	bad.OutID = &pid
	if _, err := SaveNode(ctx, db, bad, nil, nil); !errors.Is(err, ErrInvalid) {
		t.Fatalf("出站不是落地节点应拒绝: %v", err)
	}
	// 落地节点配置变化 → 引用它的代理节点置待部署
	setDeployed(t, db, pid, Deployed)
	l, _ := NodeByID(ctx, db, lid)
	l.Port = 9999
	SaveNode(ctx, db, l, nil, nil)
	if placementsOf(t, db, pid)["hk"].Deployed != PendingDeploy {
		t.Fatal("落地节点变化应让引用它的代理节点待部署")
	}
	// 禁用落地节点同样影响代理节点
	setDeployed(t, db, pid, Deployed)
	ToggleNode(ctx, db, lid, true)
	if placementsOf(t, db, pid)["hk"].Deployed != PendingDeploy {
		t.Fatal("禁用落地节点应让引用它的代理节点待部署")
	}
	// 被引用时不可删除
	if _, err := DeleteNode(ctx, db, lid); !errors.Is(err, ErrConflict) {
		t.Fatalf("被引用的落地节点不可删除: %v", err)
	}
}

func TestPlacementEdits(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "hk", "HK", 0, false, false)
	id, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)
	n, _ := NodeByID(ctx, db, id)
	ok := []PlacementEdit{{ServerID: sid, Priority: 5, Exposed: false, CheapStart: "23:00", CheapEnd: "07:00"}}
	if _, err := SaveNode(ctx, db, n, nil, ok); err != nil {
		t.Fatal(err)
	}
	p := placementsOf(t, db, id)["hk"]
	if p.Priority != 5 || p.Exposed || p.CheapStart != "23:00" {
		t.Fatalf("%+v", p)
	}
	for _, e := range []PlacementEdit{
		{ServerID: sid, CheapStart: "23:00", CheapEnd: "23:00"},
		{ServerID: sid, CheapStart: "25:00", CheapEnd: "01:00"},
		{ServerID: sid, AccessHostID: ptr(int64(999))},
	} {
		if _, err := SaveNode(ctx, db, n, nil, []PlacementEdit{e}); !errors.Is(err, ErrInvalid) {
			t.Errorf("%+v 应被拒绝: %v", e, err)
		}
	}
}

func ptr[T any](v T) *T { return &v }

func TestAutomaticNodePorts(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `INSERT INTO node(name,type,protocol,port) VALUES('used1',0,'vless',10000),('duplicate',0,'vless',10000),('higher',0,'vless',10002)`)
	n := newNode("automatic", "HK", NodeProxy, 0)
	if _, err := SaveNode(ctx, db, n, nil, nil); err != nil || n.Port != 10001 {
		t.Fatal(n.Port, err)
	}
	var wg sync.WaitGroup
	ports := make(chan int, 2)
	errs := make(chan error, 2)
	for i := 0; i < 2; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			n := newNode("parallel", "HK", NodeProxy, 0)
			_, err := SaveNode(ctx, db, n, nil, nil)
			ports <- n.Port
			errs <- err
		}()
	}
	wg.Wait()
	close(ports)
	close(errs)
	for err := range errs {
		if err != nil {
			t.Fatal(err)
		}
	}
	seen := map[int]bool{}
	for port := range ports {
		if seen[port] || port < 10003 {
			t.Fatal("并发分配冲突", port)
		}
		seen[port] = true
	}
}

func TestAutomaticNodePortExhaustion(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `WITH RECURSIVE ports(p) AS (SELECT 10000 UNION ALL SELECT p+1 FROM ports WHERE p<65535) INSERT INTO node(name,type,protocol,port) SELECT 'used',0,'vless',p FROM ports`)
	if _, err := SaveNode(ctx, db, newNode("full", "HK", NodeProxy, 0), nil, nil); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
}
