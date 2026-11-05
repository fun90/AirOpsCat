package store

import (
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
)

func TestMetricsLifecycle(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	mustExec(t, db, `INSERT INTO server(id,ip,name,bandwidth) VALUES(1,'1.1.1.1','HK',100),(2,'2.2.2.2','US',0),(3,'3.3.3.3','OFF',0),(4,'4.4.4.4','EXT',0)`)
	mustExec(t, db, `UPDATE server SET disabled=1 WHERE id=3`)
	mustExec(t, db, `UPDATE server SET external_server=1 WHERE id=4`)

	if ids, _ := MonitorServers(ctx, db, "", now); len(ids) != 0 {
		t.Fatal("未配置应关闭")
	}
	if ids, _ := MonitorServers(ctx, db, "all", now); len(ids) != 2 {
		t.Fatalf("all 应只含启用的非外部服务器: %v", ids)
	}
	if ids, _ := MonitorServers(ctx, db, "2, 3", now); len(ids) != 1 || ids[0] != 2 {
		t.Fatalf("指定列表仍须过滤禁用: %v", ids)
	}
	if _, err := MonitorServers(ctx, db, "x", now); err == nil {
		t.Fatal("非法配置应报错")
	}

	for i := 0; i < 5; i++ {
		at := now.Add(-time.Duration(i) * 2 * time.Minute)
		if err := InsertMonitorSample(ctx, db, 1, monitor.Sample{CPU: 0.5 + float64(i)/100, Memory: 0.4, MemUsed: 4, MemTotal: 10, RxRate: 100, TxRate: 200}, at); err != nil {
			t.Fatal(err)
		}
	}
	// 上月 vnstat 不算本月；本月取最新一条
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 9, RxBytes: 1 << 40}, now.Add(-time.Hour))
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 10, RxBytes: 10 << 30, TxBytes: 15 << 30}, now.Add(-10*time.Minute))
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 10, RxBytes: 20 << 30, TxBytes: 30 << 30}, now.Add(-5*time.Minute))

	list, total, err := ServerStatuses(ctx, db, "", 1, 20, now)
	if err != nil || total != 2 || len(list) != 2 {
		t.Fatalf("%v %d %+v", err, total, list)
	}
	hk := list[0]
	if hk.Name != "HK" || hk.CPU == nil || *hk.CPU != 0.5 || !hk.HasVnstat || hk.UsageRatio() != 0.5 {
		t.Fatalf("%+v ratio=%v", hk, hk.UsageRatio())
	}
	if us := list[1]; us.CPU != nil || us.HasVnstat || us.UsageRatio() != 0 {
		t.Fatalf("无数据服务器应为空: %+v", us)
	}

	pts, err := MetricPoints(ctx, db, 1, "cpu_usage", now.Add(-5*time.Minute))
	if err != nil || len(pts) != 3 || !pts[0].At.Before(pts[2].At) {
		t.Fatalf("%v %+v", err, pts)
	}
	if _, err := MetricPoints(ctx, db, 1, "cpu_usage; DROP TABLE server", now); err == nil {
		t.Fatal("列名必须白名单")
	}

	n, err := CleanupMetrics(ctx, db, 1, 2, now.Add(24*time.Hour+time.Minute))
	if err != nil || n != 8 {
		t.Fatalf("应分批（每批 2 条）删光 8 条过期记录: n=%d %v", n, err)
	}
}

func TestAlertResolveFingerprint(t *testing.T) {
	db, ctx := testDB(t)
	now := time.Now()
	for _, fp := range []string{"cpu:1", "memory:1"} {
		AlertTrigger(ctx, db, AlertKey{Type: "server-monitor-load", ResourceType: "server", ResourceID: 1, Fingerprint: fp}, "", 1, 1, "s", time.Hour, now)
	}
	if ok, err := AlertResolveFingerprint(ctx, db, "server-monitor-load", "server", 1, "cpu:1", now); err != nil || !ok {
		t.Fatal(ok, err)
	}
	if ok, _ := AlertResolveFingerprint(ctx, db, "server-monitor-load", "server", 1, "cpu:1", now); ok {
		t.Fatal("重复恢复应返回 false")
	}
	if n := alertActiveCount(db); n != 1 {
		t.Fatalf("memory 告警不应受影响: %d", n)
	}
}

