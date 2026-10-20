package web

import (
	"errors"
	"github.com/fun90/airopscat/internal/store"
	"net/http"
	"strconv"
)

func (s *Server) bindingPage(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	a, err := store.AccountByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if a == nil {
		http.NotFound(w, r)
		return
	}
	list, err := store.AccountBindings(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	choices, domains, err := store.BindingChoices(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "订阅定制", "accounts")
	d["From"] = bindingFrom(r)
	d["Account"], d["List"], d["Choices"], d["Domains"] = a, list, choices, domains
	s.render(w, "account_bindings", 200, d)
}
func (s *Server) bindingSave(w http.ResponseWriter, r *http.Request) {
	b := &store.AccountBinding{AccountID: idFrom(r), Enabled: r.PostFormValue("enabled") == "1", Remark: formValue(r, "remark")}
	b.ID, _ = strconv.ParseInt(formValue(r, "binding_id"), 10, 64)
	b.PlacementID, _ = strconv.ParseInt(formValue(r, "placement_id"), 10, 64)
	if raw := formValue(r, "record_id"); raw != "" {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			http.Error(w, "域名 ID 不正确", 400)
			return
		}
		b.RecordID = &id
	}
	b.NewDomainID, _ = strconv.ParseInt(formValue(r, "new_domain_id"), 10, 64)
	b.NewName = formValue(r, "new_name")
	err := store.SaveAccountBinding(r.Context(), s.DB, b)
	path := "/admin/accounts/" + strconv.FormatInt(b.AccountID, 10) + "/bindings" + bindingFromQuery(r)
	if err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, path, "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, path, "msg", "订阅定制已保存")
}
func (s *Server) bindingDelete(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("binding"), 10, 64)
	if err := store.DeleteAccountBinding(r.Context(), s.DB, idFrom(r), id); err != nil {
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/accounts/"+r.PathValue("id")+"/bindings"+bindingFromQuery(r), "msg", "订阅定制已删除")
}

// bindingFrom 标记入口：edit 表示从账户编辑页进入，取消回编辑页；否则回列表。
func bindingFrom(r *http.Request) string {
	if r.FormValue("from") == "edit" {
		return "edit"
	}
	return ""
}

func bindingFromQuery(r *http.Request) string {
	if bindingFrom(r) == "edit" {
		return "?from=edit"
	}
	return ""
}
