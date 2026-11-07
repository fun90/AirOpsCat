package web

import (
	"context"
	"path/filepath"
	"sync/atomic"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/store"
)

func TestFirstDelay(t *testing.T) {
	now := time.Date(2026, 11, 7, 12, 0, 0, 0, store.Shanghai)
	day := 24 * time.Hour
	for _, c := range []struct {
		name     string
		interval time.Duration
		last     time.Time
		want     time.Duration
	}{
		{"短周期任务等一个周期", time.Minute, now.Add(-time.Hour), time.Minute},
		{"无记录取最短等待", day, time.Time{}, taskStartDelay},
		{"早已到期取最短等待", day, now.Add(-30 * day), taskStartDelay},
		{"刚执行过等到下个周期", day, now.Add(-time.Hour), 23 * time.Hour},
		{"临近到期不早于最短等待", day, now.Add(-day + time.Minute), taskStartDelay},
		{"不超过一个周期", day, now.Add(time.Hour), day},
		{"1 小时任务", time.Hour, now.Add(-20 * time.Minute), 40 * time.Minute},
	} {
		if got := firstDelay(c.interval, c.last, now); got != c.want {
			t.Errorf("%s: got %v want %v", c.name, got, c.want)
		}
	}
}

func newScheduleServer(t *testing.T) *Server {
	t.Helper()
	ctx := context.Background()
	db, err := store.Open(ctx, filepath.Join(t.TempDir(), "s.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	field, _ := crypto.New(crypto.DeriveKey("test-secret-key-0123456789abcdef", "field"))
	s, err := New(db, auth.NewService(db, []byte("k"), []byte("c")), field)
	if err != nil {
		t.Fatal(err)
	}
	return s
}

// 长周期任务执行后持久化开始时间，下次启动读回并按其续排；短周期任务不写库。
func TestStartTasksPersistsLongIntervalRuns(t *testing.T) {
	s := newScheduleServer(t)
	var daily, short atomic.Int32
	s.Tasks = map[string]*taskState{
		"daily": {Name: "每日", Interval: 24 * time.Hour, Run: func(context.Context) error { daily.Add(1); return nil }},
		"short": {Name: "短", Interval: time.Hour - time.Second, Run: func(context.Context) error { short.Add(1); return nil }},
	}
	ctx, cancel := context.WithCancel(context.Background())
	s.StartTasks(ctx)
	cancel()
	if daily.Load() != 0 || short.Load() != 0 {
		t.Fatal("启动时不应立即执行")
	}
	// 手动执行写入记录
	s.Tasks["daily"].execute(context.Background())
	s.Tasks["short"].execute(context.Background())
	last, err := store.TaskLastStarts(context.Background(), s.DB)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := last["short"]; ok || len(last) != 1 {
		t.Fatalf("只应持久化长周期任务: %v", last)
	}
	started := s.Tasks["daily"].Started
	if got := last["daily"]; got.Unix() != started.Unix() {
		t.Fatalf("应记录本次开始时间: got %v want %v", got, started)
	}
	// 较早的开始时间不覆盖较新的记录
	if err := store.SaveTaskStart(context.Background(), s.DB, "daily", started.Add(-time.Hour)); err != nil {
		t.Fatal(err)
	}
	if last, _ = store.TaskLastStarts(context.Background(), s.DB); last["daily"].Unix() != started.Unix() {
		t.Fatalf("记录不应后退: %v", last["daily"])
	}
	// 模拟重启：新进程读回上次开始时间，任务页显示该时间
	s2 := &Server{DB: s.DB, Tasks: map[string]*taskState{
		"daily": {Name: "每日", Interval: 24 * time.Hour, Run: func(context.Context) error { return nil }},
	}}
	ctx2, cancel2 := context.WithCancel(context.Background())
	defer cancel2()
	s2.StartTasks(ctx2)
	if got := s2.Tasks["daily"].untilDue(time.Now()); got < 23*time.Hour || got > 24*time.Hour {
		t.Fatalf("重启后应按上次开始时间续排，剩余 %v", got)
	}
}

// 手动执行会把下次定时执行顺延一个周期；并发的手动与定时执行不会重叠。
func TestTaskLoopDefersAfterManualRun(t *testing.T) {
	var runs atomic.Int32
	task := &taskState{Name: "t", Interval: 2 * time.Second, Run: func(context.Context) error {
		runs.Add(1)
		time.Sleep(20 * time.Millisecond)
		return nil
	}}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go task.loop(ctx, task.Interval)
	time.Sleep(500 * time.Millisecond)
	task.execute(context.Background())  // 手动执行，下次定时应在约 2.5 秒
	time.Sleep(1700 * time.Millisecond) // 约 2.2 秒：原定 2 秒的定时执行应被顺延
	if n := runs.Load(); n != 1 {
		t.Fatalf("手动执行后原定时刻不应再执行，runs=%d", n)
	}
	time.Sleep(1000 * time.Millisecond) // 约 3.2 秒
	if n := runs.Load(); n != 2 {
		t.Fatalf("顺延后应执行一次，runs=%d", n)
	}
}
