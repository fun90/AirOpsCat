package store

import (
	"errors"
	"testing"
)

func TestBindingAuthorizationAndRecordRelease(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "binding")
	nodeID := deployedNode(t, db, "binding-node", tag)
	r := onboard(t, db, "binding@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	var pid, sid int64
	if err := db.QueryRow(`SELECT id,server_id FROM node_server WHERE node_id=? LIMIT 1`, nodeID).Scan(&pid, &sid); err != nil {
		t.Fatal(err)
	}
	db.Exec(`INSERT INTO domain(id,domain) VALUES(1,'example.com')`)
	db.Exec(`INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status,server_id) SELECT 1,1,'vpn','vpn.example.com','A',ip,'SYNCED',id FROM server WHERE id=?`, sid)
	rid := int64(1)
	b := &AccountBinding{AccountID: r.AccountID, PlacementID: pid, RecordID: &rid, Enabled: true}
	if err := SaveAccountBinding(ctx, db, b); err != nil {
		t.Fatal(err)
	}
	list, err := AccountBindings(ctx, db, r.AccountID)
	if err != nil || len(list) != 1 || list[0].PlacementID != pid {
		t.Fatalf("绑定未保存: %v", err)
	}
	other := onboard(t, db, "binding2@example.com", nil)
	if err := SaveAccountBinding(ctx, db, &AccountBinding{AccountID: other.AccountID, PlacementID: pid, Enabled: true}); !errors.Is(err, ErrInvalid) {
		t.Fatal("无标签授权不应绑定")
	}
	// 相同节点和部署位允许共享记录；移除一个绑定不应解绑记录。
	a, _ := AccountByID(ctx, db, other.AccountID)
	a.TagIDs = []int64{tag}
	if err := UpdateAccount(ctx, db, a.ID, a); err != nil {
		t.Fatal(err)
	}
	b2 := &AccountBinding{AccountID: other.AccountID, PlacementID: pid, RecordID: &rid, Enabled: true}
	if err := SaveAccountBinding(ctx, db, b2); err != nil {
		t.Fatal(err)
	}
	if err := DeleteAccountBinding(ctx, db, r.AccountID, b.ID); err != nil {
		t.Fatal(err)
	}
	var owner *int64
	if err := db.QueryRow(`SELECT server_id FROM domain_dns_record WHERE id=1`).Scan(&owner); err != nil || owner == nil {
		t.Fatal("共享记录被错误释放")
	}
	a.TagIDs = []int64{}
	if err := UpdateAccount(ctx, db, a.ID, a); err != nil {
		t.Fatal(err)
	}
	list, _ = AccountBindings(ctx, db, a.ID)
	if len(list) != 0 {
		t.Fatal("失去授权后绑定应清理")
	}
	if err := db.QueryRow(`SELECT server_id FROM domain_dns_record WHERE id=1`).Scan(&owner); err != nil || owner != nil {
		t.Fatal("无引用记录应释放")
	}
}

func TestNodeTagRemovalCleansOnlyUnauthorizedBindings(t *testing.T) {
	db, ctx := testDB(t)
	t1 := mustTag(t, db, "one")
	t2 := mustTag(t, db, "two")
	nid := deployedNode(t, db, "n", t1)
	db.Exec(`INSERT INTO node_tag(node_id,tag_id) VALUES(?,?)`, nid, t2)
	r1 := onboard(t, db, "one@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{t1} })
	r2 := onboard(t, db, "two@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{t2} })
	var pid int64
	db.QueryRow(`SELECT id FROM node_server WHERE node_id=?`, nid).Scan(&pid)
	for _, aid := range []int64{r1.AccountID, r2.AccountID} {
		if err := SaveAccountBinding(ctx, db, &AccountBinding{AccountID: aid, PlacementID: pid, Enabled: true}); err != nil {
			t.Fatal(err)
		}
	}
	n, _ := NodeByID(ctx, db, nid)
	n.Region = "HK"
	n.Inbound = `{"type":"vless"}`
	if _, err := SaveNode(ctx, db, n, []int64{t2}, nil); err != nil {
		t.Fatal(err)
	}
	one, _ := AccountBindings(ctx, db, r1.AccountID)
	two, _ := AccountBindings(ctx, db, r2.AccountID)
	if len(one) != 0 || len(two) != 1 {
		t.Fatalf("授权清理不正确: %d %d", len(one), len(two))
	}
	if nodeDeployed(t, db, nid) != PendingDeploy {
		t.Fatal("节点应置待部署")
	}
}

func TestBindingNewDomain(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "nd")
	nid := deployedNode(t, db, "nd-node", tag)
	r := onboard(t, db, "nd@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	var pid int64
	db.QueryRow(`SELECT id FROM node_server WHERE node_id=?`, nid).Scan(&pid)
	db.Exec(`INSERT INTO domain(id,domain) VALUES(1,'example.com')`)
	choices, domains, err := BindingChoices(ctx, db, r.AccountID)
	if err != nil || len(domains) != 1 || len(choices) != 1 || len(choices[0].Records) != 0 {
		t.Fatalf("choices=%v domains=%v err=%v", choices, domains, err)
	}
	if err := SaveAccountBinding(ctx, db, &AccountBinding{AccountID: r.AccountID, PlacementID: pid, NewDomainID: 1, NewName: "Bad_Name", Enabled: true}); !errors.Is(err, ErrInvalid) {
		t.Fatalf("非法子域名 err=%v", err)
	}
	b := &AccountBinding{AccountID: r.AccountID, PlacementID: pid, NewDomainID: 1, NewName: "hk01", Enabled: true}
	if err := SaveAccountBinding(ctx, db, b); err != nil {
		t.Fatal(err)
	}
	var full, status string
	db.QueryRow(`SELECT full_name,status FROM domain_dns_record WHERE id=?`, *b.RecordID).Scan(&full, &status)
	if full != "hk01.example.com" || status != "PENDING_CREATE" {
		t.Fatalf("%s %s", full, status)
	}
	other := onboard(t, db, "nd2@example.com", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	if err := SaveAccountBinding(ctx, db, &AccountBinding{AccountID: other.AccountID, PlacementID: pid, NewDomainID: 1, NewName: "hk01", Enabled: true}); !errors.Is(err, ErrConflict) {
		t.Fatalf("重复域名 err=%v", err)
	}
}
