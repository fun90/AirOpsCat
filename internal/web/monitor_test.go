package web

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/monitor"
	"github.com/fun90/airopscat/internal/store"
)

func TestMonitorTrafficBinaryGB(t *testing.T) {
	at := time.Date(2026, 10, 22, 10, 35, 0, 0, store.Shanghai)
	raw := `{"interfaces":[{"name":"eth0","traffic":{"month":[{"date":{"year":2026,"month":10},"rx":10569535770,"tx":10479734167}]}}]}`
	v, ok, err := monitor.CollectVnstat(context.Background(), &vnstatFixtureRemote{raw: raw}, at, 1)
	if err != nil || !ok {
		t.Fatalf("采集失败: %v, ok=%v", err, ok)
	}
	_, a := newTestServer(t)
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name,bandwidth) VALUES(1,'192.0.2.1','测试服务器',100)`); err != nil {
		t.Fatal(err)
	}
	if err := store.InsertVnstatSample(context.Background(), a.DB, 1, v, at); err != nil {
		t.Fatal(err)
	}
	list, _, err := store.ServerStatuses(context.Background(), a.DB, "", 1, 20, at)
	if err != nil || len(list) != 1 {
		t.Fatalf("读取失败: %v, %+v", err, list)
	}
	if got := newStatusView(list[0], at).UsageText; got != "19.6 / 100 GiB" {
		t.Fatalf("应与1024 进制 GB 一致，实际 %q", got)
	}
	if got := newStatusView(list[0], at).PeriodText; got != "2026-10-01 至 2026-10-31" {
		t.Fatalf("周期应为自然月，实际 %q", got)
	}
	list[0].BandwidthGB = 0
	if got := newStatusView(list[0], at).UsageText; got != "19.6 GiB" {
		t.Fatalf("未设额度时也应使用1024 进制 GB，实际 %q", got)
	}
	// 重置日 24 号：10-22 属于 9-24 起的周期，不应沿用自然月样本
	if _, err := a.DB.Exec(`UPDATE server SET bandwidth_date='2026-04-24 00:00:00' WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	list, _, err = store.ServerStatuses(context.Background(), a.DB, "", 1, 20, at)
	if err != nil || list[0].HasVnstat || list[0].ResetDay != 24 {
		t.Fatalf("不应取到其他周期的样本: %v %+v", err, list[0])
	}
	if err := store.InsertVnstatSample(context.Background(), a.DB, 1, monitor.Vnstat{Iface: "eth0", Year: 2026, Month: 9, RxBytes: 5 << 30}, at); err != nil {
		t.Fatal(err)
	}
	list, _, _ = store.ServerStatuses(context.Background(), a.DB, "", 1, 20, at)
	if v := newStatusView(list[0], at); !list[0].HasVnstat || v.UsageText != "5.0 / 100 GiB" || v.PeriodText != "2026-09-24 至 2026-10-23" {
		t.Fatalf("应按重置日取周期：%+v", v)
	}
}

type vnstatFixtureRemote struct{ raw string }

func (f *vnstatFixtureRemote) Run(context.Context, string) (string, string, int, error) {
	return f.raw, "", 0, nil
}

type fakeRemote struct{ cmds []string }

func (f *fakeRemote) Run(_ context.Context, cmd string) (string, string, int, error) {
	f.cmds = append(f.cmds, cmd)
	if cmd == monitor.VnstatCommand {
		return `{"interfaces":[{"name":"eth0","traffic":{"month":[{"date":{"year":` + time.Now().In(store.Shanghai).Format("2006") + `,"month":` + time.Now().In(store.Shanghai).Format("1") + `},"rx":` + "966367641600" + `,"tx":0}]}}]}`, "", 0, nil
	}
	return "cpu 0 0 10 100\nnet 0 0 1000 2000\nelapsed 1000\nmem 1000 500", "", 0, nil
}
func (f *fakeRemote) WriteFile(context.Context, string, []byte) error { return nil }
func (f *fakeRemote) Close() error                                    { return nil }

