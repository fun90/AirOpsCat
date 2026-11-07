package web

import (
	"context"
	"fmt"
	"net/http"
	"strings"

	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/singbox"
	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) deployRoutes(m *http.ServeMux) {
	m.HandleFunc("POST /admin/nodes/{id}/deploy", s.nodeDeploy)
	m.HandleFunc("POST /admin/nodes/deploy-pending", s.deployPending)
	m.HandleFunc("POST /admin/accounts/{id}/deploy", s.accountDeploy)
	m.HandleFunc("POST /admin/servers/{id}/deploy", s.serverDeploy)
	m.HandleFunc("GET /admin/servers/{id}/config", s.serverConfigPreview)
	m.HandleFunc("POST /admin/servers/{id}/ping", s.serverPing)
	m.HandleFunc("POST /admin/servers/{id}/calibrate-traffic", s.serverCalibrateTraffic)
	m.HandleFunc("POST /admin/servers/{id}/clear-host-key", s.serverClearHostKey)
	m.HandleFunc("POST /admin/servers/{id}/reboot", s.serverReboot)
}

// buildConfig 是部署与预览共用的配置来源（装载 + 生成）。
func (s *Server) buildConfig(r *http.Request, serverID int64) ([]byte, error) {
	in, err := store.LoadServerConfigInput(r.Context(), s.DB, serverID, store.LoadOptions{})
	if err != nil {
		return nil, err
	}
	return singbox.BuildServerConfig(*in)
}

// runDeploy 把服务器提交到后台部署队列后立即重定向回 back；结果见部署任务页。
func (s *Server) runDeploy(w http.ResponseWriter, r *http.Request, back string, ids []int64, none string) {
	if len(ids) == 0 {
		redirectMsg(w, r, back, "msg", none)
		return
	}
	id := s.EnqueueDeploy(deployReason(r), ids)
	redirectMsg(w, r, back, "msg", fmt.Sprintf("已提交后台部署 %d 台，结果见「部署任务」#%d", len(ids), id))
}

// deployReason 按触发部署的路由生成任务原因。
func deployReason(r *http.Request) string {
	id := r.PathValue("id")
	switch r.Pattern {
	case "POST /admin/nodes/{id}/deploy":
		return "部署节点 #" + id
	case "POST /admin/nodes/{id}", "POST /admin/nodes":
		return "保存并部署节点"
	case "POST /admin/nodes/batch-deploy":
		return "批量部署节点"
	case "POST /admin/nodes/deploy-pending":
		return "部署全部待部署"
	case "POST /admin/accounts/{id}/deploy":
		return "部署账户 #" + id
	case "POST /admin/accounts/{id}", "POST /admin/accounts":
		return "保存并部署账户"
	case "POST /admin/accounts/batch-deploy":
		return "批量部署账户"
	case "POST /admin/servers/{id}/deploy":
		return "整机部署服务器 #" + id
	}
	return "手动部署"
}

func (s *Server) nodeDeploy(w http.ResponseWriter, r *http.Request) {
	ids, err := store.NodeDeployServers(r.Context(), s.DB, idFrom(r), r.PostFormValue("force") == "1")
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.runDeploy(w, r, "/admin/nodes", ids, "没有待部署的部署位，无需重复部署")
}

func (s *Server) deployPending(w http.ResponseWriter, r *http.Request) {
	ids, err := store.PendingDeployServers(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	back := "/admin/nodes"
	if r.PostFormValue("from") == "accounts" {
		back = "/admin/accounts"
	}
	s.runDeploy(w, r, back, ids, "没有待部署的部署位")
}

func (s *Server) accountDeploy(w http.ResponseWriter, r *http.Request) {
	ids, err := store.AccountDeployServers(r.Context(), s.DB, idFrom(r))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	back := "/admin/accounts"
	if r.PostFormValue("from") == "edit" {
		back = fmt.Sprintf("/admin/accounts/%d/edit", idFrom(r))
	}
	s.runDeploy(w, r, back, ids, "该账户没有可用节点，无需部署")
}

// serverDeploy 整机重推本服务器的全部部署位，用于换机或重装后恢复。
func (s *Server) serverDeploy(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := fmt.Sprintf("/admin/servers/%d/edit", id)
	t, err := store.SSHTargetByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if t == nil {
		http.NotFound(w, r)
		return
	}
	if t.Disabled {
		redirectMsg(w, r, back, "err", "服务器已禁用，已跳过部署")
		return
	}
	s.runDeploy(w, r, back, []int64{id}, "")
}

func (s *Server) serverConfigPreview(w http.ResponseWriter, r *http.Request) {
	body, err := s.buildConfig(r, idFrom(r))
	if err != nil {
		http.Error(w, "生成配置失败："+err.Error(), http.StatusUnprocessableEntity)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	w.Write(body)
}

func (s *Server) serverPing(w http.ResponseWriter, r *http.Request) {
	ctx := r.Context()
	if err := s.Deployer.Ping(ctx, idFrom(r)); err != nil {
		redirectMsg(w, r, "/admin/servers", "err", "SSH 连接失败："+err.Error())
		return
	}
	redirectMsg(w, r, "/admin/servers", "msg", "SSH 连接正常")
}

// serverReboot 重启服务器主机。重启会清零 sing-box 计数，持锁先采集一次流量。
// Accept: application/json 时返回 JSON（维护页初始化后调用），否则重定向回服务器列表。
func (s *Server) serverReboot(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	t, err := store.SSHTargetByID(r.Context(), s.DB, id)
	if !s.recordFound(w, r, t != nil, err) {
		return
	}
	jsonReply := strings.Contains(r.Header.Get("Accept"), "application/json")
	reply := func(status int, msg string) {
		if jsonReply {
			if status != http.StatusOK {
				http.Error(w, msg, status)
				return
			}
			w.Header().Set("Content-Type", "application/json; charset=utf-8")
			w.Write([]byte(`{"success":true}`))
			return
		}
		key := "msg"
		if status != http.StatusOK {
			key = "err"
		}
		redirectMsg(w, r, "/admin/servers", key, msg)
	}
	if t.External {
		reply(http.StatusUnprocessableEntity, "外部服务器不支持重启")
		return
	}
	unlock := deploy.Lock(id)
	defer unlock()
	if err := s.trafficBeforeRestart(r.Context(), id, "restart"); err != nil {
		store.Log(context.WithoutCancel(r.Context()), s.DB, "MAINTENANCE", "WARN", &id, t.Name, "重启前采集流量失败，继续重启: "+err.Error(), "")
	}
	if err := s.Deployer.Reboot(r.Context(), id); err != nil {
		store.Log(context.WithoutCancel(r.Context()), s.DB, "MAINTENANCE", "ERROR", &id, t.Name, "重启服务器失败: "+err.Error(), "")
		reply(http.StatusBadGateway, "重启失败："+err.Error())
		return
	}
	store.Log(context.WithoutCancel(r.Context()), s.DB, "MAINTENANCE", "INFO", &id, t.Name, "已发送重启命令", "")
	reply(http.StatusOK, "已发送重启命令，服务器约 1 分钟后恢复")
}

func (s *Server) serverClearHostKey(w http.ResponseWriter, r *http.Request) {
	if err := store.ClearHostKey(r.Context(), s.DB, idFrom(r)); err != nil {
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/servers", "msg", "已清除主机密钥，下次连接将重新信任")
}
