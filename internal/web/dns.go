package web

import (
	"context"
	"database/sql"
	"errors"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/dns"
	"github.com/fun90/airopscat/internal/store"
)

// dnsLocks 让同一域名的拉取/推送串行，避免两次推送重复创建记录。
var dnsLocks sync.Map

func lockDomain(id int64) func() {
	m, _ := dnsLocks.LoadOrStore(id, &sync.Mutex{})
	m.(*sync.Mutex).Lock()
	return m.(*sync.Mutex).Unlock
}

func (s *Server) dnsRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/dns-providers", s.dnsProviderList)
	m.HandleFunc("GET /admin/dns-providers/new", func(w http.ResponseWriter, r *http.Request) {
		s.dnsProviderForm(w, r, 200, &store.DNSProvider{Enabled: true}, "")
	})
	m.HandleFunc("GET /admin/dns-providers/{id}/edit", s.dnsProviderEdit)
	m.HandleFunc("POST /admin/dns-providers", s.dnsProviderSave)
	m.HandleFunc("POST /admin/dns-providers/{id}", s.dnsProviderSave)
	m.HandleFunc("POST /admin/dns-providers/{id}/test", s.dnsProviderTest)
	m.HandleFunc("POST /admin/dns-providers/{id}/delete", s.dnsProviderDelete)

	m.HandleFunc("GET /admin/domains", s.domainList)
	m.HandleFunc("GET /admin/domains/new", func(w http.ResponseWriter, r *http.Request) {
		s.domainForm(w, r, 200, &store.DNSDomain{}, "")
	})
	m.HandleFunc("GET /admin/domains/{id}/edit", s.domainEdit)
	m.HandleFunc("POST /admin/domains", s.domainSave)
	m.HandleFunc("POST /admin/domains/{id}", s.domainSave)
	m.HandleFunc("POST /admin/domains/{id}/delete", s.domainDelete)
	m.HandleFunc("POST /admin/domains/{id}/pull", s.domainPull)
	m.HandleFunc("POST /admin/domains/{id}/push", s.domainPush)

	m.HandleFunc("GET /admin/domains/{id}/records", s.recordList)
	m.HandleFunc("GET /admin/domains/{id}/records/new", s.recordNew)
	m.HandleFunc("GET /admin/domains/{id}/records/{rid}/edit", s.recordEdit)
	m.HandleFunc("POST /admin/domains/{id}/records", s.recordSave)
	m.HandleFunc("POST /admin/domains/{id}/records/{rid}", s.recordSave)
	m.HandleFunc("POST /admin/domains/{id}/records/{rid}/delete", s.recordDelete)
}

func pathID(w http.ResponseWriter, r *http.Request, key string) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue(key), 10, 64)
	if err != nil || id < 1 {
		http.NotFound(w, r)
		return 0, false
	}
	return id, true
}

// saveErr 把输入/冲突错误转成表单提示；其他错误按 500 处理并返回 false。
func (s *Server) saveErr(w http.ResponseWriter, r *http.Request, err error) (string, bool) {
	if strings.Contains(err.Error(), "DNS 自动操作尚待对账") {
		return "DNS 自动操作尚待对账，请等待任务完成后再修改", true
	}
	switch {
	case errors.Is(err, store.ErrInvalid), errors.Is(err, store.ErrConflict):
		return err.Error(), true
	case errors.Is(err, sql.ErrNoRows):
		http.NotFound(w, r)
	default:
		s.fail(w, r, err)
	}
	return "", false
}

// domainOr404 查域名；出错或不存在时已写出响应并返回 nil。
func (s *Server) domainOr404(w http.ResponseWriter, r *http.Request, id int64) *store.DNSDomain {
	dm, err := store.DNSDomainByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return nil
	}
	if dm == nil {
		http.NotFound(w, r)
	}
	return dm
}

// deleteDone 统一删除类处理器的收尾：失败回显错误，成功回显 okMsg。
func (s *Server) deleteDone(w http.ResponseWriter, r *http.Request, err error, back, okMsg string) {
	if err != nil {
		if msg, ok := s.saveErr(w, r, err); ok {
			redirectMsg(w, r, back, "err", msg)
		}
		return
	}
	redirectMsg(w, r, back, "msg", okMsg)
}

// ---------- 域名 ----------

func (s *Server) domainList(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	list, total, err := store.DNSDomainList(r.Context(), s.DB, q, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "域名", "domains")
	d["List"], d["Q"], d["Page"], d["Pages"] = list, q, page, (total+size-1)/size
	s.render(w, "domains", 200, d)
}

type domainFormData struct {
	*store.DNSDomain
	PriceText string
}

