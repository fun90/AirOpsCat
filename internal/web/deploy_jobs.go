package web

import (
	"cmp"
	"context"
	"fmt"
	"log/slog"
	"maps"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/store"
)

// deployDebounce 是入队后的去抖等待，合并连续操作；同一服务器连续入队最多推迟 deployDebounceMax。
var deployDebounce = 2 * time.Second

const (
	deployDebounceMax    = 30 * time.Second
	deployJobsKept       = 50 // 内存保留的最近任务数；未完成的任务不淘汰
	deployRetryEvery     = 10 * time.Minute
	deployReasonRetry    = "失败重试"
	deployReasonExpire   = "账户到期"
	deployReasonConflict = "版本冲突补偿"
	deployConflictDelay  = 2 * time.Second // 连续冲突至少间隔一次去抖，不让后台队列紧密自旋
)

// deployJob 是一次入队请求；同一服务器的多个任务合并为一次部署，结果分别记入各任务。
type deployJob struct {
	ID                         int64
	Reason                     string
	Servers                    []int64
	Created, Started, Finished time.Time
	Results                    map[int64]deploy.Result
	canceled                   bool
	done                       func(map[int64]deploy.Result) // 全部服务器有结果后在锁外调用一次
}

// deployServerState 记录一台服务器的排队情况：waiting 等下一次部署，running 属于正在进行的这次。
type deployServerState struct {
	waiting, running []*deployJob
	busy             bool
	first, due       time.Time // first 为本轮首次入队时间，due 为最早开始时间
}

// deployQueue 是进程内后台部署队列。部署不受 HTTP 请求取消影响；同一服务器串行由 deploy.Lock 保证。
type deployQueue struct {
	run      func(ctx context.Context, serverID int64) deploy.Result
	parallel func() int
	onResult func(reasons []string, res deploy.Result)
	debounce time.Duration
	now      func() time.Time

	ctx    context.Context
	cancel context.CancelFunc
	wg     sync.WaitGroup

	mu      sync.Mutex
	closed  bool
	nextID  int64
	jobs    []*deployJob // 按 ID 升序
	servers map[int64]*deployServerState
	active  int
	timer   *time.Timer
}

func (s *Server) newDeployQueue() *deployQueue {
	ctx, cancel := context.WithCancel(context.Background())
	return &deployQueue{
		run: func(ctx context.Context, id int64) deploy.Result {
			return s.Deployer.DeployServers(ctx, []int64{id}, true)[0]
		},
		parallel: func() int { return s.Deployer.ParallelLimit() },
		onResult: s.deployJobResult,
		debounce: deployDebounce,
		now:      time.Now,
		ctx:      ctx, cancel: cancel,
		servers: map[int64]*deployServerState{},
	}
}

// EnqueueDeploy 提交后台整机部署并立即返回任务 ID（serverIDs 为空返回 0）。
// 部署使用与请求无关的 ctx；同一服务器已在排队则合并为一次，正在部署则在本次结束后再部署一次（多次入队仍合并为一次）。
// 入队后去抖 2 秒以合并连续操作；并发度沿用 Deployer.Parallel，失败沿用自动部署失败告警。进程关闭后入队的任务直接标记取消。
func (s *Server) EnqueueDeploy(reason string, serverIDs []int64) (jobID int64) {
	return s.deployQ.enqueue(reason, serverIDs, nil)
}

// EnqueueDeployThen 同 EnqueueDeploy，任务全部服务器有结果（含进程关闭取消）后调用 done；serverIDs 为空时不调用。
func (s *Server) EnqueueDeployThen(reason string, serverIDs []int64, done func(map[int64]deploy.Result)) (jobID int64) {
	return s.deployQ.enqueue(reason, serverIDs, done)
}

// finishLocked 记录任务完成时间，返回需在锁外调用的完成回调。
func (j *deployJob) finishLocked(now time.Time) func() {
	if len(j.Results) != len(j.Servers) || !j.Finished.IsZero() {
		return nil
	}
	j.Finished = now
	if j.done == nil {
		return nil
	}
	done, res := j.done, maps.Clone(j.Results)
	return func() { done(res) }
}

