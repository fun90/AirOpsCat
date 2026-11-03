package web

import (
	"context"
	"fmt"
	"strings"
	"sync"
	"testing"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/store"
)

// fakeProbe 按「探测机 → 目标端口 → 是否可达」返回 probeCmd 风格输出，不执行真实命令。
type fakeProbe struct {
	mu   sync.Mutex
	up   map[int64]map[int]bool
	cmds map[int64][]string
}

type fakeProbeRemote struct {
	f  *fakeProbe
	id int64
}

func (r fakeProbeRemote) Run(_ context.Context, c string) (string, string, int, error) {
	r.f.mu.Lock()
	defer r.f.mu.Unlock()
	r.f.cmds[r.id] = append(r.f.cmds[r.id], c)
	for port, ok := range r.f.up[r.id] {
		if strings.Contains(c, fmt.Sprintf("/%d;", port)) || strings.Contains(c, fmt.Sprintf("/%d'", port)) {
			if ok {
				return "\n0 30", "", 0, nil
			}
			return "\n124 5000", "", 0, nil
		}
	}
	return "", "", 0, fmt.Errorf("未知目标")
}
func (fakeProbeRemote) WriteFile(context.Context, string, []byte) error { return nil }
func (fakeProbeRemote) Close() error                                    { return nil }

