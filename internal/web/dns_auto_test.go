package web

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

func TestAutoDNSReconcile(t *testing.T) {
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, _ := New(a.DB, a, field)
	s.NotifyDisabled = true
	s.DNSAutoDomains = "1"
	ctx := context.Background()
	target := "1.1.1.1"
	posts := 0
	uncertain := true
	cf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == "POST" {
			posts++
			var b struct{ Patches []struct{ Content string } }
			json.NewDecoder(r.Body).Decode(&b)
			target = b.Patches[0].Content
			if uncertain {
				fmt.Fprint(w, `{"success":false}`)
				return
			}
			fmt.Fprint(w, `{"success":true,"result":{}}`)
			return
		}
		fmt.Fprintf(w, `{"success":true,"result":[{"id":"ext1","name":"a.example.com","type":"A","content":%q}],"result_info":{"total_pages":1}}`, target)
	}))
	defer cf.Close()
	s.DNSBaseURL = cf.URL
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := a.DB.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	token, _ := field.Encrypt(`{"apiToken":"t"}`)
	exec(`INSERT INTO dns_provider_config(id,provider_type,status,display_name,credential_json)VALUES(1,'CLOUDFLARE','ENABLED','cf',?)`, token)
	exec(`INSERT INTO domain(id,domain,dns_provider_config_id,dns_binding_extension_json)VALUES(1,'example.com',1,'{"zoneId":"z1"}')`)
	exec(`INSERT INTO server(id,ip,bandwidth)VALUES(1,'1.1.1.1',100),(2,'2.2.2.2',100)`)
	exec(`INSERT INTO node(id,name,type,protocol,core_type)VALUES(1,'n',0,'vless','sing-box')`)
	exec(`INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,ttl,status,external_record_id)VALUES(1,1,'a','a.example.com','A','1.1.1.1',300,'SYNCED','ext1')`)
	exec(`INSERT INTO node_server(id,node_id,server_id,deployed,access_host_id)VALUES(1,1,1,1,1),(2,1,2,1,NULL)`)
	now := time.Now().In(store.Shanghai)
	for id, used := range map[int]int64{1: 95 << 30, 2: 10 << 30} {
		exec(`INSERT INTO server_metrics(server_id,kind,sample_time,period_year,period_month,rx_bytes,tx_bytes)VALUES(?,'vnstat',?,?,?,?,0)`, id, store.FormatTime(now), now.Year(), int(now.Month()), used)
	}
	if err := s.autoDNS(ctx); err == nil {
		t.Fatal("未知请求结果应报错")
	}
	var content string
	a.DB.QueryRow(`SELECT content FROM domain_dns_record WHERE id=1`).Scan(&content)
	if content != "1.1.1.1" || target != "2.2.2.2" {
		t.Fatal(content, target)
	}
	if _, err := a.DB.Exec(`UPDATE domain_dns_record SET content='3.3.3.3' WHERE id=1`); err == nil {
		t.Fatal("操作尚待对账时不允许人工覆盖")
	}
	// 远端仍返回旧值：不能证明超时请求已结束，保持未决且不重发。
	target = "1.1.1.1"
	if err := s.autoDNS(ctx); err == nil {
		t.Fatal("旧值仍应保持待核查")
	}
	var pending int
	a.DB.QueryRow(`SELECT count(*) FROM dns_auto_operation`).Scan(&pending)
	if pending != 1 || posts != 1 {
		t.Fatal(pending, posts)
	}
	target = "2.2.2.2"
	// 模拟重启后使用同一数据库：持久化操作仍可恢复，不重复推送。
	restarted, _ := New(a.DB, a, field)
	restarted.DNSBaseURL = cf.URL
	restarted.DNSAutoDomains = "" // 关闭自动化后仍需对账已有操作
	restarted.NotifyDisabled = true
	if err := restarted.autoDNS(ctx); err != nil {
		t.Fatal(err)
	}
	var home string
	a.DB.QueryRow(`SELECT content,failover_home_content FROM domain_dns_record WHERE id=1`).Scan(&content, &home)
	if content != "2.2.2.2" || home != "1.1.1.1" || posts != 1 {
		t.Fatal(content, home, posts)
	}
	uncertain = false
	// 缺少候选时不能静默跳过已有回切状态。
	exec(`UPDATE node_server SET deployed=0`)
	if err := s.autoDNS(ctx); err != nil {
		t.Fatal(err)
	}
	var alerts, logs int
	if err := a.DB.QueryRow(`SELECT count(*) FROM alert_state WHERE alert_type='dns-reset-blocked' AND status='ACTIVE'`).Scan(&alerts); err != nil {
		t.Fatal(err)
	}
	if err := a.DB.QueryRow(`SELECT count(*) FROM system_log WHERE category='ALERT' AND detail='dns-reset-blocked'`).Scan(&logs); err != nil {
		t.Fatal(err)
	}
	if alerts != 1 || logs == 0 || posts != 1 {
		t.Fatalf("缺少候选应告警且不推送: alerts=%d logs=%d posts=%d", alerts, logs, posts)
	}
	// 曾部署的待部署位仍承载流量，应该可以完成回切。
	exec(`INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,disabled)VALUES(1,1,1,1,'test',0),(1,2,2,1,'test',0)`)
	for _, state := range []string{"disabled", "pending-delete"} {
		exec(`UPDATE node_deployment SET disabled=1`)
		if state == "pending-delete" {
			exec(`UPDATE node_deployment SET disabled=0`)
			exec(`UPDATE node_server SET deployed=2`)
		}
		candidates, err := store.DNSCandidates(ctx, a.DB, now)
		if err != nil || len(candidates) != 0 {
			t.Fatalf("%s 不应参与自动 DNS: %+v %v", state, candidates, err)
		}
	}
	exec(`UPDATE node_server SET deployed=0`)
	exec(`UPDATE node_deployment SET disabled=0`)
	exec(`UPDATE server_metrics SET rx_bytes=0 WHERE server_id=1`)
	if err := s.autoDNS(ctx); err != nil {
		t.Fatal(err)
	}
	a.DB.QueryRow(`SELECT content,coalesce(failover_home_content,'') FROM domain_dns_record WHERE id=1`).Scan(&content, &home)
	if content != "1.1.1.1" || home != "" || posts != 2 {
		t.Fatal("回切失败", content, home, posts)
	}
	if err := a.DB.QueryRow(`SELECT count(*) FROM alert_state WHERE alert_type='dns-reset-blocked' AND status='ACTIVE'`).Scan(&alerts); err != nil || alerts != 0 {
		t.Fatal("候选恢复后应解除告警", alerts, err)
	}
	if err := s.autoDNS(ctx); err != nil || posts != 2 {
		t.Fatal("幂等失败", posts, err)
	}
	// 历史时段配置不再生效，待更新记录仍不会推送。
	exec(`UPDATE domain_dns_record SET status='PENDING_UPDATE',schedule_enabled=1,schedule_start='00:00',schedule_end='00:00',schedule_content_in='3.3.3.3',schedule_content_out='4.4.4.4' WHERE id=1`)
	if err := s.autoDNS(ctx); err != nil || posts != 2 {
		t.Fatal(err, posts)
	}
	exec(`UPDATE domain_dns_record SET status='SYNCED' WHERE id=1`)
	if err := s.autoDNS(ctx); err != nil || posts != 2 {
		t.Fatal(err, posts)
	}
	if target != "1.1.1.1" {
		t.Fatal("历史时段配置不应改变当前解析", target)
	}
}

