package store

import (
	"errors"
	"fmt"
	"testing"
)

func TestAddPlacementKeepsOtherNodesDeployed(t *testing.T) {
	for _, batch := range []bool{false, true} {
		name := "表单保存"
		if batch {
			name = "批量添加"
		}
		t.Run(name, func(t *testing.T) {
			db, ctx := testDB(t)
			addServer(t, db, "原服务器", "HK", 0, false, false)
			target := addServer(t, db, "新服务器", "JP", 0, false, false)
			mustExec(t, db, `INSERT INTO tag (id,name) VALUES (1,'共用标签')`)
			landing, err := SaveNode(ctx, db, newNode("落地", "HK", NodeLanding, 8388), nil, nil)
			if err != nil {
				t.Fatal(err)
			}
			var ids []int64
			for i := range 3 {
				n := newNode("代理", "HK", NodeProxy, 8200+i)
				n.OutID = &landing
				id, err := SaveNode(ctx, db, n, []int64{1}, nil)
				if err != nil {
					t.Fatal(err)
				}
				ids = append(ids, id)
			}
			mustExec(t, db, `UPDATE node_server SET deployed=1`)
			if batch {
				if _, err := BatchNodePlacements(ctx, db, ids[:1], []int64{target}, true, nil, true); err != nil {
					t.Fatal(err)
				}
			} else {
				n, err := NodeByID(ctx, db, ids[0])
				if err != nil {
					t.Fatal(err)
				}
				if _, err := SaveNode(ctx, db, n, n.TagIDs, nil, PlacementOps{AddServerIDs: []int64{target}}); err != nil {
					t.Fatal(err)
				}
			}
			pls, err := Placements(ctx, db, ids[0])
			if err != nil || len(pls) != 2 {
				t.Fatalf("添加后应有两个部署位：%v %v", pls, err)
			}
			for _, id := range append(ids, landing) {
				for _, p := range placementsOf(t, db, id) {
					want := Deployed
					if id == ids[0] && p.ServerID == target {
						want = PendingDeploy
					}
					if p.Deployed != want {
						t.Errorf("节点 %d 部署位 %d：状态=%d，期望=%d", id, p.ID, p.Deployed, want)
					}
				}
			}
		})
	}
}

func TestBatchNodePlacements(t *testing.T) {
	db, ctx := testDB(t)
	hk0 := addServer(t, db, "hk", "HK", 0, false, false)
	jp := addServer(t, db, "jp", "JP", 0, false, false)
	a, _ := SaveNode(ctx, db, newNode("a", "HK", NodeProxy, 443), nil, nil)
	b, _ := SaveNode(ctx, db, newNode("b", "HK", NodeProxy, 444), nil, nil)

	if u, err := BatchNodePlacements(ctx, db, []int64{a, b, a}, []int64{jp}, true, nil, true); err != nil || u != 2 {
		t.Fatalf("添加: %d %v", u, err)
	}
	if u, err := BatchNodePlacements(ctx, db, []int64{a, b}, []int64{jp}, true, nil, true); err != nil || u != 0 {
		t.Fatalf("重复添加应跳过: %d %v", u, err)
	}
	if u, err := BatchNodePlacements(ctx, db, []int64{a, b}, []int64{jp}, false, nil, true); err != nil || u != 2 || len(placementsOf(t, db, a)) != 1 {
		t.Fatalf("删除: %d %v", u, err)
	}
	prio := 7
	if _, err := BatchNodePlacements(ctx, db, []int64{a}, []int64{jp}, true, &prio, false); err != nil {
		t.Fatal(err)
	}
	if p := placementsOf(t, db, a)["jp"]; p.Priority != 7 || p.Exposed {
		t.Fatalf("应按填写的优先级与不显示添加: %+v", p)
	}
	if _, err := BatchNodePlacements(ctx, db, []int64{a}, []int64{jp}, false, nil, true); err != nil {
		t.Fatal(err)
	}
	// 最后一个部署位：整体回滚，b 的部署位也不能被动
	BatchNodePlacements(ctx, db, []int64{b}, []int64{jp}, true, nil, true)
	hk := addServer(t, db, "hk2", "HK", 0, false, false)
	BatchNodePlacements(ctx, db, []int64{a}, []int64{hk}, true, nil, true)
	if _, err := BatchNodePlacements(ctx, db, []int64{b, a}, []int64{jp, hk0, hk}, false, nil, true); !errors.Is(err, ErrInvalid) {
		t.Fatalf("应因最后一个部署位拒绝: %v", err)
	}
	if len(placementsOf(t, db, b)) != 2 || len(placementsOf(t, db, a)) != 2 {
		t.Fatal("失败时应整体回滚")
	}
}

