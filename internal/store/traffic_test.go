package store

import (
	"context"
	"database/sql"
	"errors"
	"math"
	"path/filepath"
	"testing"
	"testing/fstest"
	"time"
)

func trafficTime(t *testing.T, s string) time.Time {
	t.Helper()
	at, err := ParseTime(s)
	if err != nil {
		t.Fatal(err)
	}
	return at
}
func trafficExec(t *testing.T, db *sql.DB, q string, args ...any) {
	t.Helper()
	if _, err := db.Exec(q, args...); err != nil {
		t.Fatal(err)
	}
}
func trafficAccount(t *testing.T, db *sql.DB, no, from, period string) int64 {
	t.Helper()
	res, err := db.Exec(`INSERT INTO account(account_no,uuid,auth_code,period_type,from_date,to_date,bandwidth) VALUES(?,?,?,?,?,'2027-12-20 00:00:00',1)`, no, no, no, period, from)
	if err != nil {
		t.Fatal(err)
	}
	id, _ := res.LastInsertId()
	return id
}
func TestTrafficCalendarBoundaries(t *testing.T) {
	for _, tc := range []struct{ anchor, at, period, start, end string }{
		{"2024-01-31 12:34:56", "2024-02-29 12:34:55", PeriodMonthly, "2024-01-31 12:34:56", "2024-02-29 12:34:56"},
		{"2024-01-31 12:34:56", "2024-02-29 12:34:56", PeriodMonthly, "2024-02-29 12:34:56", "2024-03-31 12:34:56"},
		{"2024-01-31 12:34:56", "2025-03-01 00:00:00", PeriodMonthly, "2025-02-28 12:34:56", "2025-03-31 12:34:56"},
		{"2024-12-31 00:00:00", "2025-01-01 00:00:00", PeriodMonthly, "2024-12-31 00:00:00", "2025-01-31 00:00:00"},
		{"2024-02-29 08:00:00", "2025-02-28 08:00:00", PeriodYearly, "2025-02-28 08:00:00", "2026-02-28 08:00:00"},
		{"2024-02-29 08:00:00", "2028-02-28 08:00:00", PeriodYearly, "2027-02-28 08:00:00", "2028-02-29 08:00:00"},
	} {
		t.Run(tc.at+tc.period, func(t *testing.T) {
			s, e, err := trafficPeriod(trafficTime(t, tc.anchor), trafficTime(t, tc.at), tc.period)
			if err != nil || FormatTime(s) != tc.start || FormatTime(e) != tc.end {
				t.Fatalf("%v %v %v", s, e, err)
			}
		})
	}
	if _, _, err := trafficPeriod(time.Now(), time.Now(), "invalid"); err == nil {
		t.Fatal("接受了无效周期")
	}
}

