package web

import (
	"encoding/json"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

func TestBindingSaveSyncsOnlyBoundRecord(t *testing.T) {
	for _, mode := range []string{"切换", "新建", "失败重试", "已同步"} {
		t.Run(mode, func(t *testing.T) {
			fail := mode == "失败重试"
			calls := 0
			cf := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
				calls++
				if !strings.HasSuffix(r.URL.Path, "/batch") {
					t.Error("非预期请求", r.URL.Path)
				}
				var batch map[string][]map[string]any
				if err := json.NewDecoder(r.Body).Decode(&batch); err != nil {
					t.Error(err)
				}
				key := "patches"
				if mode == "新建" {
					key = "posts"
				}
				if len(batch[key]) != 1 || batch[key][0]["name"] != "custom.example.com" || batch[key][0]["content"] != "192.0.2.2" {
					t.Error("应只推送定制记录", batch)
				}
				if fail {
					w.WriteHeader(502)
					fmt.Fprint(w, `{"success":false,"errors":[{"message":"unavailable"}]}`)
					return
				}
				if mode == "新建" {
					fmt.Fprint(w, `{"success":true,"result":{"posts":[{"id":"created"}]}}`)
				} else {
					fmt.Fprint(w, `{"success":true,"result":{}}`)
				}
			}))
			defer cf.Close()
			_, a := newTestServer(t)
			field, _ := crypto.New(crypto.DeriveKey("binding-test-secret", "field"))
			s, err := New(a.DB, a, field)
			if err != nil {
				t.Fatal(err)
			}
			s.DNSBaseURL = cf.URL
			token, _ := field.Encrypt(`{"apiToken":"test-token"}`)
			if _, err := a.DB.Exec(`INSERT INTO dns_provider_config(id,provider_type,status,display_name,credential_json) VALUES(1,'CLOUDFLARE','ENABLED','cf',?)`, token); err != nil {
				t.Fatal(err)
			}
			for _, q := range []string{
				`INSERT INTO tag(id,name) VALUES(1,'t')`,
				`INSERT INTO account(id,uuid,auth_code,account_no,period_type,bandwidth) VALUES(7,'u7','code7','a7','MONTHLY',0)`,
				`INSERT INTO account_tag VALUES(7,1)`,
				`INSERT INTO server(id,ip) VALUES(1,'192.0.2.1'),(2,'192.0.2.2')`,
				`INSERT INTO node(id,name,type,protocol,port) VALUES(1,'test',0,'vless',443)`,
				`INSERT INTO node_tag VALUES(1,1)`,
				`INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,1,2,1)`,
				`INSERT INTO domain(id,domain,dns_provider_config_id,dns_binding_extension_json) VALUES(1,'example.com',1,'{"zoneId":"z1"}')`,
				`INSERT INTO domain_dns_record(id,domain_id,external_record_id,name,full_name,type,content,status,server_id,ttl) VALUES(1,1,'e1','custom','custom.example.com','A','192.0.2.1','SYNCED',1,1),(2,1,'e2','other','other.example.com','A','192.0.2.1','PENDING_UPDATE',1,1)`,
				`INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,domain_dns_record_id,enabled) VALUES(5,7,1,1,1)`,
				`INSERT INTO account_node_subscription_placement VALUES(5,1)`,
				`INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json) VALUES(1,2,2,1,'x','{"type":0,"disabled":0,"protocol":"vless"}')`,
			} {
				if _, err := a.DB.Exec(q); err != nil {
					t.Fatal(q, err)
				}
			}
			h := s.Handler()
			c := login(t, h, "admin@example.com")
			form := url.Values{"csrf": {a.CSRFToken(c.Value)}, "binding_id": {"5"}, "placement_id": {"2"}, "record_id": {"1"}, "enabled": {"1"}}
			if mode == "新建" {
				a.DB.Exec(`UPDATE account_node_subscription_domain_binding SET domain_dns_record_id=NULL WHERE id=5`)
				a.DB.Exec(`DELETE FROM domain_dns_record WHERE id=1`)
				form.Del("record_id")
				form.Set("new_domain_id", "1")
				form.Set("new_name", "custom")
			}
			if mode == "已同步" {
				a.DB.Exec(`UPDATE domain_dns_record SET server_id=2,content='192.0.2.2' WHERE id=1`)
			}
			save := func() string {
				w := do(h, "POST", "/admin/accounts/7/bindings", form, c, nil)
				if w.Code != 303 {
					t.Fatalf("status=%d", w.Code)
				}
				u, _ := url.Parse(w.Header().Get("Location"))
				return u.Query().Get("err")
			}
			msg := save()
			var status string
			a.DB.QueryRow(`SELECT status FROM domain_dns_record WHERE full_name='custom.example.com'`).Scan(&status)
			if fail {
				if status != "PENDING_UPDATE" || !strings.Contains(msg, "已保存") || !strings.Contains(msg, "DNS") {
					t.Fatal("失败应保留并提示", status, msg)
				}
				fail = false
				if msg = save(); msg != "" {
					t.Fatal(msg)
				}
				a.DB.QueryRow(`SELECT status FROM domain_dns_record WHERE full_name='custom.example.com'`).Scan(&status)
			}
			if status != "SYNCED" || msg != "" {
				t.Fatal("保存后应同步", status, msg)
			}
			account, err := store.AccountByID(t.Context(), a.DB, 7)
			if err != nil {
				t.Fatal(err)
			}
			nodes, err := store.SubscriptionNodes(t.Context(), a.DB, account, nil)
			if err != nil || len(nodes) != 1 || nodes[0]["serverHost"] != "custom.example.com" {
				t.Fatalf("同步后订阅应使用定制域名：%v %v", nodes, err)
			}
			want := 1
			if mode == "失败重试" {
				want = 2
			}
			if mode == "已同步" {
				want = 0
			}
			if calls != want {
				t.Fatalf("calls=%d want=%d", calls, want)
			}
			a.DB.QueryRow(`SELECT status FROM domain_dns_record WHERE id=2`).Scan(&status)
			if status != "PENDING_UPDATE" {
				t.Fatal("不应推送其他记录", status)
			}
		})
	}
}
