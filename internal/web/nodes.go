package web

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"strings"

	"github.com/fun90/airopscat/internal/singbox"
	"github.com/fun90/airopscat/internal/store"
)

// NodeForm 保留用户输入的原始文本。
type NodeForm struct {
	ID                                                 int64
	Name, Type, Protocol, Port, Inbound, OutID, Region string
	Remark                                             string
	Disabled                                           bool
	TagIDs                                             map[int64]bool
}

func (s *Server) nodeRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/nodes/default-inbound", func(w http.ResponseWriter, r *http.Request) {
		protocol := r.URL.Query().Get("protocol")
		host := ""
		if protocol == "hysteria2" {
			nodeID, err := strconv.ParseInt(r.URL.Query().Get("node_id"), 10, 64)
			if r.URL.Query().Get("node_id") == "" {
				nodeID = 0
				err = nil
			}
			if err != nil || nodeID < 0 {
				http.Error(w, "节点 ID 不正确", 400)
				return
			}
			typ, err := strconv.Atoi(r.URL.Query().Get("type"))
			if err != nil {
				http.Error(w, "节点类型不正确", 400)
				return
			}
			host, err = store.DefaultInboundHost(r.Context(), s.DB, nodeID, r.URL.Query().Get("region"), typ)
			if err != nil {
				if errors.Is(err, store.ErrInvalid) || errors.Is(err, sql.ErrNoRows) {
					http.Error(w, err.Error(), 400)
				} else {
					s.fail(w, r, err)
				}
				return
			}
		}
		body, err := singbox.DefaultInboundForHost(protocol, host)
		if err != nil {
			http.Error(w, err.Error(), http.StatusBadRequest)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("Cache-Control", "no-store")
		w.Write(body)
	})
	m.HandleFunc("GET /admin/nodes", s.nodeList)
	m.HandleFunc("GET /admin/nodes/new", func(w http.ResponseWriter, r *http.Request) {
		s.nodeFormPage(w, r, http.StatusOK, &NodeForm{Type: "0", Protocol: "vless-reality", Inbound: "{}", TagIDs: map[int64]bool{}}, "", nil)
	})
	m.HandleFunc("GET /admin/nodes/{id}/edit", s.nodeEdit)
	m.HandleFunc("POST /admin/nodes", s.nodeSave)
	m.HandleFunc("POST /admin/nodes/batch-tags", s.nodeBatchTags)
	m.HandleFunc("POST /admin/nodes/{id}", s.nodeSave)
	m.HandleFunc("POST /admin/nodes/{id}/toggle", s.nodeToggle)
	m.HandleFunc("POST /admin/nodes/{id}/delete", s.nodeDelete)
	m.HandleFunc("POST /admin/nodes/{id}/cancel-delete", s.nodeCancelDelete)
}