func TestPointedPlacementsSkipsUnexposed(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `INSERT INTO server(id,ip)VALUES(1,'1.1.1.1'),(2,'2.2.2.2'),(3,'3.3.3.3')`)
	mustExec(t, db, `INSERT INTO node(id,name,type,protocol,core_type)VALUES(1,'n',0,'vless','sing-box')`)
	mustExec(t, db, `INSERT INTO domain(id,domain)VALUES(1,'example.com')`)
	// a 已从 1 切到 2；c 是未暴露备用部署位自己的域名
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status)VALUES
		(1,1,'a','a.example.com','A','2.2.2.2','SYNCED'),(2,1,'b','b.example.com','A','2.2.2.2','SYNCED'),(3,1,'c','c.example.com','A','3.3.3.3','SYNCED')`)
	mustExec(t, db, `INSERT INTO node_server(id,node_id,server_id,deployed,exposed,access_host_id)VALUES(1,1,1,1,1,1),(2,1,2,1,1,2),(3,1,3,1,0,3)`)
	if m, err := PointedPlacements(ctx, db); err != nil || fmt.Sprint(m[1]) != "[{2 2.2.2.2 [a.example.com b.example.com]}]" {
		t.Fatal("未暴露部署位不应标注", m, err)
	}
	// 被订阅定制选中后，未暴露部署位的访问域名也会下发
	mustExec(t, db, `INSERT INTO account(id,account_no,uuid,auth_code,period_type)VALUES(1,'x','u','c','MONTHLY')`)
	mustExec(t, db, `INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,enabled)VALUES(1,1,1,1)`)
	mustExec(t, db, `INSERT INTO account_node_subscription_placement(binding_id,node_server_id)VALUES(1,3)`)
	if m, _ := PointedPlacements(ctx, db); len(m[1]) != 2 || m[1][1].ID != 3 {
		t.Fatal("订阅定制选中的部署位应标注", m)
	}
}

func TestIPOnlyPlacementsAndAutoSwitchedServers(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `INSERT INTO server(id,ip,name)VALUES(1,'1.1.1.1','s1'),(2,'2.2.2.2','s2'),(3,'3.3.3.3','s3'),(4,'4.4.4.4','s4'),(5,'5.5.5.5','s5')`)
	mustExec(t, db, `INSERT INTO node(id,name,type,protocol,core_type)VALUES(1,'n',0,'vless','sing-box'),(2,'single',0,'vless','sing-box')`)
	mustExec(t, db, `INSERT INTO domain(id,domain)VALUES(1,'example.com')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status)VALUES
		(1,1,'a','a.example.com','A','1.1.1.1','SYNCED'),(2,1,'b','b.example.com','A','2.2.2.2','PENDING_UPDATE')`)
	// 1 有已同步域名；2 域名未同步仍用 IP；3 无域名；4 未显示到订阅；5 是单部署位节点无从切走；6 无部署快照
	mustExec(t, db, `INSERT INTO node_server(id,node_id,server_id,deployed,exposed,access_host_id)VALUES
		(1,1,1,1,1,1),(2,1,2,1,NULL,2),(3,1,3,1,1,NULL),(4,1,4,1,0,NULL),(5,2,3,1,1,NULL)`)
	for _, id := range []int{1, 2, 3, 4, 5} {
		mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json)
			SELECT node_id,id,server_id,1,'x','{}' FROM node_server WHERE id=?`, id)
	}
	mustExec(t, db, `INSERT INTO node_server(id,node_id,server_id,deployed,exposed)VALUES(6,1,5,0,1)`)
	list, err := IPOnlyPlacements(ctx, db)
	if err != nil || fmt.Sprint(list) != "[{2 1 2 n s2} {3 1 3 n s3}]" {
		t.Fatal("直连 IP 部署位不对", list, err)
	}
	// 被订阅定制选中后，未显示的部署位也会下发
	mustExec(t, db, `INSERT INTO account(id,account_no,uuid,auth_code,period_type)VALUES(1,'x','u','c','MONTHLY')`)
	mustExec(t, db, `INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,enabled)VALUES(1,1,1,1)`)
	mustExec(t, db, `INSERT INTO account_node_subscription_placement(binding_id,node_server_id)VALUES(1,4)`)
	if list, _ := IPOnlyPlacements(ctx, db); len(list) != 3 || list[2].ID != 4 {
		t.Fatal("订阅定制选中的部署位应计入", list)
	}
	// 绑定带已同步订阅域名时，选中的部署位按域名连接，不再计入
	mustExec(t, db, `UPDATE account_node_subscription_domain_binding SET domain_dns_record_id=1 WHERE id=1`)
	if list, _ := IPOnlyPlacements(ctx, db); len(list) != 2 {
		t.Fatal("绑定已同步订阅域名的选中部署位不应计入", list)
	}

	if m, err := AutoSwitchedServers(ctx, db); err != nil || len(m) != 0 {
		t.Fatal("未切走不应返回", m, err)
	}
	mustExec(t, db, `UPDATE domain_dns_record SET content='3.3.3.3', failover_home_content='1.1.1.1' WHERE id=1`)
	if m, _ := AutoSwitchedServers(ctx, db); fmt.Sprint(m) != "map[1:true]" {
		t.Fatal("自动切走应返回原服务器", m)
	}
	mustExec(t, db, `UPDATE node_server SET failover=1 WHERE id=1`)
	if m, _ := AutoSwitchedServers(ctx, db); len(m) != 0 {
		t.Fatal("手动切走不算自动切走", m)
	}
}

func TestShownPointedPlacementsNeedsSwitch(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `INSERT INTO server(id,ip)VALUES(1,'1.1.1.1'),(2,'2.2.2.2')`)
	mustExec(t, db, `INSERT INTO node(id,name,type,protocol,core_type)VALUES(1,'n',0,'vless','sing-box')`)
	mustExec(t, db, `INSERT INTO domain(id,domain)VALUES(1,'example.com')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status)VALUES
		(1,1,'a','a.example.com','A','1.1.1.1','SYNCED'),(2,1,'b','b.example.com','A','2.2.2.2','SYNCED')`)
	mustExec(t, db, `INSERT INTO node_server(id,node_id,server_id,deployed,exposed,access_host_id)VALUES(1,1,1,1,1,1),(2,1,2,1,1,2)`)
	if m, err := ShownPointedPlacements(ctx, db); err != nil || len(m[1]) != 0 {
		t.Fatal("没切换过不应标注", m, err)
	}
	mustExec(t, db, `UPDATE domain_dns_record SET content='2.2.2.2', failover_home_content='1.1.1.1' WHERE id=1`)
	if m, err := ShownPointedPlacements(ctx, db); err != nil || len(m[1]) != 1 || m[1][0].ID != 2 {
		t.Fatal("切换后应标注承接方", m, err)
	}
}
