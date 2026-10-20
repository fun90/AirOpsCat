package deploy

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

// fakeHost 记录所有服务器上发生的远端操作；Dial 返回的 Remote 共用它。
type fakeHost struct {
	mu        sync.Mutex
	cmds      []string
	failOn    string // 命令含该子串时返回退出码 1
	delay     time.Duration
	dials     int
	active    int
	maxActive int
}

type fakeRemote struct{ h *fakeHost }

func (h *fakeHost) dial(context.Context, *store.SSHTarget) (Remote, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.dials++
	h.active++
	h.maxActive = max(h.maxActive, h.active)
	return &fakeRemote{h}, nil
}

func (r *fakeRemote) Run(_ context.Context, cmd string) (string, string, int, error) {
	r.h.mu.Lock()
	defer r.h.mu.Unlock()
	r.h.cmds = append(r.h.cmds, cmd)
	if r.h.failOn != "" && strings.Contains(cmd, r.h.failOn) {
		return "", "bad config", 1, nil
	}
	return "", "", 0, nil
}

func (r *fakeRemote) WriteFile(_ context.Context, path string, data []byte) error {
	time.Sleep(r.h.delay)
	r.h.mu.Lock()
	defer r.h.mu.Unlock()
	r.h.cmds = append(r.h.cmds, "WRITE "+path)
	return nil
}

func (r *fakeRemote) Close() error {
	r.h.mu.Lock()
	defer r.h.mu.Unlock()
	r.h.active--
	return nil
}

func newTestDB(t *testing.T) *sql.DB {
	t.Helper()
	db, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "d.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	return db
}

func exec(t *testing.T, db *sql.DB, q string, args ...any) int64 {
	t.Helper()
	res, err := db.Exec(q, args...)
	if err != nil {
		t.Fatalf("%s: %v", q, err)
	}
	id, _ := res.LastInsertId()
	return id
}

func addServer(t *testing.T, db *sql.DB, disabled, external bool) int64 {
	return exec(t, db, `INSERT INTO server (ip, name, ssh_port, auth_type, username, disabled, external_server) VALUES ('203.0.113.1','s1',22,'PASSWORD','root',?,?)`, disabled, external)
}

func addNode(t *testing.T, db *sql.DB, disabled bool, outID any) int64 {
	return exec(t, db, `INSERT INTO node (port, protocol, type, disabled, out_id) VALUES (10001,'vless',0,?,?)`, disabled, outID)
}

func place(t *testing.T, db *sql.DB, nodeID, serverID int64, deployed int) int64 {
	return exec(t, db, `INSERT INTO node_server (node_id, server_id, deployed) VALUES (?,?,?)`, nodeID, serverID, deployed)
}

func newTestDeployer(db *sql.DB, h *fakeHost) *Deployer {
	d := NewDeployer(db, nil, func(context.Context, int64) ([]byte, error) { return []byte(`{"log":{}}`), nil })
	d.Dial = h.dial
	d.now = func() time.Time { return time.UnixMilli(1700000000123) }
	return d
}

func count(t *testing.T, db *sql.DB, q string, args ...any) int {
	t.Helper()
	var n int
	if err := db.QueryRow(q, args...).Scan(&n); err != nil {
		t.Fatal(err)
	}
	return n
}

func TestDeploySuccessSequenceAndState(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, false)
	pending := addNode(t, db, false, nil)    // 待部署 → 已部署
	disabled := addNode(t, db, true, nil)    // 禁用节点保持待部署
	deleted := addNode(t, db, false, nil)    // 待删除，唯一部署位 → 连节点一起删除
	referenced := addNode(t, db, false, nil) // 待删除，但被其他节点出站引用 → 节点保留
	addNode(t, db, false, referenced)        // 引用 referenced（无部署位）
	p1 := place(t, db, pending, sid, 0)
	p2 := place(t, db, disabled, sid, 0)
	place(t, db, deleted, sid, 2)
	place(t, db, referenced, sid, 2)

	h := &fakeHost{}
	res := newTestDeployer(db, h).DeployServers(context.Background(), []int64{sid}, false)
	if len(res) != 1 || !res[0].Success {
		t.Fatalf("结果 = %+v", res)
	}

	tmp := "/etc/sing-box/config.json.new-1700000000123.tmp"
	bak := "/etc/sing-box/config.json.backup.1700000000123"
	want := []string{
		"mkdir -p '/etc/sing-box'",
		"WRITE " + tmp,
		"/usr/bin/sing-box check -c '" + tmp + "'",
		"if [ -f '/etc/sing-box/config.json' ]; then cp -p '/etc/sing-box/config.json' '" + bak + "'; fi",
		"mv -f '" + tmp + "' '/etc/sing-box/config.json'",
		"systemctl restart sing-box",
	}
	if strings.Join(h.cmds, "\n") != strings.Join(want, "\n") {
		t.Fatalf("命令序列:\n%s", strings.Join(h.cmds, "\n"))
	}

	state := func(id int64) int { return count(t, db, `SELECT deployed FROM node_server WHERE id = ?`, id) }
	if state(p1) != 1 || state(p2) != 0 {
		t.Fatalf("状态 p1=%d p2=%d", state(p1), state(p2))
	}
	if count(t, db, `SELECT count(*) FROM node_server WHERE node_id IN (?,?)`, deleted, referenced) != 0 {
		t.Fatal("待删除部署位应被物理删除")
	}
	if count(t, db, `SELECT count(*) FROM node WHERE id = ?`, deleted) != 0 {
		t.Fatal("无剩余部署位的节点应被删除")
	}
	if count(t, db, `SELECT count(*) FROM node WHERE id = ?`, referenced) != 1 {
		t.Fatal("被引用的节点应保留")
	}
	var sha, ok string
	db.QueryRow(`SELECT config_sha256, success FROM server_deployment WHERE server_id = ?`, sid).Scan(&sha, &ok)
	if len(sha) != 64 || ok != "1" {
		t.Fatalf("server_deployment sha=%q success=%q", sha, ok)
	}
}

