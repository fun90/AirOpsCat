package web

import (
	"bytes"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"

	"github.com/fun90/airopscat/internal/singbox"
	"github.com/fun90/airopscat/internal/store"
)

// NodeForm 保留用户输入的原始文本。
type NodeForm struct {
	ID                                                 int64
	Name, Type, Protocol, Port, Inbound, OutID, Region string
	Disabled                                           bool
	TagIDs                                             map[int64]bool
}

func (s *Server) nodeRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/nodes/default-inbound", func(w http.ResponseWriter, r *http.Request) {
		protocol := r.URL.Query().Get("protocol")
		host := ""
		if protocol == "hysteria2" || protocol == "anytls" {
			nodeID, err := strconv.ParseInt(r.URL.Query().Get("node_id"), 10, 64)
			if r.URL.Query().Get("node_id") == "" {
				nodeID = 0
				err = nil
			}
			if err != nil || nodeID < 0 {
				http.Error(w, "节点 ID 不正确", 400)
				return
			}
			serverID, err := strconv.ParseInt(r.URL.Query().Get("server_id"), 10, 64)
			if r.URL.Query().Get("server_id") == "" {
				serverID = 0
				err = nil
			}
			if err != nil || serverID < 0 {
				http.Error(w, "服务器 ID 不正确", 400)
				return
			}
			typ, err := strconv.Atoi(r.URL.Query().Get("type"))
			if err != nil {
				http.Error(w, "节点类型不正确", 400)
				return
			}
			host, err = store.DefaultInboundHost(r.Context(), s.DB, nodeID, serverID, r.URL.Query().Get("region"), typ)
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
		inbound, err := singbox.DefaultInbound("vless-reality")
		if err != nil {
			s.fail(w, r, err)
			return
		}
		s.nodeFormPage(w, r, http.StatusOK, &NodeForm{Type: "0", Protocol: "vless-reality", Inbound: string(inbound), TagIDs: map[int64]bool{}}, "", nil)
	})
	m.HandleFunc("GET /admin/nodes/{id}/edit", s.nodeEdit)
	m.HandleFunc("POST /admin/nodes", s.nodeSave)
	m.HandleFunc("POST /admin/nodes/batch-tags", s.nodeBatchTags)
	m.HandleFunc("POST /admin/nodes/batch-placements", s.nodeBatchPlacements)
	m.HandleFunc("POST /admin/nodes/batch-failover", s.nodeBatchFailover)
	m.HandleFunc("POST /admin/nodes/batch-deploy", s.nodeBatchDeploy)
	m.HandleFunc("POST /admin/nodes/batch-toggle", s.nodeBatchToggle)
	m.HandleFunc("POST /admin/nodes/batch-delete", s.nodeBatchDelete)
	m.HandleFunc("POST /admin/nodes/{id}", s.nodeSave)
	m.HandleFunc("POST /admin/nodes/{id}/switch", s.nodeSwitch)
	m.HandleFunc("POST /admin/placements/{id}/probe", s.placementProbe)
	m.HandleFunc("POST /admin/nodes/{id}/toggle", s.nodeToggle)
	m.HandleFunc("POST /admin/nodes/{id}/delete", s.nodeDelete)
	m.HandleFunc("POST /admin/nodes/{id}/cancel-delete", s.nodeCancelDelete)
}

