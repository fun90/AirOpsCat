package web

import (
	"context"
	"encoding/json"
	"fmt"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
	"net/http"
	"net/http/httptest"
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
	exec(`UPDATE server_metrics SET rx_bytes=0 WHERE server_id=1`)
	if err := s.autoDNS(ctx); err != nil {
		t.Fatal(err)
	}
	a.DB.QueryRow(`SELECT content,coalesce(failover_home_content,'') FROM domain_dns_record WHERE id=1`).Scan(&content, &home)
	if content != "1.1.1.1" || home != "" || posts != 2 {
		t.Fatal("回切失败", content, home, posts)
	}
	if err := s.autoDNS(ctx); err != nil || posts != 2 {
		t.Fatal("幂等失败", posts, err)
	}
	// 待更新记录不会推送。
	exec(`UPDATE domain_dns_record SET status='PENDING_UPDATE',schedule_enabled=1,schedule_start='00:00',schedule_end='00:00',schedule_content_in='3.3.3.3',schedule_content_out='4.4.4.4' WHERE id=1`)
	if err := s.autoDNS(ctx); err != nil || posts != 2 {
		t.Fatal(err, posts)
	}
	exec(`UPDATE domain_dns_record SET status='SYNCED' WHERE id=1`)
	if err := s.autoDNS(ctx); err != nil || posts != 3 {
		t.Fatal(err, posts)
	}
	if target != "3.3.3.3" {
		t.Fatal(target)
	}
	// 防止只测试 planner：实际时段保存与重新加载。
	r, _ := store.DNSRecordByID(ctx, a.DB, 1)
	r.ScheduleStart = "bad"
	if _, err := store.SaveDNSRecord(ctx, a.DB, r); err == nil {
		t.Fatal("非法时间未拒绝")
	}
}