func TestAutoDNSMergesSwitchNotifications(t *testing.T) {
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, _ := New(a.DB, a, field)
	s.DNSAutoDomains = "1"
	ctx := context.Background()
	var pushes []map[string]any
	bark := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var m map[string]any
		json.NewDecoder(r.Body).Decode(&m)
		pushes = append(pushes, m)
	}))
	defer bark.Close()
	cf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"success":true,"result":{}}`)
	}))
	defer cf.Close()
	s.DNSBaseURL = cf.URL
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := a.DB.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	key, _ := field.Encrypt("devkey")
	exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.bark.url',?,'bark'),('airopscat.bark.device-key',?,'bark')`, bark.URL, key)
	token, _ := field.Encrypt(`{"apiToken":"t"}`)
	exec(`INSERT INTO dns_provider_config(id,provider_type,status,display_name,credential_json)VALUES(1,'CLOUDFLARE','ENABLED','cf',?)`, token)
	exec(`INSERT INTO domain(id,domain,dns_provider_config_id,dns_binding_extension_json)VALUES(1,'example.com',1,'{"zoneId":"z1"}')`)
	exec(`INSERT INTO server(id,ip,bandwidth)VALUES(1,'1.1.1.1',100),(2,'2.2.2.2',100)`)
	exec(`INSERT INTO node(id,name,type,protocol,core_type)VALUES(1,'n1',0,'vless','sing-box'),(2,'n2',0,'vless','sing-box')`)
	exec(`INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,ttl,status,external_record_id)VALUES(1,1,'a','a.example.com','A','1.1.1.1',300,'SYNCED','ext1'),(2,1,'b','b.example.com','A','1.1.1.1',300,'SYNCED','ext2')`)
	exec(`INSERT INTO node_server(id,node_id,server_id,deployed,access_host_id)VALUES(1,1,1,1,1),(2,1,2,1,NULL),(3,2,1,1,2),(4,2,2,1,NULL)`)
	now := time.Now().In(store.Shanghai)
	for id, used := range map[int]int64{1: 95 << 30, 2: 10 << 30} {
		exec(`INSERT INTO server_metrics(server_id,kind,sample_time,period_year,period_month,rx_bytes,tx_bytes)VALUES(?,'vnstat',?,?,?,?,0)`, id, store.FormatTime(now), now.Year(), int(now.Month()), used)
	}
	if err := s.autoDNS(ctx); err != nil {
		t.Fatal(err)
	}
	if len(pushes) != 1 {
		t.Fatalf("同一轮切换应合并为一条推送: %v", pushes)
	}
	body, _ := pushes[0]["body"].(string)
	if !strings.Contains(pushes[0]["title"].(string), "2 条") || !strings.Contains(body, "a.example.com") || !strings.Contains(body, "b.example.com") {
		t.Fatalf("合并内容不完整: %v", pushes[0])
	}
}
