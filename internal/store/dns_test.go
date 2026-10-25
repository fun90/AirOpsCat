package store

import (
	"errors"
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/dns"
)

func TestDNSProviderTokenEncryptedAndKept(t *testing.T) {
	db, ctx := testDB(t)
	c, _ := crypto.New(crypto.DeriveKey("secret", "field"))
	id, err := SaveDNSProvider(ctx, db, c, &DNSProvider{Name: "cf", AccountID: "acc", Enabled: true}, "tok-123")
	if err != nil {
		t.Fatal(err)
	}
	var raw string
	db.QueryRow(`SELECT credential_json FROM dns_provider_config WHERE id=?`, id).Scan(&raw)
	if raw == "" || strings.Contains(raw, "tok-123") {
		t.Fatalf("凭据未加密: %q", raw)
	}
	if _, err := SaveDNSProvider(ctx, db, c, &DNSProvider{ID: id, Name: "cf2", AccountID: "acc", Enabled: true}, ""); err != nil {
		t.Fatal(err)
	}
	if tok, err := DNSProviderToken(ctx, db, c, id); err != nil || tok != "tok-123" {
		t.Fatalf("留空应保留原 Token: %q %v", tok, err)
	}
	if _, err := SaveDNSProvider(ctx, db, c, &DNSProvider{Name: "x", AccountID: "a"}, ""); !errors.Is(err, ErrInvalid) {
		t.Fatal("新增缺少 Token 应拒绝")
	}
	pid := id
	did, err := SaveDNSDomain(ctx, db, &DNSDomain{Domain: "Example.com", ProviderID: &pid, ZoneID: "z1"}, "12.50")
	if err != nil {
		t.Fatal(err)
	}
	if err := DeleteDNSProvider(ctx, db, id); !errors.Is(err, ErrConflict) {
		t.Fatalf("被域名引用的服务商不能删除: %v", err)
	}
	d, _ := DNSDomainByID(ctx, db, did)
	if d.Domain != "example.com" || d.ZoneID != "z1" || *d.Price != 1250 {
		t.Fatalf("%+v", d)
	}
	if _, err := SaveDNSDomain(ctx, db, &DNSDomain{Domain: "example.com"}, ""); !errors.Is(err, ErrConflict) {
		t.Fatal("域名应唯一")
	}
	if _, err := SaveDNSDomain(ctx, db, &DNSDomain{Domain: "bad_domain"}, ""); !errors.Is(err, ErrInvalid) {
		t.Fatal("非法域名应拒绝")
	}
}

