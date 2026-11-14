package store

import (
	"errors"
	"reflect"
	"testing"
)

func TestSaveServerWithProxyPlacement(t *testing.T) {
	for _, tc := range []struct {
		name        string
		external    bool
		landing     bool
		wantInvalid bool
	}{
		{name: "编辑已有代理节点的外部服务器", external: true},
		{name: "已有代理节点的服务器改为外部"},
		{name: "普通服务器改为落地", landing: true, wantInvalid: true},
		{name: "外部服务器改为落地", external: true, landing: true, wantInvalid: true},
	} {
		t.Run(tc.name, func(t *testing.T) {
			db, ctx := testDB(t)
			sid := addServer(t, db, "原服务器", "HK", NodeProxy, tc.external, false)
			nid, err := SaveNode(ctx, db, newNode("代理", "JP", NodeProxy, 443), nil, nil, PlacementOps{AddServerIDs: []int64{sid}})
			if err != nil {
				t.Fatal(err)
			}
			before, err := Placements(ctx, db, nid)
			if err != nil {
				t.Fatal(err)
			}
			s, err := ServerByID(ctx, db, sid)
			if err != nil {
				t.Fatal(err)
			}
			s.Name, s.Remark = "修改后的服务器", "修改后的备注"
			if tc.landing {
				s.ServerType = NodeLanding
			} else {
				s.External = true
			}
			_, err = SaveServer(ctx, db, nil, s, "")
			if tc.wantInvalid {
				if !errors.Is(err, ErrInvalid) {
					t.Fatalf("仍有代理部署位时应拒绝改为落地：%v", err)
				}
			} else if err != nil {
				t.Fatalf("外部服务器应可保留代理部署位并保存：%v", err)
			}
			saved, err := ServerByID(ctx, db, sid)
			if err != nil {
				t.Fatal(err)
			}
			if tc.wantInvalid {
				if saved.Name != "原服务器" || saved.ServerType != NodeProxy || saved.External != tc.external {
					t.Fatalf("拒绝保存时不应修改服务器：%+v", saved)
				}
			} else if saved.Name != s.Name || saved.Remark != s.Remark || !saved.External {
				t.Fatalf("服务器信息未保存：%+v", saved)
			}
			after, err := Placements(ctx, db, nid)
			if err != nil {
				t.Fatal(err)
			}
			// 服务器名称和外部标记会随查询更新，部署位自身的配置与状态应保持不变。
			for _, p := range before {
				p.ServerName, p.ServerExternal = saved.Name, saved.External
			}
			if !reflect.DeepEqual(before, after) {
				t.Fatalf("保存服务器不应改动代理部署位：保存前=%+v，保存后=%+v", before, after)
			}
		})
	}
}
