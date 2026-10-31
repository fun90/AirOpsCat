package web

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"strconv"

	"github.com/fun90/airopscat/internal/store"
)

// idField 把表单里同名的多值字段解析进 ids。
type idField struct {
	name string
	ids  *[]int64
}

// postIDs 解析正整数 ID 列表；任一值不合法时写出 400（消息 errMsg）并返回 false。
func postIDs(w http.ResponseWriter, r *http.Request, errMsg string, fields ...idField) bool {
	for _, field := range fields {
		for _, raw := range r.PostForm[field.name] {
			id, err := strconv.ParseInt(raw, 10, 64)
			if err != nil || id <= 0 {
				http.Error(w, errMsg, http.StatusBadRequest)
				return false
			}
			*field.ids = append(*field.ids, id)
		}
	}
	return true
}

func (s *Server) nodeBatchTags(w http.ResponseWriter, r *http.Request) {
	var nodes, tags []int64
	if !postIDs(w, r, "节点或标签 ID 不正确", idField{"node_ids", &nodes}, idField{"tag_ids", &tags}) {
		return
	}
	updated, unchanged, err := store.BatchNodeTags(r.Context(), s.DB, nodes, tags, r.PostFormValue("mode"))
	if err != nil {
		if errors.Is(err, store.ErrInvalid) {
			redirectMsg(w, r, "/admin/nodes", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	message := fmt.Sprintf("标签已更新 %d 个节点，未变化 %d 个", updated, unchanged)
	if updated > 0 {
		message += "；请部署待部署项"
	}
	redirectMsg(w, r, "/admin/nodes", "msg", message)
}

func (s *Server) nodeBatchPlacements(w http.ResponseWriter, r *http.Request) {
	var nodes, servers []int64
	if !postIDs(w, r, "节点或服务器 ID 不正确", idField{"node_ids", &nodes}, idField{"server_ids", &servers}) {
		return
	}
	add := r.PostFormValue("placement_mode") != "REMOVE"
	updated, err := store.BatchNodePlacements(r.Context(), s.DB, nodes, servers, add)
	if err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, "/admin/nodes", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	message := fmt.Sprintf("部署位已调整 %d 个节点", updated)
	if updated > 0 {
		message += "；请部署待部署项"
	}
	redirectMsg(w, r, "/admin/nodes", "msg", message)
}

// nodeBatchFailover 切换时把所选节点从当前指向按优先级切到其他部署位，回切时取消全部切走。
func (s *Server) nodeBatchFailover(w http.ResponseWriter, r *http.Request) {
	var nodes []int64
	if !postIDs(w, r, "节点 ID 不正确", idField{"node_ids", &nodes}) {
		return
	}
	var msg string
	var changed bool
	if r.PostFormValue("failover_mode") == "RESTORE" {
		n, err := store.RestoreNodeFailover(r.Context(), s.DB, nodes)
		if s.failoverErr(w, r, "/admin/nodes", err) {
			return
		}
		msg, changed = fmt.Sprintf("已回切 %d 个部署位", n), n > 0
	} else {
		n, skipped, err := store.BatchNodeSwitch(r.Context(), s.DB, nodes)
		if s.failoverErr(w, r, "/admin/nodes", err) {
			return
		}
		msg, changed = fmt.Sprintf("已切换 %d 个节点，跳过 %d 个（无指向或无其他可切部署位）", n, skipped), n > 0
	}
	s.failoverDNS(w, r, "/admin/nodes", msg, changed)
}

// nodeSwitch 把单个节点的域名切到指定部署位；placement 为 0 时全部回切，restore 只取消指定部署位的切走。
func (s *Server) nodeSwitch(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	back := fmt.Sprintf("/admin/nodes/%d/edit", id)
	if raw := r.PostFormValue("restore"); raw != "" {
		pid, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || pid <= 0 {
			http.Error(w, "部署位 ID 不正确", http.StatusBadRequest)
			return
		}
		if s.failoverErr(w, r, back, store.RestorePlacementFailover(r.Context(), s.DB, id, pid)) {
			return
		}
		s.failoverDNS(w, r, back, "已取消切走", true)
		return
	}
	target, err := strconv.ParseInt(r.PostFormValue("placement"), 10, 64)
	if err != nil || target < 0 {
		http.Error(w, "部署位 ID 不正确", http.StatusBadRequest)
		return
	}
	if target == 0 {
		n, err := store.RestoreNodeFailover(r.Context(), s.DB, []int64{id})
		if s.failoverErr(w, r, back, err) {
			return
		}
		s.failoverDNS(w, r, back, "已回切", n > 0)
		return
	}
	if s.failoverErr(w, r, back, store.SwitchNodePlacement(r.Context(), s.DB, id, target)) {
		return
	}
	s.failoverDNS(w, r, back, "已切到指定部署位", true)
}

func (s *Server) failoverErr(w http.ResponseWriter, r *http.Request, back string, err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, store.ErrInvalid) {
		redirectMsg(w, r, back, "err", err.Error())
	} else {
		s.fail(w, r, err)
	}
	return true
}

// failoverDNS 切走标记变化后立即执行一轮 DNS 切换，不等定时任务。
func (s *Server) failoverDNS(w http.ResponseWriter, r *http.Request, back, msg string, changed bool) {
	if !changed {
		redirectMsg(w, r, back, "msg", msg)
		return
	}
	// 推送中途断开会留下待对账操作，因此不随浏览器请求取消。
	if err := s.autoDNS(context.WithoutCancel(r.Context())); err != nil {
		redirectMsg(w, r, back, "err", msg+"，但 DNS 切换未全部完成（定时任务会继续重试）："+err.Error())
		return
	}
	redirectMsg(w, r, back, "msg", msg+"并执行 DNS 切换，结果见系统日志")
}
