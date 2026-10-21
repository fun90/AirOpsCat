package web

import (
	"context"
	"fmt"
	"net/http"
	"net/mail"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/maintenance"
	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) maintenanceDir() string {
	if s.ScriptDir != "" {
		return s.ScriptDir
	}
	return store.ConfigValue(context.Background(), s.DB, "airopscat.install.scripts.dir")
}
func (s *Server) maintenanceRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/maintenance", s.maintenancePage)
	m.HandleFunc("POST /admin/maintenance/run", s.maintenanceRun)
}
func (s *Server) maintenancePage(w http.ResponseWriter, r *http.Request) {
	scripts, err := maintenance.List(s.maintenanceDir())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	ids, err := store.MonitorServers(r.Context(), s.DB, "all", time.Now())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var servers []store.SSHTarget
	for _, id := range ids {
		t, err := store.SSHTargetByID(r.Context(), s.DB, id)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if t != nil {
			t.Auth = ""
			servers = append(servers, *t)
		}
	}
	d := s.pageData(r, "维护脚本", "maintenance")
	d["Scripts"], d["Servers"] = scripts, servers
	s.render(w, "maintenance", 200, d)
}
func (s *Server) maintenanceRun(w http.ResponseWriter, r *http.Request) {
	id, err := strconv.ParseInt(formValue(r, "server_id"), 10, 64)
	if err != nil || id <= 0 {
		http.Error(w, "请选择服务器", 422)
		return
	}
	if r.PostFormValue("confirm") != "run" {
		http.Error(w, "请确认执行脚本", 422)
		return
	}
	script, err := maintenance.Read(s.maintenanceDir(), formValue(r, "script"))
	if err != nil {
		http.Error(w, "脚本不存在或不可读取", 422)
		return
	}
	if script.Hash != formValue(r, "hash") {
		http.Error(w, "脚本已变更，请刷新预览后重新确认", 409)
		return
	}
	acmeEmail := formValue(r, "acme_email")
	if script.Name == "04-certificates-acme.sh" {
		address, err := mail.ParseAddress(acmeEmail)
		if err != nil || address.Address != acmeEmail || !strings.Contains(strings.SplitN(acmeEmail, "@", 2)[1], ".") || strings.ContainsAny(acmeEmail, "\r\n") {
			http.Error(w, "请填写有效的 ACME 注册邮箱", 422)
			return
		}
	}
	unlock := deploy.Lock(id)
	defer unlock()
	t, err := store.SSHTargetByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if t == nil {
		http.NotFound(w, r)
		return
	}
	if t.Disabled || t.External {
		http.Error(w, "禁止对禁用或外部服务器执行脚本", 422)
		return
	}
	var expire string
	s.DB.QueryRowContext(r.Context(), "SELECT coalesce(expire_date,'') FROM server WHERE id=?", id).Scan(&expire)
	if expire != "" && expire[:min(10, len(expire))] < time.Now().In(store.Shanghai).Format("2006-01-02") {
		http.Error(w, "服务器已过期", 422)
		return
	}
	var encrypted string
	if err := s.DB.QueryRowContext(r.Context(), "SELECT coalesce(config_value,'') FROM system_config WHERE config_key='airopscat.api.token'").Scan(&encrypted); err != nil {
		http.Error(w, "请先配置开放接口 Token", 422)
		return
	}
	if s.Field == nil {
		http.Error(w, "加密未初始化", 500)
		return
	}
	token, err := s.Field.Decrypt(encrypted)
	if err != nil || strings.TrimSpace(token) == "" {
		http.Error(w, "请重新保存开放接口 Token", 422)
		return
	}
	rows, err := s.DB.QueryContext(r.Context(), `SELECT coalesce(full_name,name) FROM domain_dns_record WHERE server_id=? AND upper(type)='A' AND status='SYNCED' ORDER BY id`, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	var hosts []string
	for rows.Next() {
		var h string
		if err := rows.Scan(&h); err != nil {
			rows.Close()
			s.fail(w, r, err)
			return
		}
		if !strings.Contains(h, "*") {
			hosts = append(hosts, h)
		}
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		s.fail(w, r, err)
		return
	}
	host := t.IP
	if len(hosts) > 0 {
		host = hosts[0]
	}
	var day string
	s.DB.QueryRowContext(r.Context(), `SELECT coalesce(strftime('%d',bandwidth_date),'01') FROM server WHERE id=?`, id).Scan(&day)
	publicKey := formValue(r, "public_key")
	if script.Name == "03-ssh-key.sh" && publicKey == "" {
		http.Error(w, "请填写要保留的 SSH 公钥", 422)
		return
	}
	env := maintenance.Env(map[string]string{"ACME_EMAIL": acmeEmail, "airopscat_ssh_public_key": publicKey, "AIROPSCAT_STEP_NAME": script.Title, "server_ip": t.IP, "server_host": host, "server_ssh_port": strconv.Itoa(t.Port), "airopscat_domain": store.ConfigValue(r.Context(), s.DB, "airopscat.domain"), "airopscat_api_token": token, "bandwidth_day": day}, hosts)
	http.NewResponseController(w).SetWriteDeadline(time.Now().Add(31 * time.Minute))
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Minute)
	defer cancel()
	remote, err := s.MaintenanceDeployer.Dial(ctx, t)
	if err != nil {
		store.Log(r.Context(), s.DB, "MAINTENANCE", "ERROR", &id, t.Name, "维护脚本连接失败", "")
		if strings.Contains(r.Header.Get("Accept"), "application/json") {
			http.Error(w, "连接服务器失败，请查看系统日志", http.StatusBadGateway)
			return
		}
		redirectMsg(w, r, "/admin/maintenance", "err", "连接服务器失败，请查看系统日志")
		return
	}
	defer remote.Close()
	err = maintenance.Execute(ctx, remote, script, env)
	result, level := "脚本执行成功", "INFO"
	if err != nil {
		result, level = err.Error(), "ERROR"
	}
	store.Log(context.WithoutCancel(r.Context()), s.DB, "MAINTENANCE", level, &id, t.Name, result, fmt.Sprintf("script=%s sha256=%s", script.Name, script.Hash))
	if strings.Contains(r.Header.Get("Accept"), "application/json") {
		if err != nil {
			http.Error(w, result, http.StatusBadGateway)
			return
		}
		w.Header().Set("Content-Type", "application/json; charset=utf-8")
		w.Write([]byte(`{"success":true}`))
		return
	}
	if err != nil {
		redirectMsg(w, r, "/admin/maintenance", "err", result)
		return
	}
	redirectMsg(w, r, "/admin/maintenance", "msg", result)
}
