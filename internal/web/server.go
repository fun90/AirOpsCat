// Package web 注册路由、中间件和管理页面 handler。
package web

import (
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/singbox"
	"html/template"
	"io/fs"
	"log/slog"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/crypto"
	"github.com/fun90/airopscat/internal/dbbackup"
	"github.com/fun90/airopscat/internal/store"
	assets "github.com/fun90/airopscat/web"
)

type Server struct {
	DNSAutoDomains      string // 自动 DNS 域名 ID 白名单或 all；空表示关闭
	MaintenanceDeployer *deploy.Deployer
	ScriptDir           string // AIROPSCAT_SCRIPTS_DIR 优先于系统配置目录
	Guard               *guard.Aggregator
	Tasks               map[string]*taskState
	Backup              *dbbackup.Manager
	DNSBaseURL          string // 仅测试注入；生产为空，使用固定的 Cloudflare 地址
	TrafficServers      string
	limitMu             sync.Mutex
	limitApplied        map[int64]string // 服务器最近一次成功应用的限速指纹
	MonitorServers      string           // 显式启用 SSH 监控采集的服务器（ID 列表或 all），空表示关闭
	NotifyDisabled      bool             // 开发/演示库带真实 Bark 配置时关闭外发通知
	DB                  *sql.DB
	Auth                *auth.Service
	Field               *crypto.Cipher // 敏感字段加密
	Deployer            *deploy.Deployer
	loginRate           *auth.RateLimiter
	pages               map[string]*template.Template
}

type ctxKey int

const (
	userKey ctxKey = iota
	tokenKey
)

func New(db *sql.DB, a *auth.Service, field *crypto.Cipher) (*Server, error) {
	s := &Server{DB: db, Auth: a, Field: field, loginRate: auth.NewRateLimiter(15, time.Minute), pages: map[string]*template.Template{}}
	s.Deployer = deploy.NewDeployer(db, field, func(ctx context.Context, id int64) ([]byte, error) {
		in, err := store.LoadServerConfigInput(ctx, db, id, store.LoadOptions{})
		if err != nil {
			return nil, err
		}
		return singbox.BuildServerConfig(*in)
	})
	s.Deployer.Prepare = func(ctx context.Context, id int64) (*deploy.Prepared, error) {
		captured, err := store.CaptureDeployment(ctx, db, id, store.LoadOptions{})
		if err != nil {
			return nil, err
		}
		body, err := singbox.BuildServerConfig(*captured.Input)
		if err != nil {
			return nil, err
		}
		return &deploy.Prepared{Config: body, Revision: captured.Revision, Snapshots: captured.Snapshots, LimitsKey: limitsKey(captured.Input)}, nil
	}
	s.Deployer.Applied = func(id int64, key string) {
		s.markLimits(id, key)
		// 整机下发成功即视为部署恢复
		store.AlertResolve(context.Background(), db, alertDeployFail, "server", id, time.Now())
	}
	s.MaintenanceDeployer = deploy.NewDeployer(db, field, nil)
	s.MaintenanceDeployer.CommandTimeout = 30 * time.Minute
	s.Guard = guard.New(db)
	s.initTasks()
	base, err := template.New("").Funcs(funcs).ParseFS(assets.FS, "templates/layout.html", "templates/pager.html")
	if err != nil {
		return nil, err
	}
	for _, name := range pageNames {
		t, err := template.Must(base.Clone()).ParseFS(assets.FS, "templates/"+name+".html")
		if err != nil {
			return nil, err
		}
		s.pages[name] = t
	}
	return s, nil
}

// Handler 返回完整的 HTTP 处理链。
func (s *Server) Handler() http.Handler {
	mux := http.NewServeMux()
	static, _ := fs.Sub(assets.FS, "static")
	mux.Handle("GET /static/", http.StripPrefix("/static/", cacheStatic(http.FileServerFS(static))))
	mux.HandleFunc("GET /healthz", s.healthz)
	s.subscriptionRoutes(mux)
	mux.HandleFunc("POST /api/open/guard-sync", s.guardSync)
	mux.HandleFunc("POST /api/open/monitor-report", s.monitorReport)
	mux.HandleFunc("GET /login", s.loginPage)
	mux.HandleFunc("POST /login", s.login)
	mux.Handle("POST /logout", s.requireUser(http.HandlerFunc(s.logout)))
	mux.HandleFunc("GET /{$}", func(w http.ResponseWriter, r *http.Request) { http.Redirect(w, r, "/admin", http.StatusFound) })

	admin := http.NewServeMux()
	admin.HandleFunc("GET /admin", s.dashboard)
	s.serverRoutes(admin)
	s.tagRoutes(admin)
	s.nodeRoutes(admin)
	s.deployRoutes(admin)
	s.accountRoutes(admin)
	s.userRoutes(admin)
	s.routeRuleRoutes(admin)
	s.transactionRoutes(admin)
	s.renewalRoutes(admin)
	s.settingsRoutes(admin)
	s.backupRoutes(admin)
	s.dnsRoutes(admin)
	s.alertRoutes(admin)
	s.monitorRoutes(admin)
	s.maintenanceRoutes(admin)
	admin.HandleFunc("GET /admin/tasks", s.taskPage)
	admin.HandleFunc("POST /admin/tasks/{task}/run", s.taskRun)
	mux.Handle("/admin", s.requireRole("ADMIN", admin))
	mux.Handle("/admin/", s.requireRole("ADMIN", admin))

	return recoverer(securityHeaders(sameOrigin(mux)))
}

