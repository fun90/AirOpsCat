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

func TestInitialPlacementRules(t *testing.T) {
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
	id, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)
	setDeployed(t, db, id, Deployed)
	addServer(t, db, "hk2", "HK", 0, false, false) // 新服务器不再自动加入已有节点

	n, _ := NodeByID(ctx, db, id)
	n.Region = "JP"
	addServer(t, db, "jp", "JP", 0, false, false)
	if _, err := SaveNode(ctx, db, n, nil, nil); err != nil {
		t.Fatal(err)
	}
	pm := placementsOf(t, db, id)
	if len(pm) != 1 || pm["hk"].Deployed != Deployed {
		t.Fatalf("修改地区不应增删部署位: %+v", pm)
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

func TestManualPlacementAddAndTypeRules(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "hk", "HK", 0, false, false)
	jp := addServer(t, db, "jp", "JP", 0, false, false)
	land := addServer(t, db, "jp-land", "JP", 1, false, false)
	off := addServer(t, db, "off", "JP", 0, false, true)
	id, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)

	opts, err := PlacementServerOptions(ctx, db, id)
	if err != nil || len(opts) != 1 || opts[0].ID != jp {
		t.Fatalf("可选服务器应不限地区且排除已有、禁用、类型不符: %+v %v", opts, err)
	}
	if err := addPl(ctx, db, id, land); !errors.Is(err, ErrInvalid) {
		t.Fatalf("代理节点不能加到落地服务器: %v", err)
	}
	if err := addPl(ctx, db, id, off); !errors.Is(err, ErrInvalid) {
		t.Fatalf("禁用服务器应拒绝: %v", err)
	}
	if err := addPl(ctx, db, id, jp); err != nil {
		t.Fatal(err)
	}
	pm := placementsOf(t, db, id)
	if pm["jp"] == nil || pm["jp"].Deployed != PendingDeploy || pm["jp"].Priority != 2 || !pm["jp"].Exposed {
		t.Fatalf("跨地区部署位应添加为待部署: %+v", pm)
	}
	if err := addPl(ctx, db, id, jp); !errors.Is(err, ErrConflict) {
		t.Fatalf("重复添加应冲突: %v", err)
	}
	// 端口冲突
	other, _ := SaveNode(ctx, db, newNode("b", "JP", NodeProxy, 8443), nil, nil)
	hk := placementsOf(t, db, id)["hk"].ServerID
	db.Exec(`UPDATE node SET port=443 WHERE id=?`, other)
	if err := addPl(ctx, db, other, hk); !errors.Is(err, ErrConflict) {
		t.Fatalf("端口冲突应拒绝: %v", err)
	}
	// 服务器改为落地类型时，上面仍有代理节点部署位则拒绝
	s, _ := ServerByID(ctx, db, jp)
	s.ServerType = NodeLanding
	if _, err := SaveServer(ctx, db, nil, s, ""); !errors.Is(err, ErrInvalid) {
		t.Fatalf("服务器类型冲突应拒绝: %v", err)
	}
	// 节点改为落地类型可以；落地改回代理时，落地服务器上的部署位导致拒绝
	l, _ := SaveNode(ctx, db, newNode("l", "JP", NodeLanding, 9000), nil, nil)
	if _, ok := placementsOf(t, db, l)["jp-land"]; !ok {
		t.Fatal("落地节点应落在落地服务器")
	}
	ln, _ := NodeByID(ctx, db, l)
	ln.Type, ln.Protocol = NodeProxy, "vless"
	if _, err := SaveNode(ctx, db, ln, nil, nil); !errors.Is(err, ErrInvalid) {
		t.Fatalf("节点类型与部署位冲突应拒绝: %v", err)
	}
}

