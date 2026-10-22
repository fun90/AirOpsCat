package web

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

type accountForm struct {
	ID                                                                        int64
	UserID, UserSource, Email, Password, NickName, Remark                     string
	AccountNo, UUID, FromDate, ToDate, PeriodType, NodePrefix                 string
	NodeMultiple, MaxConnections, MaxIPs, Bandwidth, DownloadMbps, UploadMbps string
	Amount, PaymentMethod                                                     string
	Disabled                                                                  bool
	TagIDs                                                                    map[int64]bool
}

func (s *Server) accountRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/accounts", s.accountList)
	m.HandleFunc("GET /admin/accounts/new", s.accountNew)
	m.HandleFunc("GET /admin/accounts/{id}/edit", s.accountEdit)
	m.HandleFunc("POST /admin/accounts", s.accountSave)
	m.HandleFunc("POST /admin/accounts/{id}", s.accountSave)
	m.HandleFunc("POST /admin/accounts/{id}/toggle", s.accountToggle)
	m.HandleFunc("POST /admin/accounts/{id}/delete", s.accountDelete)
	m.HandleFunc("POST /admin/accounts/{id}/auth-code", s.accountResetCode)
	m.HandleFunc("POST /admin/accounts/{id}/renew", s.accountRenew)
	m.HandleFunc("GET /admin/accounts/{id}/usage", s.accountUsage)
	m.HandleFunc("GET /admin/accounts/{id}/bindings", s.bindingPage)
	m.HandleFunc("POST /admin/accounts/{id}/bindings", s.bindingSave)
	m.HandleFunc("POST /admin/accounts/{id}/bindings/{binding}/delete", s.bindingDelete)
}
func idFrom(r *http.Request) int64 { id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64); return id }
func (s *Server) accountList(w http.ResponseWriter, r *http.Request) {
	page, size := pageParams(r)
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	status := r.URL.Query().Get("status")
	sort, asc := accountSort(r.URL.Query().Get("sort"), r.URL.Query().Get("dir"))
	onlineFilter := r.URL.Query().Get("online")
	userID, _ := strconv.ParseInt(r.URL.Query().Get("user_id"), 10, 64)
	if userID < 0 {
		userID = 0
	}
	list, total, err := store.AccountList(r.Context(), s.DB, store.AccountFilter{Search: q, Status: status, Online: onlineFilter, UserID: userID, Sort: sort, Asc: asc}, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	snap, err := s.onlineSnapshot(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	now := time.Now()
	online := make(map[int64]string, len(list))
	lastSeen := make(map[int64]string, len(list))
	for _, a := range list {
		online[a.ID] = accountOnlineText(snap, a.AccountNo)
		if !strings.HasPrefix(online[a.ID], "在线") {
			lastSeen[a.ID] = lastOnlineText(a.LastOnline, now)
		}
	}
	d := s.pageData(r, "账户", "accounts")
	d["OnlineWarning"], err = s.onlineCoverageWarning(r.Context(), snap)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d["Online"], d["LastSeen"], d["Sort"], d["SortAsc"] = online, lastSeen, sort, asc
	d["OnlineFilter"], d["UserFilter"] = onlineFilter, userID
	d["SortLast"], d["SortTo"] = sortHeader(r.URL.Query(), "last_online", sort, asc), sortHeader(r.URL.Query(), "to_date", sort, asc)
	d["List"], d["Q"], d["Status"], d["Page"], d["Pages"] = list, q, status, page, (total+size-1)/size
	d["Now"] = store.FormatTime(now)
	d["DocsURL"] = store.ConfigValue(r.Context(), s.DB, "airopscat.docs.url")
	s.render(w, "accounts", 200, d)
}

// accountSort 只接受白名单列；其他值回到默认排序（创建时间倒序）。
func accountSort(sort, dir string) (string, bool) {
	if sort != "last_online" && sort != "to_date" {
		return "", false
	}
	return sort, dir == "asc"
}

// sortHeader 生成表头链接：未选中 → 降序 → 升序 → 恢复默认；保留筛选条件，页码回到第一页。
func sortHeader(q url.Values, col, cur string, asc bool) map[string]string {
	next := url.Values{}
	for k, v := range q {
		if k != "page" && k != "sort" && k != "dir" && k != "msg" && k != "err" {
			next[k] = v
		}
	}
	state, icon := "none", "ti-arrows-sort"
	switch {
	case cur != col:
		next.Set("sort", col)
		next.Set("dir", "desc")
	case !asc:
		state, icon = "descending", "ti-arrow-down"
		next.Set("sort", col)
		next.Set("dir", "asc")
	default:
		state, icon = "ascending", "ti-arrow-up"
	}
	return map[string]string{"URL": "?" + next.Encode(), "Aria": state, "Icon": icon}
}
func (s *Server) accountFormPage(w http.ResponseWriter, r *http.Request, status int, f *accountForm, msg string) {
	users, _, err := store.AccountUserOptions(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	tags, err := store.AllTags(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	title := "开户"
	if f.ID != 0 {
		title = "编辑账户"
	}
	d := s.pageData(r, title, "accounts")
	d["Form"], d["Users"], d["Tags"], d["FormErr"] = f, users, tags, msg
	if f.ID != 0 {
		recent, err := store.AccountRecentNodes(r.Context(), s.DB, f.ID, time.Now().AddDate(0, 0, -30), 100)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		d["Recent"] = recent
		if len(recent) > 0 {
			where := recent[0].NodeName
			if where == "" {
				where = recent[0].NodeIP
			}
			d["LastSeen"] = lastOnlineText(&recent[0].LastOnline, time.Now()) + "（" + store.FormatTime(recent[0].LastOnline) + " · " + where + "）"
		} else {
			d["LastSeen"] = lastOnlineText(nil, time.Now())
		}
		if f.AccountNo != "" {
			snap, err := s.onlineSnapshot(r.Context())
			if err == nil {
				d["OnlineRows"], err = s.accountOnlineRows(r.Context(), snap, f.AccountNo)
				d["OnlineText"] = accountOnlineText(snap, f.AccountNo)
				if err == nil {
					d["OnlineWarning"], err = s.onlineCoverageWarning(r.Context(), snap)
				}
			}
			if err != nil {
				s.fail(w, r, err)
				return
			}
		}
		if acc, err := store.AccountByID(r.Context(), s.DB, f.ID); err == nil && acc != nil {
			d["Links"] = s.accountLinks(r, acc)
		}
	}
	s.render(w, "account_form", status, d)
}
func (s *Server) accountNew(w http.ResponseWriter, r *http.Request) {
	now := time.Now().In(store.Shanghai)
	s.accountFormPage(w, r, 200, &accountForm{UserSource: "existing", PeriodType: "MONTHLY", NodeMultiple: "2", NodePrefix: "8", MaxConnections: "100", MaxIPs: "2", Bandwidth: "500", DownloadMbps: "12", UploadMbps: "8", FromDate: now.Format("2006-01-02T15:04:05"), ToDate: now.AddDate(0, 1, 0).Format("2006-01-02T15:04:05"), TagIDs: map[int64]bool{}}, "")
}
func accountToForm(a *store.Account) *accountForm {
	f := &accountForm{ID: a.ID, UserID: strconv.FormatInt(a.UserID, 10), UserSource: "existing", Remark: a.Remark, AccountNo: a.AccountNo, UUID: a.UUID, PeriodType: a.PeriodType, NodePrefix: a.NodePrefix, NodeMultiple: strconv.Itoa(a.NodeMultiple), MaxConnections: strconv.Itoa(a.MaxConnections), MaxIPs: strconv.Itoa(a.MaxIps), Bandwidth: strconv.Itoa(a.Bandwidth), Disabled: a.Disabled, TagIDs: map[int64]bool{}}
	if a.FromDate != nil {
		f.FromDate = a.FromDate.Format("2006-01-02T15:04:05")
	}
	if a.ToDate != nil {
		f.ToDate = a.ToDate.Format("2006-01-02T15:04:05")
	}
	if a.DownloadMbps > 0 {
		f.DownloadMbps = strconv.Itoa(a.DownloadMbps)
	}
	if a.UploadMbps > 0 {
		f.UploadMbps = strconv.Itoa(a.UploadMbps)
	}
	for _, id := range a.TagIDs {
		f.TagIDs[id] = true
	}
	return f
}
func (s *Server) accountEdit(w http.ResponseWriter, r *http.Request) {
	a, err := store.AccountByID(r.Context(), s.DB, idFrom(r))
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if a == nil {
		http.NotFound(w, r)
		return
	}
	s.accountFormPage(w, r, 200, accountToForm(a), "")
}
func sqlDate(v string) (string, error) {
	if v == "" {
		return "", nil
	}
	for _, layout := range []string{"2006-01-02T15:04:05", "2006-01-02T15:04"} {
		if t, err := time.ParseInLocation(layout, v, store.Shanghai); err == nil {
			return t.Format(store.TimeLayout), nil
		}
	}
	return "", fmt.Errorf("日期时间格式不正确")
}
func parseAmount(v string) (*int64, error) {
	if strings.TrimSpace(v) == "" {
		return nil, nil
	}
	n, err := store.ParseCents(v)
	if err != nil || n <= 0 {
		return nil, fmt.Errorf("金额必须大于 0，最多两位小数")
	}
	return &n, nil
}
func (s *Server) accountSave(w http.ResponseWriter, r *http.Request) {
	f := &accountForm{ID: idFrom(r), UserID: formValue(r, "user_id"), UserSource: formValue(r, "user_source"), Email: formValue(r, "email"), Password: r.PostFormValue("new_password"), NickName: formValue(r, "nick_name"), Remark: formValue(r, "remark"), AccountNo: formValue(r, "account_no"), UUID: formValue(r, "uuid"), FromDate: formValue(r, "from_date"), ToDate: formValue(r, "to_date"), PeriodType: formValue(r, "period_type"), NodePrefix: formValue(r, "node_prefix"), NodeMultiple: formValue(r, "node_multiple"), MaxConnections: formValue(r, "max_connections"), MaxIPs: formValue(r, "max_ips"), Bandwidth: formValue(r, "bandwidth"), DownloadMbps: formValue(r, "download_mbps"), UploadMbps: formValue(r, "upload_mbps"), Amount: formValue(r, "amount"), PaymentMethod: formValue(r, "payment_method"), Disabled: r.PostFormValue("disabled") == "1", TagIDs: map[int64]bool{}}
	in := &store.Account{TagIDs: []int64{}}
	in.ID = f.ID
	in.UserID, _ = strconv.ParseInt(f.UserID, 10, 64)
	in.Remark = f.Remark
	in.AccountNo = f.AccountNo
	in.UUID = f.UUID
	in.PeriodType = f.PeriodType
	in.NodePrefix = f.NodePrefix
	in.Disabled = f.Disabled
	var parseErr error
	for _, v := range []struct {
		text string
		dst  *int
	}{{f.NodeMultiple, &in.NodeMultiple}, {f.MaxConnections, &in.MaxConnections}, {f.MaxIPs, &in.MaxIps}, {f.Bandwidth, &in.Bandwidth}} {
		n, err := strconv.Atoi(v.text)
		if err != nil {
			parseErr = fmt.Errorf("节点倍数、连接数、IP 数和配额需为整数")
		}
		*v.dst = n
	}
	for _, v := range []struct {
		text string
		dst  *int
	}{{f.DownloadMbps, &in.DownloadMbps}, {f.UploadMbps, &in.UploadMbps}} {
		if v.text != "" {
			n, err := strconv.Atoi(v.text)
			if err != nil {
				parseErr = fmt.Errorf("限速需为正整数")
			}
			*v.dst = n
		}
	}
	var err error
	if f.FromDate != "" {
		v, e := sqlDate(f.FromDate)
		if e != nil {
			parseErr = e
		} else {
			t, _ := store.ParseTime(v)
			in.FromDate = &t
		}
	}
	if f.ID == 0 && f.ToDate == "" {
		start := time.Now().In(store.Shanghai)
		if in.FromDate != nil {
			start = *in.FromDate
		}
		f.ToDate = start.AddDate(0, 1, 0).Format("2006-01-02T15:04:05")
	}
	if f.ToDate != "" {
		v, e := sqlDate(f.ToDate)
		if e != nil {
			parseErr = e
		} else {
			t, _ := store.ParseTime(v)
			in.ToDate = &t
		}
	}
	amount, err := parseAmount(f.Amount)
	if err != nil {
		parseErr = err
	}
	onboard := store.OnboardInput{UserID: in.UserID, AmountCents: amount, PaymentMethod: f.PaymentMethod}
	if f.ID == 0 && f.UserSource == "new" {
		in.UserID = 0
		onboard.UserID = 0
		onboard.NewUser = &store.ManagedUser{Email: f.Email, NickName: f.NickName, Role: "VIP"}
		onboard.Password = f.Password
	}
	for _, raw := range r.PostForm["tags"] {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil {
			parseErr = fmt.Errorf("标签不正确")
			continue
		}
		in.TagIDs = append(in.TagIDs, id)
		f.TagIDs[id] = true
	}
	if strings.TrimSpace(in.Remark) == "" {
		parseErr = fmt.Errorf("账户备注不能为空")
	}
	if parseErr != nil {
		s.accountFormPage(w, r, 422, f, parseErr.Error())
		return
	}
	if parseErr == nil {
		if f.ID == 0 {
			onboard.Account = *in
			_, parseErr = store.Onboard(r.Context(), s.DB, onboard)
		} else {
			parseErr = store.UpdateAccount(r.Context(), s.DB, f.ID, in)
		}
	}
	if parseErr != nil {
		if errors.Is(parseErr, store.ErrInvalid) || errors.Is(parseErr, store.ErrConflict) {
			s.accountFormPage(w, r, 422, f, parseErr.Error())
			return
		}
		s.fail(w, r, parseErr)
		return
	}
	redirectMsg(w, r, "/admin/accounts", "msg", "账户已保存，相关部署位置为待部署")
}
func (s *Server) accountActionResult(w http.ResponseWriter, r *http.Request, err error, msg string) {
	if err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, "/admin/accounts", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/accounts", "msg", msg)
}
func (s *Server) accountToggle(w http.ResponseWriter, r *http.Request) {
	s.accountActionResult(w, r, store.SetAccountDisabled(r.Context(), s.DB, idFrom(r), r.PostFormValue("disabled") == "1"), "账户状态已更新，相关节点需重新部署")
}
func (s *Server) accountDelete(w http.ResponseWriter, r *http.Request) {
	s.accountActionResult(w, r, store.DeleteAccount(r.Context(), s.DB, idFrom(r)), "账户已删除，流水已保留，相关节点需重新部署")
}
func (s *Server) accountResetCode(w http.ResponseWriter, r *http.Request) {
	s.accountActionResult(w, r, resetAccountCode(r, s), "订阅授权码已重置，旧订阅链接立即失效")
}
func (s *Server) accountRenew(w http.ResponseWriter, r *http.Request) {
	to, err := sqlDate(formValue(r, "to_date"))
	amount, e := parseAmount(formValue(r, "amount"))
	if err == nil {
		err = e
	}
	if err == nil {
		err = renewAccount(r, s, to, amount)
	}
	s.accountActionResult(w, r, err, "续期已保存，相关节点需重新部署")
}

func resetAccountCode(r *http.Request, s *Server) error {
	_, err := store.ResetAuthCode(r.Context(), s.DB, idFrom(r))
	return err
}
func renewAccount(r *http.Request, s *Server, to string, amount *int64) error {
	t, err := store.ParseTime(to)
	if err != nil {
		return fmt.Errorf("%w：请填写有效的新到期时间", store.ErrInvalid)
	}
	return store.RenewAccount(r.Context(), s.DB, idFrom(r), t, amount, formValue(r, "payment_method"))
}

type copyLink struct{ Label, URL string }

// installText 是发给客户的安装说明：账户备注、到期日与安装链接。
func installText(docs string, a store.Account) string {
	expire := "长期有效"
	if a.ToDate != nil {
		expire = a.ToDate.Format("2006-01-02")
	}
	return fmt.Sprintf("账户：%s\n到期日：%s\n点击打开安装手册：%s/?code=%s", a.Remark, expire, docs, a.AuthCode)
}

// accountLinks 生成给客户的链接：安装/使用手册（文档站）与各平台订阅地址（与 docs-info 一致）。
func (s *Server) accountLinks(r *http.Request, a *store.Account) []copyLink {
	var out []copyLink
	if docs := store.ConfigValue(r.Context(), s.DB, "airopscat.docs.url"); docs != "" {
		out = append(out,
			copyLink{"安装手册", docs + "/?code=" + a.AuthCode},
			copyLink{"使用手册", docs + "/manual.html?code=" + a.AuthCode})
	}
	if base := store.ConfigValue(r.Context(), s.DB, "airopscat.subscription.url"); base != "" {
		for _, p := range [][2]string{{"Windows", "windows/clash-verge"}, {"macOS", "macos/clash-verge"}, {"Linux", "linux/clash-verge"}, {"Android", "android/clash-meta"}, {"iOS", "ios/shadowrocket/" + url.QueryEscape(a.Remark)}} {
			out = append(out, copyLink{p[0] + " 订阅", base + "/config/" + a.AuthCode + "/" + p[1] + "/"})
		}
	}
	return out
}

// accountUsage 沿用订阅的周期与配额快照口径，点击时读取即时用量。
func (s *Server) accountUsage(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	u, err := store.AccountUsage(r.Context(), s.DB, id, time.Now())
	if err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
		} else {
			s.fail(w, r, err)
		}
		return
	}
	percent := "不限量"
	if u.QuotaGB > 0 {
		percent = fmt.Sprintf("%.1f%%", float64(u.UsedBytes)/float64(u.QuotaGB)/(1<<30)*100)
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(map[string]string{"percent": percent, "used": fmt.Sprintf("%.2f GB", float64(u.UsedBytes)/(1<<30)), "start": u.PeriodStart.Format("2006-01-02"), "end": u.PeriodEnd.Format("2006-01-02")})
}
