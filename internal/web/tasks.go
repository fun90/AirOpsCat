package web

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

type taskState struct {
	Name              string
	Interval          time.Duration
	Run               func(context.Context) error
	mu                sync.Mutex
	Running           bool
	Started, Finished time.Time
	Result            string
	failLogs          map[string]*failLog
}

// failLog 记录某条持续失败的错误已写日志的情况，用于压制重复日志。
type failLog struct {
	count  int
	logged time.Time
}

// repeatLogInterval 同一条错误持续出现时日志的最小间隔。
const repeatLogInterval = time.Hour

type taskView struct {
	Name, Last, Result string
	Running            bool
}

func (t *taskState) execute(ctx context.Context) {
	t.mu.Lock()
	if t.Running {
		t.mu.Unlock()
		return
	}
	t.Running = true
	t.Started = time.Now()
	t.mu.Unlock()
	err := t.Run(ctx)
	t.mu.Lock()
	t.Running = false
	t.Finished = time.Now()
	t.Result = "成功"
	if err != nil {
		t.Result = err.Error()
	}
	t.logFailures(err, t.Finished)
	t.mu.Unlock()
}

// logFailures 按行去重失败日志：新错误立即记录，持续出现的同一条错误每小时最多记一次并附带累计次数；
// 本轮不再出现的错误会被遗忘，再次出现时重新记录。调用方须持有 t.mu。
func (t *taskState) logFailures(err error, now time.Time) {
	next := map[string]*failLog{}
	if err != nil {
		for _, line := range strings.Split(err.Error(), "\n") {
			if line = strings.TrimSpace(line); line == "" {
				continue
			}
			f := t.failLogs[line]
			if f == nil {
				f = &failLog{}
				slog.Error("定时任务失败", "task", t.Name, "err", line)
				f.logged = now
			} else if now.Sub(f.logged) >= repeatLogInterval {
				slog.Error("定时任务持续失败", "task", t.Name, "err", line, "累计次数", f.count+1)
				f.logged = now
			}
			f.count++
			next[line] = f
		}
	}
	t.failLogs = next
}
func (s *Server) initTasks() {
	s.Tasks = map[string]*taskState{
		"core-backup-cleanup": {Name: "远端内核配置备份清理", Interval: 24 * time.Hour, Run: s.cleanupCoreBackups},
		"dns-auto":            {Name: "DNS 时段与流量自动切换", Interval: time.Minute, Run: s.autoDNS},
		"limit-sync":          {Name: "超额限速同步", Interval: time.Minute, Run: s.syncLimits},
		"traffic-collect":     {Name: "账户流量采集", Interval: 15 * time.Minute, Run: s.collectTraffic},
		"guard-evaluate": {Name: "跨节点配额评估", Interval: 10 * time.Second, Run: func(ctx context.Context) error {
			stats, err := s.Guard.Evaluate(ctx)
			if err != nil {
				return err
			}
			return s.alertConnections(ctx, stats)
		}},
		"server-monitor":    {Name: "服务器指标采集（CPU/内存/磁盘/网速）", Interval: monitorInterval, Run: s.collectMonitor},
		"server-vnstat":     {Name: "服务器月流量采集（vnstat）", Interval: vnstatInterval, Run: s.collectVnstat},
		"placement-probe":   {Name: "部署位国内连通检测（TCP/UDP）", Interval: probeInterval, Run: s.probePlacements},
		"alert-scan":        {Name: "告警扫描（流量超额与到期）", Interval: 5 * time.Minute, Run: s.scanAlerts},
		"ip-overlap-digest": {Name: "超限每日摘要", Interval: 24 * time.Hour, Run: s.ipOverlapDigest},
		"guard-evict":       {Name: "风控过期状态回收", Interval: 30 * time.Second, Run: s.Guard.Evict},
		"account-expire":    {Name: "到期账户停用并部署", Interval: time.Hour, Run: s.expireAccounts},
		"deploy-retry":      {Name: "部署失败重试与待部署补偿", Interval: deployRetryEvery, Run: s.retryDeploys},
		"db-backup": {Name: "数据库每日备份", Interval: 24 * time.Hour, Run: func(ctx context.Context) error {
			if _, err := s.Backup.Create(ctx); err != nil {
				return err
			}
			_, err := s.Backup.Cleanup(time.Duration(s.configInt(ctx, "airopscat.backup.retention-days", 15)) * 24 * time.Hour)
			return err
		}},
		"history-cleanup": {Name: "流量与在线历史清理", Interval: 24 * time.Hour, Run: func(ctx context.Context) error {
			if _, err := store.CleanupGuardOnline(ctx, s.DB, time.Now()); err != nil {
				return err
			}
			if _, err := store.CleanupMetrics(ctx, s.DB, s.configInt(ctx, "airopscat.server.monitor.retention-days", 30), s.configInt(ctx, "airopscat.server.monitor.cleanup.batch-size", 1000), time.Now()); err != nil {
				return err
			}
			if err := store.CleanupSystemLogs(ctx, s.DB, s.configInt(ctx, "airopscat.system-log.retention-days", 90), time.Now()); err != nil {
				return err
			}
			return store.CleanupTrafficHistory(ctx, s.DB, time.Now())
		}},
	}
}

// expireAccounts 识别新到期的账户（不改手动禁用标记），把其节点所在、确实需要部署的服务器提交后台部署以移除该账户。
// 到期移除是本任务的职责，不受「操作后自动部署」开关影响；各服务器独立部署，失败由部署队列告警并保持待部署，失败重试任务会重新入队。
func (s *Server) expireAccounts(ctx context.Context) error {
	accounts, err := store.ExpireAccounts(ctx, s.DB, time.Now())
	if err != nil {
		return err
	}
	var errs []error
	var related []int64
	for _, id := range accounts {
		ids, err := store.AccountDeployServers(ctx, s.DB, id)
		if err != nil {
			errs = append(errs, fmt.Errorf("读取账户 %d 的服务器失败: %w", id, err))
			continue
		}
		related = append(related, ids...)
	}
	if len(related) > 0 {
		servers, err := s.pendingAmong(ctx, related)
		if err != nil {
			return errors.Join(append(errs, err)...)
		}
		s.enqueueAuto(deployReasonExpire, servers)
	}
	return errors.Join(errs...)
}

// StartTasks 每项任务各自串行；首次等待一个周期，启动时不立即访问远端。
func (s *Server) StartTasks(ctx context.Context) {
	for _, t := range s.Tasks {
		go func() {
			timer := time.NewTicker(t.Interval)
			defer timer.Stop()
			for {
				select {
				case <-ctx.Done():
					return
				case <-timer.C:
					t.execute(ctx)
				}
			}
		}()
	}
}
func (s *Server) taskPage(w http.ResponseWriter, r *http.Request) {
	d := s.pageData(r, "定时任务", "tasks")
	views := map[string]taskView{}
	for key, t := range s.Tasks {
		t.mu.Lock()
		last := "尚未运行"
		if !t.Started.IsZero() {
			last = store.FormatTime(t.Started)
		}
		views[key] = taskView{Name: t.Name, Last: last, Running: t.Running, Result: t.Result}
		t.mu.Unlock()
	}
	d["Tasks"] = views
	s.render(w, "tasks", 200, d)
}
func (s *Server) taskRun(w http.ResponseWriter, r *http.Request) {
	t, ok := s.Tasks[r.PathValue("task")]
	if !ok {
		http.NotFound(w, r)
		return
	}
	http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Minute))
	t.execute(r.Context())
	redirectMsg(w, r, "/admin/tasks", "msg", fmt.Sprintf("%s已执行", t.Name))
}