func TestDeletePlacementTransfersDomain(t *testing.T) {
	db, ctx := testDB(t)
	hk := addServer(t, db, "hk", "HK", 0, false, false)
	jp := addServer(t, db, "jp", "JP", 0, false, false)
	setServerIP(t, db, jp, "2.2.2.2")
	tag := mustTag(t, db, "t")
	id, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), []int64{tag}, nil)
	pm := placementsOf(t, db, id)
	src := pm["hk"]
	if err := delPl(ctx, db, id, src.ID, 0); !errors.Is(err, ErrInvalid) {
		t.Fatalf("最后一个部署位不能删除: %v", err)
	}
	if err := addPl(ctx, db, id, jp); err != nil {
		t.Fatal(err)
	}
	dst := placementsOf(t, db, id)["jp"]
	setDeployed(t, db, id, Deployed)
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status,failover_home_content) VALUES(1,1,?,'a','a.example.test','A','1.1.1.1','SYNCED','9.9.9.9')`, hk)
	mustExec(t, db, `UPDATE node_server SET access_host_id=1 WHERE id=?`, src.ID)
	r := onboard(t, db, "p@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	rid := int64(1)
	if err := SaveAccountBinding(ctx, db, &AccountBinding{AccountID: r.AccountID, PlacementID: src.ID, RecordID: &rid, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	setDeployed(t, db, id, Deployed) // 账户授权变化会把部署位置待部署

	if err := delPl(ctx, db, id, src.ID, 0); !errors.Is(err, ErrInvalid) {
		t.Fatalf("有绑定时必须选择目标: %v", err)
	}
	// 域名被其他节点共用时拒绝
	other, _ := SaveNode(ctx, db, newNode("b", "HK", NodeProxy, 8443), nil, nil)
	mustExec(t, db, `UPDATE node_server SET access_host_id=1 WHERE node_id=?`, other)
	if err := delPl(ctx, db, id, src.ID, dst.ID); !errors.Is(err, ErrConflict) {
		t.Fatalf("共用域名应拒绝: %v", err)
	}
	mustExec(t, db, `UPDATE node_server SET access_host_id=NULL WHERE node_id=?`, other)
	// DNS 自动操作未完成时拒绝
	mustExec(t, db, `INSERT INTO dns_auto_operation(record_id,old_content,target_content,reason,created_at) VALUES(1,'1.1.1.1','2.2.2.2','THRESHOLD','x')`)
	if err := delPl(ctx, db, id, src.ID, dst.ID); !errors.Is(err, ErrConflict) {
		t.Fatalf("自动操作未完成应拒绝: %v", err)
	}
	mustExec(t, db, `DELETE FROM dns_auto_operation`)
	// 目标从未部署时拒绝
	mustExec(t, db, `UPDATE node_server SET deployed=0 WHERE id=?`, dst.ID)
	if err := delPl(ctx, db, id, src.ID, dst.ID); !errors.Is(err, ErrInvalid) {
		t.Fatalf("目标从未部署应拒绝: %v", err)
	}
	// 待部署但有部署记录（服务器上已在运行）的部署位可以承接
	mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash) VALUES(?,?,?,1,'x')`, id, dst.ID, jp)
	// 目标已有其他访问域名时不覆盖
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES(2,1,?,'b','b.example.test','A','2.2.2.2','SYNCED')`, jp)
	mustExec(t, db, `UPDATE node_server SET access_host_id=2 WHERE id=?`, dst.ID)
	if err := delPl(ctx, db, id, src.ID, dst.ID); err != nil {
		t.Fatal(err)
	}
	var keep int64
	db.QueryRow(`SELECT access_host_id FROM node_server WHERE id=?`, dst.ID).Scan(&keep)
	var moved int64
	db.QueryRow(`SELECT server_id FROM domain_dns_record WHERE id=1`).Scan(&moved)
	if keep != 2 || moved != jp {
		t.Fatalf("目标保留自己的访问域名，转移记录改绑目标服务器: %d %d", keep, moved)
	}
	// 回到转移前，继续验证目标无访问域名的情况
	mustExec(t, db, `UPDATE node_server SET deployed=1, access_host_id=CASE id WHEN ? THEN 1 ELSE NULL END WHERE node_id=?`, src.ID, id)
	mustExec(t, db, `UPDATE domain_dns_record SET server_id=?, content='1.1.1.1', status='SYNCED' WHERE id=1`, hk)
	mustExec(t, db, `UPDATE account_node_subscription_placement SET node_server_id=?`, src.ID)
	setServerIP(t, db, jp, "2001:db8::1")
	if err := delPl(ctx, db, id, src.ID, dst.ID); !errors.Is(err, ErrInvalid) {
		t.Fatalf("IPv6 目标不能承接 A 记录: %v", err)
	}
	setServerIP(t, db, jp, "2.2.2.2")

	if err := delPl(ctx, db, id, src.ID, dst.ID); err != nil {
		t.Fatal(err)
	}
	var sid int64
	var content, status string
	var home sql.NullString
	db.QueryRow(`SELECT server_id,content,status,failover_home_content FROM domain_dns_record WHERE id=1`).Scan(&sid, &content, &status, &home)
	if sid != jp || content != "2.2.2.2" || status != DNSPendingUpdate || home.Valid {
		t.Fatalf("记录应改绑目标服务器并待推送: %d %s %s %v", sid, content, status, home)
	}
	pm = placementsOf(t, db, id)
	if pm["hk"].Deployed != PendingDelete || pm["hk"].AccessHostID != nil || pm["jp"].AccessHostID == nil || *pm["jp"].AccessHostID != 1 {
		t.Fatalf("访问域名应转移到目标部署位: %+v %+v", pm["hk"], pm["jp"])
	}
	var bp int64
	db.QueryRow(`SELECT node_server_id FROM account_node_subscription_placement`).Scan(&bp)
	if bp != dst.ID {
		t.Fatalf("订阅定制应转移到目标部署位: %d", bp)
	}
	// 待删除的部署位可重新添加
	if err := addPl(ctx, db, id, hk); err != nil || placementsOf(t, db, id)["hk"].Deployed != PendingDeploy {
		t.Fatalf("重新添加应恢复待删除部署位: %v", err)
	}
}

// addPl/delPl 通过保存节点提交暂存的部署位增删，与表单路径一致。
func addPl(ctx context.Context, db *sql.DB, nodeID, serverID int64) error {
	n, _ := NodeByID(ctx, db, nodeID)
	_, err := SaveNode(ctx, db, n, n.TagIDs, nil, PlacementOps{AddServerIDs: []int64{serverID}})
	return err
}

func delPl(ctx context.Context, db *sql.DB, nodeID, placementID, targetID int64) error {
	n, _ := NodeByID(ctx, db, nodeID)
	_, err := SaveNode(ctx, db, n, n.TagIDs, nil, PlacementOps{Deletes: []PlacementDelete{{placementID, targetID}}})
	return err
}

func TestPlacementOpsRollBackWithNodeSave(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "hk", "HK", 0, false, false)
	jp := addServer(t, db, "jp", "JP", 0, false, false)
	id, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)
	n, _ := NodeByID(ctx, db, id)
	n.Inbound = `{"listen":"::"}` // 节点校验失败，暂存的添加不能落库
	if _, err := SaveNode(ctx, db, n, nil, nil, PlacementOps{AddServerIDs: []int64{jp}}); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	if len(placementsOf(t, db, id)) != 1 {
		t.Fatal("保存失败时部署位不应变化")
	}
	// 同一次保存里添加并删除旧部署位：新部署位从未部署，不能承接域名，但无绑定时允许
	hk := placementsOf(t, db, id)["hk"]
	n, _ = NodeByID(ctx, db, id)
	if _, err := SaveNode(ctx, db, n, nil, nil, PlacementOps{AddServerIDs: []int64{jp}, Deletes: []PlacementDelete{{hk.ID, 0}}}); err != nil {
		t.Fatal(err)
	}
	pm := placementsOf(t, db, id)
	if len(pm) != 1 || pm["jp"] == nil {
		t.Fatalf("应只剩新部署位: %+v", pm)
	}
}