func (s *Server) healthz(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Second)
	defer cancel()
	if err := s.DB.PingContext(ctx); err != nil {
		http.Error(w, "db unavailable", http.StatusServiceUnavailable)
		return
	}
	w.Write([]byte("ok"))
}

func (s *Server) render(w http.ResponseWriter, page string, status int, data map[string]any) {
	w.Header().Set("Content-Type", "text/html; charset=utf-8")
	w.WriteHeader(status)
	name := "layout"
	if page == "login" {
		name = "login.html"
	}
	if err := s.pages[page].ExecuteTemplate(w, name, data); err != nil {
		slog.Error("渲染页面失败", "page", page, "err", err)
	}
}

// pageData 组装管理页面公共数据。
func (s *Server) pageData(r *http.Request, title, nav string) map[string]any {
	q := r.URL.Query()
	return map[string]any{
		"Query": q.Encode(),
		"Msg":   q.Get("msg"),
		"Err":   q.Get("err"),
		"Title": title,
		"Nav":   nav,
		"User":  r.Context().Value(userKey).(*store.User),
		"CSRF":  s.Auth.CSRFToken(r.Context().Value(tokenKey).(string)),
	}
}

func (s *Server) loginPage(w http.ResponseWriter, r *http.Request) {
	if c, err := r.Cookie(auth.CookieName); err == nil {
		if u, _ := s.Auth.Verify(r.Context(), c.Value); u != nil {
			http.Redirect(w, r, "/admin", http.StatusFound)
			return
		}
	}
	s.render(w, "login", http.StatusOK, map[string]any{"Title": "登录"})
}

func (s *Server) login(w http.ResponseWriter, r *http.Request) {
	if !s.loginRate.Allow(auth.ClientIP(r)) {
		http.Error(w, "登录请求过于频繁，请稍后再试", http.StatusTooManyRequests)
		return
	}
	email := r.PostFormValue("email")
	u, err := s.Auth.Login(r.Context(), email, r.PostFormValue("password"))
	if err != nil {
		var locked *auth.LockedError
		msg := err.Error()
		if !errors.Is(err, auth.ErrBadCredentials) && !errors.Is(err, auth.ErrDisabled) && !errors.As(err, &locked) {
			slog.Error("登录失败", "err", err)
			msg = "系统错误，请稍后再试"
		}
		s.render(w, "login", http.StatusUnauthorized, map[string]any{"Title": "登录", "Error": msg, "Email": email})
		return
	}
	maxAge := auth.SessionMaxAge
	if r.PostFormValue("remember") == "on" {
		maxAge = auth.RememberMaxAge
	}
	// 每次登录都签发新令牌，不沿用请求中已有的会话
	auth.SetCookie(w, r, s.Auth.IssueToken(u, maxAge), maxAge)
	http.Redirect(w, r, "/admin", http.StatusSeeOther)
}

func (s *Server) logout(w http.ResponseWriter, r *http.Request) {
	auth.ClearCookie(w, r)
	http.Redirect(w, r, "/login", http.StatusSeeOther)
}

// requireUser 校验会话；非 GET/HEAD 请求还要校验 CSRF 令牌。
func (s *Server) requireUser(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie(auth.CookieName)
		var u *store.User
		if err == nil {
			u, err = s.Auth.Verify(r.Context(), c.Value)
			if err != nil {
				slog.Error("校验会话失败", "err", err)
			}
		}
		if u == nil {
			if r.Method == http.MethodGet {
				http.Redirect(w, r, "/login", http.StatusFound)
			} else {
				http.Error(w, "未登录", http.StatusUnauthorized)
			}
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead && !s.Auth.CheckCSRF(c.Value, r.PostFormValue("csrf")) {
			http.Error(w, "CSRF 校验失败", http.StatusForbidden)
			return
		}
		ctx := context.WithValue(context.WithValue(r.Context(), userKey, u), tokenKey, c.Value)
		next.ServeHTTP(w, r.WithContext(ctx))
	})
}

func (s *Server) requireRole(role string, next http.Handler) http.Handler {
	return s.requireUser(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Context().Value(userKey).(*store.User).Role != role {
			http.Error(w, "无权访问", http.StatusForbidden)
			return
		}
		next.ServeHTTP(w, r)
	}))
}