func TestCheckFailureKeepsLiveConfig(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, false)
	p := place(t, db, addNode(t, db, false, nil), sid, 0)

	h := &fakeHost{failOn: "sing-box check"}
	res := newTestDeployer(db, h).DeployServers(context.Background(), []int64{sid}, false)
	if res[0].Success || !strings.Contains(res[0].Message, "bad config") {
		t.Fatalf("结果 = %+v", res[0])
	}
	for _, c := range h.cmds {
		if strings.Contains(c, "cp ") || strings.HasPrefix(c, "mv ") || strings.Contains(c, "systemctl") {
			t.Fatalf("校验失败后不应执行 %q", c)
		}
	}
	if last := h.cmds[len(h.cmds)-1]; !strings.HasPrefix(last, "rm -f '/etc/sing-box/config.json.new-") {
		t.Fatalf("应删除临时文件，最后命令 %q", last)
	}
	if count(t, db, `SELECT deployed FROM node_server WHERE id = ?`, p) != 0 {
		t.Fatal("失败不应改部署位状态")
	}
	if count(t, db, `SELECT success FROM server_deployment WHERE server_id = ?`, sid) != 0 {
		t.Fatal("应记录失败结果")
	}
}

func TestDisabledServerSkipped(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, true, false)
	h := &fakeHost{}
	res := newTestDeployer(db, h).DeployServers(context.Background(), []int64{sid}, true)
	if res[0].Success || res[0].Message != "服务器已禁用，已跳过部署" || h.dials != 0 {
		t.Fatalf("res=%+v dials=%d", res[0], h.dials)
	}
}

func TestExternalServerNotConnected(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, true)
	p := place(t, db, addNode(t, db, false, nil), sid, 0)
	h := &fakeHost{}
	res := newTestDeployer(db, h).DeployServers(context.Background(), []int64{sid}, false)
	if !res[0].Success || h.dials != 0 {
		t.Fatalf("res=%+v dials=%d", res[0], h.dials)
	}
	if count(t, db, `SELECT deployed FROM node_server WHERE id = ?`, p) != 1 {
		t.Fatal("外部服务器成功后应置已部署")
	}
}

func TestReloadUpdatesPlacementState(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, false)
	p := place(t, db, addNode(t, db, false, nil), sid, 0)
	h := &fakeHost{}
	res := newTestDeployer(db, h).Reload(context.Background(), sid)
	if !res.Success || h.cmds[len(h.cmds)-1] != "systemctl reload sing-box" {
		t.Fatalf("res=%+v cmds=%v", res, h.cmds)
	}
	if count(t, db, `SELECT deployed FROM node_server WHERE id = ?`, p) != 1 {
		t.Fatal("reload 应同步部署位状态")
	}
	if count(t, db, `SELECT count(*) FROM server_deployment WHERE server_id = ?`, sid) != 1 {
		t.Fatal("应记录结果")
	}
}

func TestSameServerSerialized(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, false)
	h := &fakeHost{delay: 20 * time.Millisecond}
	d := newTestDeployer(db, h)
	var wg sync.WaitGroup
	for range 4 {
		wg.Add(1)
		go func() { defer wg.Done(); d.DeployServers(context.Background(), []int64{sid}, false) }()
	}
	wg.Wait()
	if h.dials != 4 || h.maxActive != 1 {
		t.Fatalf("dials=%d maxActive=%d，同一服务器应串行", h.dials, h.maxActive)
	}
}

func TestDifferentServersParallel(t *testing.T) {
	db := newTestDB(t)
	a, b := addServer(t, db, false, false), addServer(t, db, false, false)
	h := &fakeHost{delay: 50 * time.Millisecond}
	d := newTestDeployer(db, h)
	d.Parallel = 2
	res := d.DeployServers(context.Background(), []int64{a, b}, false)
	if !res[0].Success || !res[1].Success || h.maxActive != 2 {
		t.Fatalf("res=%+v maxActive=%d", res, h.maxActive)
	}
}

