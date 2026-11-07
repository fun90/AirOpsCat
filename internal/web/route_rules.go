package web

import (
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"slices"
	"strconv"
	"strings"

	"github.com/fun90/airopscat/internal/store"
)

type RouteRuleForm struct {
	ID                                                int64
	Name, RuleType, RuleValue, OutboundNodeID, Remark string
	Enabled                                           bool
	ServerIDs                                         map[int64]bool
}

func (s *Server) routeRuleRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/routes", s.routeRuleList)
	m.HandleFunc("GET /admin/routes/new", func(w http.ResponseWriter, r *http.Request) {
		s.routeRuleFormPage(w, r, 200, &RouteRuleForm{RuleType: "domain", RuleValue: `["example.com"]`, Enabled: true, ServerIDs: map[int64]bool{}}, "")
	})
	m.HandleFunc("GET /admin/routes/{id}/edit", s.routeRuleEdit)
	m.HandleFunc("POST /admin/routes", s.routeRuleSave)
	m.HandleFunc("POST /admin/routes/{id}", s.routeRuleSave)
	m.HandleFunc("POST /admin/routes/{id}/toggle", s.routeRuleToggle)
	m.HandleFunc("POST /admin/routes/{id}/delete", s.routeRuleDelete)
}

const routeRuleDeployHint = "；强制部署相关服务器后生效"

// routeRuleServers 返回已启用规则所选的服务器；规则不存在或已停用时为空（停用规则不进入配置）。
func (s *Server) routeRuleServers(r *http.Request, id int64) []int64 {
	if id == 0 {
		return nil
	}
	rule, err := store.RouteRuleByID(r.Context(), s.DB, id)
	if err != nil || rule == nil || !rule.Enabled {
		return nil
	}
	return rule.ServerIDs
}

// routeRuleDeploy 路由规则不标记待部署：开关开启时直接把规则变更前后关联的启用服务器提交后台部署，返回提示后缀；
// 关闭时提示手动强制部署。
func (s *Server) routeRuleDeploy(r *http.Request, reason string, before, after []int64) string {
	if !s.autoDeployOn(r.Context()) {
		return routeRuleDeployHint
	}
	ids, err := store.DeployableServers(r.Context(), s.DB, append(slices.Clone(before), after...))
	if err != nil {
		slog.Warn("路由规则自动部署读取服务器失败", "err", err)
		return routeRuleDeployHint
	}
	return deployNote(s.enqueueAuto(reason, ids))
}

func (s *Server) routeRuleList(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	list, total, err := store.RouteRuleList(r.Context(), s.DB, q, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "路由规则", "routes")
	d["List"], d["Q"], d["Page"], d["Pages"] = list, q, page, (total+size-1)/size
	d["AutoDeploy"] = s.autoDeployOn(r.Context())
	s.render(w, "route_rules", 200, d)
}

func (s *Server) routeRuleFormPage(w http.ResponseWriter, r *http.Request, status int, f *RouteRuleForm, msg string) {
	servers, err := store.RouteRuleServers(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	landing, err := store.LandingNodes(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	title := "新增路由规则"
	if f.ID != 0 {
		title = "编辑路由规则"
	}
	d := s.pageData(r, title, "routes")
	d["Form"], d["FormErr"], d["Servers"], d["Landing"] = f, msg, servers, landing
	d["AutoDeploy"] = s.autoDeployOn(r.Context())
	d["RuleTypes"] = []string{"domain", "ip", "source_ip", "port", "network", "inbound", "custom"}
	s.render(w, "route_rule_form", status, d)
}

func (s *Server) routeRuleEdit(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	rule, err := store.RouteRuleByID(r.Context(), s.DB, id)
	if !s.recordFound(w, r, rule != nil, err) {
		return
	}
	f := &RouteRuleForm{ID: id, Name: rule.Name, RuleType: rule.RuleType, RuleValue: prettyJSON(rule.RuleValue), OutboundNodeID: strconv.FormatInt(rule.OutboundNodeID, 10), Remark: rule.Remark, Enabled: rule.Enabled, ServerIDs: map[int64]bool{}}
	for _, sid := range rule.ServerIDs {
		f.ServerIDs[sid] = true
	}
	s.routeRuleFormPage(w, r, 200, f, "")
}

func (s *Server) routeRuleSave(w http.ResponseWriter, r *http.Request) {
	id, ok := optionalPathID(w, r)
	if !ok {
		return
	}
	f := &RouteRuleForm{ID: id, Name: formValue(r, "name"), RuleType: formValue(r, "rule_type"), RuleValue: r.PostFormValue("rule_value"), OutboundNodeID: formValue(r, "outbound_node_id"), Remark: formValue(r, "remark"), Enabled: r.PostFormValue("enabled") == "1", ServerIDs: map[int64]bool{}}
	rule := &store.RouteRule{ID: id, Name: f.Name, RuleType: f.RuleType, RuleValue: f.RuleValue, Remark: f.Remark, Enabled: f.Enabled}
	var msg string
	rule.OutboundNodeID, _ = strconv.ParseInt(f.OutboundNodeID, 10, 64)
	for _, raw := range r.PostForm["servers"] {
		sid, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || sid < 1 {
			msg = "服务器 ID 不正确"
			continue
		}
		f.ServerIDs[sid] = true
		rule.ServerIDs = append(rule.ServerIDs, sid)
	}
	before := s.routeRuleServers(r, id)
	if msg == "" {
		err := store.SaveRouteRule(r.Context(), s.DB, rule)
		if errors.Is(err, store.ErrInvalid) {
			msg = err.Error()
		} else if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return
		} else if err != nil {
			s.fail(w, r, err)
			return
		}
	}
	if msg != "" {
		s.routeRuleFormPage(w, r, 422, f, msg)
		return
	}
	redirectMsg(w, r, "/admin/routes", "msg", "路由规则已保存"+s.routeRuleDeploy(r, "保存路由规则 #"+strconv.FormatInt(rule.ID, 10), before, s.routeRuleServers(r, rule.ID)))
}

func (s *Server) routeRuleToggle(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	enabled := r.PostFormValue("enabled")
	if enabled != "0" && enabled != "1" {
		http.Error(w, "状态不正确", 422)
		return
	}
	before := s.routeRuleServers(r, id)
	if s.requestError(w, r, store.ToggleRouteRule(r.Context(), s.DB, id, enabled == "1")) {
		return
	}
	redirectMsg(w, r, "/admin/routes", "msg", "路由规则状态已更新"+s.routeRuleDeploy(r, "启停路由规则 #"+r.PathValue("id"), before, s.routeRuleServers(r, id)))
}

func (s *Server) routeRuleDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	before := s.routeRuleServers(r, id)
	if s.requestError(w, r, store.DeleteRouteRule(r.Context(), s.DB, id)) {
		return
	}
	redirectMsg(w, r, "/admin/routes", "msg", "路由规则已删除"+s.routeRuleDeploy(r, "删除路由规则 #"+r.PathValue("id"), before, nil))
}
