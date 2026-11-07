package store

import (
	"encoding/json"
	"testing"
)

func TestSubscriptionOnlyDeployedClients(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "clients")
	nid := deployedNode(t, db, "已部署客户端", tag)
	var pid, sid int64
	if err := db.QueryRow(`SELECT id,server_id FROM node_server WHERE node_id=?`, nid).Scan(&pid, &sid); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `UPDATE node SET core_type='sing-box',protocol='vless',inbound='{"type":"vless"}' WHERE id=?`, nid)
	capture := func() *CapturedDeployment {
		t.Helper()
		c, err := CaptureDeployment(ctx, db, sid, LoadOptions{})
		if err != nil {
			t.Fatal(err)
		}
		return c
	}
	record := func(c *CapturedDeployment) {
		t.Helper()
		if err := RecordDeploymentSnapshots(ctx, db, sid, "h", true, "ok", c.Revision, c.Snapshots); err != nil {
			t.Fatal(err)
		}
	}
	c := capture()
	record(c)
	if c.Snapshots[0].Clients == nil {
		t.Fatal("空客户端集合应明确记录")
	}
	a := onboard(t, db, "fresh-client@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	account, _ := AccountByID(ctx, db, a.AccountID)
	nodes := func() []map[string]any {
		t.Helper()
		n, err := SubscriptionNodes(ctx, db, account, nil)
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	if n := nodes(); len(n) != 0 {
		t.Fatal("开户未部署却出现节点", n)
	}
	record(capture())
	if n := nodes(); len(n) != 1 {
		t.Fatal("部署后应出现节点", n)
	} else if _, leaked := n[0]["clients"]; leaked {
		t.Fatal("客户端摘要不得进入外部订阅", n)
	}
	mustExec(t, db, `UPDATE account SET uuid='22222222-2222-4222-8222-222222222222' WHERE id=?`, a.AccountID)
	account, _ = AccountByID(ctx, db, a.AccountID)
	if n := nodes(); len(n) != 0 {
		t.Fatal("新 UUID 尚未部署，不能给出旧客户端节点", n)
	}
	record(capture())
	if n := nodes(); len(n) != 1 {
		t.Fatal("新 UUID 部署后应出现节点", n)
	}
	// 旧快照缺少客户端字段，保持历史订阅行为。
	var raw string
	if err := db.QueryRow(`SELECT snapshot_json FROM node_deployment WHERE node_server_id=?`, pid).Scan(&raw); err != nil {
		t.Fatal(err)
	}
	var legacy map[string]any
	if err := json.Unmarshal([]byte(raw), &legacy); err != nil {
		t.Fatal(err)
	}
	delete(legacy, "clients")
	rawBytes, _ := json.Marshal(legacy)
	mustExec(t, db, `UPDATE node_deployment SET snapshot_json=? WHERE node_server_id=?`, string(rawBytes), pid)
	mustExec(t, db, `UPDATE account SET uuid='33333333-3333-4333-8333-333333333333' WHERE id=?`, a.AccountID)
	account, _ = AccountByID(ctx, db, a.AccountID)
	if n := nodes(); len(n) != 1 {
		t.Fatal("旧快照应保持原口径", n)
	}
}
