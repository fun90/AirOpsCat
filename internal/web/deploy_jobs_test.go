package web

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/store"
	"github.com/fun90/airopscat/internal/traffic"
)

// 测试默认不去抖，HTTP 用例只需等待队列空闲；去抖用例显式设置。
// 测试库不写入系统配置，缺省关闭「操作后自动部署」，避免旧用例触发后台 SSH；自动部署用例显式写入配置。
func init() { deployDebounce, autoDeployDefault = 0, false }

func eventually(t *testing.T, what string, cond func() bool) {
	t.Helper()
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); time.Sleep(5 * time.Millisecond) {
		if cond() {
			return
		}
	}
	t.Fatal("等待超时：" + what)
}

// fakeRuns 记录队列的每次部署，可按服务器阻塞以构造「正在部署」。
type fakeRuns struct {
	mu       sync.Mutex
	runs     []int64
	active   int
	peak     int
	block    map[int64]chan struct{}
	started  chan int64
	failWith string
}

func (f *fakeRuns) run(ctx context.Context, id int64) deploy.Result {
	f.mu.Lock()
	f.runs = append(f.runs, id)
	f.active++
	f.peak = max(f.peak, f.active)
	ch := f.block[id]
	f.mu.Unlock()
	if f.started != nil {
		f.started <- id
	}
	if ch != nil {
		<-ch
	}
	f.mu.Lock()
	f.active--
	f.mu.Unlock()
	if f.failWith != "" {
		return deploy.Result{ServerID: id, Message: f.failWith}
	}
	return deploy.Result{ServerID: id, Success: true, Message: "部署成功"}
}

func (f *fakeRuns) count(id int64) (n int) {
	f.mu.Lock()
	defer f.mu.Unlock()
	for _, v := range f.runs {
		if v == id {
			n++
		}
	}
	return n
}

func testQueue(f *fakeRuns, parallel int, debounce time.Duration) *deployQueue {
	ctx, cancel := context.WithCancel(context.Background())
	return &deployQueue{run: f.run, parallel: func() int { return parallel }, debounce: debounce, now: time.Now,
		ctx: ctx, cancel: cancel, servers: map[int64]*deployServerState{}}
}

func (q *deployQueue) job(id int64) *deployJob {
	q.mu.Lock()
	defer q.mu.Unlock()
	for _, j := range q.jobs {
		if j.ID == id {
			return j
		}
	}
	return nil
}

func TestDeployQueueMergesWaitingServers(t *testing.T) {
	f := &fakeRuns{}
	q := testQueue(f, 4, 50*time.Millisecond)
	a := q.enqueue("A", []int64{1, 2, 2}, nil)
	b := q.enqueue("B", []int64{2, 3}, nil)
	if !q.wait(5 * time.Second) {
		t.Fatal("队列未空闲")
	}
	for _, id := range []int64{1, 2, 3} {
		if n := f.count(id); n != 1 {
			t.Fatalf("服务器 %d 部署 %d 次，排队中的重复入队应合并", id, n)
		}
	}
	ja, jb := q.job(a), q.job(b)
	if len(ja.Servers) != 2 || len(ja.Results) != 2 || len(jb.Results) != 2 || ja.Finished.IsZero() || jb.Finished.IsZero() {
		t.Fatalf("A=%+v B=%+v", ja, jb)
	}
	if q.enqueue("空", nil, nil) != 0 {
		t.Fatal("空服务器列表不应建任务")
	}
}

func TestDeployQueueRerunsOnceWhenEnqueuedWhileRunning(t *testing.T) {
	gate := make(chan struct{})
	f := &fakeRuns{block: map[int64]chan struct{}{1: gate}, started: make(chan int64, 4)}
	q := testQueue(f, 4, 0)
	a := q.enqueue("A", []int64{1}, nil)
	<-f.started
	f.mu.Lock()
	f.block = nil // 之后的部署不再阻塞
	f.mu.Unlock()
	b := q.enqueue("B", []int64{1}, nil)
	c := q.enqueue("C", []int64{1}, nil)
	if q.activeJobs() != 3 {
		t.Fatal("应有 3 个未完成任务", q.activeJobs())
	}
	views, active := q.views(nil)
	if !active || views[2].Status != "部署中" || views[0].Status != "排队中" {
		t.Fatalf("%+v", views)
	}
	close(gate)
	<-f.started
	if !q.wait(5 * time.Second) {
		t.Fatal("队列未空闲")
	}
	if n := f.count(1); n != 2 {
		t.Fatalf("部署中再次入队应在结束后合并为一次，实际 %d 次", n)
	}
	for _, id := range []int64{a, b, c} {
		if q.job(id).Finished.IsZero() {
			t.Fatalf("任务 %d 未完成", id)
		}
	}
}

