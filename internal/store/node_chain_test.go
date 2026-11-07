package store

import (
	"database/sql"
	"errors"
	"testing"
	"time"
)

// chainFixture：落地节点 l 部署在落地服务器 land（JP），代理节点 p（HK）以 l 为出站，
// 路由规则以 l 为出站、作用于服务器 rule（SG），unrelated（US）与落地无关。全部已部署。
type chainFixture struct {
	land, proxy, rule, unrelated int64
	l, p, r, u                   int64
}

func newChainFixture(t *testing.T) (*chainFixture, *sql.DB, func(nodeID int64) int) {
	t.Helper()
	db, ctx := testDB(t)
	f := &chainFixture{}
	f.land = addServer(t, db, "land", "JP", NodeLanding, false, false)
	f.proxy = addServer(t, db, "proxy", "HK", NodeProxy, false, false)
	f.rule = addServer(t, db, "rule", "SG", NodeProxy, false, false)
	f.unrelated = addServer(t, db, "unrelated", "US", NodeProxy, false, false)
	setServerIP(t, db, f.land, "10.0.0.1")
	var err error
	if f.l, err = SaveNode(ctx, db, newNode("l", "JP", NodeLanding, 8388), nil, nil); err != nil {
		t.Fatal(err)
	}
	p := newNode("p", "HK", NodeProxy, 443)
	p.OutID = &f.l
	if f.p, err = SaveNode(ctx, db, p, nil, nil); err != nil {
		t.Fatal(err)
	}
	if f.r, err = SaveNode(ctx, db, newNode("r", "SG", NodeProxy, 444), nil, nil); err != nil {
		t.Fatal(err)
	}
	if f.u, err = SaveNode(ctx, db, newNode("u", "US", NodeProxy, 445), nil, nil); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO route_rule(id,name,rule_type,rule_value,outbound_node_id,enabled) VALUES(1,'rr','domain','["a.test"]',?,1)`, f.l)
	mustExec(t, db, `INSERT INTO route_rule_server(route_rule_id,server_id) VALUES(1,?)`, f.rule)
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	state := func(nodeID int64) int {
		t.Helper()
		var d int
		if err := db.QueryRow(`SELECT deployed FROM node_server WHERE node_id=?`, nodeID).Scan(&d); err != nil {
			t.Fatal(err)
		}
		return d
	}
	return f, db, state
}

func TestLandingServerIPChangeMarksReferences(t *testing.T) {
	f, db, state := newChainFixture(t)
	ctx := t.Context()
	s, _ := ServerByID(ctx, db, f.land)
	s.IP = "10.0.0.2"
	if _, err := SaveServer(ctx, db, nil, s, ""); err != nil {
		t.Fatal(err)
	}
	if state(f.p) != PendingDeploy {
		t.Fatal("落地服务器改 IP 后，以其上落地节点为出站的代理节点应待部署")
	}
	if state(f.r) != PendingDeploy {
		t.Fatal("引用该落地节点的路由规则所在服务器应待部署")
	}
	if state(f.u) != Deployed {
		t.Fatal("无关服务器不应待部署")
	}
}

func TestLandingChangeMarksRouteRuleServers(t *testing.T) {
	f, db, state := newChainFixture(t)
	ctx := t.Context()
	l, _ := NodeByID(ctx, db, f.l)
	l.Port = 8389
	if _, err := SaveNode(ctx, db, l, nil, nil); err != nil {
		t.Fatal(err)
	}
	if state(f.r) != PendingDeploy || state(f.p) != PendingDeploy || state(f.u) != Deployed {
		t.Fatalf("落地节点改端口：代理=%d 规则服务器=%d 无关=%d", state(f.p), state(f.r), state(f.u))
	}
	// 禁用的路由规则不影响配置，不必待部署
	mustExec(t, db, `UPDATE route_rule SET enabled=0`)
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	l.Port = 8390
	if _, err := SaveNode(ctx, db, l, nil, nil); err != nil {
		t.Fatal(err)
	}
	if state(f.r) != Deployed {
		t.Fatal("禁用的路由规则所在服务器不应待部署")
	}
}

func TestReferencedLandingTypeChangeRejected(t *testing.T) {
	f, db, _ := newChainFixture(t)
	ctx := t.Context()
	mustExec(t, db, `UPDATE server SET server_type=0 WHERE id=?`, f.land) // 落地节点也可在代理服务器上，改类型不被服务器类型拦下
	l, _ := NodeByID(ctx, db, f.l)
	l.Type, l.Protocol = NodeProxy, "vless"
	if _, err := SaveNode(ctx, db, l, nil, nil); !errors.Is(err, ErrConflict) {
		t.Fatalf("被引用为出站的落地节点不能改为代理节点: %v", err)
	}
	// 只被路由规则引用时也拒绝
	mustExec(t, db, `UPDATE node SET out_id=NULL WHERE id=?`, f.p)
	if _, err := SaveNode(ctx, db, l, nil, nil); !errors.Is(err, ErrConflict) {
		t.Fatalf("被路由规则引用的落地节点不能改为代理节点: %v", err)
	}
	if cur, _ := NodeByID(ctx, db, f.l); cur.Type != NodeLanding {
		t.Fatal("拒绝后类型不应变化")
	}
}

func TestOutboundMustBeUsable(t *testing.T) {
	f, db, _ := newChainFixture(t)
	ctx := t.Context()
	off, err := SaveNode(ctx, db, newNode("off", "JP", NodeLanding, 8400), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	gone, err := SaveNode(ctx, db, newNode("gone", "JP", NodeLanding, 8401), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node SET disabled=1 WHERE id=?`, off)
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE node_id=?`, gone)
	for _, out := range []int64{off, gone} {
		n := newNode("q", "HK", NodeProxy, 450)
		n.OutID = &out
		if _, err := SaveNode(ctx, db, n, nil, nil); !errors.Is(err, ErrInvalid) {
			t.Fatalf("新建时出站 %d 不可用应拒绝: %v", out, err)
		}
		p, _ := NodeByID(ctx, db, f.p)
		p.OutID = &out
		if _, err := SaveNode(ctx, db, p, nil, nil); !errors.Is(err, ErrInvalid) {
			t.Fatalf("改为不可用的出站 %d 应拒绝: %v", out, err)
		}
	}
	// 存量节点的出站后来失去部署位时，无关保存不被阻断
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE node_id=?`, f.l)
	p, _ := NodeByID(ctx, db, f.p)
	p.Remark = "x"
	p.Name = "p-2"
	if _, err := SaveNode(ctx, db, p, nil, nil); err != nil {
		t.Fatalf("出站未变化时不应校验: %v", err)
	}
}

