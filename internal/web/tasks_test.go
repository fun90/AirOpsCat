package web

import (
	"context"
	"errors"
	"path/filepath"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/store"
)

func TestExpireAccountsDeploysEachServer(t *testing.T) {
	ctx := context.Background()
	db, err := store.Open(ctx, filepath.Join(t.TempDir(), "e.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(db, auth.NewService(db, []byte("k"), []byte("c")), field)
	if err != nil {
		t.Fatal(err)
	}
	down := true
	s.Deployer.Dial = func(_ context.Context, tg *store.SSHTarget) (deploy.Remote, error) {
		if tg.ID == 1 && down {
			return nil, errors.New("dial down")
		}
		return &reloadHost{}, nil
	}
	if _, err := db.Exec(`INSERT INTO server(id,ip,name) VALUES(1,'203.0.113.1','a'),(2,'203.0.113.2','b');
 INSERT INTO tag(id,name) VALUES(1,'t');
 INSERT INTO node(id,name,type,port,protocol,core_type,inbound) VALUES(1,'n1',0,443,'vless','sing-box','{"type":"vless"}'),(2,'n2',0,443,'vless','sing-box','{"type":"vless"}');
 INSERT INTO node_tag VALUES(1,1),(2,1);
 INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,2,2,1);
 INSERT INTO account(id,uuid,account_no,auth_code,period_type,from_date,to_date) VALUES(1,'u1','A1','c1','MONTHLY','2020-01-01 00:00:00','2020-02-01 00:00:00');
 INSERT INTO account_tag VALUES(1,1)`); err != nil {
		t.Fatal(err)
	}
	err = s.expireAccounts(ctx)
	if err == nil || !strings.Contains(err.Error(), "a：") {
		t.Fatal("应报告失败的服务器", err)
	}
	var a, b, disabled int
	db.QueryRow(`SELECT deployed FROM node_server WHERE id=1`).Scan(&a)
	db.QueryRow(`SELECT deployed FROM node_server WHERE id=2`).Scan(&b)
	db.QueryRow(`SELECT disabled FROM account WHERE id=1`).Scan(&disabled)
	if disabled != 1 || a != 0 || b != 1 {
		t.Fatalf("一台失败不应影响另一台：disabled=%d a=%d b=%d", disabled, a, b)
	}
	status := func() (st string) {
		db.QueryRow(`SELECT coalesce(group_concat(resource_id||':'||status),'') FROM alert_state WHERE alert_type=?`, alertDeployFail).Scan(&st)
		return
	}
	if st := status(); st != "1:ACTIVE" {
		t.Fatal("失败的服务器应告警", st)
	}
	down = false
	if res := s.Deployer.DeployServers(ctx, []int64{1}, true); !res[0].Success {
		t.Fatal(res[0].Message)
	}
	if st := status(); st != "1:RECOVERED" {
		t.Fatal("部署成功后告警应恢复", st)
	}
}