func (q *deployQueue) enqueue(reason string, serverIDs []int64, done func(map[int64]deploy.Result)) int64 {
	return q.enqueueAfter(reason, serverIDs, done, 0)
}

func (q *deployQueue) enqueueAfter(reason string, serverIDs []int64, done func(map[int64]deploy.Result), delay time.Duration) int64 {
	var ids []int64
	for _, id := range serverIDs {
		if !slices.Contains(ids, id) {
			ids = append(ids, id)
		}
	}
	if len(ids) == 0 {
		return 0
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	now := q.now()
	q.nextID++
	job := &deployJob{ID: q.nextID, Reason: reason, Servers: ids, Created: now, Results: map[int64]deploy.Result{}, done: done}
	q.jobs = append(q.jobs, job)
	q.pruneLocked()
	if q.closed {
		if f := q.cancelLocked(job, ids, now); f != nil {
			go f()
		}
		return job.ID
	}
	for _, id := range ids {
		st := q.servers[id]
		if st == nil {
			st = &deployServerState{}
			q.servers[id] = st
		}
		if len(st.waiting) == 0 {
			st.first = now
		}
		st.waiting = append(st.waiting, job)
		st.due = now.Add(max(q.debounce, delay))
		if limit := st.first.Add(deployDebounceMax); st.due.After(limit) {
			st.due = limit
		}
		if delay > 0 && st.due.Before(now.Add(delay)) {
			st.due = now.Add(delay) // 补偿入队即使与旧请求合并，也不能被合并窗口缩成紧密循环
		}
	}
	q.pumpLocked()
	return job.ID
}

// pumpLocked 启动已到期且未在部署的服务器，直到并发上限；其余到期时间由定时器唤醒。调用方须持有 q.mu。
func (q *deployQueue) pumpLocked() {
	if q.closed {
		return
	}
	now := q.now()
	var ready []int64
	var next time.Time
	for id, st := range q.servers {
		if st.busy || len(st.waiting) == 0 {
			continue
		}
		if st.due.After(now) {
			if next.IsZero() || st.due.Before(next) {
				next = st.due
			}
			continue
		}
		ready = append(ready, id)
	}
	slices.SortFunc(ready, func(a, b int64) int {
		if c := q.servers[a].due.Compare(q.servers[b].due); c != 0 {
			return c
		}
		return cmp.Compare(a, b)
	})
	for _, id := range ready {
		if q.active >= max(q.parallel(), 1) {
			break
		}
		q.startLocked(id, q.servers[id], now)
	}
	if q.timer != nil {
		q.timer.Stop()
		q.timer = nil
	}
	if !next.IsZero() {
		q.timer = time.AfterFunc(next.Sub(now), q.pump)
	}
}

func (q *deployQueue) pump() {
	q.mu.Lock()
	defer q.mu.Unlock()
	q.pumpLocked()
}

func (q *deployQueue) startLocked(id int64, st *deployServerState, now time.Time) {
	st.busy, st.running, st.waiting = true, st.waiting, nil
	for _, j := range st.running {
		if j.Started.IsZero() {
			j.Started = now
		}
	}
	q.active++
	q.wg.Add(1)
	go q.work(id)
}

func (q *deployQueue) work(id int64) {
	defer q.wg.Done()
	res := q.run(q.ctx, id)
	q.mu.Lock()
	st := q.servers[id]
	jobs := st.running
	st.busy, st.running = false, nil
	q.active--
	now := q.now()
	var reasons []string
	var callbacks []func()
	for _, j := range jobs {
		j.Results[id] = res
		if f := j.finishLocked(now); f != nil {
			callbacks = append(callbacks, f)
		}
		if !slices.Contains(reasons, j.Reason) {
			reasons = append(reasons, j.Reason)
		}
	}
	if len(st.waiting) == 0 {
		delete(q.servers, id)
	}
	q.pumpLocked()
	q.mu.Unlock()
	if q.onResult != nil {
		q.onResult(reasons, res)
	}
	for _, f := range callbacks {
		f()
	}
}

// cancelLocked 把未开始的服务器记为未执行，返回需在锁外调用的完成回调。
func (q *deployQueue) cancelLocked(j *deployJob, ids []int64, now time.Time) func() {
	j.canceled = true
	for _, id := range ids {
		j.Results[id] = deploy.Result{ServerID: id, Message: "进程关闭，未执行部署"}
	}
	return j.finishLocked(now)
}

// pruneLocked 只淘汰已完成的旧任务。
func (q *deployQueue) pruneLocked() {
	for extra := len(q.jobs) - deployJobsKept; extra > 0; extra-- {
		i := slices.IndexFunc(q.jobs, func(j *deployJob) bool { return !j.Finished.IsZero() })
		if i < 0 {
			return
		}
		q.jobs = slices.Delete(q.jobs, i, i+1)
	}
}

// shutdown 停止启动新部署，等待进行中的部署结束；ctx 到期后取消剩余部署并等待其落库。
func (q *deployQueue) shutdown(ctx context.Context) error {
	q.mu.Lock()
	q.closed = true
	if q.timer != nil {
		q.timer.Stop()
		q.timer = nil
	}
	now := q.now()
	var callbacks []func()
	for id, st := range q.servers {
		for _, j := range st.waiting {
			if f := q.cancelLocked(j, []int64{id}, now); f != nil {
				callbacks = append(callbacks, f)
			}
		}
		st.waiting = nil
		if !st.busy {
			delete(q.servers, id)
		}
	}
	q.mu.Unlock()
	for _, f := range callbacks {
		f()
	}
	done := make(chan struct{})
	go func() { q.wg.Wait(); close(done) }()
	select {
	case <-done:
		return nil
	case <-ctx.Done():
		q.cancel()
		<-done
		return fmt.Errorf("等待后台部署超时，已中断: %w", ctx.Err())
	}
}

// ShutdownDeploys 供进程关停调用，见 deployQueue.shutdown。
func (s *Server) ShutdownDeploys(ctx context.Context) error {
	return s.deployQ.shutdown(ctx)
}

// wait 等待队列空闲（无排队与进行中的服务器），仅供测试使用。
func (q *deployQueue) wait(timeout time.Duration) bool {
	deadline := time.Now().Add(timeout)
	for time.Now().Before(deadline) {
		q.mu.Lock()
		idle := len(q.servers) == 0
		q.mu.Unlock()
		if idle {
			return true
		}
		time.Sleep(5 * time.Millisecond)
	}
	return false
}

// activeJobs 返回未完成的任务数，供导航栏提示。
func (q *deployQueue) activeJobs() int {
	if q == nil {
		return 0
	}
	q.mu.Lock()
	defer q.mu.Unlock()
	n := 0
	for _, j := range q.jobs {
		if j.Finished.IsZero() {
			n++
		}
	}
	return n
}

// deployJobResult 成功但版本冲突时补偿未确认的部署位；失败沿用告警，成功由 Deployer.Applied 恢复告警。
func (s *Server) deployJobResult(reasons []string, res deploy.Result) {
	if res.Success {
		if strings.Contains(res.Message, "部署期间数据已变化，保留原部署位状态，请再次部署") && s.autoDeployOn(context.Background()) {
			ids, err := s.pendingAmong(context.Background(), []int64{res.ServerID})
			if err != nil {
				slog.Warn("版本冲突补偿读取待部署服务器失败", "server", res.ServerID, "err", err)
			} else if len(ids) > 0 {
				s.deployQ.enqueueAfter(deployReasonConflict, ids, nil, deployConflictDelay)
			}
		}
		return
	}
	if res.Skipped {
		return
	}
	ctx := context.Background()
	reason := strings.Join(reasons, "、")
	key := store.AlertKey{Type: alertDeployFail, ResourceType: "server", ResourceID: res.ServerID, ResourceKey: res.ServerName, Fingerprint: strconv.FormatInt(res.ServerID, 10)}
	if err := s.raise(ctx, key, "critical", 0, 0, res.ServerName+" 后台部署失败", 0, notify.Message{Title: "AirOpsCat 部署失败 · " + res.ServerName,
		Body: "后台部署失败（" + reason + "），已保持待部署，失败重试任务会定时重新部署：\n" + res.Message}); err != nil {
		slog.Warn("部署失败告警处理失败", "server", res.ServerID, "err", err)
	}
}

// retryDeploys 始终重试最近部署失败的服务器；自动部署开启时补偿进程关闭、开关切换及其他同步留下的待部署位。
func (s *Server) retryDeploys(ctx context.Context) error {
	ids, err := store.RetryDeployServers(ctx, s.DB)
	if err != nil {
		return err
	}
	if s.autoDeployOn(ctx) {
		pending, err := store.PendingDeployServers(ctx, s.DB)
		if err != nil {
			return err
		}
		for _, id := range pending {
			if !slices.Contains(ids, id) {
				ids = append(ids, id)
			}
		}
	}
	s.EnqueueDeploy(deployReasonRetry, ids)
	return nil
}

type deployJobServerView struct {
	Name, State, Message string
	Dot                  string // Tabler status-dot 颜色类
}

type deployJobView struct {
	ID                        int64
	Reason, Created, Duration string
	Status, Badge             string
	Servers                   []deployJobServerView
}

func (q *deployQueue) views(names map[int64]string) (out []deployJobView, active bool) {
	q.mu.Lock()
	defer q.mu.Unlock()
	for i := len(q.jobs) - 1; i >= 0; i-- {
		j := q.jobs[i]
		v := deployJobView{ID: j.ID, Reason: j.Reason, Created: store.FormatTime(j.Created)}
		failed := 0
		for _, id := range j.Servers {
			sv := deployJobServerView{Name: names[id]}
			if sv.Name == "" {
				sv.Name = fmt.Sprintf("服务器 #%d", id)
			}
			res, ok := j.Results[id]
			switch {
			case ok && res.Success && res.Warning != "":
				sv.State, sv.Dot, sv.Message = "成功（有告警）", "status-yellow", res.Message
			case ok && res.Success:
				sv.State, sv.Dot, sv.Message = "成功", "status-green", res.Message
			case ok && (res.Skipped || j.canceled):
				sv.State, sv.Dot, sv.Message = "已跳过", "status-secondary", res.Message
			case ok:
				failed++
				sv.State, sv.Dot, sv.Message = "失败", "status-red", res.Message
			case q.servers[id] != nil && slices.Contains(q.servers[id].running, j):
				sv.State, sv.Dot = "部署中", "status-blue"
			default:
				sv.State, sv.Dot = "排队中", "status-secondary"
			}
			v.Servers = append(v.Servers, sv)
		}
		switch {
		case j.canceled:
			v.Status, v.Badge = "已取消", "bg-secondary-lt"
		case !j.Finished.IsZero() && failed > 0:
			v.Status, v.Badge = fmt.Sprintf("完成，%d 台失败", failed), "bg-red-lt"
		case !j.Finished.IsZero():
			v.Status, v.Badge = "完成", "bg-green-lt"
		case !j.Started.IsZero():
			v.Status, v.Badge, active = "部署中", "bg-blue-lt", true
		default:
			v.Status, v.Badge, active = "排队中", "bg-yellow-lt", true
		}
		if !j.Finished.IsZero() && !j.Started.IsZero() {
			v.Duration = j.Finished.Sub(j.Started).Round(time.Second).String()
		}
		out = append(out, v)
	}
	return out, active
}

func (s *Server) deployJobsPage(w http.ResponseWriter, r *http.Request) {
	names := map[int64]string{}
	rows, err := s.DB.QueryContext(r.Context(), `SELECT id, coalesce(nullif(name,''), ip) FROM server`)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	for rows.Next() {
		var id int64
		var name string
		if err := rows.Scan(&id, &name); err != nil {
			rows.Close()
			s.fail(w, r, err)
			return
		}
		names[id] = name
	}
	rows.Close()
	if err := rows.Err(); err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "部署任务", "deploy-jobs")
	d["Jobs"], d["Active"] = s.deployQ.views(names)
	s.render(w, "deploy_jobs", 200, d)
}