func TestProbePlacementsTask(t *testing.T) {
	ctx := context.Background()
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.EnsureSettings(ctx, a.DB); err != nil {
		t.Fatal(err)
	}
	f := &fakeProbe{cmds: map[int64][]string{}, up: map[int64]map[int]bool{
		8: {1001: true, 1002: false, 1003: true},
		9: {1001: true, 1002: false, 1003: false},
	}}
	s.Deployer.Dial = func(_ context.Context, t *store.SSHTarget) (deploy.Remote, error) {
		return fakeProbeRemote{f, t.ID}, nil
	}
	for _, q := range []string{
		`INSERT INTO server(id,ip,name) VALUES(1,'192.0.2.1','东京'),(2,'192.0.2.2','禁用机'),(8,'198.51.100.8','电信探测'),(9,'198.51.100.9','联通探测')`,
		`UPDATE server SET disabled=1 WHERE id=2`,
		`INSERT INTO node(id,name,type,protocol,port,inbound) VALUES(1,'vless',0,'vless',1001,NULL),(2,'h2',0,'hysteria2',1002,'{"obfs":{"type":"salamander","password":"p"}}'),
			(3,'ss',0,'shadowsocks',1003,NULL),(4,'落地',1,'vless',1004,NULL),(5,'未部署',0,'vless',1005,NULL)`,
		`INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,2,1,1),(3,3,1,1),(4,4,1,1),(5,5,1,0),(6,1,2,1)`,
		`INSERT INTO server_metrics(server_id,kind,sample_time,singbox_down) VALUES(1,'monitor','2026-11-02 10:00:00',0)`,
	} {
		if _, err := a.DB.Exec(q); err != nil {
			t.Fatal(q, err)
		}
	}
	if err := store.SaveSettings(ctx, a.DB, field, map[string]string{store.ProbeServersKey: "8,9"}); err != nil {
		t.Fatal(err)
	}
	alerts := func() (n int, summary string) {
		a.DB.QueryRow(`SELECT count(*),coalesce(max(summary),'') FROM alert_state WHERE alert_type=? AND status='ACTIVE'`, alertUnreachable).Scan(&n, &summary)
		return
	}
	for round := 1; round <= store.ProbeFailRounds; round++ {
		if n, _ := alerts(); n != 0 {
			t.Fatalf("第 %d 轮前不应告警", round)
		}
		if err := s.probePlacements(ctx); err != nil {
			t.Fatal(err)
		}
	}
	// 落地、未部署、禁用服务器上的部署位不检测；hysteria2 走 UDP
	if c := f.cmds[8]; len(c) != 3*store.ProbeFailRounds || !strings.Contains(strings.Join(c, ""), "/dev/udp/192.0.2.1/1002") {
		t.Fatalf("检测范围不正确: %d", len(c))
	}
	// h2 两台都失败满 3 轮才告警；ss 只有联通失败，不告警
	if n, summary := alerts(); n != 1 || summary != "东京 1 个部署位国内不可达" {
		t.Fatalf("告警不正确: %d %q", n, summary)
	}
	var fails int
	a.DB.QueryRow(`SELECT fails FROM placement_probe WHERE placement_id=3 AND probe_server_id=9`).Scan(&fails)
	if fails != store.ProbeFailRounds {
		t.Fatal("连续失败数不正确", fails)
	}

	h := s.Handler()
	c := login(t, h, "admin@example.com")
	if w := do(h, "GET", "/admin/servers", nil, c, nil); !strings.Contains(w.Body.String(), "1 个部署位不可达") {
		t.Fatal("服务器列表应显示不可达数")
	}
	if w := do(h, "GET", "/admin/nodes/2/edit", nil, c, nil); !strings.Contains(w.Body.String(), "联通探测 超时 ×3") {
		t.Fatal("节点页应显示定时检测结果")
	}
	// 列表：h2 红色不可达标记，ss 只有一台失败为黄色；下拉逐部署位列出各探测机结果；「国内不可达」筛选只含 h2
	list := do(h, "GET", "/admin/nodes", nil, c, nil).Body.String()
	if !strings.Contains(list, `<span class="text-red" title="1 个部署位国内不可达">`) || !strings.Contains(list, `<span class="text-yellow" title="1 个部署位检测偶发失败">`) ||
		!strings.Contains(list, `UDP</span>`) || !strings.Contains(list, `联通探测 超时 ×3</span>`) || !strings.Contains(list, `电信探测 30ms</span>`) {
		t.Fatal("节点列表应显示检测标记与明细")
	}
	filtered := do(h, "GET", "/admin/nodes?status=3", nil, c, nil).Body.String()
	if !strings.Contains(filtered, `/admin/nodes/2/edit`) || strings.Contains(filtered, `/admin/nodes/3/edit`) || strings.Contains(filtered, `/admin/nodes/1/edit`) {
		t.Fatal("国内不可达筛选不正确")
	}

	// 恢复一台探测机即恢复告警，失败计数清零
	f.up[8][1002] = true
	if err := s.probePlacements(ctx); err != nil {
		t.Fatal(err)
	}
	if n, _ := alerts(); n != 0 {
		t.Fatal("恢复后应消警")
	}

	// 探测机全部目标失败视为其自身异常：丢弃并清空其结果
	f.up[9] = map[int]bool{}
	for i := 1001; i <= 1010; i++ {
		f.up[9][i] = false
		if i > 1003 {
			if _, err := a.DB.Exec(`INSERT INTO node(id,name,type,protocol,port) VALUES(?,?,0,'vless',?)`, i, fmt.Sprint(i), i); err != nil {
				t.Fatal(err)
			}
			if _, err := a.DB.Exec(`INSERT INTO node_server(node_id,server_id,deployed) VALUES(?,1,1)`, i); err != nil {
				t.Fatal(err)
			}
			f.up[8][i] = true
		}
	}
	if err := s.probePlacements(ctx); err == nil || !strings.Contains(err.Error(), "疑似探测机自身网络异常") {
		t.Fatal("应报告探测机异常", err)
	}
	var left int
	a.DB.QueryRow(`SELECT count(*) FROM placement_probe WHERE probe_server_id=9`).Scan(&left)
	if left != 0 {
		t.Fatal("异常探测机的结果应清空", left)
	}

	// 移出配置后结果全部清理
	if err := store.SaveSettings(ctx, a.DB, field, map[string]string{store.ProbeServersKey: ""}); err != nil {
		t.Fatal(err)
	}
	if err := s.probePlacements(ctx); err != nil {
		t.Fatal(err)
	}
	a.DB.QueryRow(`SELECT count(*) FROM placement_probe`).Scan(&left)
	if left != 0 {
		t.Fatal("清空配置后应清理结果", left)
	}
}

