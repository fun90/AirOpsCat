package web

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"strconv"

	"github.com/fun90/airopscat/internal/store"
)

// serverRetire 报废服务器：不连接服务器，强制移除全部部署位；勾选时一并删除服务器记录，否则保留为禁用。
func (s *Server) serverRetire(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := fmt.Sprintf("/admin/servers/%d/edit", id)
	sc := s.beginDeploy(r.Context())
	sc.server(id, true) // 报废后服务器已禁用或删除，引用其落地节点的服务器需在报废前取得
	res, err := store.RetireServer(r.Context(), s.DB, id, r.PostFormValue("delete_server") == "1")
	if notFound(w, r, err) || s.redirectError(w, r, back, err, store.ErrConflict, store.ErrInvalid) {
		return
	}
	msg := fmt.Sprintf("已报废，强制移除 %d 个部署位", res.Removed)
	if res.DeletedNodes > 0 {
		msg += fmt.Sprintf("，清理待删除节点 %d 个", res.DeletedNodes)
	}
	if res.Transferred > 0 {
		msg += "；转移的域名需推送 DNS 后生效"
	}
	msg += sc.finish("报废服务器 #"+r.PathValue("id"), "")
	if res.ServerDeleted {
		redirectMsg(w, r, "/admin/servers", "msg", msg+"，服务器已删除")
		return
	}
	redirectMsg(w, r, back, "msg", msg+"，服务器已禁用")
}

// serverMigrate 整机迁移第一步：在目标服务器上为本服务器的节点添加部署位，之后需部署目标服务器。
func (s *Server) serverMigrate(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := fmt.Sprintf("/admin/servers/%d/edit", id)
	target, err := strconv.ParseInt(r.PostFormValue("target"), 10, 64)
	if err != nil || target <= 0 {
		redirectMsg(w, r, back, "err", "请选择目标服务器")
		return
	}
	added, err := store.MigrateServerPlacements(r.Context(), s.DB, id, target)
	if notFound(w, r, err) || s.redirectError(w, r, back, err, store.ErrConflict, store.ErrInvalid) {
		return
	}
	msg := fmt.Sprintf("已在目标服务器添加 %d 个部署位", added)
	if jobID, n := s.autoDeploy(r.Context(), "迁移服务器 #"+r.PathValue("id")+" 第一步", []int64{target}); jobID > 0 {
		redirectMsg(w, r, back, "msg", msg+deployNote(jobID, n)+"；部署成功后点「完成迁移」")
		return
	}
	redirectMsg(w, r, back, "msg", msg+"，请部署目标服务器后完成迁移")
}

// serverMigrateFinish 整机迁移第二步：目标部署位运行后转移域名与订阅定制，本服务器部署位待删除。
func (s *Server) serverMigrateFinish(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := fmt.Sprintf("/admin/servers/%d/edit", id)
	target, err := strconv.ParseInt(r.PostFormValue("target"), 10, 64)
	if err != nil || target <= 0 {
		redirectMsg(w, r, back, "err", "请选择目标服务器")
		return
	}
	sc := s.beginDeploy(r.Context())
	sc.server(id, true)
	sc.server(target, true)
	moved, err := store.FinishServerMigration(r.Context(), s.DB, id, target)
	if notFound(w, r, err) || s.redirectError(w, r, back, err, store.ErrConflict, store.ErrInvalid) {
		return
	}
	sc.server(id, true)
	sc.server(target, true)
	redirectMsg(w, r, back, "msg", fmt.Sprintf("已迁移 %d 个部署位，本服务器部署位待删除；转移的域名需推送 DNS 后生效", moved)+sc.finish("完成迁移服务器 #"+r.PathValue("id"), ""))
}

// serverMigrateCancel 撤销迁移新建的目标部署位，并部署受影响服务器。
func (s *Server) serverMigrateCancel(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := fmt.Sprintf("/admin/servers/%d/edit", id)
	target, err := strconv.ParseInt(r.PostFormValue("target"), 10, 64)
	if err != nil || target <= 0 {
		redirectMsg(w, r, back, "err", "请选择迁移目标")
		return
	}
	sc := s.beginDeploy(r.Context())
	sc.server(id, true)
	sc.server(target, true)
	if s.redirectError(w, r, back, store.CancelServerMigration(r.Context(), s.DB, id, target), store.ErrInvalid, store.ErrConflict) {
		return
	}
	sc.server(id, true)
	sc.server(target, true)
	redirectMsg(w, r, back, "msg", "已取消迁移；本次新增的目标部署位已移除或待删除"+sc.finish("取消迁移服务器 #"+r.PathValue("id"), ""))
}

// serverUnblockPort 删除封禁记录并将远端规则解封加入下次部署。
func (s *Server) serverUnblockPort(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := fmt.Sprintf("/admin/servers/%d/edit", id)
	port, err := strconv.Atoi(r.PostFormValue("port"))
	if err != nil || port < 1 || port > 65535 {
		redirectMsg(w, r, back, "err", "端口不正确")
		return
	}
	if s.redirectError(w, r, back, store.UnblockServerPort(r.Context(), s.DB, id, port), store.ErrInvalid) {
		return
	}
	redirectMsg(w, r, back, "msg", fmt.Sprintf("已解除端口 %d 的封禁，下次部署时清除远端规则", port)+deployNote(s.autoDeploy(r.Context(), "解除防刷封禁 #"+r.PathValue("id"), []int64{id})))
}

// notFound 在服务器不存在时返回 404。
func notFound(w http.ResponseWriter, r *http.Request, err error) bool {
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return true
	}
	return false
}