func TestDeployQueueParallelLimit(t *testing.T) {
	f := &fakeRuns{block: map[int64]chan struct{}{}, started: make(chan int64, 8)}
	gate := make(chan struct{})
	for id := int64(1); id <= 5; id++ {
		f.block[id] = gate
	}
	q := testQueue(f, 2, 0)
	q.enqueue("批量", []int64{1, 2, 3, 4, 5}, nil)
	<-f.started
	<-f.started
	time.Sleep(30 * time.Millisecond)
	f.mu.Lock()
	running := f.active
	f.mu.Unlock()
	if running != 2 {
		t.Fatalf("并发度应为 2，实际 %d", running)
	}
	close(gate)
	if !q.wait(5*time.Second) || f.peak != 2 || len(f.runs) != 5 {
		t.Fatalf("peak=%d runs=%v", f.peak, f.runs)
	}
}

func TestDeployQueueShutdownWaitsRunningAndCancelsQueued(t *testing.T) {
	gate := make(chan struct{})
	f := &fakeRuns{block: map[int64]chan struct{}{1: gate}, started: make(chan int64, 4)}
	q := testQueue(f, 1, 0)
	a := q.enqueue("A", []int64{1}, nil)
	b := q.enqueue("B", []int64{2}, nil) // 并发度 1，排队等待
	<-f.started
	done := make(chan error, 1)
	go func() { done <- q.shutdown(context.Background()) }()
	select {
	case <-done:
		t.Fatal("关闭应等待进行中的部署")
	case <-time.After(30 * time.Millisecond):
	}
	close(gate)
	if err := <-done; err != nil {
		t.Fatal(err)
	}
	if !q.job(a).Results[1].Success || !q.job(b).canceled || f.count(2) != 0 {
		t.Fatalf("A=%+v B=%+v", q.job(a), q.job(b))
	}
	if c := q.job(q.enqueue("C", []int64{3}, nil)); !c.canceled || f.count(3) != 0 {
		t.Fatal("关闭后入队不应执行")
	}
}

