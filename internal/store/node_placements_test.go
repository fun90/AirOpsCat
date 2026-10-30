package store

import (
	"errors"
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
