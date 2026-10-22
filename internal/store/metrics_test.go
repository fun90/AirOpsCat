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
	if hk.Name != "HK" || hk.CPU == nil || *hk.CPU != 0.5 || !hk.HasVnstat || hk.UsageRatio() < 0.536 || hk.UsageRatio() > 0.537 {
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
	if n := AlertActiveCount(ctx, db); n != 1 {
		t.Fatalf("memory 告警不应受影响: %d", n)
	}
}
