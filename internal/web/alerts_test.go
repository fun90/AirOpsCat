package web

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/store"
)

func TestAlertsEndToEnd(t *testing.T) {
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
	count := func() int { mu.Lock(); defer mu.Unlock(); return len(sent) }

	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, _ := New(a.DB, a, field)
	h := s.Handler()
	ctx := context.Background()
	key, _ := field.Encrypt("devkey")
	a.DB.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.bark.url',?,'bark'),('airopscat.bark.device-key',?,'bark'),('airopscat.bark.default-group','G','bark')`, bark.URL, key)
	soon := time.Now().In(store.Shanghai).Format("2006-01-02")
	a.DB.Exec(`INSERT INTO server(id,ip,name,expire_date) VALUES(1,'1.1.1.1','HK',?)`, soon)

	if err := s.scanAlerts(ctx); err != nil {
		t.Fatal(err)
	}
	if count() != 1 || sent[0]["group"] != "G" || !strings.Contains(sent[0]["title"].(string), "到期提醒") || sent[0]["device_key"] != "devkey" {
		t.Fatalf("应发送一次到期通知: %v", sent)
	}
	s.scanAlerts(ctx)
	if count() != 1 {
		t.Fatal("节流内不应重复发送")
	}

	// 连接数超限：普通超限只记录；≥2 倍即时推送，条件消失后恢复且默认不通知
	a.DB.Exec(`INSERT INTO account(id,account_no,remark,uuid,auth_code,period_type,from_date) VALUES(1,'acc1','Anna','u','ac','MONTHLY','2026-01-01 00:00:00')`)
	connStats := func(total int64) map[string]guard.Stats {
		return map[string]guard.Stats{"acc1": {AccountNo: "acc1", Connections: total, IPs: 2, ActiveNodes: 1, Blocked: &guard.BlockedEntry{Reason: "connections", Total: total, Limit: 5}}}
	}
	if err := s.alertConnections(ctx, connStats(6)); err != nil || count() != 1 {
		t.Fatalf("普通连接数超限不应推送: %v %v", sent, err)
	}
	if err := s.alertConnections(ctx, connStats(10)); err != nil {
		t.Fatal(err)
	}
	if count() != 2 || !strings.Contains(sent[1]["body"].(string), "Anna") || sent[1]["level"] != "timeSensitive" || sent[0]["level"] != "active" {
		t.Fatalf("连接数告警: %v", sent)
	}
	s.alertConnections(ctx, map[string]guard.Stats{})
	if count() != 2 {
		t.Fatal("恢复默认不通知")
	}
	var st string
	a.DB.QueryRow(`SELECT status FROM alert_state WHERE alert_type='account-connection-limit'`).Scan(&st)
	if st != "RECOVERED" {
		t.Fatal(st)
	}

	// IP 超限：普通超限只记录和累计，不推送；≥2 倍立即推送；次日摘要列出累计时长
	ipStats := func(total int64) map[string]guard.Stats {
		return map[string]guard.Stats{"acc1": {AccountNo: "acc1", IPs: total, ActiveIPs: total, Blocked: &guard.BlockedEntry{Reason: "ips", Total: total, Limit: 3}}}
	}
	s.alertConnections(ctx, ipStats(4))
	// 恢复后很快再次触发（抖动）：仍受节流，不重复推送
	s.alertConnections(ctx, connStats(10))
	s.alertConnections(ctx, ipStats(4))
	if count() != 2 {
		t.Fatalf("普通 IP 超限不应推送: %v", sent)
	}
	s.alertConnections(ctx, ipStats(6))
	if count() != 2 {
		t.Fatalf("节流内极端 IP 超限也不应重复推送: %v", sent)
	}
	a.DB.Exec(`UPDATE alert_state SET last_notified_time=? WHERE alert_type='account-connection-limit'`, store.FormatTime(time.Now().Add(-2*time.Hour)))
	s.alertConnections(ctx, ipStats(6))
	if count() != 3 || !strings.Contains(sent[2]["title"].(string), "IP 数超限") {
		t.Fatalf("极端 IP 超限应推送: %v", sent)
	}
	var secs int64
	a.DB.QueryRow(`SELECT seconds FROM guard_ip_overlap_daily WHERE account_no='acc1' AND kind='ips'`).Scan(&secs)
	if secs != 40 {
		t.Fatalf("每日累计秒数 = %d", secs)
	}
	a.DB.Exec(`UPDATE guard_ip_overlap_daily SET day=?`, store.FormatTime(time.Now().AddDate(0, 0, -1))[:10])
	if err := s.ipOverlapDigest(ctx); err != nil || count() != 4 || !strings.Contains(sent[3]["body"].(string), "acc1（Anna）IP 累计 1 分钟，峰值 6/3") || !strings.Contains(sent[3]["body"].(string), "acc1（Anna）连接数 累计 1 分钟，峰值 10/5") {
		t.Fatalf("每日摘要: %v %v", sent, err)
	}
	s.alertConnections(ctx, map[string]guard.Stats{})

	// 页面：确认、恢复、测试发送，均需 CSRF
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	post := func(p string) *httptest.ResponseRecorder { return do(h, "POST", p, url.Values{"csrf": {csrf}}, c, nil) }
	if w := do(h, "POST", "/admin/alerts/test", url.Values{}, c, nil); w.Code != 403 {
		t.Fatalf("无 CSRF: %d", w.Code)
	}
	if w := do(h, "GET", "/admin/alerts?status=ACTIVE", nil, c, nil); w.Code != 200 || !strings.Contains(w.Body.String(), "服务器到期") {
		t.Fatalf("列表 %d", w.Code)
	}
	var id int64
	a.DB.QueryRow(`SELECT id FROM alert_state WHERE alert_type='server-expiring'`).Scan(&id)
	if w := post("/admin/alerts/" + itoa(id) + "/ack"); strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatal(w.Header().Get("Location"))
	}
	a.DB.QueryRow(`SELECT status FROM alert_state WHERE id=?`, id).Scan(&st)
	if st != store.AlertAcknowledged {
		t.Fatal(st)
	}
	if w := post("/admin/alerts/" + itoa(id) + "/ack"); !strings.Contains(w.Header().Get("Location"), "err=") {
		t.Fatal("重复确认应提示")
	}
	if w := post("/admin/alerts/test"); strings.Contains(w.Header().Get("Location"), "err=") || count() != 5 {
		t.Fatalf("测试发送 %s %d", w.Header().Get("Location"), count())
	}
	a.DB.Exec(`UPDATE server SET expire_date='2030-01-01' WHERE id=1`)
	s.scanAlerts(ctx)
	a.DB.QueryRow(`SELECT status FROM alert_state WHERE id=?`, id).Scan(&st)
	if st != store.AlertRecovered {
		t.Fatalf("续费后应自动恢复: %s", st)
	}
}