func (s *Server) domainForm(w http.ResponseWriter, r *http.Request, status int, dm *store.DNSDomain, msg string) {
	provs, err := store.DNSEnabledProviders(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	title := "新增域名"
	if dm.ID != 0 {
		title = "编辑域名"
	}
	f := domainFormData{DNSDomain: dm}
	if dm.Price != nil {
		f.PriceText = store.FormatCents(*dm.Price)
	}
	if v := r.PostFormValue("price"); v != "" {
		f.PriceText = v
	}
	d := s.pageData(r, title, "domains")
	d["Form"], d["FormErr"], d["Providers"] = f, msg, provs
	s.render(w, "domain_form", status, d)
}

func (s *Server) domainEdit(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if dm := s.domainOr404(w, r, id); dm != nil {
		s.domainForm(w, r, 200, dm, "")
	}
}

func (s *Server) domainSave(w http.ResponseWriter, r *http.Request) {
	dm := &store.DNSDomain{Domain: formValue(r, "domain"), ZoneID: formValue(r, "zone_id"), ExpireDate: formValue(r, "expire_date"), Supplier: formValue(r, "supplier"), Remark: formValue(r, "remark")}
	if r.PathValue("id") != "" {
		var ok bool
		if dm.ID, ok = pathID(w, r, "id"); !ok {
			return
		}
	}
	defer lockDomain(dm.ID)()
	if v, err := strconv.ParseInt(formValue(r, "provider_id"), 10, 64); err == nil && v > 0 {
		dm.ProviderID = &v
	}
	if _, err := store.SaveDNSDomain(r.Context(), s.DB, dm, r.PostFormValue("price")); err != nil {
		if msg, ok := s.saveErr(w, r, err); ok {
			s.domainForm(w, r, 422, dm, msg)
		}
		return
	}
	redirectMsg(w, r, "/admin/domains", "msg", "域名已保存")
}

func (s *Server) domainDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	defer lockDomain(id)()
	s.deleteDone(w, r, store.DeleteDNSDomain(r.Context(), s.DB, id), "/admin/domains", "域名已删除（服务商侧记录不受影响）")
}

// domainClient 取域名的服务商客户端与 Zone；未绑定或服务商已停用时返回用户可读错误。
func (s *Server) domainClient(ctx context.Context, id int64) (*store.DNSDomain, *dns.Client, error) {
	dm, err := store.DNSDomainByID(ctx, s.DB, id)
	if err != nil || dm == nil {
		if err == nil {
			err = sql.ErrNoRows
		}
		return nil, nil, err
	}
	if dm.ProviderID == nil || dm.ZoneID == "" {
		return dm, nil, errors.New("域名未绑定 DNS 服务商或缺少 Zone ID")
	}
	p, err := store.DNSProviderByID(ctx, s.DB, *dm.ProviderID)
	if err != nil || p == nil || !p.Enabled {
		if err == nil {
			err = errors.New("DNS 服务商已停用或不存在")
		}
		return dm, nil, err
	}
	c, err := s.dnsClient(ctx, *dm.ProviderID)
	return dm, c, err
}

func (s *Server) recordsBack(id int64) string {
	return "/admin/domains/" + strconv.FormatInt(id, 10) + "/records"
}

func (s *Server) domainPull(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	defer lockDomain(id)()
	dm, c, err := s.domainClient(r.Context(), id)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		redirectMsg(w, r, s.recordsBack(id), "err", err.Error())
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
	defer cancel()
	recs, err := c.List(ctx, dm.ZoneID)
	if err != nil {
		store.SetDNSDomainSync(r.Context(), s.DB, id, store.DNSSyncFailed)
		redirectMsg(w, r, s.recordsBack(id), "err", "拉取失败："+err.Error())
		return
	}
	a, u, rm, err := store.ApplyDNSPull(r.Context(), s.DB, id, recs)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	slog.Info("DNS 拉取完成", "domain", dm.Domain, "added", a, "updated", u, "removed", rm)
	redirectMsg(w, r, s.recordsBack(id), "msg", "拉取完成：新增 "+strconv.Itoa(a)+"，更新 "+strconv.Itoa(u)+"，移除 "+strconv.Itoa(rm))
}