func TestVnstatTrafficOffset(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `INSERT INTO server(id,ip,name,traffic_offset,traffic_offset_period) VALUES(1,'1.1.1.1','HK',7000,'2026-10')`)
	at := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 10, RxBytes: 100, TxBytes: 200}, at)
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 11, RxBytes: 100, TxBytes: 200}, at)
	var sums []int64
	rows, _ := db.QueryContext(ctx, `SELECT rx_bytes+tx_bytes FROM server_metrics WHERE kind='vnstat' ORDER BY id`)
	for rows.Next() {
		var v int64
		rows.Scan(&v)
		sums = append(sums, v)
	}
	if len(sums) != 2 || sums[0] != 7300 || sums[1] != 300 {
		t.Fatalf("校准值只应叠加到所属周期: %v", sums)
	}
}

func TestCalibrateServerTraffic(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `INSERT INTO server(id,ip,name) VALUES(1,'1.1.1.1','HK')`)
	at := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	if err := CalibrateServerTraffic(ctx, db, 1, 100, at); err == nil {
		t.Fatal("无 vnstat 数据应报错")
	}
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 10, RxBytes: 1000, TxBytes: 2000}, at)
	for _, want := range []int64{5000, 800} { // 重复校准以原始值为基准，可上调也可下调
		if err := CalibrateServerTraffic(ctx, db, 1, want, at); err != nil {
			t.Fatal(err)
		}
		var sum int64
		db.QueryRow(`SELECT rx_bytes+tx_bytes FROM server_metrics WHERE kind='vnstat' ORDER BY id DESC LIMIT 1`).Scan(&sum)
		if sum != want {
			t.Fatalf("校准后应为 %d，实际 %d", want, sum)
		}
	}
	// 后续采样沿用校准值：原始 3500 → 800 - 3000 = -2200 偏移
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 10, RxBytes: 1500, TxBytes: 2000}, at.Add(time.Minute))
	var sum int64
	db.QueryRow(`SELECT rx_bytes+tx_bytes FROM server_metrics WHERE kind='vnstat' ORDER BY id DESC LIMIT 1`).Scan(&sum)
	if sum != 1300 {
		t.Fatalf("后续样本应叠加校准值: %d", sum)
	}
	if err := CalibrateServerTraffic(ctx, db, 1, -1, at); err == nil {
		t.Fatal("负值应报错")
	}
}

func TestServerListUsageSortFilter(t *testing.T) {
	db, ctx := testDB(t)
	mustExec(t, db, `INSERT INTO server(id,ip,name,expire_date,bandwidth) VALUES(1,'192.0.2.1','A','2026-12-30',100),(2,'192.0.2.2','B','2026-11-10',0),(3,'192.0.2.3','C',NULL,NULL)`)
	at := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	InsertVnstatSample(ctx, db, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 10, RxBytes: 100, TxBytes: 200}, at)
	list, total, err := ServerList(ctx, db, "", "", "expire", true, at, 1, 10)
	if err != nil || total != 3 || list[0].ID != 2 || list[1].ID != 1 || list[2].ID != 3 {
		t.Fatalf("按到期日升序且空值在后: %v %v", err, list)
	}
	byID := map[int64]*Server{}
	for _, s := range list {
		byID[s.ID] = s
	}
	if !byID[1].HasUsage || byID[1].UsedBytes != 300 || byID[2].HasUsage {
		t.Fatalf("流量只应出现在有 vnstat 的服务器: %+v", byID[1])
	}
	if byID[1].Bandwidth == nil || *byID[1].Bandwidth != 100 || byID[2].Bandwidth == nil || *byID[2].Bandwidth != 0 || byID[3].Bandwidth != nil {
		t.Fatal("列表应保留额度数值，并区分零额度和未设置")
	}
	if l, _, err := ServerList(ctx, db, "", "abnormal", "", false, at, 1, 10); err != nil || len(l) != 0 {
		t.Fatalf("无异常部署位: %v %v", err, l)
	}
}
