package store

import "testing"

func TestDefaultInboundHostUsesPlacementAndNewRegion(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	nid := addNode(t, db, "n", "vless", 0, 443, `{"type":"vless"}`, nil)
	mustExec(t, db, `UPDATE node SET region='HK' WHERE id=?`, nid)
	pid := place(t, db, nid, sid, 0, 0)
	var ip string
	if err := db.QueryRow(`SELECT ip FROM server WHERE id=?`, sid).Scan(&ip); err != nil {
		t.Fatal(err)
	}
	host, err := DefaultInboundHost(ctx, db, nid, 0, "HK", NodeProxy)
	if err != nil || host != ip {
		t.Fatal(host, err)
	}
	mustExec(t, db, `INSERT INTO domain(id,domain) VALUES(901,'example.test')`)
	mustExec(t, db, `INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status) VALUES(901,901,'vpn','vpn.example.test','A','203.0.113.1','SYNCED')`)
	mustExec(t, db, `UPDATE node_server SET access_host_id=901 WHERE id=?`, pid)
	host, err = DefaultInboundHost(ctx, db, nid, 0, "HK", NodeProxy)
	if err != nil || host != "vpn.example.test" {
		t.Fatal(host, err)
	}
	mustExec(t, db, `UPDATE domain_dns_record SET status='PENDING_UPDATE' WHERE id=901`)
	host, err = DefaultInboundHost(ctx, db, nid, 0, "HK", NodeProxy)
	if err != nil || host != ip {
		t.Fatal(host, err)
	}
	// 部署位不随地区变化，改地区仍取现有生效部署位
	if host, err := DefaultInboundHost(ctx, db, nid, 0, "JP", NodeProxy); err != nil || host != ip {
		t.Fatal("应使用现有部署位地址", host, err)
	}
	if _, err := DefaultInboundHost(ctx, db, 999999, 0, "HK", NodeProxy); err == nil {
		t.Fatal("不存在节点未拒绝")
	}
	host, err = DefaultInboundHost(ctx, db, 0, 0, "HK", NodeProxy)
	if err != nil || host != ip {
		t.Fatal(host, err)
	}
	// 新建节点手动选择的部署位优先于地区匹配，即使地区不符。
	other := addServer(t, db, "s2", "JP", 0, false, false)
	var otherIP string
	if err := db.QueryRow(`SELECT ip FROM server WHERE id=?`, other).Scan(&otherIP); err != nil {
		t.Fatal(err)
	}
	if host, err := DefaultInboundHost(ctx, db, 0, other, "HK", NodeProxy); err != nil || host != otherIP {
		t.Fatal("应使用待添加部署位", host, err)
	}
	mustExec(t, db, `UPDATE server SET disabled=1 WHERE id=?`, other)
	if _, err := DefaultInboundHost(ctx, db, 0, other, "HK", NodeProxy); err == nil {
		t.Fatal("已禁用服务器未拒绝")
	}
}
