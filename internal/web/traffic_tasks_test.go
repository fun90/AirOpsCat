package web

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
)

func TestCollectTrafficFailureStillChecksLimitsAndDNS(t *testing.T) {
	_, a := newTestServer(t)
	field, err := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	if err != nil {
		t.Fatal(err)
	}
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	var sent []map[string]any
	bark := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		var message map[string]any
		if err := json.NewDecoder(r.Body).Decode(&message); err != nil {
			t.Error(err)
		}
		sent = append(sent, message)
	}))
	defer bark.Close()
	key, err := field.Encrypt("test-key")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.bark.url',?,'bark'),('airopscat.bark.device-key',?,'bark');`, bark.URL, key); err != nil {
		t.Fatal(err)
	}
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip) VALUES(1,'203.0.113.1'),(2,'203.0.113.2');
 INSERT INTO node(id,name,type,protocol,port) VALUES(1,'n',0,'vless',443);
 INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,1,1),(1,2,1)`); err != nil {
		t.Fatal(err)
	}
	cfg, err := s.barkConfig(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if cfg.URL != bark.URL || cfg.DeviceKey != "test-key" {
		t.Fatal("Bark 测试配置不匹配")
	}
	s.TrafficServers = "all"
	s.DNSAutoDomains = "invalid"
	var prepared []int64
	s.Deployer.Prepare = func(_ context.Context, id int64) (*deploy.Prepared, error) {
		prepared = append(prepared, id)
		return nil, errors.New("限速准备探针")
	}
	// 缺少 SSH 登录凭据使两台采集立即失败，无真实 SSH 请求。
	err = s.collectTraffic(context.Background())
	if err == nil || !strings.Contains(err.Error(), "服务器 1") || !strings.Contains(err.Error(), "服务器 2") || !strings.Contains(err.Error(), "限速准备探针") || !strings.Contains(err.Error(), "域名") {
		t.Fatal(err)
	}
	if len(prepared) != 2 {
		t.Fatal("限速检查被采集失败阻断", prepared)
	}
	if len(sent) != 1 || !strings.Contains(sent[0]["body"].(string), "失败: 2") || strings.Contains(sent[0]["body"].(string), "密钥") {
		t.Fatal(sent)
	}
	// 无目标时不执行后续检查或发通知。
	s.TrafficServers = ""
	if err := s.collectTraffic(context.Background()); err != nil {
		t.Fatal(err)
	}
	if len(sent) != 1 || len(prepared) != 2 {
		t.Fatal("空集合仍触发检查")
	}
}
