package web

import (
	"errors"
	"fmt"
	"net/http"

	"github.com/fun90/airopscat/internal/store"
)

// accountBatchDeploy 部署所选账户可用节点所在的服务器（与单个账户「部署账户」同口径）。
func (s *Server) accountBatchDeploy(w http.ResponseWriter, r *http.Request) {
	var accounts []int64
	if !postIDs(w, r, "账户 ID 不正确", idField{"account_ids", &accounts}) {
		return
	}
	seen := map[int64]bool{}
	var servers []int64
	for _, id := range accounts {
		ids, err := store.AccountDeployServers(r.Context(), s.DB, id)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		for _, sid := range ids {
			if !seen[sid] {
				seen[sid] = true
				servers = append(servers, sid)
			}
		}
	}
	s.runDeploy(w, r, "/admin/accounts", servers, "所选账户没有可用节点，无需部署")
}

// accountBatchTags 批量追加或替换标签；有变化的账户相关节点会置为待部署。
func (s *Server) accountBatchTags(w http.ResponseWriter, r *http.Request) {
	var accounts, tags []int64
	if !postIDs(w, r, "账户或标签 ID 不正确", idField{"account_ids", &accounts}, idField{"tag_ids", &tags}) {
		return
	}
	updated, unchanged, err := store.BatchAccountTags(r.Context(), s.DB, accounts, tags, r.PostFormValue("mode"))
	if err != nil {
		if errors.Is(err, store.ErrInvalid) {
			redirectMsg(w, r, "/admin/accounts", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	msg := fmt.Sprintf("标签已更新 %d 个账户，未变化 %d 个", updated, unchanged)
	if updated > 0 {
		msg += "；相关节点需重新部署"
	}
	redirectMsg(w, r, "/admin/accounts", "msg", msg)
}
