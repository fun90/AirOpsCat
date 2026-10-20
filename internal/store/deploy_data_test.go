package store

import (
	"database/sql"
	"strings"
	"testing"
	"time"
)

func mustExec(t *testing.T, db *sql.DB, q string, args ...any) int64 {
	t.Helper()
	res, err := db.Exec(q, args...)
	if err != nil {
		t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}

func setServerIP(t *testing.T, db *sql.DB, serverID int64, ip string) {
	t.Helper()
	mustExec(t, db, `UPDATE server SET ip = ? WHERE id = ?`, ip, serverID)
}

func addNode(t *testing.T, db *sql.DB, name, protocol string, typ, port int, inbound string, outID any) int64 {
	t.Helper()
	return mustExec(t, db, `INSERT INTO node (name, type, protocol, port, inbound, out_id) VALUES (?, ?, ?, ?, ?, ?)`, name, typ, protocol, port, inbound, outID)
}

func place(t *testing.T, db *sql.DB, nodeID, serverID int64, priority, deployed int) int64 {
	t.Helper()
	return mustExec(t, db, `INSERT INTO node_server (node_id, server_id, priority, deployed) VALUES (?, ?, ?, ?)`, nodeID, serverID, priority, deployed)
}

func accountNo(t *testing.T, db *sql.DB, id int64) string {
	t.Helper()
	var no string
	if err := db.QueryRow(`SELECT account_no FROM account WHERE id = ?`, id).Scan(&no); err != nil {
		t.Fatal(err)
	}
	return no
}

func TestLoadServerConfigInputClients(t *testing.T) {
	db, ctx := testDB(t)
	hk := mustTag(t, db, "hk")
	jp := mustTag(t, db, "jp")
	sid := addServer(t, db, "s1", "HK", 0, false, false)
	nid := addNode(t, db, "n1", "vless", 0, 443, `{"type":"vless"}`, nil)
	place(t, db, nid, sid, 1, 1)
	mustExec(t, db, `INSERT INTO node_tag (node_id, tag_id) VALUES (?, ?)`, nid, hk)
	// 待删除部署位：入站不应出现
	gone := addNode(t, db, "gone", "vless", 0, 444, `{"type":"vless"}`, nil)
	place(t, db, gone, sid, 0, 2)

	mk := func(email string, tags ...int64) int64 {
		return onboard(t, db, email, func(in *OnboardInput) {
			in.Account.TagIDs = tags
			in.Account.DownloadMbps, in.Account.UploadMbps, in.Account.MaxIps = 50, 20, 3
		}).AccountID
	}
	active := mk("a@x.com", hk)
	both := mk("b@x.com", hk, jp) // 多个标签只出现一次
	disabled := mk("c@x.com", hk)
	expired := mk("d@x.com", hk)
	future := mk("e@x.com", hk)
	other := mk("f@x.com", jp)
	mustExec(t, db, `UPDATE account SET disabled = 1 WHERE id = ?`, disabled)
	mustExec(t, db, `UPDATE account SET to_date = ? WHERE id = ?`, FormatTime(time.Now().Add(-time.Hour)), expired)
	mustExec(t, db, `UPDATE account SET to_date = ? WHERE id = ?`, FormatTime(time.Now().Add(24*time.Hour)), future)
	_ = other

	in, err := LoadServerConfigInput(ctx, db, sid, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(in.Nodes) != 1 || in.Nodes[0].ID != nid {
		t.Fatalf("待删除部署位应被排除: %+v", in.Nodes)
	}
	got := in.Nodes[0].Clients
	if len(got) != 3 || got[0].Name != accountNo(t, db, active) || got[1].Name != accountNo(t, db, both) || got[2].Name != accountNo(t, db, future) {
		t.Fatalf("账户过滤结果不对: %+v", got)
	}
	if c := got[0]; c.DownloadMbps != 50 || c.UploadMbps != 20 || c.MaxIPs != 3 || c.UUID == "" {
		t.Fatalf("限速字段: %+v", c)
	}

	// 覆盖钩子整体替换两个限速值，MaxIPs 不受影响
	noA := accountNo(t, db, active)
	in, err = LoadServerConfigInput(ctx, db, sid, LoadOptions{Overrides: func(no string) (int, int, bool) { return 1, 1, no == noA }})
	if err != nil {
		t.Fatal(err)
	}
	if c := in.Nodes[0].Clients[0]; c.DownloadMbps != 1 || c.UploadMbps != 1 || c.MaxIPs != 3 {
		t.Fatalf("覆盖: %+v", c)
	}
	if c := in.Nodes[0].Clients[1]; c.DownloadMbps != 50 {
		t.Fatalf("未命中覆盖的账户不应变: %+v", c)
	}
}

func TestLoadServerConfigInputCoreTypeAndProtocol(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "t")
	sid := addServer(t, db, "s1", "HK", 0, false, false)
	other := addNode(t, db, "o", "vless", 0, 1, `{"type":"vless"}`, nil)
	mustExec(t, db, `UPDATE node SET core_type = 'xray' WHERE id = ?`, other)
	ss := addNode(t, db, "ss", "shadowsocks", 1, 2, `{"type":"shadowsocks"}`, nil)
	for _, n := range []int64{other, ss} {
		place(t, db, n, sid, int(n), 1)
		mustExec(t, db, `INSERT INTO node_tag (node_id, tag_id) VALUES (?, ?)`, n, tag)
	}
	onboard(t, db, "a@x.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	in, err := LoadServerConfigInput(ctx, db, sid, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(in.Nodes) != 2 || len(in.Nodes[0].Clients) != 0 || len(in.Nodes[1].Clients) != 0 {
		t.Fatalf("非 sing-box 内核与 ss 节点不装载账户: %+v", in.Nodes)
	}
}

func TestLoadServerConfigInputLandingAndRules(t *testing.T) {
	db, ctx := testDB(t)
	proxySrv := addServer(t, db, "proxy", "HK", 0, false, false)
	l1 := addServer(t, db, "land1", "HK", 1, false, false)
	l2 := addServer(t, db, "land2", "HK", 1, false, false)
	otherSrv := addServer(t, db, "other", "HK", 0, false, false)
	setServerIP(t, db, l1, "10.0.0.1")
	setServerIP(t, db, l2, "10.0.0.2")

	land := addNode(t, db, "land", "shadowsocks", 1, 8388, `{"type":"shadowsocks","method":"aes-256-gcm","users":[{"name":"x","password":"pw"}]}`, nil)
	place(t, db, land, l2, 2, 1)
	p1 := place(t, db, land, l1, 1, 1)
	proxy := addNode(t, db, "p", "vless", 0, 443, `{"type":"vless"}`, land)
	place(t, db, proxy, proxySrv, 1, 1)

	in, err := LoadServerConfigInput(ctx, db, proxySrv, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if o := in.Nodes[0].Out; o == nil || o.ServerIP != "10.0.0.1" || o.Port != 8388 || o.NodeID != land || o.Protocol != "shadowsocks" {
		t.Fatalf("应取 priority 最小的生效部署位: %+v", o)
	}

	// 生效部署位待删除后顺延到下一个
	mustExec(t, db, `UPDATE node_server SET deployed = 2 WHERE id = ?`, p1)
	in, _ = LoadServerConfigInput(ctx, db, proxySrv, LoadOptions{})
	if o := in.Nodes[0].Out; o == nil || o.ServerIP != "10.0.0.2" {
		t.Fatalf("待删除部署位不能作为生效部署位: %+v", o)
	}

	// 路由规则：仅启用、绑定本服务器的规则；出站节点无生效部署位时静默跳过
	orphan := addNode(t, db, "orphan", "shadowsocks", 1, 9, `{"type":"shadowsocks"}`, nil)
	rule := func(typ, val string, out int64, enabled int, servers ...int64) int64 {
		id := mustExec(t, db, `INSERT INTO route_rule (name, rule_type, rule_value, outbound_node_id, enabled) VALUES ('r', ?, ?, ?, ?)`, typ, val, out, enabled)
		for _, s := range servers {
			mustExec(t, db, `INSERT INTO route_rule_server (route_rule_id, server_id) VALUES (?, ?)`, id, s)
		}
		return id
	}
	rule("domain", `"b.com"`, land, 1, proxySrv)
	rule("domain", `"a.com"`, land, 1, proxySrv, otherSrv)
	rule("domain", `"off.com"`, land, 0, proxySrv)
	rule("domain", `"elsewhere.com"`, land, 1, otherSrv)
	rule("domain", `"orphan.com"`, orphan, 1, proxySrv)
	in, err = LoadServerConfigInput(ctx, db, proxySrv, LoadOptions{})
	if err != nil {
		t.Fatal(err)
	}
	if len(in.Rules) != 2 || in.Rules[0].Value != `"b.com"` || in.Rules[1].Value != `"a.com"` || in.Rules[0].Out.ServerIP != "10.0.0.2" {
		t.Fatalf("路由规则装载: %+v", in.Rules)
	}

	// 代理节点的出站节点没有生效部署位：整台服务器装载失败
	mustExec(t, db, `UPDATE node_server SET deployed = 2 WHERE node_id = ?`, land)
	if _, err := LoadServerConfigInput(ctx, db, proxySrv, LoadOptions{}); err == nil || !strings.Contains(err.Error(), "没有可用的部署服务器") {
		t.Fatal(err)
	}
}

func TestLoadServerConfigInputSecretAndMissingServer(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s1", "HK", 0, false, false)
	in, err := LoadServerConfigInput(ctx, db, sid, LoadOptions{})
	if err != nil || in.ClashSecret != "" {
		t.Fatal(in, err)
	}
	mustExec(t, db, `INSERT INTO system_config (config_key, config_value, group_key) VALUES (?, ?, 'x')`, ClashSecretKey, "abc/def/")
	if in, _ = LoadServerConfigInput(ctx, db, sid, LoadOptions{}); in.ClashSecret != "abc/def/" {
		t.Fatalf("secret 不应裁剪斜杠: %q", in.ClashSecret)
	}
	if _, err := LoadServerConfigInput(ctx, db, 999, LoadOptions{}); err == nil {
		t.Fatal("服务器不存在应报错")
	}
}

func TestShadowTLSAccountAuthorization(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "shadowtls")
	sid := addServer(t, db, "st", "HK", 0, false, false)
	nid := addNode(t, db, "st", "shadowtls", 0, 10443, `{"type":"shadowtls","version":3,"handshake":{"server":"www.apple.com"}}`, nil)
	place(t, db, nid, sid, 0, 0)
	mustExec(t, db, `INSERT INTO node_tag(node_id,tag_id) VALUES(?,?)`, nid, tag)
	r := onboard(t, db, "st@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	in, err := LoadServerConfigInput(ctx, db, sid, LoadOptions{})
	if err != nil || len(in.Nodes[0].Clients) != 1 {
		t.Fatalf("%+v %v", in, err)
	}
	if err := SetAccountDisabled(ctx, db, r.AccountID, true); err != nil {
		t.Fatal(err)
	}
	in, err = LoadServerConfigInput(ctx, db, sid, LoadOptions{})
	if err != nil || len(in.Nodes[0].Clients) != 0 {
		t.Fatalf("禁用账户未移除 %+v %v", in, err)
	}
}
