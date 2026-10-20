package store

import (
	"context"
	"path/filepath"
	"testing"
	"time"
)

func TestGuardOnlineSessionsMappingAndCleanup(t *testing.T) {
	ctx := context.Background()
	db, err := Open(ctx, filepath.Join(t.TempDir(), "online.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	for _, sql := range []string{
		`INSERT INTO account(account_no,uuid,auth_code,period_type) VALUES ('A','a','a','MONTHLY')`,
		`INSERT INTO server(id,name,ip) VALUES (1,'s','203.0.113.1')`,
		`INSERT INTO node(id,name,type,disabled) VALUES (1,'n',0,0),(2,'disabled',0,1)`,
		`INSERT INTO node_server(node_id,server_id,deployed) VALUES (1,1,1),(2,1,1)`,
	} {
		if _, err := db.Exec(sql); err != nil {
			t.Fatal(err)
		}
	}
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)
	start := now.Add(-time.Minute)
	accounts := []GuardOnlineAccount{{AccountNo: "A", NodeTag: "node_1", Connections: []GuardConnection{{ClientIP: "198.51.100.1", ConnectionID: "c", Start: start.Format(time.RFC3339)}}}, {AccountNo: "A", NodeTag: "node_2", ClientIPs: []string{"198.51.100.2", "198.51.100.2"}}, {AccountNo: "unknown", ClientIPs: []string{"bad"}}}
	refresh := func(at time.Time) {
		t.Helper()
		if err := RefreshGuardOnline(ctx, db, "203.0.113.1", accounts, at); err != nil {
			t.Fatal(err)
		}
	}
	refresh(now)
	var count int
	db.QueryRow(`SELECT count(*) FROM account_online_ip`).Scan(&count)
	if count != 2 {
		t.Fatal(count)
	}
	var nodeID int64
	var session string
	if err := db.QueryRow(`SELECT node_id,session_start_time FROM account_online_ip WHERE connection_id='c'`).Scan(&nodeID, &session); err != nil || nodeID != 1 || session != FormatTime(start) {
		t.Fatal(nodeID, session, err)
	}
	db.QueryRow(`SELECT count(*) FROM account_online_ip WHERE node_tag='node_2' AND node_id IS NULL`).Scan(&count)
	if count != 1 {
		t.Fatal("禁用节点被映射")
	}
	accounts[0].Connections[0].Start = now.Format(time.RFC3339)
	refresh(now.Add(time.Minute))
	db.QueryRow(`SELECT session_start_time FROM account_online_ip WHERE connection_id='c'`).Scan(&session)
	if session != FormatTime(start) {
		t.Fatal(session)
	}
	refresh(now.Add(11 * time.Minute))
	db.QueryRow(`SELECT session_start_time FROM account_online_ip WHERE connection_id='c'`).Scan(&session)
	if session != FormatTime(now) {
		t.Fatal(session)
	}
	if err := RefreshGuardOnline(ctx, db, "203.0.113.1", nil, now.Add(12*time.Minute)); err != nil {
		t.Fatal(err)
	}
	removed, err := CleanupGuardOnline(ctx, db, now.AddDate(0, 0, 31))
	if err != nil || removed != 2 {
		t.Fatal(removed, err)
	}
}
