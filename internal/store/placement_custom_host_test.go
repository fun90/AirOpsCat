package store

import (
	"encoding/json"
	"errors"
	"testing"
)

// 外部自定义访问域名：订阅连接地址与 SNI 优先于已同步记录，账户订阅定制域名仍覆盖它。
func TestSubscriptionUsesCustomHost(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "custom")
	nid := deployedNode(t, db, "外部", tag)
	r := onboard(t, db, "custom@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	a, _ := AccountByID(ctx, db, r.AccountID)
	var pid, sid int64
	var ip string
	db.QueryRow(`SELECT ns.id,ns.server_id,s.ip FROM node_server ns JOIN server s ON s.id=ns.server_id WHERE ns.node_id=?`, nid).Scan(&pid, &sid, &ip)
	raw, _ := json.Marshal(map[string]any{"type": 0, "disabled": 0, "protocol": "hysteria2"})
	mustExec(t, db, `INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json) VALUES(?,?,?,1,'x',?)`, nid, pid, sid, string(raw))
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES
		(1,1,?,'hk','hk.example.test','A',?,'SYNCED'),(2,1,?,'sub','sub.example.test','A',?,'SYNCED')`, sid, ip, sid, ip)
	mustExec(t, db, `UPDATE node_server SET deployed=1, access_host_id=1, access_host_custom='edge.partner.example' WHERE id=?`, pid)
	one := func() map[string]any {
		t.Helper()
		nodes, err := SubscriptionNodes(ctx, db, a, nil)
		if err != nil || len(nodes) != 1 {
			t.Fatal(nodes, err)
		}
		return nodes[0]
	}
	if n := one(); n["serverHost"] != "edge.partner.example" || n["sni"] != "edge.partner.example" {
		t.Fatal("应使用外部自定义域名", n["serverHost"], n["sni"])
	}
	mustExec(t, db, `INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,domain_dns_record_id,enabled) VALUES(1,?,?,2,1)`, a.ID, nid)
	if n := one(); n["serverHost"] != "sub.example.test" {
		t.Fatal("账户订阅定制域名应覆盖部署位自定义域名", n["serverHost"])
	}
	mustExec(t, db, `DELETE FROM account_node_subscription_domain_binding`)
	mustExec(t, db, `UPDATE node_server SET access_host_custom=NULL WHERE id=?`, pid)
	if n := one(); n["serverHost"] != "hk.example.test" {
		t.Fatal("清空自定义域名后回到已同步访问域名", n["serverHost"])
	}

	if host, err := DefaultInboundHost(ctx, db, nid, 0, "", NodeProxy); err != nil || host != "hk.example.test" {
		t.Fatal(host, err)
	}
	mustExec(t, db, `UPDATE node_server SET access_host_custom='edge.partner.example' WHERE id=?`, pid)
	if host, err := DefaultInboundHost(ctx, db, nid, 0, "", NodeProxy); err != nil || host != "edge.partner.example" {
		t.Fatal("默认入站主机名应优先自定义域名", host, err)
	}
}

func TestPlacementEditCustomHost(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	nid := addNode(t, db, "n", "vless", 0, 443, `{"type":"vless"}`, nil)
	pid := place(t, db, nid, sid, 0, 0)
	var ip string
	db.QueryRow(`SELECT ip FROM server WHERE id=?`, sid).Scan(&ip)
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(1,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,server_id,name,full_name,type,content,status) VALUES(1,1,?,'hk','hk.example.test','A',?,'SYNCED')`, sid, ip)
	n := &Node{ID: nid, Type: NodeProxy}
	rec := int64(1)
	read := func() (*int64, string) {
		t.Helper()
		pls, err := Placements(ctx, db, nid)
		if err != nil || len(pls) != 1 || pls[0].ID != pid {
			t.Fatal(pls, err)
		}
		return pls[0].AccessHostID, pls[0].CustomHost
	}

	// 规范化并与 DNS 记录互斥
	if err := applyPlacementEdits(ctx, db, n, []PlacementEdit{{ServerID: sid, Exposed: true, AccessHostID: &rec, CustomHost: " Edge.Partner.Example. "}}); err != nil {
		t.Fatal(err)
	}
	if h, c := read(); h != nil || c != "edge.partner.example" {
		t.Fatal("自定义域名应规范化并清空访问域名记录", h, c)
	}
	sp, err := ServerPlacements(ctx, db, sid)
	if err != nil || len(sp) != 1 || sp[0].AccessHost != "edge.partner.example" {
		t.Fatal("服务器页应显示自定义域名", sp, err)
	}

	for _, bad := range []string{"203.0.113.9", "localhost", "a_b.example", "*.example.test", "http://x.example"} {
		err := applyPlacementEdits(ctx, db, n, []PlacementEdit{{ServerID: sid, CustomHost: bad}})
		if !errors.Is(err, ErrInvalid) {
			t.Fatal("应拒绝非法自定义域名", bad, err)
		}
	}
	if _, c := read(); c != "edge.partner.example" {
		t.Fatal("校验失败不应改动", c)
	}

	// 改回 DNS 记录时清空自定义域名
	if err := applyPlacementEdits(ctx, db, n, []PlacementEdit{{ServerID: sid, Exposed: true, AccessHostID: &rec}}); err != nil {
		t.Fatal(err)
	}
	if h, c := read(); h == nil || *h != 1 || c != "" {
		t.Fatal("选择 DNS 记录后应清空自定义域名", h, c)
	}
}
