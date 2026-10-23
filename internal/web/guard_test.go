package web

import (
	"bytes"
	"compress/gzip"
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/store"
)

func guardServer(t *testing.T) *Server {
	t.Helper()
	db, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "guard.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	field, err := crypto.New(crypto.DeriveKey("guard-tests-have-a-real-field-key", "field"))
	if err != nil {
		t.Fatal(err)
	}
	token, err := field.Encrypt("test-token")
	if err != nil {
		t.Fatal(err)
	}
	for _, query := range []string{`INSERT INTO server(id,name,ip) VALUES (1,'node','203.0.113.1')`, `INSERT INTO account(account_no,uuid,auth_code,period_type,max_connections) VALUES ('A','a','a','MONTHLY',1)`, `INSERT INTO node(id,name,type) VALUES (1,'n',0)`, `INSERT INTO node_server(node_id,server_id,deployed) VALUES (1,1,1)`} {
		if _, err := db.Exec(query); err != nil {
			t.Fatal(err)
		}
	}
	if _, err := db.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES (?,?,'guard')`, store.GuardTokenKey, token); err != nil {
		t.Fatal(err)
	}
	return &Server{DB: db, Field: field, Guard: guard.New(db)}
}
func guardCall(s *Server, body []byte, token, encoding string) *httptest.ResponseRecorder {
	r := httptest.NewRequest(http.MethodPost, "/api/open/guard-sync", bytes.NewReader(body))
	r.Header.Set("Token", token)
	r.Header.Set("Content-Encoding", encoding)
	w := httptest.NewRecorder()
	s.guardSync(w, r)
	return w
}
func zipGuard(t *testing.T, data []byte) []byte {
	t.Helper()
	var buffer bytes.Buffer
	writer := gzip.NewWriter(&buffer)
	if _, err := writer.Write(data); err != nil {
		t.Fatal(err)
	}
	if err := writer.Close(); err != nil {
		t.Fatal(err)
	}
	return buffer.Bytes()
}
func TestGuardSyncTokenAndTrustBoundary(t *testing.T) {
	s := guardServer(t)
	valid := []byte(`{"nodeIp":"203.0.113.1","accounts":[{"accountNo":"A","connections":3}]}`)
	for _, test := range []struct {
		name            string
		body            []byte
		token, encoding string
		status          int
	}{
		{"missing token", valid, "", "", 401}, {"bad token", valid, "bad", "", 401},
		{"malformed", []byte(`{`), "test-token", "", 400}, {"null", []byte(`null`), "test-token", "", 400},
		{"missing node", []byte(`{}`), "test-token", "", 400}, {"unknown node", []byte(`{"nodeIp":"203.0.113.2"}`), "test-token", "", 403},
		{"invalid node", []byte(`{"nodeIp":"example.com"}`), "test-token", "", 400},
		{"trailing JSON", append(append([]byte{}, valid...), []byte(`{}`)...), "test-token", "", 400},
		{"bad type", []byte(`{"nodeIp":"203.0.113.1","accounts":{}}`), "test-token", "", 400},
		{"gzip broken", []byte("broken"), "test-token", "gzip", 400},
		{"unsupported encoding", valid, "test-token", "br", 415},
		{"compressed limit", bytes.Repeat([]byte(" "), guardCompressedLimit+1), "test-token", "", 413},
		{"decoded limit", zipGuard(t, bytes.Repeat([]byte(" "), guardDecodedLimit+1)), "test-token", "gzip", 413},
	} {
		t.Run(test.name, func(t *testing.T) {
			w := guardCall(s, test.body, test.token, test.encoding)
			if w.Code != test.status {
				t.Fatalf("%d %s", w.Code, w.Body.String())
			}
		})
	}
	truncated := zipGuard(t, valid)
	truncated = truncated[:len(truncated)-4]
	if w := guardCall(s, truncated, "test-token", "gzip"); w.Code != 400 {
		t.Fatal(w.Code)
	}
	stats, err := s.Guard.Evaluate(context.Background())
	if err != nil || len(stats) != 0 {
		t.Fatal("非法请求修改聚合状态", stats, err)
	}
	field := s.Field
	s.Field = nil
	if w := guardCall(s, valid, "test-token", ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
	s.Field = field
	for _, value := range []string{"", "test-token", "v1:broken"} {
		s.DB.Exec(`UPDATE system_config SET config_value=? WHERE config_key=?`, value, store.GuardTokenKey)
		if w := guardCall(s, valid, "test-token", ""); w.Code != 401 {
			t.Fatal(value, w.Code)
		}
	}
	empty, _ := field.Encrypt(" ")
	s.DB.Exec(`UPDATE system_config SET config_value=?`, empty)
	if w := guardCall(s, valid, " ", ""); w.Code != 401 {
		t.Fatal(w.Code)
	}
}
func TestGuardSyncGzipResponseAndOnline(t *testing.T) {
	s := guardServer(t)
	payload := []byte(`{"nodeIp":"203.0.113.1","generatedAtEpochSeconds":1,"accounts":[{"accountNo":"A","connections":3},{"accountNo":"unknown","connections":9}],"onlineAccountIps":[{"accountNo":"A","nodeTag":"node_1","clientIps":["198.51.100.1"]},{"accountNo":"unknown","clientIps":["bad"]}]}`)
	for i := 0; i < 5; i++ {
		w := guardCall(s, zipGuard(t, payload), "test-token", "gzip")
		if w.Code != 200 {
			t.Fatal(w.Code, w.Body.String())
		}
	}
	ctx := context.Background()
	s.Guard.Evaluate(ctx)
	s.Guard.Evaluate(ctx)
	s.Guard.Evaluate(ctx)
	w := guardCall(s, payload, "test-token", "")
	if w.Code != 200 {
		t.Fatal(w.Code, w.Body.String())
	}
	var response guard.Response
	if err := json.Unmarshal(w.Body.Bytes(), &response); err != nil {
		t.Fatal(err)
	}
	if response.SchemaVersion != 1 || response.TTLSeconds != 30 || len(response.BlockedAccounts) != 1 || response.BlockedAccounts["A"].Reason != "connections" {
		t.Fatal(response)
	}
	var accountNo, start, last string
	var id int64
	if err := s.DB.QueryRow(`SELECT account_no,node_id,session_start_time,last_online_time FROM account_online_ip`).Scan(&accountNo, &id, &start, &last); err != nil || accountNo != "A" || id != 1 || start == "" || last == "" {
		t.Fatal(accountNo, id, start, last, err)
	}
	var count int
	s.DB.QueryRow(`SELECT count(*) FROM account_online_ip`).Scan(&count)
	if count != 1 {
		t.Fatal(count)
	}
	w = guardCall(s, []byte(`{"nodeIp":"203.0.113.1","accounts":[]}`), "test-token", "")
	if w.Code != 200 || !strings.Contains(w.Body.String(), `"blockedAccounts":{}`) {
		t.Fatal(w.Code, w.Body.String())
	}
	s.DB.QueryRow(`SELECT count(*) FROM account_online_ip`).Scan(&count)
	if count != 1 {
		t.Fatal("空报告删除了历史")
	}
}
