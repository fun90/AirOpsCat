package deploy

import (
	"context"
	"strings"
	"testing"
)

func TestDeployUnblocksFirewallAndConfirms(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, false)
	exec(t, db, `INSERT INTO server_unblocked_port(server_id,port,create_time) VALUES(?,8000,'2026-01-01 00:00:00')`, sid)
	h := &fakeHost{}
	d := newTestDeployer(db, h)
	res := d.DeployServers(context.Background(), []int64{sid}, false)[0]
	if !res.Success {
		t.Fatal(res)
	}
	if count(t, db, `SELECT count(*) FROM server_unblocked_port WHERE server_id=?`, sid) != 0 {
		t.Fatal("远端解封成功后需清除待执行动作")
	}
	cmd := strings.Join(h.cmds, "\n")
	for _, want := range []string{"while iptables -C INPUT -p tcp --dport 8000 -j DROP", "while iptables -C INPUT -p udp --dport 8000 -j DROP", "iptables-save"} {
		if !strings.Contains(cmd, want) {
			t.Fatalf("缺少幂等解封命令 %q: %s", want, cmd)
		}
	}
}

func TestDeployUnblockFailureKeepsPending(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, false)
	exec(t, db, `INSERT INTO server_unblocked_port(server_id,port,create_time) VALUES(?,8000,'2026-01-01 00:00:00')`, sid)
	h := &fakeHost{failOn: "while iptables -C"}
	res := newTestDeployer(db, h).DeployServers(context.Background(), []int64{sid}, false)[0]
	if !res.Success || res.Warning == "" || count(t, db, `SELECT count(*) FROM server_unblocked_port WHERE server_id=?`, sid) != 1 {
		t.Fatalf("配置成功但远端解封失败需保留重试: %+v", res)
	}
}