func (s *Server) nodeBatchTags(w http.ResponseWriter, r *http.Request) {
	var nodes, tags []int64
	for _, field := range []struct {
		name string
		ids  *[]int64
	}{{"node_ids", &nodes}, {"tag_ids", &tags}} {
		for _, raw := range r.PostForm[field.name] {
			id, err := strconv.ParseInt(raw, 10, 64)
			if err != nil || id <= 0 {
				http.Error(w, "节点或标签 ID 不正确", http.StatusBadRequest)
				return
			}
			*field.ids = append(*field.ids, id)
		}
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

func (s *Server) nodeList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := store.NodeFilter{Search: strings.TrimSpace(q.Get("q")), Type: -1, Status: -2}
	if v, err := strconv.Atoi(q.Get("type")); err == nil && (v == 0 || v == 1) {
		f.Type = v
	}
	if v, err := strconv.Atoi(q.Get("status")); err == nil && v >= -1 && v <= 2 {
		f.Status = v
	}
	f.TagID, _ = strconv.ParseInt(q.Get("tag"), 10, 64)
	page, size := pageParams(r)
	list, total, err := store.NodeList(r.Context(), s.DB, f, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	tags, err := store.AllTags(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	snap, err := s.onlineSnapshot(r.Context())
	var placed map[int64][]string
	if err == nil {
		placed, err = store.NodeServerIPs(r.Context(), s.DB)
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	online := make(map[int64]string, len(list))
	for _, n := range list {
		if n.Type == 0 {
			online[n.ID] = nodeOnlineText(snap, n.ID, placed[n.ID])
		}
	}
	d := s.pageData(r, "节点", "nodes")
	d["Online"] = online
	d["List"], d["Tags"], d["Q"], d["Page"], d["Pages"] = list, tags, f.Search, page, (total+size-1)/size
	d["Type"], d["Status"], d["TagID"] = q.Get("type"), q.Get("status"), q.Get("tag")
	s.render(w, "nodes", http.StatusOK, d)
}

func formFromNode(n *store.Node) *NodeForm {
	f := &NodeForm{ID: n.ID, Name: n.Name, Type: strconv.Itoa(n.Type), Protocol: n.Protocol, Port: strconv.Itoa(n.Port), Inbound: n.Inbound,
		Region: n.Region, Remark: n.Remark, Disabled: n.Disabled, TagIDs: map[int64]bool{}}
	f.Inbound = prettyJSON(n.Inbound)
	if n.OutID != nil {
		f.OutID = strconv.FormatInt(*n.OutID, 10)
	}
	for _, id := range n.TagIDs {
		f.TagIDs[id] = true
	}
	return f
}

// nodeFormPage 渲染节点表单；编辑时附带部署位。formErr 为整体错误，pls 为用户提交的部署位输入（回显用）。
func (s *Server) nodeFormPage(w http.ResponseWriter, r *http.Request, status int, f *NodeForm, formErr string, pls []*store.Placement, ops ...store.PlacementOps) {
	ctx := r.Context()
	title := "新增节点"
	if f.ID != 0 {
		title = "编辑节点"
	}
	d := s.pageData(r, title, "nodes")
	tags, err1 := store.AllTags(ctx, s.DB)
	landing, err2 := store.LandingNodes(ctx, s.DB)
	regions, err3 := store.Regions(ctx, s.DB)
	if err := errors.Join(err1, err2, err3); err != nil {
		s.fail(w, r, err)
		return
	}
	if f.ID != 0 && pls == nil {
		var err error
		if pls, err = store.Placements(ctx, s.DB, f.ID); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	var sids []int64
	for _, p := range pls {
		sids = append(sids, p.ServerID)
	}
	hosts, err := store.AccessHostOptions(ctx, s.DB, sids)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if f.ID != 0 {
		opts, err := store.PlacementServerOptions(ctx, s.DB, f.ID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		domains, err := store.PlacementDomains(ctx, s.DB, f.ID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		d["ServerOptions"], d["PlacementDomains"] = opts, domains
	}
	// 校验失败回显时保留暂存的部署位增删
	addIDs, delTargets := map[int64]bool{}, map[int64]string{}
	for _, op := range ops {
		for _, id := range op.AddServerIDs {
			addIDs[id] = true
		}
		for _, x := range op.Deletes {
			delTargets[x.PlacementID] = strconv.FormatInt(x.TargetID, 10) // 非空表示已暂存删除
		}
	}
	d["AddIDs"], d["DelTargets"] = addIDs, delTargets
	d["Form"], d["FormErr"], d["Tags"], d["Landing"], d["Regions"], d["Placements"], d["Hosts"] = f, formErr, tags, landing, regions, pls, hosts
	d["ProxyProtocols"], d["LandingProtocols"] = store.ProtocolsFor(store.NodeProxy), store.ProtocolsFor(store.NodeLanding)
	s.render(w, "node_form", status, d)
}

// prettyJSON 缩进 JSON 且保持键顺序；不合法时原样返回。
func prettyJSON(s string) string {
	var b bytes.Buffer
	if json.Indent(&b, []byte(s), "", "  ") != nil {
		return s
	}
	return b.String()
}

func (s *Server) nodeEdit(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	n, err := store.NodeByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if n == nil {
		http.NotFound(w, r)
		return
	}
	s.nodeFormPage(w, r, http.StatusOK, formFromNode(n), "", nil)
}

func (s *Server) nodeSave(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	f := &NodeForm{ID: id, Name: formValue(r, "name"), Type: formValue(r, "type"), Protocol: formValue(r, "protocol"), Port: formValue(r, "port"),
		Inbound: strings.TrimSpace(r.PostFormValue("inbound")), OutID: formValue(r, "out_id"), Region: formValue(r, "region"),
		Remark: formValue(r, "remark"), Disabled: r.PostFormValue("disabled") == "1", TagIDs: map[int64]bool{}}
	n := &store.Node{ID: id, Name: f.Name, Protocol: f.Protocol, Inbound: f.Inbound, Region: f.Region, Remark: f.Remark, Disabled: f.Disabled}
	n.Type, _ = strconv.Atoi(f.Type)
	var portErr error
	if f.Port != "" || id != 0 {
		port, err := strconv.Atoi(f.Port)
		if err != nil || port < 1 || port > 65535 {
			portErr = fmt.Errorf("端口范围 1-65535；仅新建可留空自动分配")
		}
		n.Port = port
	}
	if f.OutID != "" {
		if v, err := strconv.ParseInt(f.OutID, 10, 64); err == nil {
			n.OutID = &v
		}
	}
	var tagIDs []int64
	for _, v := range r.PostForm["tags"] {
		if t, err := strconv.ParseInt(v, 10, 64); err == nil {
			tagIDs = append(tagIDs, t)
			f.TagIDs[t] = true
		}
	}
	var edits []store.PlacementEdit
	var echo []*store.Placement
	if id != 0 {
		pls, err := store.Placements(r.Context(), s.DB, id)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		for _, p := range pls {
			k := "p" + strconv.FormatInt(p.ServerID, 10) + "_"
			if p.Deployed == store.PendingDelete {
				echo = append(echo, p)
				continue
			}
			e := store.PlacementEdit{ServerID: p.ServerID, Exposed: r.PostFormValue(k+"exposed") == "1",
				CheapStart: formValue(r, k+"cheap_start"), CheapEnd: formValue(r, k+"cheap_end")}
			e.Priority, _ = strconv.Atoi(formValue(r, k+"priority"))
			if h, err := strconv.ParseInt(formValue(r, k+"host"), 10, 64); err == nil {
				e.AccessHostID = &h
			}
			edits = append(edits, e)
			p.Priority, p.Exposed, p.CheapStart, p.CheapEnd, p.AccessHostID = e.Priority, e.Exposed, e.CheapStart, e.CheapEnd, e.AccessHostID
			echo = append(echo, p)
		}
	}
	// 部署位增删在表单中暂存，随节点保存一起提交
	var ops store.PlacementOps
	if id != 0 {
		for _, v := range r.PostForm["add_server"] {
			if sid, err := strconv.ParseInt(v, 10, 64); err == nil && sid > 0 {
				ops.AddServerIDs = append(ops.AddServerIDs, sid)
			}
		}
		for _, v := range r.PostForm["del_placement"] {
			if pid, err := strconv.ParseInt(v, 10, 64); err == nil && pid > 0 {
				t, _ := strconv.ParseInt(r.PostFormValue("del_target_"+v), 10, 64)
				ops.Deletes = append(ops.Deletes, store.PlacementDelete{PlacementID: pid, TargetID: t})
			}
		}
	}
	if portErr != nil {
		s.nodeFormPage(w, r, http.StatusUnprocessableEntity, f, portErr.Error(), echo, ops)
		return
	}
	nodeID, err := store.SaveNode(r.Context(), s.DB, n, tagIDs, edits, ops)
	if err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
			s.nodeFormPage(w, r, http.StatusUnprocessableEntity, f, err.Error(), echo, ops)
			return
		}
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return
		}
		s.fail(w, r, err)
		return
	}
	_ = nodeID
	msg := "节点已保存"
	if len(ops.Deletes) > 0 {
		msg += "；转移的域名需推送 DNS 后生效"
	}
	redirectMsg(w, r, "/admin/nodes", "msg", msg)
}

func (s *Server) nodeToggle(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err := store.ToggleNode(r.Context(), s.DB, id, r.PostFormValue("disabled") == "1"); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/nodes", "msg", "节点状态已更新，需重新部署后生效")
}

func (s *Server) nodeDelete(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	gone, err := store.DeleteNode(r.Context(), s.DB, id)
	if err != nil {
		if errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, "/admin/nodes", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	msg := "节点已删除"
	if !gone {
		msg = "节点已标记待删除，部署成功后会从服务器移除"
	}
	redirectMsg(w, r, "/admin/nodes", "msg", msg)
}

func (s *Server) nodeCancelDelete(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err := store.CancelDeleteNode(r.Context(), s.DB, id); err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, "/admin/nodes", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/nodes", "msg", "已取消删除，节点置为待部署")
}
