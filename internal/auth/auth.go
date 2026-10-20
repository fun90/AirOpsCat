// Package auth 负责登录校验、登录锁定、登录限流、会话 Cookie 和 CSRF 令牌。
package auth

import (
	"context"
	"crypto/hmac"
	"crypto/sha256"
	"database/sql"
	"encoding/base64"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/store"
	"golang.org/x/crypto/bcrypt"
)

const (
	CookieName     = "airopscat_session"
	MaxAttempts    = 5
	LockDuration   = 30 * time.Minute
	RememberMaxAge = 14 * 24 * time.Hour
	SessionMaxAge  = 12 * time.Hour
	BcryptCost     = 10
	MinPasswordLen = 6
)

var (
	ErrBadCredentials = errors.New("邮箱或密码错误")
	ErrDisabled       = errors.New("账号已被禁用")
)

// LockedError 表示账号处于锁定期。
type LockedError struct{ Remaining time.Duration }

func (e *LockedError) Error() string {
	return fmt.Sprintf("账号仍处于锁定状态，请 %d 分钟后再试", int(e.Remaining.Minutes())+1)
}

type Service struct {
	DB         *sql.DB
	sessionKey []byte
	csrfKey    []byte
	Now        func() time.Time
}

func NewService(db *sql.DB, sessionKey, csrfKey []byte) *Service {
	return &Service{DB: db, sessionKey: sessionKey, csrfKey: csrfKey, Now: time.Now}
}

var dummyHash = sync.OnceValue(func() []byte {
	h, _ := bcrypt.GenerateFromPassword([]byte("airopscat-dummy"), BcryptCost)
	return h
})

// HashPassword 生成 bcrypt 哈希。
func HashPassword(pw string) (string, error) {
	if len(pw) < MinPasswordLen {
		return "", fmt.Errorf("密码至少 %d 位", MinPasswordLen)
	}
	h, err := bcrypt.GenerateFromPassword([]byte(pw), BcryptCost)
	return string(h), err
}

// Login 校验邮箱密码，处理锁定与失败计数，成功返回用户。
func (s *Service) Login(ctx context.Context, email, password string) (*store.User, error) {
	u, err := store.UserByEmail(ctx, s.DB, strings.TrimSpace(email))
	if err != nil {
		return nil, err
	}
	if u == nil {
		// 用户不存在时也做一次哈希比较，避免通过耗时判断邮箱是否存在
		bcrypt.CompareHashAndPassword(dummyHash(), []byte(password))
		return nil, ErrBadCredentials
	}
	now := s.Now()
	if u.LockTime != nil {
		if until := u.LockTime.Add(LockDuration); now.Before(until) {
			return nil, &LockedError{Remaining: until.Sub(now)}
		}
		// 锁定已过期：失败次数一并清零，避免过期后一次失败立即再锁
		if err := store.ResetLoginFailures(ctx, s.DB, u.ID); err != nil {
			return nil, err
		}
	}
	if bcrypt.CompareHashAndPassword([]byte(u.Password), []byte(password)) != nil {
		if err := store.RecordLoginFailure(ctx, s.DB, u.ID, MaxAttempts, now); err != nil {
			return nil, err
		}
		return nil, ErrBadCredentials
	}
	if u.Disabled {
		return nil, ErrDisabled
	}
	if u.FailedAttempts > 0 || u.LockTime != nil {
		if err := store.ResetLoginFailures(ctx, s.DB, u.ID); err != nil {
			return nil, err
		}
	}
	return u, nil
}

// IssueToken 生成会话令牌：b64url(email).过期秒.b64url(HMAC)。签名包含密码哈希，改密码后旧令牌失效。
func (s *Service) IssueToken(u *store.User, maxAge time.Duration) string {
	payload := base64.RawURLEncoding.EncodeToString([]byte(u.Email)) + "." + strconv.FormatInt(s.Now().Add(maxAge).Unix(), 10)
	return payload + "." + s.sign(payload, u.Password)
}