func itoa(n int64) string { return strconv.FormatInt(n, 10) }

func TestQuotaAlertMentionsNativeLimitOnlyWhenEnabled(t *testing.T) {
	var mu sync.Mutex
	var bodies []string
	bark := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		mu.Lock()
		bodies = append(bodies, m["body"].(string))
		mu.Unlock()
	}))
	defer bark.Close()
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, _ := New(a.DB, a, field)
	key, _ := field.Encrypt("devkey")
	a.DB.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.bark.url',?,'bark'),('airopscat.bark.device-key',?,'bark')`, bark.URL, key)
	a.DB.Exec(`INSERT INTO account(id,account_no,remark,uuid,auth_code,period_type,from_date,bandwidth) VALUES(1,'acc1','Anna','u','ac','MONTHLY','2026-01-01 00:00:00',1)`)
	a.DB.Exec(`INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota,download_bytes) VALUES(1,'2000-01-01 00:00:00','2099-01-01 00:00:00',1,?)`, int64(2)<<30)
	if err := s.scanAlerts(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(bodies) != 1 || strings.Contains(bodies[0], "sing-box") {
		t.Fatal(bodies)
	}
	a.DB.Exec(`DELETE FROM alert_state`)
	a.DB.Exec(`INSERT OR REPLACE INTO system_config(config_key,config_value,group_key) VALUES('airopscat.ratelimit.enabled','true','account')`)
	if err := s.scanAlerts(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(bodies) != 2 || !strings.Contains(bodies[1], "sing-box 原生超额限速") {
		t.Fatal(bodies)
	}
}

type recordRemote struct{ cmds *[]string }

func (r recordRemote) Run(_ context.Context, cmd string) (string, string, int, error) {
	*r.cmds = append(*r.cmds, cmd)
	return "", "", 0, nil
}
func (recordRemote) WriteFile(context.Context, string, []byte) error { return nil }
func (recordRemote) Close() error                                    { return nil }

func TestSurgeAlertRotatePortsEntry(t *testing.T) {
	_, a := newTestServer(t)
	s, _ := New(a.DB, a, nil)
	h := s.Handler()
	ctx := context.Background()
	var cmds []string
	s.Deployer.Prepare = nil
	s.Deployer.Config = func(context.Context, int64) ([]byte, error) { return []byte("{}"), nil }
	s.Deployer.Dial = func(context.Context, *store.SSHTarget) (deploy.Remote, error) { return recordRemote{&cmds}, nil }
	a.DB.Exec(`INSERT INTO server(id,ip,name,auth_type,username,auth) VALUES(1,'1.1.1.1','HK','PASSWORD','root','x')`)
	a.DB.Exec(`INSERT INTO node (id, name, type, protocol, port) VALUES (1, 'r', 0, 'vless-reality', 8000), (2, 'p', 0, 'vless', 8001)`)
	a.DB.Exec(`INSERT INTO node_server (node_id, server_id, deployed) VALUES (1, 1, 1), (2, 1, 1)`)
	surge, _, err := store.AlertTrigger(ctx, a.DB, store.AlertKey{Type: alertMonitorLoad, ResourceType: "server", ResourceID: 1, ResourceKey: "1.1.1.1", Fingerprint: surgeFingerprint + "1"}, "warning", 150, 100, "HK 疑似被刷", time.Hour, time.Now())
	if err != nil {
		t.Fatal(err)
	}
	cpu, _, _ := store.AlertTrigger(ctx, a.DB, store.AlertKey{Type: alertMonitorLoad, ResourceType: "server", ResourceID: 1, ResourceKey: "1.1.1.1", Fingerprint: "cpu:1"}, "warning", 0.95, 0.9, "HK CPU", time.Hour, time.Now())

	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	list := func() string { return do(h, "GET", "/admin/alerts", nil, c, nil).Body.String() }
	page := list()
	if strings.Count(page, "/admin/alerts/"+itoa(surge)+"/rotate-ports") != 2 || strings.Contains(page, "/admin/alerts/"+itoa(cpu)+"/rotate-ports") || strings.Contains(page, "/failover") {
		t.Fatal("只有流量防刷告警提供换端口封禁（PC 与移动端各一）")
	}
	if !strings.Contains(page, `href="/admin/monitor/1"`) || !strings.Contains(page, `href="/admin/servers/1/edit"`) {
		t.Fatal("服务器类告警应提供监控与服务器跳转")
	}
	post := func(id int64) string {
		return do(h, "POST", "/admin/alerts/"+itoa(id)+"/rotate-ports", url.Values{"csrf": {csrf}}, c, nil).Header().Get("Location")
	}
	if loc := post(cpu); !strings.Contains(loc, "err=") {
		t.Fatal("非防刷告警不应可处置", loc)
	}
	if loc, _ := url.QueryUnescape(post(surge)); !strings.Contains(loc, "msg=") || !strings.Contains(loc, "已提交后台部署 1 台") {
		t.Fatal(loc)
	}
	s.deployQ.wait(5 * time.Second)
	eventually(t, "部署完成后释放处置锁", func() bool { _, busy := s.rotating.Load(int64(1)); return !busy })
	var p1, p2 int
	var status, by string
	a.DB.QueryRow(`SELECT port FROM node WHERE id=1`).Scan(&p1)
	a.DB.QueryRow(`SELECT port FROM node WHERE id=2`).Scan(&p2)
	a.DB.QueryRow(`SELECT status,coalesce(acknowledged_by,'') FROM alert_state WHERE id=?`, surge).Scan(&status, &by)
	if p1 == 8000 || p2 != 8001 || status != store.AlertAcknowledged || by != "admin@example.com" {
		t.Fatalf("应只给 REALITY 节点换端口并确认告警: p1=%d p2=%d status=%s by=%s", p1, p2, status, by)
	}
	joined := strings.Join(cmds, "\n")
	for _, want := range []string{"iptables -I INPUT -p tcp --dport 8000 -j DROP", "iptables -I INPUT -p udp --dport 8000 -j DROP", "systemctl restart sing-box"} {
		if !strings.Contains(joined, want) {
			t.Fatalf("缺少命令 %q:\n%s", want, joined)
		}
	}
	if strings.Index(joined, "systemctl restart") > strings.Index(joined, "--dport 8000 -j DROP") {
		t.Fatal("应先部署新端口再封禁旧端口")
	}
	if !strings.Contains(list(), "/admin/alerts/"+itoa(surge)+"/rotate-ports") {
		t.Fatal("已确认但未恢复时仍可再次换端口（刷量追上新端口的情况）")
	}
	store.AlertRecoverManual(ctx, a.DB, surge, time.Now())
	if loc := post(surge); !strings.Contains(loc, "err=") {
		t.Fatal("已恢复告警不应可处置", loc)
	}
	if w := do(h, "POST", "/admin/alerts/999/rotate-ports", url.Values{"csrf": {csrf}}, c, nil); w.Code != 404 {
		t.Fatalf("不存在的告警: %d", w.Code)
	}
}
