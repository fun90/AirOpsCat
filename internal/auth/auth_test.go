package auth

import (
	"context"
	"errors"
	"net/http/httptest"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func newTestService(t *testing.T) (*Service, *time.Time) {
	t.Helper()
	db, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "t.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	hash, _ := HashPassword("secret123")
	if _, err := store.CreateUser(context.Background(), db, "Admin@Example.com", hash, "ADMIN", ""); err != nil {
		t.Fatal(err)
	}
	now := time.Date(2026, 10, 19, 10, 0, 0, 0, store.Shanghai)
	s := NewService(db, []byte("session-key-0123456789abcdef0123"), []byte("csrf-key"))
	s.Now = func() time.Time { return now }
	return s, &now
}

func TestLoginLockout(t *testing.T) {
	s, now := newTestService(t)
	ctx := context.Background()
	if _, err := s.Login(ctx, "admin@example.com", "secret123"); err != nil {
		t.Fatalf("邮箱应不区分大小写: %v", err)
	}
	for range MaxAttempts {
		if _, err := s.Login(ctx, "admin@example.com", "bad"); !errors.Is(err, ErrBadCredentials) {
			t.Fatal(err)
		}
	}
	var locked *LockedError
	if _, err := s.Login(ctx, "admin@example.com", "secret123"); !errors.As(err, &locked) {
		t.Fatalf("应处于锁定: %v", err)
	}

	// 锁定过期后失败计数清零：再失败一次不会立即重新锁定
	*now = now.Add(LockDuration + time.Second)
	if _, err := s.Login(ctx, "admin@example.com", "bad"); !errors.Is(err, ErrBadCredentials) {
		t.Fatal(err)
	}
	if _, err := s.Login(ctx, "admin@example.com", "secret123"); err != nil {
		t.Fatalf("过期后应能登录: %v", err)
	}
}

func TestConcurrentFailuresAllCounted(t *testing.T) {
	s, _ := newTestService(t)
	ctx := context.Background()
	var wg sync.WaitGroup
	for range 8 {
		wg.Add(1)
		go func() { defer wg.Done(); s.Login(ctx, "admin@example.com", "bad") }()
	}
	wg.Wait()
	u, _ := store.UserByEmail(ctx, s.DB, "admin@example.com")
	if u.FailedAttempts != 8 || u.LockTime == nil {
		t.Fatalf("failed=%d lock=%v", u.FailedAttempts, u.LockTime)
	}
	var locked *LockedError
	if _, err := s.Login(ctx, "admin@example.com", "secret123"); !errors.As(err, &locked) {
		t.Fatal("并发失败后应锁定")
	}
}

func TestTokenLifecycle(t *testing.T) {
	s, now := newTestService(t)
	ctx := context.Background()
	u, _ := s.Login(ctx, "admin@example.com", "secret123")
	tok := s.IssueToken(u, SessionMaxAge)
	if got, _ := s.Verify(ctx, tok); got == nil {
		t.Fatal("令牌应有效")
	}
	if got, _ := s.Verify(ctx, tok[:len(tok)-1]+"x"); got != nil {
		t.Fatal("篡改签名应无效")
	}
	other := NewService(s.DB, []byte("other-key"), nil)
	other.Now = s.Now
	if got, _ := other.Verify(ctx, tok); got != nil {
		t.Fatal("不同密钥签发的令牌应无效")
	}

	// 改密码后旧令牌失效
	hash, _ := HashPassword("newpass123")
	s.DB.Exec(`UPDATE user SET password = ? WHERE id = ?`, hash, u.ID)
	if got, _ := s.Verify(ctx, tok); got != nil {
		t.Fatal("改密后旧令牌应失效")
	}
	u, _ = s.Login(ctx, "admin@example.com", "newpass123")
	tok = s.IssueToken(u, SessionMaxAge)
	*now = now.Add(SessionMaxAge)
	if got, _ := s.Verify(ctx, tok); got != nil {
		t.Fatal("过期令牌应无效")
	}
}

func TestCSRF(t *testing.T) {
	s, _ := newTestService(t)
	tok := "a.b.c"
	if !s.CheckCSRF(tok, s.CSRFToken(tok)) || s.CheckCSRF(tok, "") || s.CheckCSRF(tok, s.CSRFToken("x.y.z")) {
		t.Fatal("CSRF 校验结果不对")
	}
}

func TestClientIPTrustsOnlyLoopbackProxy(t *testing.T) {
	r := httptest.NewRequest("POST", "/login", nil)
	r.RemoteAddr = "203.0.113.5:1234"
	r.Header.Set("X-Forwarded-For", "1.2.3.4")
	if ClientIP(r) != "203.0.113.5" {
		t.Fatal("非本机来源不应信任转发头")
	}
	r.RemoteAddr = "127.0.0.1:1234"
	if ClientIP(r) != "1.2.3.4" {
		t.Fatal("本机反向代理应采用 X-Forwarded-For")
	}
}

func TestRateLimiter(t *testing.T) {
	l := NewRateLimiter(2, time.Minute)
	now := time.Now()
	l.Now = func() time.Time { return now }
	if !l.Allow("a") || !l.Allow("a") || l.Allow("a") {
		t.Fatal("第三次应被拒绝")
	}
	now = now.Add(time.Minute)
	if !l.Allow("a") {
		t.Fatal("新窗口应放行")
	}
}
