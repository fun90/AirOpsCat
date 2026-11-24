package web

import (
	"context"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) bindingPage(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	a, err := store.AccountByID(r.Context(), s.DB, id)
	if !s.recordFound(w, r, a != nil, err) {
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
	d := s.pageData(r, "订阅定制 · "+a.Remark, "accounts")
	self, query := bindingNavigation(r, id)
	d["From"], d["BindingBack"] = query.Get("from"), query.Get("back")
	d["EditReturn"] = "/admin/accounts"
	if query.Get("from") == "edit" {
		d["EditReturn"] = fmt.Sprintf("/admin/accounts/%d/edit", id)
	}
	if back := query.Get("back"); back != "" {
		d["EditReturn"] = back
	}
	d["Self"] = self.String()
	d["EditBase"] = "?edit="
	if self.RawQuery != "" {
		d["EditBase"] = "?" + self.RawQuery + "&edit="
	}
	if editID, _ := strconv.ParseInt(r.URL.Query().Get("edit"), 10, 64); editID > 0 {
		for _, b := range list {
			if b.ID == editID {
				d["Edit"] = b
			}
		}
	}
	d["Account"], d["List"], d["Choices"], d["Domains"] = a, list, choices, domains
	s.render(w, "account_bindings", 200, d)
}
func (s *Server) bindingSave(w http.ResponseWriter, r *http.Request) {
	b := &store.AccountBinding{AccountID: idFrom(r), Enabled: r.PostFormValue("enabled") == "1", Remark: formValue(r, "remark"), ExternalUUID: formValue(r, "external_uuid")}
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
	domainID := b.NewDomainID
	if b.NewName == "" && b.RecordID != nil {
		record, err := store.DNSRecordByID(r.Context(), s.DB, *b.RecordID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if record != nil {
			domainID = record.DomainID
		}
	}
	if domainID > 0 {
		defer lockDomain(domainID)()
	}
	editID := b.ID
	err := store.SaveAccountBinding(r.Context(), s.DB, b)
	if err == nil {
		editID = b.ID
	}
	// 保存后（无论入口）停留在该定制的编辑表单；新增失败则回到新增。
	page, query := bindingNavigation(r, b.AccountID)
	if editID != 0 {
		query.Set("edit", strconv.FormatInt(editID, 10))
		page.RawQuery = query.Encode()
	}
	path := page.String()
	if err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, path, "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	if b.RecordID != nil {
		if err := s.syncBindingDNS(r.Context(), domainID, *b.RecordID); err != nil {
			redirectMsg(w, r, path, "err", "订阅定制已保存，但 DNS 同步失败："+err.Error()+"；请重试保存或在 DNS 页面推送。同步成功前订阅使用部署位默认地址")
			return
		}
		redirectMsg(w, r, path, "msg", "订阅定制已保存，DNS 已同步")
		return
	}
	redirectMsg(w, r, path, "msg", "订阅定制已保存")
}

// syncBindingDNS 由持有域名锁的保存流程调用，只推送本次绑定的记录。
func (s *Server) syncBindingDNS(ctx context.Context, domainID, recordID int64) error {
	p, err := store.LoadDNSRecordPush(ctx, s.DB, recordID)
	if err != nil || p.Empty() {
		return err
	}
	ctx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	dm, c, err := s.domainClient(ctx, domainID)
	if err != nil {
		return err
	}
	ids, err := c.Push(ctx, dm.ZoneID, p.Batch)
	if err != nil {
		return err
	}
	return store.ApplyDNSPush(ctx, s.DB, domainID, p, ids)
}

func (s *Server) bindingDelete(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("binding"), 10, 64)
	if err := store.DeleteAccountBinding(r.Context(), s.DB, idFrom(r), id); err != nil {
		s.fail(w, r, err)
		return
	}
	page, _ := bindingNavigation(r, idFrom(r))
	redirectNotice(w, r, page.String(), "msg", "订阅定制已删除")
}

func bindingNavigation(r *http.Request, id int64) (*url.URL, url.Values) {
	page := &url.URL{Path: fmt.Sprintf("/admin/accounts/%d/bindings", id)}
	query := make(url.Values)
	if r.FormValue("from") == "edit" {
		query.Set("from", "edit")
	}
	if back := explicitReturnURL(r, page.Path); back != "" {
		query.Set("back", back)
	}
	page.RawQuery = query.Encode()
	return page, query
}