func TestMonitorCollectAndAlerts(t *testing.T) {
	var mu sync.Mutex
	var sent []map[string]any
	bark := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		mu.Lock()
		sent = append(sent, m)
		mu.Unlock()
	}))
	defer bark.Close()
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, _ := New(a.DB, a, field)
	ctx := context.Background()
	key, _ := field.Encrypt("devkey")
	a.DB.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.bark.url',?,'bark'),('airopscat.bark.device-key',?,'bark')`, bark.URL, key)
	a.DB.Exec(`INSERT INTO server(id,ip,name,auth_type,username,auth,bandwidth) VALUES(1,'1.1.1.1','HK','PASSWORD','root','x',900),(2,'2.2.2.2','US','PASSWORD','root','x',100)`)
	remote := &fakeRemote{}
	var dialed []int64
	s.Deployer.Dial = func(_ context.Context, tg *store.SSHTarget) (deploy.Remote, error) {
		dialed = append(dialed, tg.ID)
		return remote, nil
	}

	if err := s.collectMonitor(ctx); err != nil || len(dialed) != 0 {
		t.Fatalf("未配置监控服务器不应连接: %v %v", err, dialed)
	}
	s.MonitorServers = "1"
	if err := s.collectMonitor(ctx); err != nil {
		t.Fatal(err)
	}
	if err := s.collectVnstat(ctx); err != nil {
		t.Fatal(err)
	}
	if len(dialed) != 2 || dialed[0] != 1 || dialed[1] != 1 {
		t.Fatalf("只应连接选中的服务器: %v", dialed)
	}
	list, _, _ := store.ServerStatuses(ctx, a.DB, "", 1, 20, time.Now())
	hk := list[0]
	if hk.CPU == nil || *hk.CPU != 0.9 || !hk.HasVnstat {
		t.Fatalf("采集结果未入库: %+v", hk)
	}
	// 1024 进制 900 GB / 900 GB 额度 = 100% → 流量告警并通知
	mu.Lock()
	n := len(sent)
	mu.Unlock()
	if n != 1 || !strings.Contains(sent[0]["body"].(string), "累计流量达到 100% (900.00 GiB / 900 GiB)") {
		t.Fatalf("应发出一条流量告警: %v", sent)
	}

	// CPU：窗口内持续超阈值才告警；样本不足既不告警也不恢复历史告警
	a.DB.Exec(`DELETE FROM server_metrics WHERE kind='monitor'`)
	a.DB.Exec(`INSERT INTO alert_state(alert_type,resource_type,resource_id,fingerprint,status,trigger_count,create_time) VALUES('server-monitor-load','server',1,'cpu:1','ACTIVE',1,'2026-10-01 00:00:00')`)
	s.evaluateServerAlerts(ctx)
	var st string
	a.DB.QueryRow(`SELECT status FROM alert_state WHERE fingerprint='cpu:1'`).Scan(&st)
	if st != "ACTIVE" {
		t.Fatalf("无数据不应恢复历史告警: %s", st)
	}
	now := time.Now()
	for i := 0; i <= 16; i++ {
		store.InsertMonitorSample(ctx, a.DB, 1, monitor.Sample{CPU: 0.95, Memory: 0.2}, now.Add(-time.Duration(i)*2*time.Minute))
	}
	if err := s.evaluateServerAlerts(ctx); err != nil {
		t.Fatal(err)
	}
	a.DB.QueryRow(`SELECT status FROM alert_state WHERE fingerprint='cpu:1'`).Scan(&st)
	mu.Lock()
	n = len(sent)
	mu.Unlock()
	if st != "ACTIVE" || n != 2 || !strings.Contains(sent[1]["body"].(string), "CPU使用率连续30分钟达到 95%") {
		t.Fatalf("持续超阈值应通知: %s %v", st, sent)
	}
	var memAlerts int
	a.DB.QueryRow(`SELECT count(*) FROM alert_state WHERE fingerprint='memory:1'`).Scan(&memAlerts)
	if memAlerts != 0 {
		t.Fatal("内存正常不应告警")
	}
	// 恢复：窗口内出现低值
	store.InsertMonitorSample(ctx, a.DB, 1, monitor.Sample{CPU: 0.1}, now.Add(-time.Second))
	s.evaluateServerAlerts(ctx)
	a.DB.QueryRow(`SELECT status FROM alert_state WHERE fingerprint='cpu:1'`).Scan(&st)
	if st != "RECOVERED" {
		t.Fatalf("应恢复: %s", st)
	}

	// 页面
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	if w := do(h, "GET", "/admin/monitor", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "HK") || !strings.Contains(w.Body.String(), time.Now().In(store.Shanghai).Format("2006-01")+"-01 至 ") {
		t.Fatalf("监控页 %d", w.Code)
	}
	if w := do(h, "GET", "/admin/monitor/1", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "<polyline") {
		t.Fatalf("详情页 %d", w.Code)
	}
	if w := do(h, "GET", "/admin/monitor/999", nil, c, nil); w.Code != 404 {
		t.Fatal(w.Code)
	}
}

