package web

import (
	"compress/gzip"
	"encoding/json"
	"strconv"

	"fmt"
	"io/fs"
	"net/http"
	"net/url"
	"os"
	"path"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/store"
	"github.com/fun90/airopscat/internal/subscribe"
)

func (s *Server) subscriptionRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /subscribe/config/{code}/{os}/{app}", s.subscriptionConfig)
	m.HandleFunc("GET /subscribe/config/{code}/{os}/{app}/{remark...}", s.subscriptionConfig)
	m.HandleFunc("GET /subscribe/nodes/{code}/{app}", s.subscriptionNodeList)
	m.HandleFunc("GET /subscribe/nodes/{code}/{app}/{$}", s.subscriptionNodeList)
	m.HandleFunc("GET /subscribe/rules/{app}/{name}", s.subscriptionRules)
	m.HandleFunc("GET /api/open/docs-info/{code}", s.docsInfo)
	m.HandleFunc("GET /api/open/docs-info/{code}/{$}", s.docsInfo)
}

func validSubscriptionPart(v string) bool {
	return v != "" && !strings.ContainsAny(v, "/\\") && v != "." && v != ".."
}

func (s *Server) subscriptionFS(r *http.Request) fs.FS {
	root := store.ConfigValue(r.Context(), s.DB, "airopscat.config.templates.dir")
	if root == "" {
		root = "./config"
	}
	// Go 模板单独目录，避免把旧 Java Qute 模板当作普通文本发给客户端。
	return subscriptionFiles{external: os.DirFS(root + "/subscription-go"), fallback: subscribe.Templates}
}

type subscriptionFiles struct{ external, fallback fs.FS }

func (f subscriptionFiles) Open(name string) (fs.File, error) {
	file, err := f.external.Open(name)
	if err == nil {
		return file, nil
	}
	if !os.IsNotExist(err) {
		return nil, err
	}
	return f.fallback.Open(name)
}

func (s *Server) subscriptionData(r *http.Request) (map[string]any, *store.Account, error) {
	code := r.PathValue("code")
	if strings.TrimSpace(code) == "" {
		return nil, nil, fmt.Errorf("认证码不能为空")
	}
	a, multiple, err := store.SubscriptionAccount(r.Context(), s.DB, code)
	if err != nil {
		return nil, nil, err
	}
	if a == nil {
		return nil, nil, fmt.Errorf("无效的认证码，账户不存在")
	}
	if a.Disabled || (a.ToDate != nil && time.Now().After(*a.ToDate)) {
		return nil, nil, fmt.Errorf("账户已被禁用，请联系管理员")
	}
	nodes, err := store.SubscriptionNodes(r.Context(), s.DB, a, multiple)
	if err != nil {
		return nil, nil, err
	}
	if len(nodes) == 0 {
		return nil, nil, fmt.Errorf("当前账户没有可用的节点，请联系管理员")
	}
	data := map[string]any{"account": map[string]any{"uuid": a.UUID, "authCode": a.AuthCode, "remark": a.Remark, "accountNo": a.AccountNo}, "nodes": nodes, "timestamp": time.Now().UnixMilli(), "subscriptionUrl": store.ConfigValue(r.Context(), s.DB, "airopscat.subscription.url"), "osName": r.PathValue("os"), "appName": r.PathValue("app")}
	dns := "dns"
	if r.URL.Query().Get("version") == "1" {
		dns = "dns2"
	}
	if r.URL.Query().Has(dns) {
		data["dns"] = r.URL.Query().Get(dns)
	}
	return data, a, nil
}

func subscriptionError(w http.ResponseWriter, status int, err error) {
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.WriteHeader(status)
	fmt.Fprint(w, "错误: "+err.Error())
}