func (s *Service) sign(payload, passwordHash string) string {
	m := hmac.New(sha256.New, s.sessionKey)
	m.Write([]byte(payload + "\x00" + passwordHash))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

// Verify 校验令牌，返回有效且未禁用的用户；无效返回 nil。
func (s *Service) Verify(ctx context.Context, token string) (*store.User, error) {
	parts := strings.Split(token, ".")
	if len(parts) != 3 {
		return nil, nil
	}
	email, err := base64.RawURLEncoding.DecodeString(parts[0])
	if err != nil {
		return nil, nil
	}
	exp, err := strconv.ParseInt(parts[1], 10, 64)
	if err != nil || s.Now().Unix() >= exp {
		return nil, nil
	}
	u, err := store.UserByEmail(ctx, s.DB, string(email))
	if err != nil || u == nil || u.Disabled {
		return nil, err
	}
	if !hmac.Equal([]byte(s.sign(parts[0]+"."+parts[1], u.Password)), []byte(parts[2])) {
		return nil, nil
	}
	return u, nil
}

// SetCookie 写入会话 Cookie；HTTPS（含反向代理转发）时加 Secure。
func SetCookie(w http.ResponseWriter, r *http.Request, token string, maxAge time.Duration) {
	http.SetCookie(w, &http.Cookie{
		Name: CookieName, Value: token, Path: "/", MaxAge: int(maxAge.Seconds()),
		HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: IsHTTPS(r),
	})
}

// ClearCookie 删除会话 Cookie。
// shortcut: 会话无服务端状态，退出只删浏览器 Cookie，已泄露的令牌在过期前仍有效；需要强制下线时改密码即可，要单点吊销再加会话表。
func ClearCookie(w http.ResponseWriter, r *http.Request) {
	http.SetCookie(w, &http.Cookie{Name: CookieName, Value: "", Path: "/", MaxAge: -1, HttpOnly: true, SameSite: http.SameSiteLaxMode, Secure: IsHTTPS(r)})
}

func IsHTTPS(r *http.Request) bool {
	return r.TLS != nil || (fromTrustedProxy(r) && r.Header.Get("X-Forwarded-Proto") == "https")
}

// CSRFToken 由会话令牌派生，表单以隐藏字段 csrf 提交。
func (s *Service) CSRFToken(sessionToken string) string {
	m := hmac.New(sha256.New, s.csrfKey)
	m.Write([]byte(sessionToken))
	return base64.RawURLEncoding.EncodeToString(m.Sum(nil))
}

func (s *Service) CheckCSRF(sessionToken, got string) bool {
	return got != "" && hmac.Equal([]byte(s.CSRFToken(sessionToken)), []byte(got))
}

// fromTrustedProxy 只信任本机反向代理传入的转发头（程序默认只监听 127.0.0.1）。
func fromTrustedProxy(r *http.Request) bool {
	host, _, err := net.SplitHostPort(r.RemoteAddr)
	if err != nil {
		return false
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}

// ClientIP 返回客户端地址，仅在请求来自本机反向代理时采用 X-Forwarded-For / X-Real-IP。
func ClientIP(r *http.Request) string {
	host, _, _ := net.SplitHostPort(r.RemoteAddr)
	if !fromTrustedProxy(r) {
		return host
	}
	if xff := r.Header.Get("X-Forwarded-For"); xff != "" {
		return strings.TrimSpace(strings.Split(xff, ",")[0])
	}
	if xr := r.Header.Get("X-Real-IP"); xr != "" {
		return strings.TrimSpace(xr)
	}
	return host
}

// RateLimiter 按 key 做固定窗口计数，计数只在内存中。
type RateLimiter struct {
	mu     sync.Mutex
	limit  int
	window time.Duration
	hits   map[string]*window
	Now    func() time.Time
}

type window struct {
	start time.Time
	n     int
}

func NewRateLimiter(limit int, d time.Duration) *RateLimiter {
	return &RateLimiter{limit: limit, window: d, hits: map[string]*window{}, Now: time.Now}
}

// Allow 记录一次请求，超过限额返回 false。
func (l *RateLimiter) Allow(key string) bool {
	l.mu.Lock()
	defer l.mu.Unlock()
	now := l.Now()
	if len(l.hits) > 10000 {
		for k, w := range l.hits {
			if now.Sub(w.start) >= l.window {
				delete(l.hits, k)
			}
		}
	}
	w := l.hits[key]
	if w == nil || now.Sub(w.start) >= l.window {
		l.hits[key] = &window{start: now, n: 1}
		return true
	}
	w.n++
	return w.n <= l.limit
}