func TestMonitorTrafficDecimalGB(t *testing.T) {
	at := time.Date(2026, 10, 22, 10, 35, 0, 0, store.Shanghai)
	_, a := newTestServer(t)
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name,bandwidth,bandwidth_unit) VALUES(1,'192.0.2.1','服务商按GB',2000,'GB')`); err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO server_metrics(server_id,kind,sample_time,period_year,period_month,rx_bytes,tx_bytes) VALUES(1,'vnstat',?,2026,10,1000000000000,500000000000)`, store.FormatTime(at)); err != nil {
		t.Fatal(err)
	}
	list, _, err := store.ServerStatuses(context.Background(), a.DB, "", 1, 20, at)
	if err != nil || len(list) != 1 {
		t.Fatalf("读取失败: %v, %+v", err, list)
	}
	if got := newStatusView(list[0], at).UsageText; got != "1500.0 / 2000 GB" {
		t.Fatalf("GB 应按 10⁹ 字节计，实际 %q", got)
	}
	if r := list[0].UsageRatio(); r < 0.7499 || r > 0.7501 {
		t.Fatalf("占比应为 75%%，实际 %v", r)
	}
}

func TestTrafficSurgeAlert(t *testing.T) {
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, _ := New(a.DB, a, field)
	ctx := context.Background()
	a.DB.Exec(`INSERT INTO server(id,ip,name) VALUES(1,'1.1.1.1','HK')`)
	now := time.Now()
	at := now.In(store.Shanghai)
	ins := func(gib int64, ago time.Duration) {
		t.Helper()
		if err := store.InsertVnstatSample(ctx, a.DB, 1, monitor.Vnstat{Iface: "eth0", Year: at.Year(), Month: int(at.Month()), RxBytes: gib << 30}, now.Add(-ago)); err != nil {
			t.Fatal(err)
		}
	}
	state := func() (n int, status string) {
		a.DB.QueryRow(`SELECT count(*),coalesce(max(status),'') FROM alert_state WHERE fingerprint='traffic-surge:1'`).Scan(&n, &status)
		return
	}
	// 24 小时仅增长 20 GiB：不告警
	ins(100, 24*time.Hour)
	ins(120, time.Minute)
	if err := s.evaluateServerAlerts(ctx); err != nil {
		t.Fatal(err)
	}
	if n, _ := state(); n != 0 {
		t.Fatal("正常增长不应告警")
	}
	// 增长到 220 GiB：超过默认 100 GiB 阈值
	ins(220, 30*time.Second)
	if err := s.evaluateServerAlerts(ctx); err != nil {
		t.Fatal(err)
	}
	if n, st := state(); n != 1 || st != "ACTIVE" {
		t.Fatalf("应触发流量异常告警: %d %s", n, st)
	}
	// 窗口内样本不足以覆盖 24 小时时不恢复
	a.DB.Exec(`DELETE FROM server_metrics WHERE kind='vnstat'`)
	ins(300, time.Hour)
	ins(301, time.Minute)
	s.evaluateServerAlerts(ctx)
	if _, st := state(); st != "ACTIVE" {
		t.Fatalf("窗口未覆盖不应恢复: %s", st)
	}
	// 窗口被覆盖且增量回落：恢复
	ins(300, 23*time.Hour+50*time.Minute)
	s.evaluateServerAlerts(ctx)
	if _, st := state(); st != "RECOVERED" {
		t.Fatalf("应恢复: %s", st)
	}
}