// deployTestServer 返回接入 fake SSH 的 Server；gate 非空时连接被阻塞到关闭为止。
func deployTestServer(t *testing.T) (*Server, http.Handler, *http.Cookie, string) {
	t.Helper()
	_, a := newTestServer(t)
	field, _ := crypto.New(crypto.DeriveKey("0123456789abcdef0123456789abcdef", "field"))
	s, err := New(a.DB, a, field)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { s.ShutdownDeploys(context.Background()) })
	h := s.Handler()
	c := login(t, h, "admin@example.com")
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name) VALUES(1,'203.0.113.1','a'),(2,'203.0.113.2','b');
 INSERT INTO node(id,name,type,port,protocol,core_type,inbound) VALUES(1,'n1',0,443,'vless','sing-box','{"type":"vless"}');
 INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,0)`); err != nil {
		t.Fatal(err)
	}
	return s, h, c, a.CSRFToken(c.Value)
}

func deployedState(t *testing.T, s *Server, placement int64) (v int) {
	t.Helper()
	s.DB.QueryRow(`SELECT deployed FROM node_server WHERE id=?`, placement).Scan(&v)
	return v
}

// HTTP 部署入口入队后立即重定向，请求取消（浏览器断开）不影响后台部署。
func TestDeployHTTPEnqueuesAndIgnoresRequestCancel(t *testing.T) {
	s, h, c, csrf := deployTestServer(t)
	gate := make(chan struct{})
	dialed := make(chan struct{}, 1)
	s.Deployer.Dial = func(ctx context.Context, _ *store.SSHTarget) (deploy.Remote, error) {
		dialed <- struct{}{}
		<-gate
		if ctx.Err() != nil {
			return nil, ctx.Err()
		}
		return &reloadHost{}, nil
	}
	ctx, cancel := context.WithCancel(context.Background())
	form := url.Values{"csrf": {csrf}}
	r := httptest.NewRequestWithContext(ctx, "POST", "/admin/nodes/1/deploy", strings.NewReader(form.Encode()))
	r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	r.AddCookie(c)
	w := httptest.NewRecorder()
	h.ServeHTTP(w, r)
	loc, _ := url.QueryUnescape(w.Header().Get("Location"))
	if w.Code != http.StatusSeeOther || !strings.HasPrefix(loc, "/admin/nodes") || !strings.Contains(loc, "已提交后台部署 1 台") {
		t.Fatal(w.Code, loc)
	}
	cancel()
	<-dialed
	if page := do(h, "GET", "/admin/deploy-jobs", nil, c, nil).Body.String(); !strings.Contains(page, "部署节点 #1") || !strings.Contains(page, "data-deploy-refresh") {
		t.Fatal("部署任务页应展示进行中的任务并自动刷新", page)
	}
	if nav := do(h, "GET", "/admin", nil, c, nil).Body.String(); !strings.Contains(nav, `href="/admin/deploy-jobs" title="后台部署进行中"`) {
		t.Fatal("导航应提示进行中的部署")
	}
	close(gate)
	eventually(t, "后台部署完成", func() bool { return deployedState(t, s, 1) == 1 })
	s.deployQ.wait(5 * time.Second)
	if page := do(h, "GET", "/admin/deploy-jobs", nil, c, nil).Body.String(); strings.Contains(page, "data-deploy-refresh") || !strings.Contains(page, "部署成功") {
		t.Fatal("完成后应展示结果并停止刷新", page)
	}
	// 禁用服务器显式整机部署直接提示跳过，不入队。
	s.DB.Exec(`UPDATE server SET disabled=1 WHERE id=2`)
	w = do(h, "POST", "/admin/servers/2/deploy", url.Values{"csrf": {csrf}}, c, nil)
	if loc, _ := url.QueryUnescape(w.Header().Get("Location")); w.Code != http.StatusSeeOther || !strings.Contains(loc, "服务器已禁用，已跳过部署") || s.deployQ.activeJobs() != 0 {
		t.Fatal(w.Code, loc)
	}
}

// 失败重试任务把仍需部署且最近部署失败的服务器重新入队；失败沿用自动部署失败告警。
func TestDeployRetryTaskRequeuesFailedServers(t *testing.T) {
	s, _, _, _ := deployTestServer(t)
	down := true
	var mu sync.Mutex
	s.Deployer.Dial = func(context.Context, *store.SSHTarget) (deploy.Remote, error) {
		mu.Lock()
		defer mu.Unlock()
		if down {
			return nil, errors.New("dial down")
		}
		return &reloadHost{}, nil
	}
	s.EnqueueDeploy("测试", []int64{1})
	s.deployQ.wait(5 * time.Second)
	alert := func() (st string) {
		s.DB.QueryRow(`SELECT coalesce(group_concat(resource_id||':'||status),'') FROM alert_state WHERE alert_type=?`, alertDeployFail).Scan(&st)
		return
	}
	if deployedState(t, s, 1) != 0 || alert() != "1:ACTIVE" {
		t.Fatal("失败应保持待部署并告警", alert())
	}
	mu.Lock()
	down = false
	mu.Unlock()
	s.Tasks["deploy-retry"].execute(context.Background())
	if s.Tasks["deploy-retry"].Result != "成功" {
		t.Fatal(s.Tasks["deploy-retry"].Result)
	}
	s.deployQ.wait(5 * time.Second)
	if deployedState(t, s, 1) != 1 || alert() != "1:RECOVERED" {
		t.Fatal("重试成功应确认部署位并恢复告警", alert())
	}
	views, _ := s.deployQ.views(nil)
	if views[0].Reason != deployReasonRetry {
		t.Fatalf("%+v", views[0])
	}
	// 没有失败的服务器时不入队。
	s.Tasks["deploy-retry"].execute(context.Background())
	if v, _ := s.deployQ.views(nil); len(v) != 2 {
		t.Fatal("无失败服务器时不应入队", len(v))
	}
}

// restart 前在持有服务器锁时先采流量，不死锁，且先于 SSH 推送。
func TestDeployCollectsTrafficBeforeRestart(t *testing.T) {
	s, _, _, _ := deployTestServer(t)
	s.DB.Exec(`INSERT INTO node_deployment(node_id,node_server_id,version,snapshot_hash,disabled) VALUES(1,1,1,'h',0)`)
	s.TrafficServers = "all"
	var order []string
	var mu sync.Mutex
	s.trafficCollect = func(context.Context, *sql.DB, *crypto.Cipher, int64) (map[string]traffic.Counters, error) {
		mu.Lock()
		defer mu.Unlock()
		order = append(order, "collect")
		return nil, nil
	}
	s.Deployer.Dial = func(context.Context, *store.SSHTarget) (deploy.Remote, error) {
		mu.Lock()
		defer mu.Unlock()
		order = append(order, "dial")
		return &reloadHost{}, nil
	}
	done := make(chan []deploy.Result, 1)
	go func() { done <- s.Deployer.DeployServers(context.Background(), []int64{1}, true) }()
	select {
	case res := <-done:
		if !res[0].Success {
			t.Fatal(res[0].Message)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("部署前采集死锁")
	}
	if strings.Join(order, ",") != "collect,dial" {
		t.Fatal(order)
	}
	// reload（限速同步）不重复采集。
	order = nil
	if res := s.Deployer.Reload(context.Background(), 1); !res.Success || strings.Join(order, ",") != "dial" {
		t.Fatal(res, order)
	}
}
