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
				if _, err := BatchNodePlacements(ctx, db, ids[:1], []int64{target}, true); err != nil {
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

	if u, err := BatchNodePlacements(ctx, db, []int64{a, b, a}, []int64{jp}, true); err != nil || u != 2 {
		t.Fatalf("添加: %d %v", u, err)
	}
	if u, err := BatchNodePlacements(ctx, db, []int64{a, b}, []int64{jp}, true); err != nil || u != 0 {
		t.Fatalf("重复添加应跳过: %d %v", u, err)
	}
	if u, err := BatchNodePlacements(ctx, db, []int64{a, b}, []int64{jp}, false); err != nil || u != 2 || len(placementsOf(t, db, a)) != 1 {
		t.Fatalf("删除: %d %v", u, err)
	}
	// 最后一个部署位：整体回滚，b 的部署位也不能被动
	BatchNodePlacements(ctx, db, []int64{b}, []int64{jp}, true)
	hk := addServer(t, db, "hk2", "HK", 0, false, false)
	BatchNodePlacements(ctx, db, []int64{a}, []int64{hk}, true)
	if _, err := BatchNodePlacements(ctx, db, []int64{b, a}, []int64{jp, hk0, hk}, false); !errors.Is(err, ErrInvalid) {
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