// sameOrigin 拒绝来自其他站点的表单提交；对外接口（订阅、开放 API）不经浏览器，不做检查。
func sameOrigin(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			if o := r.Header.Get("Origin"); o != "" {
				u, err := url.Parse(o)
				if err != nil || u.Host != r.Host {
					http.Error(w, "跨站请求被拒绝", http.StatusForbidden)
					return
				}
			}
		}
		next.ServeHTTP(w, r)
	})
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		h := w.Header()
		h.Set("X-Frame-Options", "DENY")
		h.Set("X-Content-Type-Options", "nosniff")
		h.Set("Referrer-Policy", "strict-origin-when-cross-origin")
		if auth.IsHTTPS(r) {
			h.Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
		}
		next.ServeHTTP(w, r)
	})
}

func cacheStatic(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Cache-Control", "public, max-age=86400")
		next.ServeHTTP(w, r)
	})
}

func recoverer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		defer func() {
			if v := recover(); v != nil {
				if v == http.ErrAbortHandler {
					panic(v)
				}
				slog.Error("请求处理 panic", "path", r.URL.Path, "panic", v)
				http.Error(w, "系统内部错误", http.StatusInternalServerError)
			}
		}()
		next.ServeHTTP(w, r)
	})
}

var pageNames = []string{"login", "dashboard", "servers", "server_idle", "server_form", "tags", "nodes", "node_form", "accounts", "account_form", "account_bindings", "users", "user_form", "tasks", "route_rules", "route_rule_form", "transactions", "transaction_form", "renewals", "settings", "logs", "backups", "dns_providers", "dns_provider_form", "domains", "domain_form", "dns_records", "dns_record_form", "alerts", "monitor", "monitor_detail", "maintenance"}

// pageURL 用结构化参数生成完整地址，避免 html/template 把筛选分隔符编码进 q。
func pageURL(query string, page int) string {
	values, _ := url.ParseQuery(query)
	values.Set("page", strconv.Itoa(page))
	values.Del("msg")
	values.Del("err")
	return "?" + values.Encode()
}

var funcs = template.FuncMap{
	"pageURL":   pageURL,
	"lower":     strings.ToLower,
	"hasPrefix": strings.HasPrefix,
	"gb":        func(b int64) string { return fmt.Sprintf("%.2f GB", float64(b)/(1<<30)) },
	"dbtime": func(v any) string {
		switch t := v.(type) {
		case time.Time:
			return store.FormatTime(t)
		case *time.Time:
			if t != nil {
				return store.FormatTime(*t)
			}
		}
		return ""
	},
	// dbminute 与 dbtime 同时区，只保留到分钟，用于列表的紧凑展示。
	"dbminute": func(v any) string {
		if t, ok := v.(*time.Time); ok && t != nil {
			return t.In(store.Shanghai).Format("2006-01-02 15:04")
		}
		return ""
	},
	"cents":       store.FormatCents,
	"deref":       func(p *int64) int64 { return *p },
	"installText": installText,
	"json":        func(v any) string { b, _ := json.Marshal(v); return string(b) },
	"add":         func(a, b int) int { return a + b },
	"sub":         func(a, b int) int { return a - b },
	"seq": func(n int) []int {
		r := make([]int, n)
		for i := range r {
			r[i] = i + 1
		}
		return r
	},
}

// redirectMsg 带提示信息跳转；提示经查询参数传递并由模板转义输出。
func redirectMsg(w http.ResponseWriter, r *http.Request, path, key, msg string) {
	sep := "?"
	if strings.Contains(path, "?") {
		sep = "&"
	}
	if back := backTarget(r, path); back != "" {
		path = back
		if strings.Contains(back, "?") {
			sep = "&"
		}
	}
	http.Redirect(w, r, path+sep+key+"="+url.QueryEscape(msg), http.StatusSeeOther)
}

// backTarget 返回同一列表页的原始地址（含分页与筛选），来源为表单 back 字段或 Referer；无效时返回空。
func backTarget(r *http.Request, path string) string {
	cands := []string{r.PostFormValue("back")}
	if ref, err := url.Parse(r.Referer()); err == nil && ref.Host == r.Host {
		cands = append(cands, ref.RequestURI())
	}
	for _, c := range cands {
		u, err := url.Parse(c)
		if err != nil || u.Host != "" || u.Path != path {
			continue
		}
		q := u.Query()
		q.Del("msg")
		q.Del("err")
		if len(q) == 0 {
			return path
		}
		return path + "?" + q.Encode()
	}
	return ""
}

// pageParams 解析分页参数。
func pageParams(r *http.Request) (page, size int) {
	page, _ = strconv.Atoi(r.URL.Query().Get("page"))
	if page < 1 {
		page = 1
	}
	return page, 20
}

// fail 记录内部错误并返回通用提示，不把数据库细节暴露给页面。
func (s *Server) fail(w http.ResponseWriter, r *http.Request, err error) {
	slog.Error("请求失败", "method", r.Method, "path", r.URL.Path, "err", err)
	http.Error(w, "系统内部错误", http.StatusInternalServerError)
}
