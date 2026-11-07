package store

import (
	"reflect"
	"testing"
	"time"
)

// 待部署但已有启用快照的部署位仍在服务器上运行：采流量、guard 归属、探测都不能把它当成停止。
func TestPendingPlacementWithSnapshotStillRunning(t *testing.T) {
	db, ctx := testDB(t)
	mustExec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.ExecContext(ctx, q, args...); err != nil {
			t.Fatal(err)
		}
	}
	mustExec(`INSERT INTO server(id,ip) VALUES(1,'203.0.113.1'),(2,'203.0.113.2'),(3,'203.0.113.3'),(4,'203.0.113.4');
 INSERT INTO node(id,name,type,protocol,port,disabled,out_id) VALUES(1,'a',0,'vless',443,0,9),(2,'b',0,'vless',444,0,NULL),(3,'c',0,'vless',445,0,NULL),(9,'land',1,'socks',1080,0,NULL);
 INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,0),(2,2,2,0),(3,3,3,2),(9,9,4,1);
 INSERT INTO node_deployment(node_id,node_server_id,version,snapshot_hash,disabled) VALUES(1,1,1,'h',0),(3,3,1,'h',0)`)

	ids, err := RuntimeTrafficServers(ctx, db, "all", time.Now())
	if err != nil || !reflect.DeepEqual(ids, []int64{1, 4}) {
		t.Fatalf("流量采集应包含运行中的待部署位，排除从未下发与待删除: %v %v", ids, err)
	}
	targets, err := ProbeTargets(ctx, db)
	if err != nil || len(targets) != 1 || targets[0].ID != 1 {
		t.Fatalf("探测目标: %+v %v", targets, err)
	}
	landing, err := LandingProbeTargets(ctx, db)
	if err != nil || len(landing) != 1 || !reflect.DeepEqual(landing[0].Probers, []int64{1}) {
		t.Fatalf("落地探测应由运行中的待部署代理拨测: %+v %v", landing, err)
	}
	ips, err := NodeServerIPs(ctx, db)
	if err != nil || !reflect.DeepEqual(ips[1], []string{"203.0.113.1"}) || len(ips[2]) != 0 || len(ips[3]) != 0 {
		t.Fatalf("在线覆盖范围: %v %v", ips, err)
	}
	mustExec(`INSERT INTO account(account_no,uuid,auth_code,period_type) VALUES('a1','u1','c1','MONTHLY')`)
	if err := RefreshGuardOnline(ctx, db, "203.0.113.1", []GuardOnlineAccount{{AccountNo: "a1", NodeTag: "node_1", ClientIPs: []string{"198.51.100.1"}}}, time.Now()); err != nil {
		t.Fatal(err)
	}
	var nodeID *int64
	if err := db.QueryRowContext(ctx, `SELECT node_id FROM account_online_ip WHERE account_no='a1'`).Scan(&nodeID); err != nil || nodeID == nil || *nodeID != 1 {
		t.Fatalf("guard 上报应归属运行中的待部署节点: %v %v", nodeID, err)
	}
}

// 节点已禁用且服务器上没有它（禁用快照或从未下发）的待部署位与服务器一致，不算待部署；禁用服务器不是部署目标。
func TestDeployTargetsIgnoreInSyncDisabledAndDisabledServers(t *testing.T) {
	db, ctx := testDB(t)
	mustExec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.ExecContext(ctx, q, args...); err != nil {
			t.Fatal(err)
		}
	}
	mustExec(`INSERT INTO server(id,ip,disabled) VALUES(1,'203.0.113.1',0),(2,'203.0.113.2',0),(3,'203.0.113.3',0),(4,'203.0.113.4',1),(5,'203.0.113.5',0),(6,'203.0.113.6',0);
 INSERT INTO node(id,name,type,disabled) VALUES(1,'无快照禁用',0,1),(2,'禁用快照',0,1),(3,'启用快照',0,1),(4,'禁用服务器',0,0),(5,'待删除',0,0),(6,'禁用待删除',0,1);
 INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,0),(2,2,2,0),(3,3,3,0),(4,4,4,0),(5,5,5,2),(6,6,6,2);
 INSERT INTO node_deployment(node_id,node_server_id,version,snapshot_hash,disabled) VALUES(2,2,1,'h',1),(3,3,1,'h',0);
 INSERT INTO tag(id,name) VALUES(1,'t');
 INSERT INTO node_tag(node_id,tag_id) VALUES(1,1),(3,1),(4,1);
 INSERT INTO account(id,account_no,uuid,auth_code,period_type) VALUES(1,'a1','u1','c1','MONTHLY');
 INSERT INTO account_tag(account_id,tag_id) VALUES(1,1)`)

	pending, err := PendingDeployServers(ctx, db)
	if err != nil || !reflect.DeepEqual(pending, []int64{3, 5, 6}) {
		t.Fatalf("待部署服务器: %v %v", pending, err)
	}
	for _, c := range []struct {
		node  int64
		force bool
		want  []int64
	}{{1, false, nil}, {1, true, []int64{1}}, {2, false, nil}, {3, false, []int64{3}}, {4, false, nil}, {4, true, nil}, {6, false, []int64{6}}} {
		got, err := NodeDeployServers(ctx, db, c.node, c.force)
		if err != nil || !reflect.DeepEqual(got, c.want) {
			t.Errorf("节点 %d force=%v: %v %v，预期 %v", c.node, c.force, got, err, c.want)
		}
	}
	got, err := AccountDeployServers(ctx, db, 1)
	if err != nil || !reflect.DeepEqual(got, []int64{1, 3}) {
		t.Fatalf("账户部署应排除禁用服务器: %v %v", got, err)
	}
	d, err := DashboardLoad(ctx, db, time.Now(), 1)
	if err != nil || d.PendingPlacements != 1 {
		t.Fatalf("概览待部署位应与待部署判断一致: %+v %v", d, err)
	}
}

// 失败重试只选启用服务器中仍需部署且最近一次部署失败的。
func TestRetryDeployServers(t *testing.T) {
	db, ctx := testDB(t)
	if _, err := db.ExecContext(ctx, `INSERT INTO server(id,ip,disabled) VALUES(1,'203.0.113.1',0),(2,'203.0.113.2',0),(3,'203.0.113.3',1),(4,'203.0.113.4',0),(5,'203.0.113.5',0);
 INSERT INTO node(id,name,type,disabled) VALUES(1,'n',0,0),(2,'off',0,1);
 INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,1,0),(1,2,0),(1,3,0),(1,4,1),(2,5,0);
 INSERT INTO server_deployment(server_id,success) VALUES(1,0),(2,1),(3,0),(4,0),(5,0)`); err != nil {
		t.Fatal(err)
	}
	ids, err := RetryDeployServers(ctx, db)
	if err != nil || !reflect.DeepEqual(ids, []int64{1}) {
		t.Fatalf("%v %v", ids, err)
	}
}
