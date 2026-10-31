package web

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"os/exec"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/store"
)

// probeRemote 在本机 bash 执行探测命令，模拟远端探测机。
type probeRemote struct{ cmds []string }

func (p *probeRemote) Run(_ context.Context, c string) (string, string, int, error) {
	p.cmds = append(p.cmds, c)
	out, _ := exec.Command("bash", "-c", c).Output()
	return string(out), "", 0, nil
}
func (p *probeRemote) WriteFile(context.Context, string, []byte) error { return nil }
func (p *probeRemote) Close() error                                    { return nil }

func TestParseProbe(t *testing.T) {
	for out, want := range map[string]probeResult{
		"\n0 35":     {OK: true, Ms: 35, Msg: "可达"},
		"\n124 5003": {Msg: "超时"},
		"bash: connect: Connection refused\nbash: line 1: /dev/tcp/1.2.3.4/1: Connection refused\n\n1 3": {Msg: "拒绝连接"},
		"bash: connect: No route to host\n\n1 10":                                                        {Msg: "网络不可达"},
		"garbage": {Msg: "探测输出无法解析"},
	} {
		if got := parseProbe(probeResult{}, out); got != want {
			t.Errorf("%q: got %+v want %+v", out, got, want)
		}
	}
}

func TestPlacementProbe(t *testing.T) {
	if _, err := exec.LookPath("timeout"); err != nil {
		t.Skip("本机缺少 coreutils timeout，探测命令只在 Linux 探测机上运行")
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	open := ln.Addr().(*net.TCPAddr).Port
	closed, _ := net.Listen("tcp", "127.0.0.1:0")
	closedPort := closed.Addr().(*net.TCPAddr).Port
	closed.Close()

	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.EnsureSettings(context.Background(), a.DB); err != nil {
		t.Fatal(err)
	}
	remote := &probeRemote{}
	s.Deployer.Dial = func(context.Context, *store.SSHTarget) (deploy.Remote, error) { return remote, nil }
	for _, q := range []string{
		`INSERT INTO server(id,ip,name) VALUES(1,'127.0.0.1','目标'),(2,'192.0.2.9','电信探测')`,
		fmt.Sprintf(`INSERT INTO node(id,name,type,protocol,port) VALUES(1,'开',0,'vless',%d),(2,'关',0,'vless',%d),(3,'h2',0,'hysteria2',443)`, open, closedPort),
		`INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,2,1,1),(3,3,1,1)`,
	} {
		if _, err := a.DB.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	form := url.Values{"csrf": {a.CSRFToken(c.Value)}}
	if w := do(h, "POST", "/admin/placements/1/probe", form, c, nil); w.Code != 422 {
		t.Fatal("未配置探测服务器应拒绝", w.Code)
	}
	if err := store.SaveSettings(context.Background(), a.DB, field, map[string]string{store.ProbeServersKey: "x"}); err == nil {
		t.Fatal("非法 ID 应拒绝")
	}
	if err := store.SaveSettings(context.Background(), a.DB, field, map[string]string{store.ProbeServersKey: "2"}); err != nil {
		t.Fatal(err)
	}
	if w := do(h, "GET", "/admin/nodes/1/edit", nil, c, nil); !strings.Contains(w.Body.String(), `data-tcp-probe="1"`) {
		t.Fatal("配置后应显示检测按钮")
	}
	if w := do(h, "POST", "/admin/placements/3/probe", form, c, nil); w.Code != 422 {
		t.Fatal("hysteria2 不应检测", w.Code)
	}
	for id, want := range map[string]string{"1": "可达", "2": "拒绝连接"} {
		w := do(h, "POST", "/admin/placements/"+id+"/probe", form, c, nil)
		var res []probeResult
		if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &res) != nil || len(res) != 1 || res[0].Name != "电信探测" || res[0].Msg != want {
			t.Fatalf("部署位 %s: %d %s", id, w.Code, w.Body.String())
		}
	}
}
