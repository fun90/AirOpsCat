package web

import (
	"context"
	"fmt"
	"log/slog"
	"net/http"
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
}
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
		slog.Error("定时任务失败", "task", t.Name, "err", err)
	}
	t.mu.Unlock()
}
func (s *Server) initTasks() {
	s.Tasks = map[string]*taskState{
		"core-backup-cleanup": {Name: "远端内核配置备份清理", Interval: 24 * time.Hour, Run: s.cleanupCoreBackups},
		"dns-auto":            {Name: "DNS 时段与流量自动切换", Interval: time.Minute, Run: s.autoDNS},
		"traffic-collect":     {Name: "账户流量采集", Interval: 15 * time.Minute, Run: s.collectTraffic},
		"guard-evaluate": {Name: "跨节点配额评估", Interval: 10 * time.Second, Run: func(ctx context.Context) error {
			stats, err := s.Guard.Evaluate(ctx)
			if err != nil {
				return err
			}
			return s.alertConnections(ctx, stats)
		}},
		"server-monitor": {Name: "服务器指标采集（CPU/内存/网速）", Interval: monitorInterval, Run: s.collectMonitor},
		"server-vnstat":  {Name: "服务器月流量采集（vnstat）", Interval: vnstatInterval, Run: s.collectVnstat},
		"alert-scan":     {Name: "告警扫描（流量超额与到期）", Interval: 5 * time.Minute, Run: s.scanAlerts},
		"guard-evict":    {Name: "风控过期状态回收", Interval: 30 * time.Second, Run: s.Guard.Evict},
		"account-expire": {Name: "到期账户停用", Interval: time.Hour, Run: func(ctx context.Context) error { _, err := store.ExpireAccounts(ctx, s.DB, time.Now()); return err }},
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