func (s *Server) nodeList(w http.ResponseWriter, r *http.Request) {
	q := r.URL.Query()
	f := store.NodeFilter{Search: strings.TrimSpace(q.Get("q")), Type: -1, Status: -2}
	if v, err := strconv.Atoi(q.Get("type")); err == nil && (v == 0 || v == 1) {
		f.Type = v
	}
	if v, err := strconv.Atoi(q.Get("status")); err == nil && v >= -1 && v <= store.StatusLandingUnreachable {
		f.Status = v
	}
	f.TagID, _ = strconv.ParseInt(q.Get("tag"), 10, 64)
	if sort := q.Get("sort"); sort == "name" || sort == "status" {
		f.Sort, f.Asc = sort, q.Get("dir") == "asc"
	}
	page, size := pageParams(r)
	counts, err := store.NodeStatusCounts(r.Context(), s.DB, f)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	pendingServers, err := store.PendingDeployServers(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
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
	servers, err := store.BatchServerOptions(r.Context(), s.DB)
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
	pointed, err := store.ShownPointedPlacements(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	failover, err := store.FailoverServers(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	// 批量切换从当前指向切走，口径与 BatchNodeSwitch 一致
	allPointed, err := store.PointedPlacements(r.Context(), s.DB)
	var direct ipOnly
	if err == nil {
		direct, err = s.loadIPOnly(r.Context())
	}
	if err != nil {
		s.fail(w, r, err)
		return
	}
	switchWarn := map[int64]string{}
	for _, n := range list {
		switchWarn[n.ID] = direct.pointedWarn(allPointed[n.ID])
	}
	briefs, err := store.NodePlacementBriefs(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	// 按节点汇总检测异常：Bad 为不可达部署位数，Flaky 为偶发失败数
	probeSum := map[int64]struct{ Bad, Flaky int }{}
	for id, bs := range briefs {
		var v struct{ Bad, Flaky int }
		for _, b := range bs {
			if b.Unreachable() {
				v.Bad++
			} else if b.Flaky() {
				v.Flaky++
			}
		}
		if v.Bad+v.Flaky > 0 {
			probeSum[id] = v
		}
	}
	online := make(map[int64]nodeOnlineView, len(list))
	for _, n := range list {
		if n.Type == 0 {
			online[n.ID] = nodeOnline(snap, n.ID, placed[n.ID])
		}
	}
	d := s.pageData(r, "节点", "nodes")
	d["SwitchWarn"] = switchWarn
	d["Online"], d["Servers"], d["Pointed"], d["Failover"], d["Briefs"], d["ProbeSum"], d["ProbeFailRounds"] = online, servers, pointed, failover, briefs, probeSum, store.ProbeFailRounds
	d["List"], d["Tags"], d["Q"], d["Page"], d["Pages"] = list, tags, f.Search, page, (total+size-1)/size
	d["Type"], d["Status"], d["TagID"] = q.Get("type"), q.Get("status"), q.Get("tag")
	d["Tabs"], d["PendingServers"] = nodeTabs(q, counts), len(pendingServers)
	d["Sort"], d["SortAsc"] = f.Sort, f.Asc
	d["SortName"], d["SortStatus"] = sortHeader(q, "name", f.Sort, f.Asc), sortHeader(q, "status", f.Sort, f.Asc)
	s.render(w, "nodes", http.StatusOK, d)
}

type nodeTab struct {
	Label, URL string
	Active     bool
	Count      int
	Alert      bool // 有数量时需要关注
}

// nodeTabs 生成状态 tab：保留搜索、类型、标签和排序，页码回到第一页。
func nodeTabs(q url.Values, counts map[int]int) []nodeTab {
	cur := q.Get("status")
	defs := []struct {
		label  string
		status string
		key    int
		alert  bool
	}{{"全部", "", -2, false}, {"待部署", "0", store.PendingDeploy, true}, {"已部署", "1", store.Deployed, false},
		{"待删除", "2", store.PendingDelete, true}, {"无部署位", "-1", -1, true}, {"国内不可达", "3", store.StatusUnreachable, true}, {"落地不可达", "4", store.StatusLandingUnreachable, true}}
	tabs := make([]nodeTab, 0, len(defs))
	for _, d := range defs {
		next := url.Values{}
		for k, v := range q {
			if k != "page" && k != "status" && k != "msg" && k != "err" {
				next[k] = v
			}
		}
		if d.status != "" {
			next.Set("status", d.status)
		}
		tabs = append(tabs, nodeTab{d.label, "?" + next.Encode(), cur == d.status, counts[d.key], d.alert && counts[d.key] > 0})
	}
	return tabs
}

func formFromNode(n *store.Node) *NodeForm {
	f := &NodeForm{ID: n.ID, Name: n.Name, Type: strconv.Itoa(n.Type), Protocol: n.Protocol, Port: strconv.Itoa(n.Port), Inbound: n.Inbound,
		Region: n.Region, Disabled: n.Disabled, TagIDs: map[int64]bool{}}
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
	opts, err := store.PlacementServerOptions(ctx, s.DB, f.ID)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d["ServerOptions"], d["SwitchWarn"] = opts, map[int64]string{}
	if d["Domains"], err = store.BindingDomains(ctx, s.DB); err != nil {
		s.fail(w, r, err)
		return
	}
	if f.ID != 0 {
		domains, err := store.PlacementDomains(ctx, s.DB, f.ID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		pointed, err := store.ShownPointedPlacements(ctx, s.DB)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		targets := map[int64][]string{}
		for _, p := range pointed[f.ID] {
			targets[p.ID] = p.Domains
		}
		direct, err := s.loadIPOnly(ctx)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		warn := map[int64]string{}
		for _, p := range pls {
			warn[p.ID] = direct.switchWarn(f.ID, p.ID)
		}
		d["PlacementDomains"], d["PlacementTargets"], d["SwitchWarn"] = domains, targets, warn
		if f.Type == strconv.Itoa(store.NodeProxy) {
			d["SubNames"] = store.PlacementSubscriptionNames(f.Name, pls)
		}
	}
	// 校验失败回显时保留暂存的部署位增删
	addIDs, delTargets, newHosts := map[int64]bool{}, map[int64]string{}, map[int64]*store.NewHost{}
	for _, op := range ops {
		for _, h := range op.NewHosts {
			newHosts[h.ServerID] = h
		}
		for _, id := range op.AddServerIDs {
			addIDs[id] = true
		}
		for _, x := range op.Deletes {
			delTargets[x.PlacementID] = strconv.FormatInt(x.TargetID, 10) // 非空表示已暂存删除
		}
	}
	d["AddIDs"], d["DelTargets"], d["NewHosts"] = addIDs, delTargets, newHosts
	d["Form"], d["FormErr"], d["Tags"], d["Landing"], d["Regions"], d["Placements"], d["Hosts"] = f, formErr, tags, landing, regions, pls, hosts
	d["ProxyProtocols"], d["LandingProtocols"] = store.ProtocolsFor(store.NodeProxy), store.ProtocolsFor(store.NodeLanding)
	d["Probe"] = store.ConfigValue(ctx, s.DB, store.ProbeServersKey) != ""
	if f.ID != 0 {
		states, err := store.NodeProbeStates(ctx, s.DB, f.ID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		d["ProbeStates"], d["ProbeFailRounds"] = states, store.ProbeFailRounds
	}
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
	id := idFrom(r)
	n, err := store.NodeByID(r.Context(), s.DB, id)
	if !s.recordFound(w, r, n != nil, err) {
		return
	}
	s.nodeFormPage(w, r, http.StatusOK, formFromNode(n), "", nil)
}

func (s *Server) nodeSave(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	f := &NodeForm{ID: id, Name: formValue(r, "name"), Type: formValue(r, "type"), Protocol: formValue(r, "protocol"), Port: formValue(r, "port"),
		Inbound: strings.TrimSpace(r.PostFormValue("inbound")), OutID: formValue(r, "out_id"), Region: formValue(r, "region"),
		Disabled: r.PostFormValue("disabled") == "1", TagIDs: map[int64]bool{}}
	n := &store.Node{ID: id, Name: f.Name, Protocol: f.Protocol, Inbound: f.Inbound, Region: f.Region, Disabled: f.Disabled}
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
	var ops store.PlacementOps
	// 已有与待添加的部署位共用 p{服务器ID}_new_* 字段新增访问域名
	newHost := func(sid int64) {
		k := "p" + strconv.FormatInt(sid, 10) + "_"
		if name := formValue(r, k+"new_name"); name != "" {
			did, _ := strconv.ParseInt(formValue(r, k+"new_domain_id"), 10, 64)
			ops.NewHosts = append(ops.NewHosts, &store.NewHost{ServerID: sid, DomainID: did, Name: name})
		}
	}
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
			newHost(p.ServerID)
			p.Priority, p.Exposed, p.CheapStart, p.CheapEnd, p.AccessHostID = e.Priority, e.Exposed, e.CheapStart, e.CheapEnd, e.AccessHostID
			echo = append(echo, p)
		}
	}
	// 部署位增删在表单中暂存，随节点保存一起提交；新建时只用表单里的待添加列表
	for _, v := range r.PostForm["add_server"] {
		if sid, err := strconv.ParseInt(v, 10, 64); err == nil && sid > 0 {
			ops.AddServerIDs = append(ops.AddServerIDs, sid)
			newHost(sid)
		}
	}
	if id != 0 {
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
		s.requestError(w, r, err)
		return
	}
	msg := "节点已保存"
	if len(ops.Deletes) > 0 {
		msg += "；转移的域名需推送 DNS 后生效"
	}
	var dnsErrs []string
	for _, h := range ops.NewHosts {
		unlock := lockDomain(h.DomainID)
		err := s.syncBindingDNS(r.Context(), h.DomainID, h.RecordID)
		unlock()
		if err != nil {
			dnsErrs = append(dnsErrs, h.Name+"："+err.Error())
		}
	}
	if len(dnsErrs) > 0 {
		msg += "，但新增域名 DNS 同步失败（" + strings.Join(dnsErrs, "；") + "），请到 DNS 页面推送"
		if r.PostFormValue("deploy") == "1" {
			msg += "；未执行部署"
		}
		redirectMsg(w, r, "/admin/nodes", "err", msg)
		return
	}
	if len(ops.NewHosts) > 0 {
		msg += "，新增域名 DNS 已同步"
	}
	if r.PostFormValue("deploy") == "1" {
		ids, err := store.NodeDeployServers(r.Context(), s.DB, nodeID, false)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		s.runDeploy(w, r, "/admin/nodes", ids, msg+"，没有待部署的部署位")
		return
	}
	redirectMsg(w, r, "/admin/nodes", "msg", msg)
}

func (s *Server) nodeToggle(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	if err := store.ToggleNode(r.Context(), s.DB, id, r.PostFormValue("disabled") == "1"); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return
		}
		if errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, "/admin/nodes", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/nodes", "msg", "节点状态已更新，需重新部署后生效")
}

func (s *Server) nodeDelete(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	gone, err := store.DeleteNode(r.Context(), s.DB, id)
	if s.redirectError(w, r, "/admin/nodes", err, store.ErrConflict) {
		return
	}
	msg := "节点已删除"
	if !gone {
		msg = "节点已标记待删除，部署成功后会从服务器移除"
	}
	redirectMsg(w, r, "/admin/nodes", "msg", msg)
}

func (s *Server) nodeCancelDelete(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	if s.redirectError(w, r, "/admin/nodes", store.CancelDeleteNode(r.Context(), s.DB, id), store.ErrInvalid, store.ErrConflict) {
		return
	}
	redirectMsg(w, r, "/admin/nodes", "msg", "已取消删除，节点置为待部署")
}