func TestDNSRecordLifecycle(t *testing.T) {
	db, ctx := testDB(t)
	did, err := SaveDNSDomain(ctx, db, &DNSDomain{Domain: "example.com"}, "")
	if err != nil {
		t.Fatal(err)
	}
	res, _ := db.Exec(`INSERT INTO server(name,ip) VALUES('HK','192.0.2.1')`)
	sid, _ := res.LastInsertId()
	rec := &DNSRecord{DomainID: did, Name: "hk", Content: "192.0.2.9", TTL: 300, ServerID: &sid}
	if _, err := SaveDNSRecord(ctx, db, rec); !errors.Is(err, ErrInvalid) {
		t.Fatalf("绑定服务器时内容须等于服务器 IP: %v", err)
	}
	rec.Content = "192.0.2.1"
	id, err := SaveDNSRecord(ctx, db, rec)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := SaveDNSRecord(ctx, db, &DNSRecord{DomainID: did, Name: "HK", Content: "192.0.2.1", TTL: 300}); !errors.Is(err, ErrConflict) {
		t.Fatal("同名 A 记录应冲突")
	}
	for _, bad := range []*DNSRecord{{DomainID: did, Name: "a", Content: "::1", TTL: 300}, {DomainID: did, Name: "a", Content: "1.1.1.1", TTL: 30}, {DomainID: did, Name: "A_B", Content: "1.1.1.1", TTL: 300}} {
		if _, err := SaveDNSRecord(ctx, db, bad); !errors.Is(err, ErrInvalid) {
			t.Fatalf("应拒绝 %+v: %v", bad, err)
		}
	}
	p, _ := LoadDNSPush(ctx, db, did)
	if len(p.PostIDs) != 1 || p.Batch.Posts[0].Name != "hk.example.com" {
		t.Fatalf("%+v", p)
	}
	if err := ApplyDNSPush(ctx, db, did, p, []string{"ext1"}); err != nil {
		t.Fatal(err)
	}
	r, _ := DNSRecordByID(ctx, db, id)
	if r.Status != DNSSynced || r.ExternalID != "ext1" || r.ServerID == nil {
		t.Fatalf("%+v", r)
	}
	// 修改已同步记录 → 待更新
	r.Content = "192.0.2.1"
	r.TTL = 600
	SaveDNSRecord(ctx, db, r)
	if r, _ = DNSRecordByID(ctx, db, id); r.Status != DNSPendingUpdate {
		t.Fatal(r.Status)
	}
	// 拉取不覆盖待推送的本地修改，也不删除被其他记录引用的远端缺失记录
	if _, _, _, err := ApplyDNSPull(ctx, db, did, []dns.Record{{ID: "ext1", Name: "hk.example.com", Type: "A", Content: "9.9.9.9"}}); err != nil {
		t.Fatal(err)
	}
	if r, _ = DNSRecordByID(ctx, db, id); r.Content != "192.0.2.1" || r.TTL != 600 {
		t.Fatalf("待推送修改被覆盖: %+v", r)
	}
	// 推送回写
	p, _ = LoadDNSPush(ctx, db, did)
	if len(p.PatchIDs) != 1 {
		t.Fatalf("%+v", p)
	}
	ApplyDNSPush(ctx, db, did, p, nil)
	// 引用保护
	db.Exec(`INSERT INTO node(id,name,type,protocol,core_type) VALUES(1,'n',0,'vless','sing-box')`)
	db.Exec(`INSERT INTO node_server(node_id,server_id,access_host_id) VALUES(1,?,?)`, sid, id)
	if err := DeleteDNSRecord(ctx, db, id); !errors.Is(err, ErrConflict) {
		t.Fatalf("被引用记录不能删除: %v", err)
	}
	if _, _, removed, _ := ApplyDNSPull(ctx, db, did, nil); removed != 0 {
		t.Fatal("被引用记录不应被拉取删除")
	}
	if r, _ = DNSRecordByID(ctx, db, id); r == nil || r.Status != DNSSyncFailed {
		t.Fatalf("远端缺失应标 SYNC_FAILED: %+v", r)
	}
	db.Exec(`DELETE FROM node_server`)
	if err := DeleteDNSRecord(ctx, db, id); err != nil {
		t.Fatal(err)
	}
	if r, _ = DNSRecordByID(ctx, db, id); r.Status != DNSPendingDelete || r.ServerID != nil {
		t.Fatalf("%+v", r)
	}
	p, _ = LoadDNSPush(ctx, db, did)
	if len(p.DeleteIDs) != 1 || len(p.Batch.Deletes) != 1 {
		t.Fatalf("%+v", p)
	}
	ApplyDNSPush(ctx, db, did, p, nil)
	if r, _ = DNSRecordByID(ctx, db, id); r != nil {
		t.Fatal("推送删除后应移除本地记录")
	}
}

func TestDNSPullAddsAndLinksPendingCreate(t *testing.T) {
	db, ctx := testDB(t)
	did, _ := SaveDNSDomain(ctx, db, &DNSDomain{Domain: "example.com"}, "")
	id, _ := SaveDNSRecord(ctx, db, &DNSRecord{DomainID: did, Name: "@", Content: "1.1.1.1", TTL: 1})
	ttl := 300
	added, _, _, err := ApplyDNSPull(ctx, db, did, []dns.Record{
		{ID: "e1", Name: "example.com", Type: "A", Content: "2.2.2.2", TTL: &ttl},
		{ID: "e2", Name: "www.example.com", Type: "CNAME", Content: "example.com", TTL: &ttl}})
	if err != nil || added != 1 {
		t.Fatalf("added=%d %v", added, err)
	}
	r, _ := DNSRecordByID(ctx, db, id)
	if r.ExternalID != "e1" || r.Status != DNSPendingUpdate || r.Content != "1.1.1.1" {
		t.Fatalf("同名待创建记录应关联远端并保留本地内容: %+v", r)
	}
	list, total, _ := DNSRecordList(ctx, db, did, "www", 1, 20)
	if total != 1 || list[0].Type != "CNAME" || list[0].Name != "www" {
		t.Fatalf("%+v", list)
	}
}

// 生产案例：961.52 GB / 1000 GB 告警已达 96%，DNS 不得按 GiB 算成 0.8955 而回切。
func TestDNSCandidatesDecimalGB(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Now().In(Shanghai)
	for _, q := range []string{
		`INSERT INTO server(id,ip,bandwidth)VALUES(1,'192.0.2.1',1000)`,
		`INSERT INTO node(id,name,type,protocol,core_type)VALUES(1,'n',0,'vless','sing-box')`,
		`INSERT INTO node_server(id,node_id,server_id,deployed)VALUES(1,1,1,1)`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.Exec(`INSERT INTO server_metrics(server_id,kind,sample_time,period_year,period_month,rx_bytes,tx_bytes)VALUES(1,'vnstat',?,?,?,478791242211,482727447544)`, FormatTime(now), now.Year(), int(now.Month())); err != nil {
		t.Fatal(err)
	}
	cs, err := DNSCandidates(ctx, db, now)
	if err != nil || len(cs) != 1 || !cs[0].Known || cs[0].Usage < 0.95 {
		t.Fatalf("%v %+v", err, cs)
	}
}