func TestTrafficRenewalAndExistingPeriod(t *testing.T) {
	db, ctx := testDB(t)
	id := trafficAccount(t, db, "A1", "2026-01-31 10:00:00", PeriodMonthly)
	at := trafficTime(t, "2026-02-28 10:00:00")
	before, err := AccountUsage(ctx, db, id, at)
	if err != nil {
		t.Fatal(err)
	}
	if err = RenewAccount(ctx, db, id, time.Now().AddDate(5, 0, 7), nil, ""); err != nil {
		t.Fatal(err)
	}
	trafficExec(t, db, `UPDATE account SET from_date='2026-01-09 00:00:00' WHERE id=?`, id)
	after, err := AccountUsage(ctx, db, id, at)
	if err != nil || !after.PeriodStart.Equal(before.PeriodStart) || FormatTime(after.PeriodEnd) != "2026-03-31 10:00:00" {
		t.Fatalf("续期漂移: %+v %v", after, err)
	}
	trafficExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,upload_bytes,download_bytes) VALUES(?,'2026-02-15 00:00:00','2026-03-15 00:00:00',3,4)`, id)
	u, err := AccountUsage(ctx, db, id, at)
	if err != nil || u.UsedBytes != 7 || u.QuotaGB != 1 || FormatTime(u.PeriodStart) != "2026-02-15 00:00:00" {
		t.Fatalf("现存周期: %+v %v", u, err)
	}
	u, err = AccountUsage(ctx, db, id, trafficTime(t, "2026-04-01 00:00:00"))
	if err != nil || u.UsedBytes != 0 || FormatTime(u.PeriodStart) != "2026-03-31 10:00:00" {
		t.Fatalf("无采集换期: %+v %v", u, err)
	}
	if _, err = AccountUsage(ctx, db, 999, at); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal(err)
	}
}

func TestTrafficCountersRestartMultiplierAndAtomicity(t *testing.T) {
	db, ctx := testDB(t)
	id := trafficAccount(t, db, "A1", "2026-01-31 00:00:00", PeriodMonthly)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	trafficExec(t, db, `UPDATE server SET multiple=1.5 WHERE id=?`, sid)
	at := trafficTime(t, "2026-03-01 00:00:00")
	readings := []TrafficReading{{"A1", "uplink", 100}, {"A1", "downlink", 200}}
	collect := func(at time.Time, r []TrafficReading) {
		t.Helper()
		if err := CollectAccountTraffic(ctx, db, sid, at, r); err != nil {
			t.Fatal(err)
		}
	}
	collect(at, readings)
	u, err := AccountUsage(ctx, db, id, at)
	if err != nil || u.UsedBytes != 0 {
		t.Fatal(u, err)
	}
	readings[0].Value = 110
	readings[1].Value = 220
	collect(at.Add(time.Minute), readings)
	collect(at.Add(time.Minute), readings)
	collect(at.Add(2*time.Minute), readings)
	u, err = AccountUsage(ctx, db, id, at)
	if err != nil || u.UsedBytes != 45 {
		t.Fatal(u, err)
	}
	readings[0].Value = 4
	readings[1].Value = 8
	collect(at.Add(3*time.Minute), readings)
	u, err = AccountUsage(ctx, db, id, at)
	if err != nil || u.UsedBytes != 63 {
		t.Fatal(u, err)
	}
	var up, down int64
	if err = db.QueryRow(`SELECT upload_bytes,download_bytes FROM account_traffic_stats WHERE account_id=?`, id).Scan(&up, &down); err != nil || up != 21 || down != 42 {
		t.Fatal(up, down, err)
	}
	for _, bad := range [][]TrafficReading{
		{{"A1", "uplink", 10}, {"missing", "downlink", 0}},
		{{"A1", "uplink", 10}, {"A1", "bad", 1}},
		{{"A1", "uplink", 10}, {"A1", "downlink", -1}},
		{{"A1", "uplink", 10}, {"a1", "uplink", 10}},
		{{"A1", "uplink", math.MaxInt64}},
	} {
		if err = CollectAccountTraffic(ctx, db, sid, at.Add(4*time.Minute), bad); err == nil {
			t.Fatal("无效批次被接受", bad)
		}
		u, err = AccountUsage(ctx, db, id, at)
		if err != nil || u.UsedBytes != 63 {
			t.Fatal("失败写入未回滚", u, err)
		}
		var value int64
		if err = db.QueryRow(`SELECT value FROM account_traffic_counter WHERE server_id=? AND account_no='A1' AND direction='uplink'`, sid).Scan(&value); err != nil || value != 4 {
			t.Fatal("失败基线未回滚", value, err)
		}
	}
	if err = CollectAccountTraffic(ctx, db, sid, at.Add(3*time.Minute), []TrafficReading{{"A1", "uplink", 5}}); err == nil {
		t.Fatal("同时间不同读数被接受")
	}
	if err = CollectAccountTraffic(ctx, db, sid, at.Add(time.Minute), readings); err == nil {
		t.Fatal("过时采样被接受")
	}
	trafficExec(t, db, `CREATE TRIGGER fail_traffic BEFORE UPDATE ON account_traffic_counter BEGIN SELECT RAISE(ABORT,'test'); END`)
	if err = CollectAccountTraffic(ctx, db, sid, at.Add(4*time.Minute), []TrafficReading{{"A1", "uplink", 10}}); err == nil {
		t.Fatal("应回滚")
	}
	u, err = AccountUsage(ctx, db, id, at)
	if err != nil || u.UsedBytes != 63 {
		t.Fatal("SQL失败未回滚", u, err)
	}
	trafficExec(t, db, `DROP TRIGGER fail_traffic`)
	trafficExec(t, db, `UPDATE account_traffic_stats SET upload_bytes=?,download_bytes=0 WHERE account_id=?`, int64(math.MaxInt64), id)
	if err = CollectAccountTraffic(ctx, db, sid, at.Add(4*time.Minute), []TrafficReading{{"A1", "uplink", 10}}); err == nil {
		t.Fatal("累加溢出被接受")
	}
	trafficExec(t, db, `UPDATE server SET multiple=-1 WHERE id=?`, sid)
	if err = CollectAccountTraffic(ctx, db, sid, at, nil); err == nil {
		t.Fatal("负倍率被接受")
	}
}

func TestTrafficCounterPersistsAndDirectionIsIndependent(t *testing.T) {
	ctx := context.Background()
	path := filepath.Join(t.TempDir(), "traffic.db")
	db, err := Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	id := trafficAccount(t, db, "A1", "2026-01-01 00:00:00", PeriodMonthly)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	at := trafficTime(t, "2026-01-02 00:00:00")
	if err = CollectAccountTraffic(ctx, db, sid, at, []TrafficReading{{"A1", "uplink", 100}}); err != nil {
		t.Fatal(err)
	}
	db.Close()
	db, err = Open(ctx, path)
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	if err = CollectAccountTraffic(ctx, db, sid, at.Add(time.Minute), []TrafficReading{{"A1", "uplink", 120}, {"A1", "downlink", 50}}); err != nil {
		t.Fatal(err)
	}
	u, err := AccountUsage(ctx, db, id, at)
	if err != nil || u.UsedBytes != 20 {
		t.Fatal(u, err)
	}
}

func TestTrafficLimitsAndCleanup(t *testing.T) {
	db, ctx := testDB(t)
	id := trafficAccount(t, db, "A1", "2026-01-01 00:00:00", PeriodYearly)
	at := trafficTime(t, "2026-06-01 00:00:00")
	trafficExec(t, db, `UPDATE account SET download_mbps=30,upload_mbps=40 WHERE id=?`, id)
	trafficExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,upload_bytes,bandwidth_quota) VALUES(?,'2026-01-01 00:00:00','2027-01-01 00:00:00',?,NULL)`, id, int64(1<<30))
	l, err := limitsOf(ctx, db, "A1", at)
	if err != nil || l.DownloadMbps != 30 || l.UploadMbps != 40 || !l.OverQuota {
		t.Fatal(l, err)
	}
	for k, v := range map[string]string{"airopscat.ratelimit.enabled": "true", "airopscat.account.traffic-over-quota.download-mbps": "7", "airopscat.account.traffic-over-quota.upload-mbps": "0"} {
		trafficExec(t, db, `INSERT INTO system_config(config_key,config_value,group_key) VALUES(?,?,'traffic')`, k, v)
	}
	l, err = limitsOf(ctx, db, "A1", at)
	if err != nil || l.DownloadMbps != 7 || l.UploadMbps != 1 {
		t.Fatal(l, err)
	}
	trafficExec(t, db, `UPDATE account_traffic_stats SET bandwidth_quota=0 WHERE account_id=?`, id)
	l, err = limitsOf(ctx, db, "A1", at)
	if err != nil || l.OverQuota || l.DownloadMbps != 30 {
		t.Fatal(l, err)
	}
	trafficExec(t, db, `UPDATE account_traffic_stats SET bandwidth_quota=1 WHERE account_id=?`, id)
	l, err = limitsOf(ctx, db, "A1", trafficTime(t, "2027-01-01 00:00:00"))
	if err != nil || l.OverQuota || l.DownloadMbps != 30 {
		t.Fatal(l, err)
	}
	trafficExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end) VALUES(?,'2024-01-01 00:00:00','2025-01-01 00:00:00')`, id)
	trafficExec(t, db, `INSERT INTO account_online_ip(account_no,client_ip,node_ip,last_online_time) VALUES('A1','1','2','2026-01-01 00:00:00'),('A1','2','2','2026-05-31 00:00:00')`)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	if err = CollectAccountTraffic(ctx, db, sid, at.AddDate(0, -3, 0), []TrafficReading{{"A1", "uplink", 100}}); err != nil {
		t.Fatal(err)
	}
	if err = CleanupTrafficHistory(ctx, db, at); err != nil {
		t.Fatal(err)
	}
	for table, want := range map[string]int{"account_traffic_stats": 1, "account_online_ip": 1, "account_traffic_counter": 1} {
		var got int
		if err = db.QueryRow(`SELECT count(*) FROM ` + table).Scan(&got); err != nil || got != want {
			t.Fatal(table, got, err)
		}
	}
}

func TestTrafficExpirationAtomic(t *testing.T) {
	db, ctx := testDB(t)
	a := trafficAccount(t, db, "A1", "2026-01-01 00:00:00", PeriodMonthly)
	b := trafficAccount(t, db, "A2", "2026-01-01 00:00:00", PeriodMonthly)
	trafficExec(t, db, `UPDATE account SET to_date='2026-02-01 00:00:00'`)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	trafficExec(t, db, `INSERT INTO node(id,name) VALUES(1,'n')`)
	trafficExec(t, db, `INSERT INTO tag(id,name) VALUES(1,'t')`)
	trafficExec(t, db, `INSERT INTO account_tag VALUES(?,1)`, a)
	trafficExec(t, db, `INSERT INTO node_tag VALUES(1,1)`)
	trafficExec(t, db, `INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,?,1)`, sid)
	at := trafficTime(t, "2026-02-01 00:00:00")
	trafficExec(t, db, `CREATE TRIGGER fail_expire BEFORE UPDATE OF disabled ON account BEGIN SELECT RAISE(ABORT,'test'); END`)
	if _, err := ExpireAccounts(ctx, db, at); err == nil {
		t.Fatal("应失败")
	}
	var deployed int
	db.QueryRow(`SELECT deployed FROM node_server`).Scan(&deployed)
	if deployed != 1 {
		t.Fatal("待部署标记未回滚")
	}
	trafficExec(t, db, `DROP TRIGGER fail_expire`)
	ids, err := ExpireAccounts(ctx, db, at)
	if err != nil || len(ids) != 2 {
		t.Fatal(ids, err)
	}
	for _, id := range []int64{a, b} {
		var disabled int
		db.QueryRow(`SELECT disabled FROM account WHERE id=?`, id).Scan(&disabled)
		if disabled != 1 {
			t.Fatal("无节点账户也必须禁用")
		}
	}
	db.QueryRow(`SELECT deployed FROM node_server`).Scan(&deployed)
	if deployed != 0 {
		t.Fatal("未标记待部署")
	}
	ids, err = ExpireAccounts(ctx, db, at)
	if err != nil || len(ids) != 0 {
		t.Fatal(ids, err)
	}
}

func TestTrafficBatchLimitsReuseTransaction(t *testing.T) {
	db, ctx := testDB(t)
	id := trafficAccount(t, db, "A1", "2026-01-01 00:00:00", PeriodMonthly)
	inactive := trafficAccount(t, db, "A2", "2026-01-01 00:00:00", PeriodMonthly)
	trafficExec(t, db, `UPDATE account SET disabled=1 WHERE id=?`, inactive)
	trafficExec(t, db, `UPDATE account SET download_mbps=30,upload_mbps=40 WHERE id=?`, id)
	at := trafficTime(t, "2026-01-02 00:00:00")
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		t.Fatal(err)
	}
	defer tx.Rollback()
	// 未提交的配置与统计必须在原事务内可见，不能另取连接或开启事务。
	if _, err = tx.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.ratelimit.enabled','true','traffic')`); err != nil {
		t.Fatal(err)
	}
	if _, err = tx.Exec(`INSERT INTO account_traffic_stats(account_id,period_start,period_end,upload_bytes) VALUES(?,'2026-01-01 00:00:00','2026-02-01 00:00:00',?)`, id, int64(1<<30)); err != nil {
		t.Fatal(err)
	}
	limits, err := EffectiveLimitOverrides(ctx, tx, at)
	if err != nil || len(limits) != 1 || limits["A1"].DownloadMbps != 1 || !limits["A1"].OverQuota {
		t.Fatal(limits, err)
	}
	if err = tx.Commit(); err != nil {
		t.Fatal(err)
	}
	limits, err = loadLimits(ctx, db, at)
	if err != nil {
		t.Fatal(err)
	}
	trafficExec(t, db, `UPDATE system_config SET config_value='false' WHERE config_key='airopscat.ratelimit.enabled'`)
	limits, err = loadLimits(ctx, db, at)
	if err != nil || !limits["A1"].OverQuota || limits["A1"].DownloadMbps != 30 {
		t.Fatal(limits, err)
	}
	trafficExec(t, db, `UPDATE account SET to_date=? WHERE id=?`, FormatTime(at), id)
	expired, err := loadLimits(ctx, db, at)
	if err != nil || len(expired) != 0 {
		t.Fatal("到期边界必须过滤", expired, err)
	}
	trafficExec(t, db, `UPDATE account SET to_date=NULL WHERE id=?`, id)
	limits, err = loadLimits(ctx, db, trafficTime(t, "2026-02-01 00:00:00"))
	if err != nil || limits["A1"].OverQuota || limits["A1"].DownloadMbps != 30 {
		t.Fatal(limits, err)
	}
}

