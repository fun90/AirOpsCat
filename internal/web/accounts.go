package web

import (
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
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
	online := make(map[int64]accountOnlineView, len(list))
	lastSeen := make(map[int64]string, len(list))
	view := make(map[int64]accountView, len(list))
	for _, a := range list {
		view[a.ID] = accountRowView(a, now)
		online[a.ID] = accountOnline(snap, a.AccountNo)
		if !online[a.ID].Online {
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
	d["OnlineFilter"], d["UserFilter"], d["View"] = onlineFilter, userID, view
	if userID > 0 {
		clear := r.URL.Query()
		for _, k := range []string{"user_id", "page", "msg", "err"} {
			clear.Del(k)
		}
		d["ClearUserURL"] = "?" + clear.Encode()
	}
	d["SortLast"], d["SortTo"], d["SortUsed"] = sortHeader(r.URL.Query(), "last_online", sort, asc), sortHeader(r.URL.Query(), "to_date", sort, asc), sortHeader(r.URL.Query(), "used", sort, asc)
	d["List"], d["Q"], d["Status"], d["Page"], d["Pages"] = list, q, status, page, (total+size-1)/size
	d["Now"] = store.FormatTime(now)
	d["DocsURL"] = store.ConfigValue(r.Context(), s.DB, "airopscat.docs.url")
	s.render(w, "accounts", 200, d)
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
	tagNodes, err := store.TagNodeIDs(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	// 前端按所选标签取并集，提示账户可用节点数。
	tagNodeText := make(map[int64]string, len(tagNodes))
	for id, nodes := range tagNodes {
		ids := make([]string, len(nodes))
		for i, n := range nodes {
			ids[i] = strconv.FormatInt(n, 10)
		}
		tagNodeText[id] = strings.Join(ids, " ")
	}
	title := "开户"
	if f.ID != 0 {
		title = "编辑账户"
	}
	d := s.pageData(r, title, "accounts")
	d["Form"], d["Users"], d["Tags"], d["TagNodes"], d["FormErr"] = f, users, tags, tagNodeText, msg
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
			u, err := store.AccountUsage(r.Context(), s.DB, acc.ID, time.Now())
			if err != nil {
				s.fail(w, r, err)
				return
			}
			d["Account"], d["Usage"] = acc, u
			hist, err := store.AccountTrafficHistory(r.Context(), s.DB, acc.ID)
			if err != nil {
				s.fail(w, r, err)
				return
			}
			type historyRow struct {
				store.TrafficPeriod
				Current bool
				View    accountView
			}
			rows := make([]historyRow, len(hist))
			for i, p := range hist {
				rows[i] = historyRow{p, p.PeriodStart.Equal(u.PeriodStart), accountRowView(&store.AccountRow{UsedBytes: p.UploadBytes + p.DownloadBytes, QuotaGB: int(p.QuotaGB)}, time.Now())}
			}
			d["History"] = rows
			d["View"] = accountRowView(&store.AccountRow{Account: *acc, UsedBytes: u.UsedBytes, QuotaGB: int(u.QuotaGB)}, time.Now())
			if docs := store.ConfigValue(r.Context(), s.DB, "airopscat.docs.url"); docs != "" {
				d["Install"] = installText(docs, *acc)
			}
		}
	}
	s.render(w, "account_form", status, d)
}
func (s *Server) accountNew(w http.ResponseWriter, r *http.Request) {
	now := time.Now().In(store.Shanghai)
	// 复制为新账户：沿用用户、标签、周期与限额，编号/UUID 重新生成，日期与备注重填
	if from, _ := strconv.ParseInt(r.URL.Query().Get("from"), 10, 64); from > 0 {
		a, err := store.AccountByID(r.Context(), s.DB, from)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if a != nil {
			f := accountToForm(a)
			f.ID, f.Remark, f.AccountNo, f.UUID, f.Disabled = 0, "", "", "", false
			f.FromDate, f.ToDate = now.Format("2006-01-02T15:04:05"), now.AddDate(0, 1, 0).Format("2006-01-02T15:04:05")
			s.accountFormPage(w, r, 200, f, "")
			return
		}
	}
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
	date := func(text string) *time.Time {
		v, e := sqlDate(text)
		if e != nil {
			parseErr = e
			return nil
		}
		t, _ := store.ParseTime(v)
		return &t
	}
	if f.FromDate != "" {
		in.FromDate = date(f.FromDate)
	}
	if f.ID == 0 && f.ToDate == "" {
		start := time.Now().In(store.Shanghai)
		if in.FromDate != nil {
			start = *in.FromDate
		}
		f.ToDate = start.AddDate(0, 1, 0).Format("2006-01-02T15:04:05")
	}
	if f.ToDate != "" {
		in.ToDate = date(f.ToDate)
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
	deploy := r.PostFormValue("deploy") == "1"
	var before []int64 // 保存前的服务器：换标签后旧标签节点也要下发移除
	if f.ID == 0 {
		onboard.Account = *in
		var res *store.OnboardResult
		if res, parseErr = store.Onboard(r.Context(), s.DB, onboard); parseErr == nil {
			back := fmt.Sprintf("/admin/accounts/%d/edit", res.AccountID)
			if deploy {
				s.deployAccountPending(w, r, back, res.AccountID, nil, "开户成功，该账户没有可用节点，无需部署")
				return
			}
			redirectMsg(w, r, back, "msg", "开户成功：复制安装说明发给客户，点「部署」下发到节点")
			return
		}
	} else if before, parseErr = store.AccountDeployServers(r.Context(), s.DB, f.ID); parseErr == nil {
		parseErr = store.UpdateAccount(r.Context(), s.DB, f.ID, in)
	}
	if parseErr != nil {
		if errors.Is(parseErr, store.ErrInvalid) || errors.Is(parseErr, store.ErrConflict) {
			s.accountFormPage(w, r, 422, f, parseErr.Error())
			return
		}
		s.fail(w, r, parseErr)
		return
	}
	if deploy {
		s.deployAccountPending(w, r, "/admin/accounts", f.ID, before, "账户已保存，无需部署")
		return
	}
	ids, err := s.accountPendingServers(r, f.ID, before)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if len(ids) == 0 {
		redirectMsg(w, r, "/admin/accounts", "msg", "账户已保存，无需部署")
		return
	}
	redirectMsg(w, r, "/admin/accounts", "err", fmt.Sprintf("账户已保存，%d 台服务器待部署，尚未生效，请点「部署」下发", len(ids)))
}

// accountPendingServers 返回账户保存前后涉及、且有待部署/待删除部署位的服务器。
func (s *Server) accountPendingServers(r *http.Request, accountID int64, before []int64) ([]int64, error) {
	after, err := store.AccountDeployServers(r.Context(), s.DB, accountID)
	if err != nil {
		return nil, err
	}
	pending, err := store.PendingDeployServers(r.Context(), s.DB)
	if err != nil {
		return nil, err
	}
	related := map[int64]bool{}
	for _, id := range append(before, after...) {
		related[id] = true
	}
	var ids []int64
	for _, id := range pending {
		if related[id] {
			ids = append(ids, id)
		}
	}
	return ids, nil
}

func (s *Server) deployAccountPending(w http.ResponseWriter, r *http.Request, back string, accountID int64, before []int64, none string) {
	ids, err := s.accountPendingServers(r, accountID, before)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	s.runDeploy(w, r, back, ids, none)
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
	s.accountActionResult(w, r, err, "续期已保存")
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
