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

	// 连接数超限：先告警，条件消失后恢复且默认不通知
	a.DB.Exec(`INSERT INTO account(id,account_no,remark,uuid,auth_code,period_type,from_date) VALUES(1,'acc1','Anna','u','ac','MONTHLY','2026-01-01 00:00:00')`)
	stats := map[string]guard.Stats{"acc1": {AccountNo: "acc1", Connections: 9, IPs: 2, ActiveNodes: 1, Blocked: &guard.BlockedEntry{Reason: "connections", Total: 9, Limit: 5}}}
	if err := s.alertConnections(ctx, stats); err != nil {
		t.Fatal(err)
	}
	if count() != 2 || !strings.Contains(sent[1]["body"].(string), "Anna") {
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
	s.alertConnections(ctx, ipStats(4))
	if count() != 2 {
		t.Fatalf("普通 IP 超限不应推送: %v", sent)
	}
	s.alertConnections(ctx, ipStats(6))
	if count() != 3 || !strings.Contains(sent[2]["title"].(string), "IP 数超限") {
		t.Fatalf("极端 IP 超限应推送: %v", sent)
	}
	var secs int64
	a.DB.QueryRow(`SELECT seconds FROM guard_ip_overlap_daily WHERE account_no='acc1'`).Scan(&secs)
	if secs != 30 {
		t.Fatalf("每日累计秒数 = %d", secs)
	}
	a.DB.Exec(`UPDATE guard_ip_overlap_daily SET day=?`, store.FormatTime(time.Now().AddDate(0, 0, -1))[:10])
	if err := s.ipOverlapDigest(ctx); err != nil || count() != 4 || !strings.Contains(sent[3]["body"].(string), "acc1（Anna）累计 1 分钟，峰值 6/3") {
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