func TestTrafficDefaultDeploymentOverrides(t *testing.T) {
	db, ctx := testDB(t)
	id := trafficAccount(t, db, "A1", "2026-01-01 00:00:00", PeriodMonthly)
	trafficExec(t, db, `UPDATE account SET to_date=NULL,download_mbps=30,upload_mbps=40 WHERE id=?`, id)
	sid := addServer(t, db, "s", "HK", 0, false, false)
	nid := addNode(t, db, "n", "vless", 0, 443, `{"type":"vless"}`, nil)
	place(t, db, nid, sid, 1, 1)
	tag := mustTag(t, db, "traffic")
	trafficExec(t, db, `INSERT INTO account_tag VALUES(?,?)`, id, tag)
	trafficExec(t, db, `INSERT INTO node_tag VALUES(?,?)`, nid, tag)
	at := nowFunc()
	trafficExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,upload_bytes) VALUES(?,?,?,?)`, id, FormatTime(at.Add(-time.Hour)), FormatTime(at.Add(time.Hour)), int64(1<<30))
	for _, enabled := range []string{"false", "true"} {
		trafficExec(t, db, `INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.ratelimit.enabled',?,'traffic') ON CONFLICT(config_key) DO UPDATE SET config_value=excluded.config_value`, enabled)
		// 单连接下 CaptureDeployment 必须复用原事务，另开事务会阻塞到超时。
		db.SetMaxOpenConns(1)
		callCtx, cancel := context.WithTimeout(ctx, 2*time.Second)
		captured, err := CaptureDeployment(callCtx, db, sid, LoadOptions{})
		cancel()
		if err != nil {
			t.Fatal(err)
		}
		preview, err := LoadServerConfigInput(ctx, db, sid, LoadOptions{})
		if err != nil {
			t.Fatal(err)
		}
		wantDown, wantUp := 30, 40
		if enabled == "true" {
			wantDown, wantUp = 1, 1
		}
		for _, c := range []struct{ down, up int }{{captured.Input.Nodes[0].Clients[0].DownloadMbps, captured.Input.Nodes[0].Clients[0].UploadMbps}, {preview.Nodes[0].Clients[0].DownloadMbps, preview.Nodes[0].Clients[0].UploadMbps}} {
			if c.down != wantDown || c.up != wantUp {
				t.Fatal(enabled, c)
			}
		}
	}
	explicit, err := LoadServerConfigInput(ctx, db, sid, LoadOptions{Overrides: func(string) (int, int, bool) { return 8, 9, true }})
	if err != nil || explicit.Nodes[0].Clients[0].DownloadMbps != 8 || explicit.Nodes[0].Clients[0].UploadMbps != 9 {
		t.Fatal(explicit, err)
	}
}