func (s *Server) subscriptionConfig(w http.ResponseWriter, r *http.Request) {
	app := r.PathValue("app")
	for _, p := range []struct{ v, msg string }{{r.PathValue("os"), "操作系统名称不能为空"}, {app, "应用名称不能为空"}} {
		if strings.TrimSpace(p.v) == "" {
			subscriptionError(w, 400, fmt.Errorf("%s", p.msg))
			return
		}
	}
	data, a, err := s.subscriptionData(r)
	if err != nil {
		subscriptionError(w, 400, err)
		return
	}
	if !validSubscriptionPart(app) {
		subscriptionError(w, 400, fmt.Errorf("系统内部错误，请联系管理员"))
		return
	}
	ext := ".yaml"
	switch strings.ToLower(app) {
	case "loon", "shadowrocket":
		ext = ".conf"
	case "sing-box":
		ext = ".json"
	}
	body, err := subscribe.Render(s.subscriptionFS(r), app+ext, data)
	if err != nil || a.ToDate == nil {
		subscriptionError(w, 400, fmt.Errorf("系统内部错误，请联系管理员"))
		return
	}
	used, quota, err := s.subscriptionUsage(r, a)
	if err != nil {
		subscriptionError(w, 400, fmt.Errorf("系统内部错误，请联系管理员"))
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("charset", "UTF-8")
	w.Header().Set("profile-update-interval", "72")
	if r.URL.Query().Get("view") != "1" {
		w.Header().Set("Content-Disposition", "attachment;filename*=UTF-8''"+javaFormEncode(a.Remark))
	}
	if strings.EqualFold(app, "shadowrocket") {
		w.Header().Set("subscription-userinfo", fmt.Sprintf("tfc: %sG; exp: %s", usedGiB(used), a.ToDate.Format("2006/01/02")))
	} else {
		if quota <= 0 {
			quota = 500
		}
		w.Header().Set("subscription-userinfo", fmt.Sprintf("download=%d; total=%d; expire=%d", used, quota*(1<<30), a.ToDate.Unix()))
	}
	writeSubscription(w, r, body)
}

func (s *Server) subscriptionNodeList(w http.ResponseWriter, r *http.Request) {
	data, _, err := s.subscriptionData(r)
	if err != nil {
		subscriptionError(w, 200, err)
		return
	}
	app := r.PathValue("app")
	if !validSubscriptionPart(app) {
		http.Error(w, "系统内部错误，请联系管理员", 500)
		return
	}
	body, err := subscribe.Render(s.subscriptionFS(r), path.Join("nodes", app+".html"), data)
	if err != nil {
		http.Error(w, "系统内部错误，请联系管理员", 500)
		return
	}
	var lines []string
	for _, line := range strings.Split(string(body), "\n") {
		if strings.TrimSpace(line) != "" {
			lines = append(lines, line)
		}
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("charset", "UTF-8")
	writeSubscription(w, r, []byte(strings.TrimSpace(strings.Join(lines, "\n"))))
}
func (s *Server) subscriptionRules(w http.ResponseWriter, r *http.Request) {
	app, name := r.PathValue("app"), r.PathValue("name")
	if !validSubscriptionPart(app) || !validSubscriptionPart(name) {
		http.NotFound(w, r)
		return
	}
	body, err := fs.ReadFile(s.subscriptionFS(r), path.Join("rules", app, name))
	if err != nil {
		http.Error(w, "系统内部错误，请联系管理员", 500)
		return
	}
	w.Header().Set("Content-Type", "text/plain; charset=utf-8")
	w.Header().Set("charset", "UTF-8")
	writeSubscription(w, r, body)
}

func (s *Server) subscriptionUsage(r *http.Request, a *store.Account) (int64, int64, error) {
	u, err := store.AccountUsage(r.Context(), s.DB, a.ID, time.Now())
	if err != nil {
		return 0, 0, err
	}
	return u.UsedBytes, u.QuotaGB, nil
}

// usedGiB 以整数实现 Java HALF_UP，拆分整部避免字节数乘 100 溢出。
func usedGiB(bytes int64) string {
	const gib int64 = 1 << 30
	hundredths := (bytes%gib*100 + gib/2) / gib
	return fmt.Sprintf("%d.%02d", bytes/gib+hundredths/100, hundredths%100)
}

func javaFormEncode(s string) string {
	const hex = "0123456789ABCDEF"
	var b strings.Builder
	for _, c := range []byte(s) {
		switch {
		case c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || strings.ContainsRune(".-*_", rune(c)):
			b.WriteByte(c)
		case c == ' ':
			b.WriteByte('+')
		default:
			b.WriteByte('%')
			b.WriteByte(hex[c>>4])
			b.WriteByte(hex[c&15])
		}
	}
	return b.String()
}

func (s *Server) docsInfo(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	// 安装手册站点跨域读取：只放行 airopscat.docs.url 的源；简单 GET 无需预检。
	w.Header().Add("Vary", "Origin")
	if docs, err := url.Parse(store.ConfigValue(r.Context(), s.DB, "airopscat.docs.url")); err == nil && docs.Host != "" && r.Header.Get("Origin") == docs.Scheme+"://"+docs.Host {
		w.Header().Set("Access-Control-Allow-Origin", docs.Scheme+"://"+docs.Host)
	}
	a, _, err := store.SubscriptionAccount(r.Context(), s.DB, r.PathValue("code"))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if a == nil || a.Disabled || (a.ToDate != nil && time.Now().After(*a.ToDate)) {
		w.WriteHeader(400)
		json.NewEncoder(w).Encode(map[string]any{"error": "无效的认证码，账户不存在"})
		return
	}
	links := map[string]string{}
	base := store.ConfigValue(r.Context(), s.DB, "airopscat.subscription.url")
	for _, p := range [][3]string{{"windows", "windows", "clash-verge"}, {"linux", "linux", "clash-verge"}, {"macos", "macos", "clash-verge"}, {"android", "android", "clash-meta"}, {"ios", "ios", "shadowrocket/" + javaFormEncode(a.Remark)}} {
		links[p[0]] = base + "/config/" + a.AuthCode + "/" + p[1] + "/" + p[2] + "/"
	}
	used, quota, err := s.subscriptionUsage(r, a)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	usage := map[string]any{"usedBytes": used, "remainingDays": 0}
	if quota > 0 {
		usage["totalBytes"] = quota * (1 << 30)
	}
	if a.ToDate != nil {
		usage["expireDate"] = a.ToDate.Format("2006-01-02")
		loc := a.ToDate.Location()
		now := time.Now().In(loc)
		day := time.Date(now.Year(), now.Month(), now.Day(), 0, 0, 0, 0, loc)
		end := time.Date(a.ToDate.Year(), a.ToDate.Month(), a.ToDate.Day(), 0, 0, 0, 0, loc)
		usage["remainingDays"] = max(0, int(end.Sub(day).Hours()/24))
	}
	var reset string
	now := store.FormatTime(time.Now())
	if err := s.DB.QueryRowContext(r.Context(), `SELECT period_end FROM account_traffic_stats WHERE account_id=? AND period_start<=? AND period_end>? ORDER BY period_start DESC LIMIT 1`, a.ID, now, now).Scan(&reset); err == nil && len(reset) >= 10 {
		usage["resetDate"] = reset[:10]
	}
	out := map[string]any{"subscriptionUrl": links, "usage": usage}
	if strings.TrimSpace(a.Remark) != "" {
		out["nickName"] = a.Remark
	}
	for key, name := range map[string]string{"airopscat.apple.id": "appleId", "airopscat.apple.pwd": "applePwd"} {
		var value string
		err := s.DB.QueryRowContext(r.Context(), `SELECT config_value FROM system_config WHERE config_key=? AND config_value IS NOT NULL`, key).Scan(&value)
		if err == nil {
			if s.Field != nil {
				value, err = s.Field.Decrypt(value)
			}
			if err == nil {
				out[name] = value
			}
		}
	}
	body, err := json.Marshal(out)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	writeSubscription(w, r, append(body, '\n'))
}

// writeSubscription 只压缩已生成的公开响应，不包装 ResponseWriter 或改变错误处理。
func writeSubscription(w http.ResponseWriter, r *http.Request, body []byte) {
	w.Header().Add("Vary", "Accept-Encoding")
	gzipQ, wildcardQ := -1.0, 0.0
	for _, item := range strings.Split(strings.Join(r.Header.Values("Accept-Encoding"), ","), ",") {
		parts := strings.Split(item, ";")
		coding := strings.ToLower(strings.TrimSpace(parts[0]))
		q := 1.0
		for _, param := range parts[1:] {
			pair := strings.SplitN(strings.TrimSpace(param), "=", 2)
			if len(pair) == 2 && strings.EqualFold(pair[0], "q") {
				value, err := strconv.ParseFloat(pair[1], 64)
				if err != nil || !(value >= 0 && value <= 1) {
					q = 0
				} else {
					q = value
				}
			}
		}
		if coding == "gzip" {
			gzipQ = q
		} else if coding == "*" {
			wildcardQ = q
		}
	}
	if gzipQ < 0 {
		gzipQ = wildcardQ
	}
	if gzipQ > 0 && w.Header().Get("Content-Encoding") == "" {
		w.Header().Set("Content-Encoding", "gzip")
		w.Header().Del("Content-Length")
		if r.Method == http.MethodHead {
			return
		}
		z := gzip.NewWriter(w)
		_, _ = z.Write(body)
		_ = z.Close()
		return
	}
	if r.Method != http.MethodHead {
		_, _ = w.Write(body)
	}
}