func TestCheckHostKey(t *testing.T) {
	if rec, err := checkHostKey("", "SHA256:a"); !rec || err != nil {
		t.Fatal("首次应记录", rec, err)
	}
	if rec, err := checkHostKey("SHA256:a", "SHA256:a"); rec || err != nil {
		t.Fatal("一致应放行", rec, err)
	}
	if _, err := checkHostKey("SHA256:a", "SHA256:b"); !errors.Is(err, ErrHostKeyMismatch) {
		t.Fatal("不一致应返回 ErrHostKeyMismatch", err)
	}
}

func TestParseAuth(t *testing.T) {
	if _, err := parseAuth("PASSWORD", ""); err == nil {
		t.Fatal("空密码应拒绝")
	}
	if _, err := parseAuth("PASSWORD", "pw"); err != nil {
		t.Fatal(err)
	}
	if _, err := parseAuth("KEY", "not a key"); err == nil {
		t.Fatal("缺少 PRIVATE KEY 应拒绝")
	}
	if _, err := parseAuth("KEY", "-----BEGIN RSA PRIVATE KEY-----\nProc-Type: 4,ENCRYPTED\n-----END RSA PRIVATE KEY-----"); err == nil || !strings.Contains(err.Error(), "口令") {
		t.Fatal("加密私钥应拒绝", err)
	}
	if _, err := parseAuth("OTHER", "x"); err == nil {
		t.Fatal("未知认证类型应拒绝")
	}
}

func TestShQuote(t *testing.T) {
	if got := shQuote(`a'b c`); got != `'a'\''b c'` {
		t.Fatal(got)
	}
}

func TestPreparedDeploymentSnapshots(t *testing.T) {
	for _, fail := range []bool{false, true} {
		t.Run(fmt.Sprint(fail), func(t *testing.T) {
			db := newTestDB(t)
			sid := addServer(t, db, false, false)
			nid := addNode(t, db, false, nil)
			pid := place(t, db, nid, sid, 0)
			h := &fakeHost{}
			if fail {
				h.failOn = "sing-box check"
			}
			d := newTestDeployer(db, h)
			d.Config = func(context.Context, int64) ([]byte, error) { t.Fatal("Prepare 应优先于 Config"); return nil, nil }
			d.Prepare = func(ctx context.Context, id int64) (*Prepared, error) {
				c, err := store.CaptureDeployment(ctx, db, id, store.LoadOptions{})
				if err != nil {
					return nil, err
				}
				cfg, err := json.Marshal(c.Input)
				return &Prepared{Config: cfg, Revision: c.Revision, Snapshots: c.Snapshots}, err
			}
			d.Dial = func(ctx context.Context, target *store.SSHTarget) (Remote, error) {
				exec(t, db, `UPDATE node SET port=10002 WHERE id=?`, nid)
				return h.dial(ctx, target)
			}
			res := d.Reload(context.Background(), sid)
			if res.Success == fail {
				t.Fatal(res)
			}
			if count(t, db, `SELECT deployed FROM node_server WHERE id=?`, pid) != 0 {
				t.Fatal("编辑后必须保持 pending")
			}
			if fail {
				if count(t, db, `SELECT count(*) FROM node_deployment`) != 0 {
					t.Fatal("失败写入快照")
				}
			} else {
				if !strings.Contains(res.Message, "部署期间数据已变化") {
					t.Fatal(res)
				}
				var raw string
				if err := db.QueryRow(`SELECT snapshot_json FROM node_deployment WHERE node_server_id=?`, pid).Scan(&raw); err != nil {
					t.Fatal(err)
				}
				var s store.DeploymentSnapshot
				if err := json.Unmarshal([]byte(raw), &s); err != nil {
					t.Fatal(err)
				}
				if s.Port != 10001 {
					t.Fatalf("保存的必须是提交前的输入: %+v", s)
				}
			}
		})
	}
}

func TestEditDuringDeploymentKeepsPending(t *testing.T) {
	db := newTestDB(t)
	sid := addServer(t, db, false, false)
	nid := addNode(t, db, false, nil)
	p := place(t, db, nid, sid, 0)
	h := &fakeHost{}
	d := newTestDeployer(db, h)
	d.Dial = func(ctx context.Context, target *store.SSHTarget) (Remote, error) {
		exec(t, db, `UPDATE node SET port=10002 WHERE id=?`, nid)
		return h.dial(ctx, target)
	}
	res := d.DeployServers(context.Background(), []int64{sid}, true)
	if !res[0].Success || !strings.Contains(res[0].Message, "部署期间数据已变化") {
		t.Fatal(res)
	}
	if count(t, db, `SELECT deployed FROM node_server WHERE id=?`, p) != 0 {
		t.Fatal("部署期间的修改不应确认已部署")
	}
}