func TestTrafficMigrationInitializesAnchors(t *testing.T) {
	ctx := context.Background()
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "legacy.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	old := fstest.MapFS{}
	for _, name := range []string{"0001_init.sql", "0002_deploy.sql", "0003_deploy_revision.sql"} {
		body, err := schemaFS.ReadFile("schema/" + name)
		if err != nil {
			t.Fatal(err)
		}
		old["schema/"+name] = &fstest.MapFile{Data: body}
	}
	if err = migrate(ctx, db, old); err != nil {
		t.Fatal(err)
	}
	a := trafficAccount(t, db, "A1", "2026-01-10 00:00:00", PeriodMonthly)
	b := trafficAccount(t, db, "A2", "2026-01-15 00:00:00", PeriodMonthly)
	trafficExec(t, db, `INSERT INTO account(account_no,uuid,auth_code,period_type,to_date) VALUES('A3','A3','A3','YEARLY','2024-02-29 10:00:00')`)
	trafficExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end) VALUES(?,'2026-02-28 10:00:00','2026-03-31 10:00:00')`, a)
	if err = migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
	for id, want := range map[int64]string{a: "2026-03-31 10:00:00", b: "2026-01-15 00:00:00", 3: "2024-02-29 10:00:00"} {
		var got string
		if err = db.QueryRow(`SELECT traffic_anchor FROM account WHERE id=?`, id).Scan(&got); err != nil || got != want {
			t.Fatal(id, got, err)
		}
	}
	if err = migrate(ctx, db, schemaFS); err != nil {
		t.Fatal("重复迁移", err)
	}
}

func TestAccountTrafficHistoryAndUsedSort(t *testing.T) {
	db, ctx := testDB(t)
	a := trafficAccount(t, db, "A1", "2026-01-01 00:00:00", PeriodMonthly)
	b := trafficAccount(t, db, "A2", "2026-01-01 00:00:00", PeriodMonthly)
	trafficExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,upload_bytes,download_bytes) VALUES
(?,'2026-01-01 00:00:00','2026-02-01 00:00:00',1,2),(?,'2026-02-01 00:00:00','2026-03-01 00:00:00',3,4),(?,'2026-02-01 00:00:00','2026-03-01 00:00:00',50,50)`, a, a, b)
	h, err := AccountTrafficHistory(ctx, db, a)
	if err != nil || len(h) != 2 || h[0].UploadBytes != 3 || h[1].DownloadBytes != 2 {
		t.Fatal(h, err)
	}
	old := nowFunc
	nowFunc = func() time.Time { return trafficTime(t, "2026-02-10 00:00:00") }
	defer func() { nowFunc = old }()
	rows, _, err := AccountList(ctx, db, AccountFilter{Sort: "used"}, 1, 10)
	if err != nil || len(rows) != 2 || rows[0].ID != b {
		t.Fatal(rows, err)
	}
}

func loadLimits(ctx context.Context, db *sql.DB, at time.Time) (map[string]MbpsLimits, error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return nil, err
	}
	defer tx.Rollback()
	return EffectiveLimitOverrides(ctx, tx, at)
}

func limitsOf(ctx context.Context, db *sql.DB, code string, at time.Time) (MbpsLimits, error) {
	m, err := loadLimits(ctx, db, at)
	return m[code], err
}
