package web

import (
	"bytes"
	"errors"
	"log/slog"
	"strings"
	"testing"
	"time"
)

func TestTaskFailureLogDedup(t *testing.T) {
	var buf bytes.Buffer
	old := slog.Default()
	slog.SetDefault(slog.New(slog.NewTextHandler(&buf, nil)))
	defer slog.SetDefault(old)

	task := &taskState{Name: "t"}
	now := time.Now()
	e := errors.New("服务器 2：超时\n服务器 3：偶发")
	task.logFailures(e, now)
	task.logFailures(errors.New("服务器 2：超时"), now.Add(5*time.Minute))
	task.logFailures(errors.New("服务器 2：超时"), now.Add(10*time.Minute))
	if n := strings.Count(buf.String(), "服务器 2"); n != 1 {
		t.Fatalf("重复错误应只记一次，实际 %d：%s", n, buf.String())
	}
	if n := strings.Count(buf.String(), "服务器 3"); n != 1 {
		t.Fatalf("服务器 3 应记一次，实际 %d", n)
	}
	task.logFailures(errors.New("服务器 2：超时"), now.Add(70*time.Minute))
	if !strings.Contains(buf.String(), "累计次数=4") {
		t.Fatalf("超过间隔应带累计次数重新记录：%s", buf.String())
	}
	task.logFailures(nil, now.Add(80*time.Minute))
	buf.Reset()
	task.logFailures(errors.New("服务器 2：超时"), now.Add(85*time.Minute))
	if !strings.Contains(buf.String(), "定时任务失败") {
		t.Fatal("恢复后再次失败应立即记录")
	}
}