// 落地部署位由引用它的代理服务器拨测（仅 TCP），全部失败满轮数后按落地类型单独告警；不依赖国内探测机配置。
func TestProbeLandingPlacements(t *testing.T) {
	ctx := context.Background()
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	if err := store.EnsureSettings(ctx, a.DB); err != nil {
		t.Fatal(err)
	}
	f := &fakeProbe{cmds: map[int64][]string{}, up: map[int64]map[int]bool{
		1: {2001: false},
		2: {2001: false},
	}}
	s.Deployer.Dial = func(_ context.Context, t *store.SSHTarget) (deploy.Remote, error) {
		return fakeProbeRemote{f, t.ID}, nil
	}
	for _, q := range []string{
		`INSERT INTO server(id,ip,name) VALUES(1,'192.0.2.1','东京'),(2,'192.0.2.2','香港'),(3,'203.0.113.3','落地机')`,
		`INSERT INTO node(id,name,type,protocol,port,out_id) VALUES(1,'p1',0,'vless',1001,3),(2,'p2',0,'vless',1002,3),(3,'ss',1,'shadowsocks',2001,NULL)`,
		`INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,2,2,1),(3,3,3,1)`,
	} {
		if _, err := a.DB.Exec(q); err != nil {
			t.Fatal(q, err)
		}
	}
	for i := 0; i < store.ProbeFailRounds; i++ {
		if err := s.probePlacements(ctx); err != nil {
			t.Fatal(err)
		}
	}
	if len(f.cmds[1]) != store.ProbeFailRounds || len(f.cmds[2]) != store.ProbeFailRounds || strings.Contains(strings.Join(f.cmds[1], ""), "/dev/udp") {
		t.Fatalf("代理服务器应只做落地 TCP 检测: %v", f.cmds)
	}
	var n int
	var summary string
	a.DB.QueryRow(`SELECT count(*),coalesce(max(summary),'') FROM alert_state WHERE alert_type=? AND status='ACTIVE'`, alertLandingUnreachable).Scan(&n, &summary)
	if n != 1 || summary != "落地机 1 个部署位落地不可达" {
		t.Fatalf("落地告警不正确: %d %q", n, summary)
	}
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	if w := do(h, "GET", "/admin/servers", nil, c, nil); !strings.Contains(w.Body.String(), "1 个落地不可达") || strings.Contains(w.Body.String(), "个部署位不可达") {
		t.Fatal("服务器列表应单独显示落地不可达数")
	}
	if w := do(h, "GET", "/admin/nodes?status=4", nil, c, nil).Body.String(); !strings.Contains(w, "/admin/nodes/3/edit") || strings.Contains(w, "/admin/nodes/1/edit") {
		t.Fatal("落地不可达筛选不正确")
	}
	if w := do(h, "GET", "/admin/nodes?status=3", nil, c, nil).Body.String(); strings.Contains(w, "/admin/nodes/3/edit") {
		t.Fatal("国内不可达筛选不应含落地")
	}
	// 一台代理服务器恢复即消警
	f.up[1][2001] = true
	if err := s.probePlacements(ctx); err != nil {
		t.Fatal(err)
	}
	a.DB.QueryRow(`SELECT count(*) FROM alert_state WHERE alert_type=? AND status='ACTIVE'`, alertLandingUnreachable).Scan(&n)
	if n != 0 {
		t.Fatal("恢复后应消警")
	}
	// 代理节点不再引用落地后结果清理
	if _, err := a.DB.Exec(`UPDATE node SET out_id=NULL`); err != nil {
		t.Fatal(err)
	}
	if err := s.probePlacements(ctx); err != nil {
		t.Fatal(err)
	}
	a.DB.QueryRow(`SELECT count(*) FROM placement_probe`).Scan(&n)
	if n != 0 {
		t.Fatal("无人引用时应清理结果", n)
	}
}
