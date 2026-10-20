package monitor

import (
	"context"
	"math"
	"testing"
	"time"
)

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
	v, ok, err := ParseVnstat(raw, at)
	if err != nil || !ok || v.Iface != "eth0" || v.RxBytes != 100 || v.TxBytes != 200 {
		t.Fatalf("%v %v %+v", err, ok, v)
	}
	if _, ok, _ := ParseVnstat(raw, at.AddDate(0, 2, 0)); ok {
		t.Fatal("无本月数据应返回 false")
	}
	if _, ok, err := ParseVnstat("", at); ok || err != nil {
		t.Fatal("未安装 vnstat 不是错误")
	}
	if _, _, err := ParseVnstat("not json", at); err == nil {
		t.Fatal("应报错")
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
