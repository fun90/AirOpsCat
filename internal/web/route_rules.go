package web

import (
	"database/sql"
	"errors"
	"net/http"
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

const routeRuleDeployHint = "强制部署相关服务器后生效"

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
	d["RuleTypes"] = []string{"domain", "ip", "source_ip", "port", "network", "inbound", "custom"}
	s.render(w, "route_rule_form", status, d)
}

func routeRuleID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id < 1 {
		http.NotFound(w, r)
		return 0, false
	}
	return id, true
}

func (s *Server) routeRuleEdit(w http.ResponseWriter, r *http.Request) {
	id, ok := routeRuleID(w, r)
	if !ok {
		return
	}
	rule, err := store.RouteRuleByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if rule == nil {
		http.NotFound(w, r)
		return
	}
	f := &RouteRuleForm{ID: id, Name: rule.Name, RuleType: rule.RuleType, RuleValue: prettyJSON(rule.RuleValue), OutboundNodeID: strconv.FormatInt(rule.OutboundNodeID, 10), Remark: rule.Remark, Enabled: rule.Enabled, ServerIDs: map[int64]bool{}}
	for _, sid := range rule.ServerIDs {
		f.ServerIDs[sid] = true
	}
	s.routeRuleFormPage(w, r, 200, f, "")
}

func (s *Server) routeRuleSave(w http.ResponseWriter, r *http.Request) {
	var id int64
	if r.PathValue("id") != "" {
		var ok bool
		id, ok = routeRuleID(w, r)
		if !ok {
			return
		}
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
	redirectMsg(w, r, "/admin/routes", "msg", "路由规则已保存；"+routeRuleDeployHint)
}

func (s *Server) routeRuleToggle(w http.ResponseWriter, r *http.Request) {
	id, ok := routeRuleID(w, r)
	if !ok {
		return
	}
	enabled := r.PostFormValue("enabled")
	if enabled != "0" && enabled != "1" {
		http.Error(w, "状态不正确", 422)
		return
	}
	if err := store.ToggleRouteRule(r.Context(), s.DB, id, enabled == "1"); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
		} else {
			s.fail(w, r, err)
		}
		return
	}
	redirectMsg(w, r, "/admin/routes", "msg", "路由规则状态已更新；"+routeRuleDeployHint)
}

func (s *Server) routeRuleDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := routeRuleID(w, r)
	if !ok {
		return
	}
	if err := store.DeleteRouteRule(r.Context(), s.DB, id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
		} else {
			s.fail(w, r, err)
		}
		return
	}
	redirectMsg(w, r, "/admin/routes", "msg", "路由规则已删除；"+routeRuleDeployHint)
}
