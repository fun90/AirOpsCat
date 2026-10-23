package web

import (
	"bytes"
	"context"
	"encoding/json"
	"net/http/httptest"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
	"github.com/fun90/airopscat/internal/store"
)

func TestMonitorAgentAuthenticationAndCounters(t *testing.T) {
	s := guardServer(t)
	ctx := context.Background()
	token, err := store.RotateMonitorAgentToken(ctx, s.DB, 1)
	if err != nil {
		t.Fatal(err)
	}
	ids, err := store.MonitorSSHServers(ctx, s.DB, "all", time.Now())
	if err != nil || len(ids) != 1 || ids[0] != 1 {
		t.Fatal("仅登记 Token 不应停止 SSH 采集", ids, err)
	}
	report := monitor.AgentReport{ServerID: 1, SampledAt: time.Now().Unix() - 10, BootID: "boot-a", UptimeMS: 10000, CPUIdle: 100, CPUTotal: 200, RxBytes: 1000, TxBytes: 2000, MemTotal: 1000, MemAvailable: 400}
	send := func(token string, r monitor.AgentReport) int {
		body, _ := json.Marshal(r)
		req := httptest.NewRequest("POST", "/api/open/monitor-report", bytes.NewReader(body))
		req.Header.Set("Authorization", "Bearer "+token)
		w := httptest.NewRecorder()
		s.Handler().ServeHTTP(w, req)
		return w.Code
	}
	if got := send("bad", report); got != 401 {
		t.Fatal(got)
	}
	other := report
	other.ServerID = 2
	if got := send(token, other); got != 401 {
		t.Fatal("跨服务器 Token 应拒绝", got)
	}
	if got := send(token, report); got != 200 {
		t.Fatal(got)
	}
	ids, err = store.MonitorSSHServers(ctx, s.DB, "all", time.Now())
	if err != nil || len(ids) != 0 {
		t.Fatal("agent 上报后应停用 SSH 监控", ids, err)
	}
	if got := send(token, report); got != 422 {
		t.Fatal("重复采样应拒绝", got)
	}
	report.Vnstat = `{"interfaces":[{"name":"eth0","traffic":{"month":[{"date":{"year":` + time.Now().In(store.Shanghai).Format("2006") + `,"month":` + time.Now().In(store.Shanghai).Format("1") + `},"rx":1000,"tx":2000}]}}]}`
	report.SampledAt += 10
	report.UptimeMS += 10000
	report.CPUIdle += 20
	report.CPUTotal += 100
	report.RxBytes += 10000
	report.TxBytes += 20000
	if got := send(token, report); got != 200 {
		t.Fatal(got)
	}
	var cpu float64
	var rx, tx int64
	if err := s.DB.QueryRow(`SELECT cpu_usage,rx_rate,tx_rate FROM server_metrics WHERE server_id=1 AND kind='monitor'`).Scan(&cpu, &rx, &tx); err != nil {
		t.Fatal(err)
	}
	var vnCount int
	if err := s.DB.QueryRow(`SELECT count(*) FROM server_metrics WHERE kind='vnstat' AND rx_bytes=1000 AND tx_bytes=2000`).Scan(&vnCount); err != nil || vnCount != 1 {
		t.Fatal("月流量未保存", vnCount, err)
	}
	if cpu != 0.8 || rx != 1000 || tx != 2000 {
		t.Fatal(cpu, rx, tx)
	}
	report.SampledAt++
	report.MemAvailable = 2000
	if got := send(token, report); got != 422 {
		t.Fatal("非法内存应拒绝", got)
	}
	report.MemAvailable = 400
	report.SampledAt -= 100
	if got := send(token, report); got != 422 {
		t.Fatal("过期采样应拒绝", got)
	}
	rotated, err := store.RotateMonitorAgentToken(ctx, s.DB, 1)
	if err != nil || rotated == token {
		t.Fatal(err)
	}
	if got := send(token, report); got != 401 {
		t.Fatal("旧 Token 应失效", got)
	}
}

func TestMonitorAgentCounterResets(t *testing.T) {
	p := monitor.AgentReport{BootID: "a", UptimeMS: 10000, CPUIdle: 100, CPUTotal: 200, RxBytes: 1000, TxBytes: 2000}
	current := p
	current.UptimeMS += 10000
	current.CPUIdle += 20
	current.CPUTotal += 100
	current.RxBytes += 1000
	current.TxBytes += 2000
	current.MemTotal = 1000
	current.MemAvailable = 500
	for _, mutate := range []func(*monitor.AgentReport){
		func(r *monitor.AgentReport) { r.BootID = "b" },
		func(r *monitor.AgentReport) { r.RxBytes = 0 },
		func(r *monitor.AgentReport) { r.UptimeMS = p.UptimeMS },
		func(r *monitor.AgentReport) { r.UptimeMS += 40000 },
		func(r *monitor.AgentReport) { r.CPUIdle += 1000 },
	} {
		r := current
		mutate(&r)
		if _, ok := monitor.SampleFromCounters(p, r); ok {
			t.Fatal("异常计数应重建基线", r)
		}
	}
}
