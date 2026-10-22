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

func TestMonitorTrafficDecimalGB(t *testing.T) {
	at := time.Date(2026, 10, 22, 10, 35, 0, 0, store.Shanghai)
	raw := `{"interfaces":[{"name":"eth0","traffic":{"month":[{"date":{"year":2026,"month":10},"rx":10569535770,"tx":10479734167}]}}]}`
	v, ok, err := monitor.CollectVnstat(context.Background(), &vnstatFixtureRemote{raw: raw}, at)
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
	if got := newStatusView(list[0], at).UsageText; got != "21.0 / 100 GB（21.0%）" {
		t.Fatalf("应与十进制 GB 一致，实际 %q", got)
	}
	list[0].BandwidthGB = 0
	if got := newStatusView(list[0], at).UsageText; got != "21.0 GB" {
		t.Fatalf("未设额度时也应使用十进制 GB，实际 %q", got)
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
		return `{"interfaces":[{"name":"eth0","traffic":{"month":[{"date":{"year":` + time.Now().In(store.Shanghai).Format("2006") + `,"month":` + time.Now().In(store.Shanghai).Format("1") + `},"rx":` + "900000000000" + `,"tx":0}]}}]}`, "", 0, nil
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
	// 十进制 900 GB / 900 GB 额度 = 100% → 流量告警并通知
	mu.Lock()
	n := len(sent)
	mu.Unlock()
	if n != 1 || !strings.Contains(sent[0]["body"].(string), "累计流量达到 100% (900.00 GB / 900 GB)") {
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
	if w := do(h, "GET", "/admin/monitor", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "HK") {
		t.Fatalf("监控页 %d", w.Code)
	}
	if w := do(h, "GET", "/admin/monitor/1", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "<polyline") {
		t.Fatalf("详情页 %d", w.Code)
	}
	if w := do(h, "GET", "/admin/monitor/999", nil, c, nil); w.Code != 404 {
		t.Fatal(w.Code)
	}
}
