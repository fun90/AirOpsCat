package store

import (
	"errors"
	"testing"
)

func TestBatchNodeTagsAtomicAndIdempotent(t *testing.T) {
	db, ctx := testDB(t)
	one, two := mustTag(t, db, "one"), mustTag(t, db, "two")
	n1, n2 := deployedNode(t, db, "n1", one), deployedNode(t, db, "n2", two)
	r := onboard(t, db, "batch@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{one} })
	var pid int64
	if err := db.QueryRow(`SELECT id FROM node_server WHERE node_id=?`, n1).Scan(&pid); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(901,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status,server_id) SELECT 901,901,'batch','batch.example.test','A',s.ip,'SYNCED',s.id FROM server s JOIN node_server ns ON ns.server_id=s.id WHERE ns.id=?`, pid)
	rid := int64(901)
	if err := SaveAccountBinding(ctx, db, &AccountBinding{AccountID: r.AccountID, PlacementID: pid, RecordID: &rid, Enabled: true}); err != nil {
		t.Fatal(err)
	}
	resetDeployed(db, n1)
	resetDeployed(db, n2)
	u, s, err := BatchNodeTags(ctx, db, []int64{n1, n1, n2}, []int64{one, one}, "append")
	if err != nil || u != 1 || s != 1 {
		t.Fatal(u, s, err)
	}
	if nodeDeployed(t, db, n1) != Deployed || nodeDeployed(t, db, n2) != PendingDeploy {
		t.Fatal("未变化节点被置位")
	}
	if _, _, err := BatchNodeTags(ctx, db, []int64{n1, 999999}, []int64{two}, "REPLACE"); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	node, err := NodeByID(ctx, db, n1)
	if err != nil || len(node.TagIDs) != 1 || node.TagIDs[0] != one {
		t.Fatal("失败未回滚", err)
	}
	if _, _, err := BatchNodeTags(ctx, db, []int64{n1}, []int64{999999}, "REPLACE"); !errors.Is(err, ErrInvalid) {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE node_id=?`, n1)
	u, s, err = BatchNodeTags(ctx, db, []int64{n1}, nil, "")
	if err != nil || u != 1 || s != 0 || nodeDeployed(t, db, n1) != PendingDelete {
		t.Fatal(u, s, err)
	}
	bindings, err := AccountBindings(ctx, db, r.AccountID)
	if err != nil || len(bindings) != 0 {
		t.Fatal("失去授权的绑定未清理", err)
	}
	var serverID *int64
	if err := db.QueryRow(`SELECT server_id FROM domain_dns_record WHERE id=901`).Scan(&serverID); err != nil || serverID != nil {
		t.Fatal("DNS关联未释放", err)
	}
	for _, mode := range []string{"INVALID", "APPEND"} {
		if _, _, err := BatchNodeTags(ctx, db, nil, nil, mode); !errors.Is(err, ErrInvalid) {
			t.Fatal(err)
		}
	}
}

func TestTagNodeIDsOnlyUsableProxyNodes(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "t")
	ok := deployedNode(t, db, "ok", tag)
	off, landing, gone := deployedNode(t, db, "off", tag), deployedNode(t, db, "landing", tag), deployedNode(t, db, "gone", tag)
	mustExec(t, db, `UPDATE node SET disabled=1 WHERE id=?`, off)
	mustExec(t, db, `UPDATE node SET type=1 WHERE id=?`, landing)
	mustExec(t, db, `UPDATE node_server SET deployed=2 WHERE node_id=?`, gone)
	got, err := TagNodeIDs(ctx, db)
	if err != nil || len(got) != 1 || len(got[tag]) != 1 || got[tag][0] != ok {
		t.Fatal(got, err)
	}
}
