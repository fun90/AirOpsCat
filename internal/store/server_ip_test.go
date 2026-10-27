package store

import (
	"errors"
	"testing"
)

func TestSaveServerIPRetargetsDNS(t *testing.T) {
	db, ctx := testDB(t)
	sid := addServer(t, db, "s", "HK", NodeProxy, false, false)
	db.Exec(`INSERT INTO domain(id,domain) VALUES(1,'example.com')`)
	db.Exec(`INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status,server_id,external_record_id) VALUES
		(1,1,'a','a.example.com','A','1.1.1.1','SYNCED',?,'x1'),
		(2,1,'b','b.example.com','A','1.1.1.1','PENDING_CREATE',NULL,NULL),
		(3,1,'c','c.example.com','A','9.9.9.9','SYNCED',NULL,'x3'),
		(4,1,'d','d.example.com','A','1.1.1.1','PENDING_DELETE',NULL,'x4'),
		(5,1,'e','e.example.com','A','8.8.8.8','SYNCED',NULL,'x5')`, sid)
	db.Exec(`UPDATE domain_dns_record SET failover_home_content='1.1.1.1' WHERE id=3`)

	s, _ := ServerByID(ctx, db, sid)
	s.IP = "2.2.2.2"
	if _, err := SaveServer(ctx, db, nil, s, ""); err != nil {
		t.Fatal(err)
	}
	want := map[int64][3]string{
		1: {"2.2.2.2", "PENDING_UPDATE", ""},
		2: {"2.2.2.2", "PENDING_CREATE", ""},
		3: {"9.9.9.9", "SYNCED", "2.2.2.2"},
		4: {"1.1.1.1", "PENDING_DELETE", ""},
		5: {"8.8.8.8", "SYNCED", ""},
	}
	for id, w := range want {
		var got [3]string
		db.QueryRow(`SELECT content,status,coalesce(failover_home_content,'') FROM domain_dns_record WHERE id=?`, id).Scan(&got[0], &got[1], &got[2])
		if got != w {
			t.Fatalf("记录 %d = %v，期望 %v", id, got, w)
		}
	}

	// 自动切换未对账时拒绝改 IP，服务器 IP 保持不变
	db.Exec(`INSERT INTO dns_auto_operation(record_id,old_content,target_content,reason,created_at) VALUES(3,'9.9.9.9','2.2.2.2','x','2026-01-01 00:00:00')`)
	s.IP = "3.3.3.3"
	if _, err := SaveServer(ctx, db, nil, s, ""); !errors.Is(err, ErrInvalid) {
		t.Fatalf("自动切换进行中应拒绝: %v", err)
	}
	if cur, _ := ServerByID(ctx, db, sid); cur.IP != "2.2.2.2" {
		t.Fatalf("拒绝后 IP 不应变化: %s", cur.IP)
	}
}
