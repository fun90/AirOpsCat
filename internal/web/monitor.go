package web

import (
	"context"
	"fmt"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/store"
)

const (
	monitorInterval = 2 * time.Minute // 采集周期固定，不提供暂停
	vnstatInterval  = 5 * time.Minute
)

func (s *Server) monitorRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/monitor", s.monitorList)
	m.HandleFunc("GET /admin/monitor/{id}", s.monitorDetail)
}

// forServers 并发执行 fn，最多 max-parallel-servers 台同时进行，汇总失败信息。
func (s *Server) forServers(ctx context.Context, ids []int64, fn func(context.Context, int64) error) error {
	sem := make(chan struct{}, s.configInt(ctx, "airopscat.server.monitor.max-parallel-servers", 10))
	var mu sync.Mutex
	var failed []string
	var wg sync.WaitGroup
	for _, id := range ids {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			cctx, cancel := context.WithTimeout(ctx, 40*time.Second)
			defer cancel()
			if err := fn(cctx, id); err != nil {
				mu.Lock()
				failed = append(failed, fmt.Sprintf("服务器 %d：%v", id, err))
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	if len(failed) > 0 {
		return fmt.Errorf("%s", strings.Join(failed, "；"))
	}
	return nil
}

func (s *Server) dialServer(ctx context.Context, id int64) (monitor.Runner, func(), error) {
	t, err := store.SSHTargetByID(ctx, s.DB, id)
	if err != nil {
		return nil, nil, err
	}
	if t == nil {
		return nil, nil, fmt.Errorf("服务器不存在")
	}
	r, err := s.Deployer.Dial(ctx, t)
	if err != nil {
		return nil, nil, err
	}
	return r, func() { r.Close() }, nil
}

// collectMonitor 只对显式启用的服务器（AIROPSCAT_MONITOR_SERVERS）经 SSH 只读采集，随后评估告警。
func (s *Server) collectMonitor(ctx context.Context) error {
	if !s.configBool(ctx, "airopscat.server.monitor.enabled", true) {
		return nil
	}
	ids, err := store.MonitorServers(ctx, s.DB, s.MonitorServers, time.Now())
	if err != nil {
		return err
	}
	collectErr := s.forServers(ctx, ids, func(ctx context.Context, id int64) error {
		r, closeFn, err := s.dialServer(ctx, id)
		if err != nil {
			return err
		}
		defer closeFn()
		sample, err := monitor.Collect(ctx, r)
		if err != nil {
			return err
		}
		return store.InsertMonitorSample(ctx, s.DB, id, sample, time.Now())
	})
	if err := s.evaluateServerAlerts(ctx); err != nil {
		return err
	}
	return collectErr
}

func (s *Server) collectVnstat(ctx context.Context) error {
	if !s.configBool(ctx, "airopscat.server.monitor.enabled", true) {
		return nil
	}
	ids, err := store.MonitorServers(ctx, s.DB, s.MonitorServers, time.Now())
	if err != nil {
		return err
	}
	collectErr := s.forServers(ctx, ids, func(ctx context.Context, id int64) error {
		r, closeFn, err := s.dialServer(ctx, id)
		if err != nil {
			return err
		}
		defer closeFn()
		v, ok, err := monitor.CollectVnstat(ctx, r, time.Now().In(store.Shanghai))
		if err != nil || !ok {
			return err // 未安装 vnstat 或无本月数据不算失败
		}
		return store.InsertVnstatSample(ctx, s.DB, id, v, time.Now())
	})
	if err := s.evaluateServerAlerts(ctx); err != nil {
		return err
	}
	if err := s.autoDNS(ctx); err != nil {
		return err
	}
	return collectErr
}

// evaluateServerAlerts 判定 CPU/内存持续超阈值与流量用量；样本不足时既不告警也不恢复，避免采集故障造成误恢复。
func (s *Server) evaluateServerAlerts(ctx context.Context) error {
	now := time.Now()
	threshold := func(key string, def float64) float64 {
		v, _ := strconv.ParseFloat(store.ConfigValue(ctx, s.DB, key), 64)
		return monitor.NormalizeThreshold(v, def)
	}
	cpuT := threshold("airopscat.server.monitor.alert.cpu-threshold", 0.9)
	memT := threshold("airopscat.server.monitor.alert.memory-threshold", 0.95)
	trafficT := threshold("airopscat.server.monitor.alert.traffic-threshold", 0.85)
	window := time.Duration(s.configInt(ctx, "airopscat.server.monitor.alert.continuous-minutes", 30)) * time.Minute
	interval := time.Duration(s.configInt(ctx, "airopscat.server.monitor.alert.min-interval-minutes", 60)) * time.Minute
	statuses, _, err := store.ServerStatuses(ctx, s.DB, "", 1, 100000, now)
	if err != nil {
		return err
	}
	for _, st := range statuses {
		label := st.Name + " (" + st.IP + ")"
		for _, m := range []struct {
			fp, column, name string
			t                float64
		}{{"cpu", "cpu_usage", "CPU使用率", cpuT}, {"memory", "memory_usage", "内存使用率", memT}} {
			pts, err := store.MetricPoints(ctx, s.DB, st.ID, m.column, now.Add(-window-monitorInterval))
			if err != nil {
				return err
			}
			over, covered := monitor.Sustained(pts, now, window, monitorInterval, m.t)
			if !covered {
				continue
			}
			fp := m.fp + ":" + strconv.FormatInt(st.ID, 10)
			if !over {
				if _, err := store.AlertResolveFingerprint(ctx, s.DB, alertMonitorLoad, "server", st.ID, fp, now); err != nil {
					return err
				}
				continue
			}
			latest := pts[len(pts)-1].Value
			summary := fmt.Sprintf("%s %s连续%d分钟达到 %.0f%%", label, m.name, int(window.Minutes()), latest*100)
			if err := s.raise(ctx, store.AlertKey{Type: alertMonitorLoad, ResourceType: "server", ResourceID: st.ID, ResourceKey: st.IP, Fingerprint: fp}, "warning",
				latest, m.t, summary, interval, notify.Message{Title: "AirOpsCat 服务器负载告警", Body: summary, Level: "timeSensitive"}); err != nil {
				return err
			}
		}
		vnstatAt, vnstatErr := store.ParseTime(st.VnstatTime)
		if st.BandwidthGB <= 0 || !st.HasVnstat || vnstatErr != nil || now.Sub(vnstatAt) > 3*vnstatInterval {
			continue
		}
		fp := "traffic:" + strconv.FormatInt(st.ID, 10)
		ratio := st.UsageRatio()
		if ratio < trafficT {
			if _, err := store.AlertResolveFingerprint(ctx, s.DB, alertMonitorLoad, "server", st.ID, fp, now); err != nil {
				return err
			}
			continue
		}
		used := float64(st.VnstatRx+st.VnstatTx) / (1 << 30)
		summary := fmt.Sprintf("%s 累计流量达到 %.0f%% (%.2f GB / %d GB)", label, ratio*100, used, st.BandwidthGB)
		if err := s.raise(ctx, store.AlertKey{Type: alertMonitorLoad, ResourceType: "server", ResourceID: st.ID, ResourceKey: st.IP, Fingerprint: fp}, "warning",
			ratio, trafficT, summary, interval, notify.Message{Title: "AirOpsCat 服务器流量告警", Body: summary, Level: "timeSensitive"}); err != nil {
			return err
		}
	}
	return nil
}

// ---------- 页面 ----------

type statusView struct {
	store.ServerStatus
	CPUText, MemText, RateText, UsageText string
	Stale                                 bool
	Online                                string
	UsagePct                              int
}

func pct(v *float64) string {
	if v == nil {
		return "—"
	}
	return fmt.Sprintf("%.0f%%", *v*100)
}

func humanRate(b int64) string {
	switch {
	case b >= 1<<20:
		return fmt.Sprintf("%.1f MB/s", float64(b)/(1<<20))
	case b >= 1<<10:
		return fmt.Sprintf("%.0f KB/s", float64(b)/(1<<10))
	}
	return fmt.Sprintf("%d B/s", b)
}

func newStatusView(st store.ServerStatus, now time.Time) statusView {
	v := statusView{ServerStatus: st, CPUText: pct(st.CPU), MemText: pct(st.Memory), RateText: "—", UsageText: "—"}
	if st.RxRate != nil && st.TxRate != nil {
		v.RateText = "↓ " + humanRate(*st.RxRate) + " ↑ " + humanRate(*st.TxRate)
	}
	if st.HasVnstat {
		used := float64(st.VnstatRx+st.VnstatTx) / (1 << 30)
		if st.BandwidthGB > 0 {
			v.UsageText = fmt.Sprintf("%.1f / %d GB（%.1f%%）", used, st.BandwidthGB, st.UsageRatio()*100)
			v.UsagePct = min(int(st.UsageRatio()*100), 100)
		} else {
			v.UsageText = fmt.Sprintf("%.1f GB", used)
		}
	}
	if t, err := store.ParseTime(st.Sampled); err == nil {
		v.Stale = now.Sub(t) > 3*monitorInterval
	}
	return v
}

func (s *Server) monitorList(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	now := time.Now()
	list, total, err := store.ServerStatuses(r.Context(), s.DB, q, page, size, now)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	snap, err := s.onlineSnapshot(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	views := make([]statusView, len(list))
	for i, st := range list {
		views[i] = newStatusView(st, now)
		views[i].Online = serverOnlineText(snap, st.IP)
	}
	d := s.pageData(r, "服务器监控", "monitor")
	d["List"], d["Q"], d["Page"], d["Pages"] = views, q, page, (total+size-1)/size
	d["Enabled"] = strings.TrimSpace(s.MonitorServers) != ""
	s.render(w, "monitor", http.StatusOK, d)
}

type chartView struct {
	Title, Points, Last string
}

// svgPoints 把 0–1 的序列映射到 300×80 的折线坐标；时间轴固定为最近 24 小时。
func svgPoints(pts []monitor.Point, end time.Time) string {
	if len(pts) < 2 {
		return ""
	}
	var b strings.Builder
	start := end.Add(-24 * time.Hour)
	for _, p := range pts {
		x := float64(p.At.Sub(start)) / float64(24*time.Hour) * 300
		y := 78 - min(max(p.Value, 0), 1)*76
		if x < 0 {
			continue
		}
		fmt.Fprintf(&b, "%.1f,%.1f ", x, y)
	}
	return strings.TrimSpace(b.String())
}

func (s *Server) monitorDetail(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	now := time.Now()
	list, _, err := store.ServerStatuses(r.Context(), s.DB, "", 1, 100000, now)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var st *store.ServerStatus
	for i := range list {
		if list[i].ID == id {
			st = &list[i]
		}
	}
	if st == nil {
		http.NotFound(w, r)
		return
	}
	var charts []chartView
	for _, c := range []struct{ title, col string }{{"CPU 使用率", "cpu_usage"}, {"内存使用率", "memory_usage"}} {
		pts, err := store.MetricPoints(r.Context(), s.DB, id, c.col, now.Add(-24*time.Hour))
		if err != nil {
			s.fail(w, r, err)
			return
		}
		last := "—"
		if len(pts) > 0 {
			last = fmt.Sprintf("%.0f%%", pts[len(pts)-1].Value*100)
		}
		charts = append(charts, chartView{c.title, svgPoints(pts, now), last})
	}
	snap, err := s.onlineSnapshot(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	sv := newStatusView(*st, now)
	sv.Online = serverOnlineText(snap, st.IP)
	d := s.pageData(r, st.Name+" 监控", "monitor")
	d["Server"], d["Charts"] = sv, charts
	s.render(w, "monitor_detail", http.StatusOK, d)
}
