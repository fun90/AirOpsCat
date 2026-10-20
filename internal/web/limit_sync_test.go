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
	sync(false) // 进程启动后无记录，各 reload 一次
	if a, b := count(); a != 1 || b != 1 {
		t.Fatal(a, b)
	}
	sync(false) // 无变化不 reload
	if a, b := count(); a != 1 || b != 1 {
		t.Fatal(a, b)
	}
	// 超额：两台都应进入限速；一台失败后可重试，成功的一台不重复 reload。
	exec(`INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes)
 VALUES(1,'2000-01-01 00:00:00','2099-01-01 00:00:00',1,0,?)`, int64(2)<<30)
	hosts["203.0.113.2"].fail = true
	sync(true)
	if a, b := count(); a != 2 || b != 1 {
		t.Fatal(a, b)
	}
	hosts["203.0.113.2"].fail = false
	sync(false)
	if a, b := count(); a != 2 || b != 2 {
		t.Fatal(a, b)
	}
	// 提高配额后恢复。
	exec(`UPDATE account_traffic_stats SET bandwidth_quota=100`)
	sync(false)
	if a, b := count(); a != 3 || b != 3 {
		t.Fatal(a, b)
	}
}
