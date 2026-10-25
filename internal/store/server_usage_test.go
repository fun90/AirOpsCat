package store

import (
	"fmt"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
)

func TestIdleServers(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	mustExec(t, db, `INSERT INTO server(id,ip,name,region,price,price_months) VALUES(1,'1.1.1.1','low','HK',1200,12),(2,'2.2.2.2','busy','HK',3000,1),(3,'3.3.3.3','solo','JP',NULL,1)`)
	mustExec(t, db, `INSERT INTO node(id,name,type) VALUES(1,'hk',0),(2,'jp',0)`)
	mustExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,1,1),(1,2,1),(2,3,1)`)
	for id, gb := range map[int64]int64{1: 1, 2: 100, 3: 1} {
		InsertVnstatSample(ctx, db, id, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 10, RxBytes: gb * 1e9}, now)
	}
	online := func(acc, ip string, at time.Time) {
		mustExec(t, db, `INSERT INTO account_online_ip(account_no,client_ip,connection_id,node_ip,last_online_time) VALUES(?,?,?,?,?)`,
			acc, "9.9.9.9", acc+ip+FormatTime(at), ip, FormatTime(at))
	}
	online("a1", "1.1.1.1", now.AddDate(0, 0, -1))
	online("a1", "1.1.1.1", now.AddDate(0, 0, -2))
	online("old", "1.1.1.1", now.AddDate(0, 0, -40)) // 窗口外不计
	for i := range 10 {
		online(fmt.Sprint("b", i), "2.2.2.2", now)
	}

	list, err := IdleServers(ctx, db, now)
	if err != nil || len(list) != 3 {
		t.Fatalf("%v %+v", err, list)
	}
	low := list[0]
	if low.ID != 1 || !low.Candidate || low.ActiveAccounts != 1 || low.MonthlyCents == nil || *low.MonthlyCents != 100 || len(low.Blockers) != 0 {
		t.Fatalf("低使用应为首个候选，年价折算为月: %+v", low)
	}
	for _, s := range list[1:] {
		switch s.ID {
		case 2:
			if s.Candidate || s.ActiveAccounts != 10 || *s.MonthlyCents != 3000 {
				t.Fatalf("高使用不应为候选: %+v", s)
			}
		case 3:
			if s.Candidate || len(s.Blockers) != 2 || s.MonthlyCents != nil {
				t.Fatalf("唯一节点与地区唯一应阻断: %+v", s)
			}
		}
	}
}