// domainPush 修改真实解析，必须显式 confirm=push，且永不由任何后台任务触发。
func (s *Server) domainPush(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if r.PostFormValue("confirm") != "push" {
		redirectMsg(w, r, s.recordsBack(id), "err", "推送需要明确确认")
		return
	}
	defer lockDomain(id)()
	dm, c, err := s.domainClient(r.Context(), id)
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		redirectMsg(w, r, s.recordsBack(id), "err", err.Error())
		return
	}
	p, err := store.LoadDNSPush(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if p.Empty() {
		redirectMsg(w, r, s.recordsBack(id), "err", "没有待推送的变更")
		return
	}
	var ids []string
	if len(p.Batch.Deletes)+len(p.Batch.Patches)+len(p.Batch.Posts) > 0 {
		ctx, cancel := context.WithTimeout(r.Context(), 2*time.Minute)
		defer cancel()
		if ids, err = c.Push(ctx, dm.ZoneID, p.Batch); err != nil {
			store.SetDNSDomainSync(r.Context(), s.DB, id, store.DNSSyncFailed)
			redirectMsg(w, r, s.recordsBack(id), "err", "推送失败（本地变更已保留）："+err.Error())
			return
		}
	}
	if err := store.ApplyDNSPush(r.Context(), s.DB, id, p, ids); err != nil {
		s.fail(w, r, err)
		return
	}
	slog.Info("DNS 推送完成", "domain", dm.Domain, "delete", len(p.DeleteIDs), "update", len(p.PatchIDs), "create", len(p.PostIDs))
	redirectMsg(w, r, s.recordsBack(id), "msg", "推送完成：创建 "+strconv.Itoa(len(p.PostIDs))+"，更新 "+strconv.Itoa(len(p.PatchIDs))+"，删除 "+strconv.Itoa(len(p.DeleteIDs)))
}

// ---------- 记录 ----------

func (s *Server) recordList(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	dm := s.domainOr404(w, r, id)
	if dm == nil {
		return
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	list, total, err := store.DNSRecordList(r.Context(), s.DB, id, q, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, dm.Domain+" 的 DNS 记录", "domains")
	d["Domain"], d["List"], d["Q"], d["Page"], d["Pages"], d["ListURL"] = dm, list, q, page, (total+size-1)/size, r.URL.RequestURI()
	s.render(w, "dns_records", 200, d)
}

type recordFormData struct {
	*store.DNSRecord
	ServerSel string
}

func (s *Server) recordForm(w http.ResponseWriter, r *http.Request, status int, dm *store.DNSDomain, rec *store.DNSRecord, msg string) {
	servers, err := store.DNSServerOptions(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	title := "新增 A 记录"
	if rec.ID != 0 {
		title = "编辑 A 记录"
	}
	f := recordFormData{DNSRecord: rec}
	if rec.ServerID != nil {
		f.ServerSel = strconv.FormatInt(*rec.ServerID, 10)
	}
	d := s.pageData(r, title, "domains")
	d["Domain"], d["Form"], d["FormErr"], d["Servers"] = dm, f, msg, servers
	s.render(w, "dns_record_form", status, d)
}

func (s *Server) recordNew(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if dm := s.domainOr404(w, r, id); dm != nil {
		s.recordForm(w, r, 200, dm, &store.DNSRecord{DomainID: id, TTL: 300}, "")
	}
}

func (s *Server) recordEdit(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	rid, ok := pathID(w, r, "rid")
	if !ok {
		return
	}
	dm, err := store.DNSDomainByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	rec, err := store.DNSRecordByID(r.Context(), s.DB, rid)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if dm == nil || rec == nil || rec.DomainID != id || rec.Type != "A" {
		http.NotFound(w, r)
		return
	}
	s.recordForm(w, r, 200, dm, rec, "")
}

func (s *Server) recordSave(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	defer lockDomain(id)()
	dm := s.domainOr404(w, r, id)
	if dm == nil {
		return
	}
	rec := &store.DNSRecord{DomainID: id, Name: formValue(r, "name"), Content: formValue(r, "content"), Remark: formValue(r, "remark"), Proxied: r.PostFormValue("proxied") == "1"}
	if r.PathValue("rid") != "" {
		if rec.ID, ok = pathID(w, r, "rid"); !ok {
			return
		}
	}
	rec.TTL, _ = strconv.Atoi(formValue(r, "ttl"))
	if v, err := strconv.ParseInt(formValue(r, "server_id"), 10, 64); err == nil && v > 0 {
		rec.ServerID = &v
	}
	if _, err := store.SaveDNSRecord(r.Context(), s.DB, rec); err != nil {
		if msg, ok := s.saveErr(w, r, err); ok {
			s.recordForm(w, r, 422, dm, rec, msg)
		}
		return
	}
	redirectMsg(w, r, s.recordsBack(id), "msg", "记录已保存（尚未推送到服务商）")
}

func (s *Server) recordDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	defer lockDomain(id)()
	rid, ok := pathID(w, r, "rid")
	if !ok {
		return
	}
	if rec, err := store.DNSRecordByID(r.Context(), s.DB, rid); err != nil {
		s.fail(w, r, err)
		return
	} else if rec == nil || rec.DomainID != id {
		http.NotFound(w, r)
		return
	}
	s.deleteDone(w, r, store.DeleteDNSRecord(r.Context(), s.DB, rid), s.recordsBack(id), "记录已删除（如已存在于服务商，需推送后生效）")
}
