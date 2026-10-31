package web

import (
	"context"
	"database/sql"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/dns"
	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) dnsClient(ctx context.Context, providerID int64) (*dns.Client, error) {
	tok, err := store.DNSProviderToken(ctx, s.DB, s.Field, providerID)
	if err != nil {
		return nil, err
	}
	return &dns.Client{BaseURL: s.DNSBaseURL, Token: tok}, nil
}

func (s *Server) dnsProviderList(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	list, total, err := store.DNSProviderList(r.Context(), s.DB, q, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "DNS 服务商", "dns")
	d["List"], d["Q"], d["Page"], d["Pages"] = list, q, page, (total+size-1)/size
	s.render(w, "dns_providers", 200, d)
}

func (s *Server) dnsProviderForm(w http.ResponseWriter, r *http.Request, status int, p *store.DNSProvider, msg string) {
	title := "新增 DNS 服务商"
	if p.ID != 0 {
		title = "编辑 DNS 服务商"
	}
	d := s.pageData(r, title, "dns")
	d["Form"], d["FormErr"] = p, msg
	s.render(w, "dns_provider_form", status, d)
}

func (s *Server) dnsProviderEdit(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	p, err := store.DNSProviderByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if p == nil {
		http.NotFound(w, r)
		return
	}
	s.dnsProviderForm(w, r, 200, p, "")
}

func (s *Server) dnsProviderSave(w http.ResponseWriter, r *http.Request) {
	p := &store.DNSProvider{Name: formValue(r, "name"), AccountID: formValue(r, "account_id"), Remark: formValue(r, "remark"), Enabled: r.PostFormValue("enabled") == "1"}
	if r.PathValue("id") != "" {
		var ok bool
		if p.ID, ok = pathID(w, r, "id"); !ok {
			return
		}
	}
	_, err := store.SaveDNSProvider(r.Context(), s.DB, s.Field, p, strings.TrimSpace(r.PostFormValue("api_token")))
	if err != nil {
		msg, ok := s.saveErr(w, r, err)
		if ok {
			if p.ID != 0 {
				if cur, _ := store.DNSProviderByID(r.Context(), s.DB, p.ID); cur != nil {
					p.HasToken = cur.HasToken
				}
			}
			s.dnsProviderForm(w, r, 422, p, msg)
		}
		return
	}
	redirectMsg(w, r, "/admin/dns-providers", "msg", "DNS 服务商已保存")
}

func (s *Server) dnsProviderTest(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	c, err := s.dnsClient(r.Context(), id)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 30*time.Second)
	defer cancel()
	if err == nil {
		err = c.Verify(ctx)
	}
	msg := "Token 验证通过"
	if err != nil {
		msg = err.Error()
	}
	if e := store.RecordDNSProviderCheck(r.Context(), s.DB, id, err == nil, msg); e != nil {
		s.fail(w, r, e)
		return
	}
	if err != nil {
		redirectMsg(w, r, "/admin/dns-providers", "err", "验证失败："+msg)
		return
	}
	redirectMsg(w, r, "/admin/dns-providers", "msg", msg)
}

func (s *Server) dnsProviderDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	s.deleteDone(w, r, store.DeleteDNSProvider(r.Context(), s.DB, id), "/admin/dns-providers", "DNS 服务商已删除")
}
