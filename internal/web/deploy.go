package web

import (
	"fmt"
	"net/http"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/singbox"
	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) deployRoutes(m *http.ServeMux) {
	m.HandleFunc("POST /admin/nodes/{id}/deploy", s.nodeDeploy)
	m.HandleFunc("POST /admin/nodes/deploy-pending", s.deployPending)
	m.HandleFunc("POST /admin/accounts/{id}/deploy", s.accountDeploy)
	m.HandleFunc("GET /admin/servers/{id}/config", s.serverConfigPreview)
	m.HandleFunc("POST /admin/servers/{id}/ping", s.serverPing)
	m.HandleFunc("POST /admin/servers/{id}/clear-host-key", s.serverClearHostKey)
}

// buildConfig 是部署与预览共用的配置来源（装载 + 生成）。
func (s *Server) buildConfig(r *http.Request, serverID int64) ([]byte, error) {
	in, err := store.LoadServerConfigInput(r.Context(), s.DB, serverID, store.LoadOptions{})
	if err != nil {
		return nil, err
	}
	return singbox.BuildServerConfig(*in)
}

// runDeploy 同步部署并重定向回 back，成功和失败用不同提示。部署可能较慢，放宽本次请求的写超时。
func (s *Server) runDeploy(w http.ResponseWriter, r *http.Request, back string, ids []int64, none string) {
	if len(ids) == 0 {
		redirectMsg(w, r, back, "msg", none)
		return
	}
	http.NewResponseController(w).SetWriteDeadline(time.Now().Add(10 * time.Minute))
	results := s.Deployer.DeployServers(r.Context(), ids, true)
	var failed []string
	for _, res := range results {
		if !res.Success {
			failed = append(failed, fmt.Sprintf("%s：%s", res.ServerName, res.Message))
		}
	}
	if len(failed) == 0 {
		redirectMsg(w, r, back, "msg", fmt.Sprintf("部署完成，%d 台服务器成功", len(results)))
		return
	}
	failedCount := len(failed)
	if len(failed) > 3 {
		failed = append(failed[:3], fmt.Sprintf("等共 %d 台失败", len(failed)))
	}
	redirectMsg(w, r, back, "err", fmt.Sprintf("成功 %d 台，失败 %d 台：%s", len(results)-failedCount, failedCount, strings.Join(failed, "；")))
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
	s.runDeploy(w, r, "/admin/nodes", ids, "没有待部署的部署位")
}

func (s *Server) accountDeploy(w http.ResponseWriter, r *http.Request) {
	ids, err := store.AccountDeployServers(r.Context(), s.DB, idFrom(r))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.runDeploy(w, r, "/admin/accounts", ids, "该账户没有可用节点，无需部署")
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

func (s *Server) serverClearHostKey(w http.ResponseWriter, r *http.Request) {
	if err := store.ClearHostKey(r.Context(), s.DB, idFrom(r)); err != nil {
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/servers", "msg", "已清除主机密钥，下次连接将重新信任")
}
