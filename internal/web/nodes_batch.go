package web

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"

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
	if s.redirectError(w, r, "/admin/nodes", err, store.ErrInvalid) {
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
	var priority *int
	if v := strings.TrimSpace(r.PostFormValue("priority")); add && v != "" {
		p, err := strconv.Atoi(v)
		if err != nil {
			redirectMsg(w, r, "/admin/nodes", "err", "优先级必须是整数")
			return
		}
		priority = &p
	}
	updated, err := store.BatchNodePlacements(r.Context(), s.DB, nodes, servers, add, priority, r.PostFormValue("exposed") != "")
	if s.redirectError(w, r, "/admin/nodes", err, store.ErrInvalid, store.ErrConflict) {
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
		if s.redirectError(w, r, "/admin/nodes", err, store.ErrInvalid) {
			return
		}
		msg, changed = fmt.Sprintf("已回切 %d 个部署位", n), n > 0
	} else {
		n, skipped, err := store.BatchNodeSwitch(r.Context(), s.DB, nodes)
		if s.redirectError(w, r, "/admin/nodes", err, store.ErrInvalid) {
			return
		}
		msg, changed = fmt.Sprintf("已切换 %d 个节点，跳过 %d 个（无指向或无其他可切部署位）", n, skipped), n > 0
	}
	s.failoverDNS(w, r, "/admin/nodes", msg, changed)
}

// nodeSwitch 把单个节点的域名切到指定部署位；placement 为 0 时全部回切，restore 只取消指定部署位的切走。
func (s *Server) nodeSwitch(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := fmt.Sprintf("/admin/nodes/%d/edit", id)
	if raw := r.PostFormValue("restore"); raw != "" {
		pid, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || pid <= 0 {
			http.Error(w, "部署位 ID 不正确", http.StatusBadRequest)
			return
		}
		if s.redirectError(w, r, back, store.RestorePlacementFailover(r.Context(), s.DB, id, pid), store.ErrInvalid) {
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
		if s.redirectError(w, r, back, err, store.ErrInvalid) {
			return
		}
		s.failoverDNS(w, r, back, "已回切", n > 0)
		return
	}
	if s.redirectError(w, r, back, store.SwitchNodePlacement(r.Context(), s.DB, id, target), store.ErrInvalid) {
		return
	}
	s.failoverDNS(w, r, back, "已切到指定部署位", true)
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

// nodeBatchDeploy 部署所选节点的待部署/待删除部署位所在的服务器。
func (s *Server) nodeBatchDeploy(w http.ResponseWriter, r *http.Request) {
	var nodes []int64
	if !postIDs(w, r, "节点 ID 不正确", idField{"node_ids", &nodes}) {
		return
	}
	seen := map[int64]bool{}
	var servers []int64
	for _, id := range nodes {
		ids, err := store.NodeDeployServers(r.Context(), s.DB, id, false)
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
	s.runDeploy(w, r, "/admin/nodes", servers, "所选节点没有待部署的部署位")
}

// batchEach 逐个节点执行 fn；单个失败不中断，汇总成功数和首个失败原因。
func (s *Server) batchEach(w http.ResponseWriter, r *http.Request, nodes []int64, fn func(id int64) error) (ok int, firstErr string, fatal bool) {
	failed := 0
	for _, id := range nodes {
		if err := fn(id); err != nil {
			if !errors.Is(err, store.ErrConflict) && !errors.Is(err, store.ErrInvalid) && !errors.Is(err, sql.ErrNoRows) {
				s.fail(w, r, err)
				return ok, "", true
			}
			if failed++; firstErr == "" {
				firstErr = err.Error()
			}
			continue
		}
		ok++
	}
	return ok, firstErr, false
}

func batchResult(w http.ResponseWriter, r *http.Request, verb string, ok, total int, firstErr string) {
	if firstErr != "" {
		redirectMsg(w, r, "/admin/nodes", "err", fmt.Sprintf("%s成功 %d 个，失败 %d 个：%s", verb, ok, total-ok, firstErr))
		return
	}
	redirectMsg(w, r, "/admin/nodes", "msg", fmt.Sprintf("已%s %d 个节点；请部署待部署项", verb, ok))
}

func (s *Server) nodeBatchToggle(w http.ResponseWriter, r *http.Request) {
	var nodes []int64
	if !postIDs(w, r, "节点 ID 不正确", idField{"node_ids", &nodes}) {
		return
	}
	disable := r.PostFormValue("disabled") == "1"
	ok, firstErr, fatal := s.batchEach(w, r, nodes, func(id int64) error { return store.ToggleNode(r.Context(), s.DB, id, disable) })
	if fatal {
		return
	}
	verb := "启用"
	if disable {
		verb = "禁用"
	}
	batchResult(w, r, verb, ok, len(nodes), firstErr)
}

func (s *Server) nodeBatchDelete(w http.ResponseWriter, r *http.Request) {
	var nodes []int64
	if !postIDs(w, r, "节点 ID 不正确", idField{"node_ids", &nodes}) {
		return
	}
	ok, firstErr, fatal := s.batchEach(w, r, nodes, func(id int64) error { _, err := store.DeleteNode(r.Context(), s.DB, id); return err })
	if fatal {
		return
	}
	batchResult(w, r, "删除", ok, len(nodes), firstErr)
}
