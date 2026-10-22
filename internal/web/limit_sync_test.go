package web

import (
	"context"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/singbox"
	"github.com/fun90/airopscat/internal/store"
)

type reloadHost struct {
	mu      sync.Mutex
	reloads int
	fail    bool
}

func (h *reloadHost) Run(_ context.Context, cmd string) (string, string, int, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if strings.Contains(cmd, "systemctl reload") {
		if h.fail {
			return "", "boom", 1, nil
		}
		h.reloads++
	}
	return "", "", 0, nil
}
func (h *reloadHost) WriteFile(context.Context, string, []byte) error { return nil }
func (h *reloadHost) Close() error                                    { return nil }

func TestLimitsKeyTracksOnlyQuotaOverrides(t *testing.T) {
	in := &singbox.ServerConfigInput{Nodes: []singbox.NodeInput{{ID: 1, Clients: []singbox.Client{{Name: "A1", DownloadMbps: 50, MaxIPs: 2}}}}}
	if key := limitsKey(in); key != "" {
		t.Fatal("普通限速不应触发超额同步", key)
	}
	in.Nodes[0].Clients[0].QuotaLimited = true
	in.Nodes[0].Clients[0].DownloadMbps = 2
	key := limitsKey(in)
	in.Nodes = append(in.Nodes, singbox.NodeInput{ID: 2, Clients: in.Nodes[0].Clients})
	if key == "" || limitsKey(in) != key {
		t.Fatal("同账户多个部署位应共用超额策略")
	}
	in.Nodes[0].Clients[0].DownloadMbps = 3
	if limitsKey(in) == key {
		t.Fatal("超额限速值变化必须触发同步")
	}
}

func TestSyncLimitsAcrossServers(t *testing.T) {
	ctx := context.Background()
	db, err := store.Open(ctx, filepath.Join(t.TempDir(), "l.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(db, auth.NewService(db, []byte("k"), []byte("c")), field)
	if err != nil {
		t.Fatal(err)
	}
	s.TrafficServers = "all"
	hosts := map[string]*reloadHost{"203.0.113.1": {}, "203.0.113.2": {}}
	s.Deployer.Dial = func(_ context.Context, tg *store.SSHTarget) (deploy.Remote, error) { return hosts[tg.IP], nil }
	exec := func(q string, args ...any) {
		t.Helper()
		if _, err := db.Exec(q, args...); err != nil {
			t.Fatal(err)
		}
	}
	exec(`INSERT OR REPLACE INTO system_config(config_key,config_value,group_key) VALUES('airopscat.ratelimit.enabled','true','t'),('airopscat.account.traffic-over-quota.download-mbps','2','t'),('airopscat.account.traffic-over-quota.upload-mbps','2','t')`)
	exec(`INSERT INTO server(id,ip,name) VALUES(1,'203.0.113.1','a'),(2,'203.0.113.2','b');
 INSERT INTO tag(id,name) VALUES(1,'t');
 INSERT INTO node(id,name,type,port,protocol,core_type,inbound) VALUES(1,'n1',0,443,'vless','sing-box','{"type":"vless"}'),(2,'n2',0,443,'vless','sing-box','{"type":"vless"}');
 INSERT INTO node_tag VALUES(1,1),(2,1);
 INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,2,2,1);
 INSERT INTO account(id,uuid,account_no,auth_code,period_type,from_date,to_date,bandwidth,download_mbps,upload_mbps) VALUES(1,'u1','A1','c1','MONTHLY','2020-01-01 00:00:00','2099-01-01 00:00:00',1,50,50);
 INSERT INTO account_tag VALUES(1,1)`)
	count := func() (int, int) { return hosts["203.0.113.1"].reloads, hosts["203.0.113.2"].reloads }
	sync := func(wantErr bool) {
		t.Helper()
		if err := s.syncLimits(ctx); (err != nil) != wantErr {
			t.Fatal(wantErr, err)
		}
	}
	sync(false) // 没有超额账户，启动后也不应 reload。
	if a, b := count(); a != 0 || b != 0 {
		t.Fatal(a, b)
	}
	sync(false) // 无变化不 reload
	if a, b := count(); a != 0 || b != 0 {
		t.Fatal(a, b)
	}
	// 普通带宽、IP、连接数与部署位变化均不触发超额限速同步。
	exec(`UPDATE account SET download_mbps=60, max_ips=3, max_connections=4`)
	exec(`INSERT INTO node_server(node_id,server_id,deployed) VALUES(2,1,2)`)
	sync(false)
	if a, b := count(); a != 0 || b != 0 {
		t.Fatal(a, b)
	}
	var state int
	if err := db.QueryRow(`SELECT deployed FROM node_server WHERE node_id=2 AND server_id=1`).Scan(&state); err != nil || state != 2 {
		t.Fatalf("后台同步不应应用部署位删除：state=%d err=%v", state, err)
	}
	// 超额：两台都应进入限速；一台失败后可重试，成功的一台不重复 reload。
	exec(`INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes)
 VALUES(1,'2000-01-01 00:00:00','2099-01-01 00:00:00',1,0,?)`, int64(2)<<30)
	hosts["203.0.113.2"].fail = true
	sync(true)
	if a, b := count(); a != 1 || b != 0 {
		t.Fatal(a, b)
	}
	hosts["203.0.113.2"].fail = false
	sync(false)
	if a, b := count(); a != 1 || b != 1 {
		t.Fatal(a, b)
	}
	// 同账户增加部署位不改变超额策略，也不触发自动部署。
	exec(`INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,2,0)`)
	sync(false)
	if a, b := count(); a != 1 || b != 1 {
		t.Fatal(a, b)
	}
	// 提高配额后恢复。
	exec(`UPDATE account_traffic_stats SET bandwidth_quota=100`)
	sync(false)
	if a, b := count(); a != 2 || b != 2 {
		t.Fatal(a, b)
	}
	// 降低配额后再次进入限速。
	exec(`UPDATE account_traffic_stats SET bandwidth_quota=1`)
	sync(false)
	if a, b := count(); a != 3 || b != 3 {
		t.Fatal(a, b)
	}
	// 无新增流量跨入新周期：当前周期结束且无新周期记录，限速恢复。
	exec(`UPDATE account_traffic_stats SET period_end='2001-01-01 00:00:00'`)
	sync(false)
	if a, b := count(); a != 4 || b != 4 {
		t.Fatal(a, b)
	}
	// 未超额账户到期不属于超额限速同步。
	exec(`UPDATE account SET to_date='2001-01-01 00:00:00'`)
	hosts["203.0.113.2"].fail = true
	sync(false)
	if a, b := count(); a != 4 || b != 4 {
		t.Fatal(a, b)
	}
	hosts["203.0.113.2"].fail = false
	sync(false)
	if a, b := count(); a != 4 || b != 4 {
		t.Fatal(a, b)
	}
	sync(false)
	if a, b := count(); a != 4 || b != 4 {
		t.Fatal(a, b)
	}
	// 超额期间关闭策略应解除限速，之后不再重复下发。
	exec(`UPDATE account SET to_date='2099-01-01 00:00:00'`)
	exec(`UPDATE account_traffic_stats SET period_end='2099-01-01 00:00:00'`)
	sync(false)
	if a, b := count(); a != 5 || b != 5 {
		t.Fatal(a, b)
	}
	exec(`UPDATE system_config SET config_value='false' WHERE config_key='airopscat.ratelimit.enabled'`)
	sync(false)
	sync(false)
	if a, b := count(); a != 6 || b != 6 {
		t.Fatal(a, b)
	}
}
