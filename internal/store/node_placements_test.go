package store

import (
	"errors"
	"testing"
)

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
