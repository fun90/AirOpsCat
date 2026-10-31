package monitor

import (
	"context"
	"math"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func TestMonitorScriptLargeCounters(t *testing.T) {
	dir := t.TempDir()
	files := map[string]string{
		"stat":   "cpu 4000000000 0 0 4000000000 0 0 0 0\n",
		"net":    "header\nheader\n eth0: 452830000001 0 0 0 0 0 0 0 443988000001 0 0 0 0 0 0 0\n",
		"mem":    "MemTotal: 8000000 kB\nMemAvailable: 2000000 kB\n",
		"uptime": "100.00 0\n",
	}
	for name, content := range files {
		if err := os.WriteFile(filepath.Join(dir, name), []byte(content), 0600); err != nil {
			t.Fatal(err)
		}
	}
	// 用固定的大计数执行真实采集脚本，保留相邻采样间的小增量。
	advance := `printf 'cpu 4000000050 0 0 4000000050 0 0 0 0\n' > "` + filepath.Join(dir, "stat") + `"; ` +
		`printf 'header\nheader\n eth0: 452830002001 0 0 0 0 0 0 0 443988003001 0 0 0 0 0 0 0\n' > "` + filepath.Join(dir, "net") + `"; ` +
		`printf '101.00 0\n' > "` + filepath.Join(dir, "uptime") + `"`
	script := strings.NewReplacer("/proc/net/dev", `"`+filepath.Join(dir, "net")+`"`,
		"/proc/stat", `"`+filepath.Join(dir, "stat")+`"`,
		"/proc/meminfo", `"`+filepath.Join(dir, "mem")+`"`,
		"/proc/uptime", `"`+filepath.Join(dir, "uptime")+`"`, "sleep 1", advance).Replace(Script)
	out, err := exec.Command("sh", "-c", script).CombinedOutput()
	if err != nil {
		t.Fatalf("采集脚本失败: %v: %s", err, out)
	}
	s, err := ParseMonitor(string(out))
	if err != nil {
		t.Fatalf("大计数采集输出应可解析: %v\n%s", err, out)
	}
	if s.CPU != 0.5 || s.Memory != 0.75 || s.MemTotal != 8192000000 || s.RxRate != 2000 || s.TxRate != 3000 {
		t.Fatalf("大计数的小增量计算错误: %+v", s)
	}
}

func TestParseMonitor(t *testing.T) {
	out := "cpu 800 1000 850 1100\nnet 1000 2000 3000 5000\nelapsed 1000\nmem 8000000 2000000\n"
	s, err := ParseMonitor(out)
	if err != nil {
		t.Fatal(err)
	}
	// idle 增量 50，总增量 100 → CPU 50%
	if math.Abs(s.CPU-0.5) > 1e-9 || s.RxRate != 2000 || s.TxRate != 3000 || s.MemTotal != 8000000 || s.MemUsed != 6000000 || math.Abs(s.Memory-0.75) > 1e-9 {
		t.Fatalf("%+v", s)
	}
	// 计数回绕：速率为 0 而不是负数
	s, _ = ParseMonitor("cpu 1 2 3 4\nnet 500 500 100 100\nelapsed 1000\nmem 100 50")
	if s.RxRate != 0 || s.TxRate != 0 {
		t.Fatalf("%+v", s)
	}
	for _, bad := range []string{"", "cpu 1 2\nnet 1 2 3 4\nelapsed 1000\nmem 1 1", "cpu a b c d\nnet 1 2 3 4\nelapsed 1000\nmem 1 1", "cpu 1 2 3 4\nnet 1 2 3 4\nelapsed 1000\nmem 0 0"} {
		if _, err := ParseMonitor(bad); err == nil {
			t.Fatalf("应拒绝 %q", bad)
		}
	}
}

func TestParseVnstat(t *testing.T) {
	raw := `{"vnstatversion":"2.9","interfaces":[
 {"name":"lo","traffic":{"month":[{"date":{"year":2026,"month":10},"rx":999999,"tx":999999}]}},
 {"name":"eth0","traffic":{"month":[{"date":{"year":2026,"month":9},"rx":1,"tx":1},{"date":{"year":2026,"month":10},"rx":100,"tx":200}]}},
 {"name":"docker0","traffic":{"month":[{"date":{"year":2026,"month":10},"rx":5,"tx":5}]}}]}`
	at := time.Date(2026, 10, 20, 0, 0, 0, 0, time.UTC)
	v, ok, err := ParseVnstat(raw, at, 1)
	if err != nil || !ok || v.Iface != "eth0" || v.RxBytes != 100 || v.TxBytes != 200 {
		t.Fatalf("%v %v %+v", err, ok, v)
	}
	if _, ok, _ := ParseVnstat(raw, at.AddDate(0, 2, 0), 1); ok {
		t.Fatal("无本月数据应返回 false")
	}
	if _, ok, _ := ParseVnstat(raw, at, 24); ok {
		t.Fatal("重置日非 1 号时不应使用自然月数据")
	}
	if _, ok, err := ParseVnstat("", at, 1); ok || err != nil {
		t.Fatal("未安装 vnstat 不是错误")
	}
	if _, _, err := ParseVnstat("not json", at, 1); err == nil {
		t.Fatal("应报错")
	}
}

func TestParseVnstatDaily(t *testing.T) {
	raw := `{"interfaces":[{"name":"eth0","traffic":{"day":[
 {"date":{"year":2026,"month":9,"day":23},"rx":1000,"tx":1000},
 {"date":{"year":2026,"month":9,"day":24},"rx":1,"tx":2},
 {"date":{"year":2026,"month":10,"day":23},"rx":10,"tx":20},
 {"date":{"year":2026,"month":10,"day":24},"rx":100,"tx":200}]}}]}`
	v, ok, err := ParseVnstat(raw, time.Date(2026, 10, 23, 12, 0, 0, 0, time.UTC), 24)
	if err != nil || !ok || v.Year != 2026 || v.Month != 9 || v.RxBytes != 11 || v.TxBytes != 22 {
		t.Fatalf("应汇总 9-24 至 10-23：%v %v %+v", err, ok, v)
	}
	v, ok, _ = ParseVnstat(raw, time.Date(2026, 10, 24, 0, 0, 0, 0, time.UTC), 24)
	if !ok || v.Month != 10 || v.RxBytes != 100 || v.TxBytes != 200 {
		t.Fatalf("重置日当天应进入新周期：%+v", v)
	}
}

func TestTrafficPeriod(t *testing.T) {
	for _, c := range []struct {
		reset      int
		at         time.Time
		start, end string
	}{
		{1, time.Date(2026, 10, 31, 9, 0, 0, 0, time.UTC), "2026-10-01", "2026-10-31"},
		{0, time.Date(2026, 2, 3, 0, 0, 0, 0, time.UTC), "2026-02-01", "2026-02-28"},
		{24, time.Date(2026, 10, 31, 0, 0, 0, 0, time.UTC), "2026-10-24", "2026-11-23"},
		{24, time.Date(2026, 1, 5, 0, 0, 0, 0, time.UTC), "2025-12-24", "2026-01-23"},
		{31, time.Date(2026, 3, 5, 0, 0, 0, 0, time.UTC), "2026-02-28", "2026-03-30"},
		{31, time.Date(2026, 2, 28, 0, 0, 0, 0, time.UTC), "2026-02-28", "2026-03-30"},
	} {
		s, e := TrafficPeriod(c.reset, c.at)
		if s.Format("2006-01-02") != c.start || e.Format("2006-01-02") != c.end {
			t.Errorf("reset=%d at=%s: %s ~ %s", c.reset, c.at, s.Format("2006-01-02"), e.Format("2006-01-02"))
		}
	}
}

type fakeRunner struct{ out string }

func (f fakeRunner) Run(context.Context, string) (string, string, int, error) {
	return f.out, "", 0, nil
}

func TestCollect(t *testing.T) {
	s, err := Collect(context.Background(), fakeRunner{"cpu 0 0 0 100\nnet 0 0 10 20\nelapsed 1000\nmem 100 40"})
	if err != nil || s.CPU != 1 || s.RxRate != 10 {
		t.Fatalf("%v %+v", err, s)
	}
}

func TestSustained(t *testing.T) {
	now := time.Date(2026, 10, 20, 12, 0, 0, 0, time.UTC)
	iv := 2 * time.Minute
	mk := func(vals ...float64) []Point { // 从 now 向前每 iv 一个点，vals[0] 为最新
		var p []Point
		for i, v := range vals {
			p = append(p, Point{now.Add(-time.Duration(i) * iv), v})
		}
		return p
	}
	check := func(pts []Point, wantOver, wantCovered bool, msg string) {
		t.Helper()
		if o, c := Sustained(pts, now, 10*time.Minute, iv, 0.9); o != wantOver || c != wantCovered {
			t.Fatalf("%s: over=%v covered=%v", msg, o, c)
		}
	}
	check(mk(0.95, 0.95, 0.95, 0.95, 0.95, 0.95), true, true, "持续超阈值应告警")
	check(mk(0.95, 0.5, 0.95, 0.95, 0.95, 0.95), false, true, "中间一次低于阈值：有结论但不告警")
	check(mk(0.95, 0.95, 0.95), false, false, "样本未覆盖窗口：无结论")
	check(mk(0.95), false, false, "单个样本：无结论")
	stale := []Point{{now.Add(-20 * time.Minute), 1}, {now.Add(-18 * time.Minute), 1}, {now.Add(-12 * time.Minute), 1}}
	check(stale, false, false, "采集停止后不沿用旧数据")
	gap := []Point{{now, 1}, {now.Add(-2 * time.Minute), 1}, {now.Add(-9 * time.Minute), 1}, {now.Add(-10 * time.Minute), 1}}
	check(gap, false, false, "中间缺口过大：无结论")
}

func TestNormalizeThreshold(t *testing.T) {
	for in, want := range map[float64]float64{0.9: 0.9, 90: 0.9, 0: 0.85, -1: 0.85, 150: 1} {
		if got := NormalizeThreshold(in, 0.85); math.Abs(got-want) > 1e-9 {
			t.Fatalf("%v → %v, want %v", in, got, want)
		}
	}
}

func TestParseMonitorDisk(t *testing.T) {
	base := "cpu 800 1000 850 1100\nnet 1000 2000 3000 5000\nelapsed 1000\nmem 8000000 2000000\n"
	s, err := ParseMonitor(base + "disk 30 120\n")
	if err != nil || s.DiskUsed != 30 || s.DiskTotal != 120 || math.Abs(s.Disk-0.25) > 1e-9 {
		t.Fatalf("%+v %v", s, err)
	}
	// 缺少 disk 行（df 不可用）不影响其余指标，且不产生虚假的 0%
	if s, err = ParseMonitor(base); err != nil || s.DiskTotal != 0 {
		t.Fatalf("%+v %v", s, err)
	}
}

func TestParseMonitorSingbox(t *testing.T) {
	base := "cpu 800 1000 850 1100\nnet 1000 2000 3000 5000\nelapsed 1000\nmem 8000000 2000000\n"
	for out, want := range map[string]string{base: "", base + "sb 0\n": "active", base + "sb 1\n": "down"} {
		if s, err := ParseMonitor(out); err != nil || s.Singbox != want {
			t.Fatalf("%q: %+v %v", out, s, err)
		}
	}
}