func TestSwitchTargetMustBeRunning(t *testing.T) {
	db, ctx := testDB(t)
	a := addServer(t, db, "a", "HK", NodeProxy, false, false)
	b := addServer(t, db, "b", "HK", NodeProxy, false, false)
	setServerIP(t, db, b, "2.2.2.2")
	id, err := SaveNode(ctx, db, newNode("n", "HK", NodeProxy, 443), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	pm := placementsOf(t, db, id)
	mustExec(t, db, `UPDATE node_server SET deployed=1 WHERE id=?`, pm["a"].ID)
	target := pm["b"].ID
	if err := SwitchNodePlacement(ctx, db, id, target); !errors.Is(err, ErrInvalid) {
		t.Fatalf("目标从未部署时应拒绝: %v", err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	for _, bad := range []string{`UPDATE server SET disabled=1 WHERE id=?`, `UPDATE server SET expire_date='2000-01-01' WHERE id=?`, `UPDATE server SET external_server=1 WHERE id=?`} {
		mustExec(t, db, bad, b)
		if err := SwitchNodePlacement(ctx, db, id, target); !errors.Is(err, ErrInvalid) {
			t.Fatalf("%s 后应拒绝切到该部署位: %v", bad, err)
		}
		mustExec(t, db, `UPDATE server SET disabled=0, expire_date=NULL, external_server=0 WHERE id=?`, b)
	}
	mustExec(t, db, `UPDATE node SET disabled=1 WHERE id=?`, id)
	if err := SwitchNodePlacement(ctx, db, id, target); !errors.Is(err, ErrInvalid) {
		t.Fatalf("节点禁用时应拒绝: %v", err)
	}
	mustExec(t, db, `UPDATE node SET disabled=0 WHERE id=?`, id)
	// 待部署但有未禁用快照的部署位仍在运行，可以承接
	mustExec(t, db, `UPDATE node_server SET deployed=0 WHERE id=?`, target)
	mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,disabled) VALUES(?,?,?,1,'x',0)`, id, target, b)
	if err := SwitchNodePlacement(ctx, db, id, target); err != nil {
		t.Fatal(err)
	}
	if !placementsOf(t, db, id)["a"].Failover || placementsOf(t, db, id)["b"].Failover {
		t.Fatal("切换后其余部署位应切走")
	}
	_ = a
}

func TestBatchSwitchSkipsWhenRestNotRunning(t *testing.T) {
	db, ctx := testDB(t)
	a := addServer(t, db, "a", "HK", NodeProxy, false, false)
	b := addServer(t, db, "b", "HK", NodeProxy, false, false)
	setServerIP(t, db, b, "2.2.2.2")
	id, err := SaveNode(ctx, db, newNode("n", "HK", NodeProxy, 443), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	pm := placementsOf(t, db, id)
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES(1,1,?,'a','a.example.test','A','1.1.1.1','SYNCED')`, a)
	mustExec(t, db, `UPDATE node_server SET access_host_id=1, deployed=1 WHERE id=?`, pm["a"].ID)
	mustExec(t, db, `UPDATE server SET disabled=1 WHERE id=?`, b)
	mustExec(t, db, `UPDATE node_server SET deployed=1 WHERE id=?`, pm["b"].ID)
	if sw, sk, err := BatchNodeSwitch(ctx, db, []int64{id}); err != nil || sw != 0 || sk != 1 {
		t.Fatalf("其余部署位所在服务器已禁用时应跳过: %d %d %v", sw, sk, err)
	}
	mustExec(t, db, `UPDATE server SET disabled=0 WHERE id=?`, b)
	if sw, sk, err := BatchNodeSwitch(ctx, db, []int64{id}); err != nil || sw != 1 || sk != 0 {
		t.Fatalf("其余部署位可用时应切走: %d %d %v", sw, sk, err)
	}
}

func TestReaddPendingDeletePlacementResetsFailover(t *testing.T) {
	db, ctx := testDB(t)
	addServer(t, db, "a", "HK", NodeProxy, false, false)
	b := addServer(t, db, "b", "HK", NodeProxy, false, false)
	id, err := SaveNode(ctx, db, newNode("n", "HK", NodeProxy, 443), nil, nil)
	if err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	mustExec(t, db, `UPDATE node_server SET failover=1, deployed=2 WHERE server_id=?`, b)
	if err := addPl(ctx, db, id, b); err != nil {
		t.Fatal(err)
	}
	if p := placementsOf(t, db, id)["b"]; p.Deployed != PendingDeploy || p.Failover {
		t.Fatalf("恢复待删除部署位应重置切走标记: %+v", p)
	}
}

func TestDNSCandidatesKeepUnavailableOrigin(t *testing.T) {
	db, ctx := testDB(t)
	a := addServer(t, db, "a", "HK", NodeProxy, false, false)
	b := addServer(t, db, "b", "HK", NodeProxy, false, false)
	setServerIP(t, db, b, "2.2.2.2")
	if _, err := SaveNode(ctx, db, newNode("n", "HK", NodeProxy, 443), nil, nil); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=1`)
	mustExec(t, db, `UPDATE server SET disabled=1 WHERE id=?`, a)
	mustExec(t, db, `UPDATE server SET expire_date='2000-01-01' WHERE id=?`, b)
	cs, err := DNSCandidates(ctx, db, time.Now())
	if err != nil || len(cs) != 2 {
		t.Fatalf("禁用或到期服务器上的运行部署位仍应作为原服务器返回: %+v %v", cs, err)
	}
	for _, c := range cs {
		if !c.Unavailable {
			t.Fatalf("禁用或到期服务器的候选应标记不可用: %+v", c)
		}
	}
}
