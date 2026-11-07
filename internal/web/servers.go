package web

import (
	"context"
	"errors"
	"fmt"
	"math"
	"net/http"
	"net/url"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

// ServerForm 保留用户输入的原始文本，校验失败时原样回显。
type ServerForm struct {
	ID                                                    int64
	Name, IP, SSHPort, AuthType, Username, Region         string
	ServerType, ExpireDate, BandwidthDay, Supplier, Price string
	PriceMonths, Multiple, Bandwidth, CPUCores, Remark    string
	SupplierURL                                           string
	BandwidthUnit                                         string
	Disabled, External, HasAuth                           bool
}

func formFromServer(s *store.Server) *ServerForm {
	f := &ServerForm{ID: s.ID, Name: s.Name, IP: s.IP, SSHPort: strconv.Itoa(s.SSHPort), AuthType: s.AuthType, Username: s.Username,
		Region: s.Region, ServerType: strconv.Itoa(s.ServerType), ExpireDate: s.ExpireDate, BandwidthDay: s.BandwidthDay,
		Supplier: s.Supplier, SupplierURL: s.SupplierURL, PriceMonths: strconv.Itoa(s.PriceMonths), Multiple: strconv.FormatFloat(s.Multiple, 'f', -1, 64), Remark: s.Remark,
		Disabled: s.Disabled, External: s.External, HasAuth: s.HasAuth, BandwidthUnit: store.BandwidthUnitOrDefault(s.BandwidthUnit)}
	if s.Price != nil {
		f.Price = store.FormatCents(*s.Price)
	}
	if s.Bandwidth != nil {
		f.Bandwidth = strconv.Itoa(*s.Bandwidth)
	}
	if s.CPUCores != nil {
		f.CPUCores = strconv.Itoa(*s.CPUCores)
	}
	return f
}

func defaultServerForm() *ServerForm {
	return &ServerForm{SSHPort: "22", AuthType: "PASSWORD", Username: "root", ServerType: "0", PriceMonths: "12", Multiple: "1", BandwidthUnit: store.UnitGiB}
}

// parseServerForm 校验表单，返回待保存的服务器和字段错误。
func parseServerForm(r *http.Request) (*ServerForm, *store.Server, map[string]string) {
	f := &ServerForm{Name: formValue(r, "name"), IP: formValue(r, "ip"), SSHPort: formValue(r, "ssh_port"), AuthType: formValue(r, "auth_type"),
		Username: formValue(r, "username"), Region: formValue(r, "region"), ServerType: formValue(r, "server_type"),
		ExpireDate: formValue(r, "expire_date"), BandwidthDay: formValue(r, "bandwidth_day"), Supplier: formValue(r, "supplier"), SupplierURL: formValue(r, "supplier_url"),
		Price: formValue(r, "price"), PriceMonths: formValue(r, "price_months"), Multiple: formValue(r, "multiple"), Bandwidth: formValue(r, "bandwidth"), CPUCores: formValue(r, "cpu_cores"), BandwidthUnit: formValue(r, "bandwidth_unit"),
		Remark:   formValue(r, "remark"),
		Disabled: r.PostFormValue("disabled") == "1", External: r.PostFormValue("external_server") == "1"}
	errs := map[string]string{}
	s := &store.Server{Name: f.Name, IP: f.IP, Username: f.Username, Region: f.Region, ExpireDate: f.ExpireDate,
		BandwidthDay: f.BandwidthDay, Supplier: f.Supplier, SupplierURL: f.SupplierURL, Disabled: f.Disabled, External: f.External, Remark: f.Remark}
	if f.SupplierURL != "" {
		u, err := url.Parse(f.SupplierURL)
		if err != nil || (u.Scheme != "http" && u.Scheme != "https") || u.Hostname() == "" || u.User != nil || len(f.SupplierURL) > 2048 {
			errs["supplier_url"] = "请填写有效的 HTTP 或 HTTPS 网址，不能包含账号密码"
		}
	}
	if f.BandwidthUnit == "" {
		f.BandwidthUnit = store.UnitGiB
	}
	if f.BandwidthUnit != store.UnitGB && f.BandwidthUnit != store.UnitGiB {
		errs["bandwidth_unit"] = "流量单位不正确"
	}
	s.BandwidthUnit = f.BandwidthUnit
	if f.IP == "" {
		errs["ip"] = "请填写 IP"
	}
	if s.SSHPort, _ = strconv.Atoi(f.SSHPort); s.SSHPort < 1 || s.SSHPort > 65535 {
		errs["ssh_port"] = "端口范围 1-65535"
	}
	if f.AuthType != "PASSWORD" && f.AuthType != "KEY" {
		errs["auth_type"] = "认证方式不正确"
	}
	s.AuthType = f.AuthType
	if f.ServerType != "0" && f.ServerType != "1" {
		errs["server_type"] = "类型不正确"
	}
	s.ServerType, _ = strconv.Atoi(f.ServerType)
	if f.PriceMonths != "1" && f.PriceMonths != "12" {
		errs["price_months"] = "计价周期不正确"
	}
	s.PriceMonths, _ = strconv.Atoi(f.PriceMonths)
	var err error
	if s.Multiple, err = strconv.ParseFloat(f.Multiple, 64); err != nil || s.Multiple <= 0 {
		errs["multiple"] = "倍率必须大于 0"
	}
	if f.ExpireDate != "" {
		if _, err := time.Parse("2006-01-02", f.ExpireDate); err != nil {
			errs["expire_date"] = "日期格式应为 YYYY-MM-DD"
		}
	}
	if f.BandwidthDay != "" {
		if d, err := strconv.Atoi(f.BandwidthDay); err != nil || d < 1 || d > 31 {
			errs["bandwidth_day"] = "请填写 1-31"
		}
	}
	if f.Price != "" {
		c, err := store.ParseCents(f.Price)
		if err != nil || c < 0 {
			errs["price"] = "价格格式不正确"
		}
		s.Price = &c
	}
	for _, n := range []struct {
		key, val string
		dst      **int
	}{{"bandwidth", f.Bandwidth, &s.Bandwidth}, {"cpu_cores", f.CPUCores, &s.CPUCores}} {
		if n.val != "" {
			v, err := strconv.Atoi(n.val)
			if err != nil || v < 0 {
				errs[n.key] = "请填写非负整数"
			}
			*n.dst = &v
		}
	}
	return f, s, errs
}

func (s *Server) serverRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/servers", s.serverList)
	m.HandleFunc("GET /admin/servers/new", func(w http.ResponseWriter, r *http.Request) {
		s.serverFormPage(w, r, http.StatusOK, "新增服务器", defaultServerForm(), map[string]string{})
	})
	m.HandleFunc("GET /admin/servers/idle", s.serverIdle)
	m.HandleFunc("GET /admin/servers/{id}/edit", s.serverEdit)
	m.HandleFunc("POST /admin/servers", s.serverSave)
	m.HandleFunc("POST /admin/servers/{id}", s.serverSave)
	m.HandleFunc("POST /admin/servers/{id}/delete", s.serverDelete)
	m.HandleFunc("POST /admin/servers/{id}/failover", s.serverFailover)
	m.HandleFunc("POST /admin/servers/{id}/placement-switch", s.serverPlacementSwitch)
	m.HandleFunc("POST /admin/servers/{id}/retire", s.serverRetire)
	m.HandleFunc("POST /admin/servers/{id}/migrate", s.serverMigrate)
	m.HandleFunc("POST /admin/servers/{id}/migrate-finish", s.serverMigrateFinish)
	m.HandleFunc("POST /admin/servers/{id}/migrate-cancel", s.serverMigrateCancel)
	m.HandleFunc("POST /admin/servers/{id}/unblock-port", s.serverUnblockPort)
}

// serverPlacementSwitch 把部署位所属节点的域名切到本服务器的该部署位，随后立即执行 DNS 切换。
func (s *Server) serverPlacementSwitch(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	back := "/admin/servers/" + strconv.FormatInt(id, 10) + "/edit"
	pid, err := strconv.ParseInt(r.PostFormValue("placement"), 10, 64)
	if err != nil || pid <= 0 {
		http.Error(w, "部署位 ID 不正确", http.StatusBadRequest)
		return
	}
	pls, err := store.ServerPlacements(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	for _, p := range pls {
		if p.ID == pid {
			if !s.redirectError(w, r, back, store.SwitchNodePlacement(r.Context(), s.DB, p.NodeID, pid), store.ErrInvalid) {
				s.failoverDNS(w, r, back, p.NodeName+" 已切到本服务器", true)
			}
			return
		}
	}
	redirectMsg(w, r, back, "err", "部署位不属于该服务器")
}

// serverFailover 标记后立即执行一轮 DNS 切换，不等定时任务；切走与回切都复用自动切换的对账流程。
func (s *Server) serverFailover(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	on := r.PostFormValue("on") == "1"
	if s.requestError(w, r, store.SetServerFailover(r.Context(), s.DB, id, on)) {
		return
	}
	action := "恢复"
	if on {
		action = "切走"
	}
	// 推送中途断开会留下待对账操作，因此不随浏览器请求取消。
	if err := s.autoDNS(context.WithoutCancel(r.Context())); err != nil {
		redirectMsg(w, r, "/admin/servers", "err", "已标记"+action+"，但 DNS 切换未全部完成（定时任务会继续重试）："+err.Error())
		return
	}
	redirectMsg(w, r, "/admin/servers", "msg", "已"+action+"并执行 DNS 切换，结果见系统日志")
}

// serverRow 是列表行：到期与流量的展示文字在这里算好。
type serverRow struct {
	*store.Server
	ExpireClass, ExpireNote string
	ExpireShort             string
	UsageText               string
	UsagePct                int
	UsageHigh               bool
	StatusDot, StatusLabel  string // 状态点颜色与简短文字；详情放进 StatusTitle 悬浮提示
	StatusTitle             string
	FailoverWarn            string // 切走确认框追加的提示：订阅里直连本服务器 IP 的节点
}

func newServerRow(sv *store.Server, today time.Time) serverRow {
	r := serverRow{Server: sv, StatusDot: "status-green", StatusLabel: "正常"}
	var detail []string
	if sv.Failover > 0 {
		detail = append(detail, fmt.Sprintf("已切走 %d/%d 个部署位", sv.Failover, sv.Placements))
	}
	if sv.Unreachable > 0 {
		detail = append(detail, fmt.Sprintf("%d 个部署位不可达", sv.Unreachable))
	}
	if sv.LandingUnreachable > 0 {
		detail = append(detail, fmt.Sprintf("%d 个落地不可达", sv.LandingUnreachable))
	}
	switch {
	case sv.Disabled:
		r.StatusDot, r.StatusLabel = "status-secondary", "已禁用"
	case sv.Unreachable > 0 || sv.LandingUnreachable > 0:
		r.StatusDot, r.StatusLabel = "status-red", "不可达"
	case sv.Failover > 0:
		r.StatusDot, r.StatusLabel = "status-yellow", "已切走"
	}
	r.StatusTitle = strings.Join(detail, "；")
	r.ExpireShort = sv.ExpireDate
	if len(sv.ExpireDate) >= 10 {
		if exp, err := time.ParseInLocation("2006-01-02", sv.ExpireDate[:10], store.Shanghai); err == nil {
			if exp.Year() == today.Year() {
				r.ExpireShort = exp.Format("01-02")
			}
			days := int(exp.Sub(today).Hours() / 24)
			switch {
			case days < 0:
				r.ExpireClass, r.ExpireNote = "text-danger", "已过期 "+strconv.Itoa(-days)+" 天"
			case days <= 7:
				r.ExpireClass, r.ExpireNote = "text-warning", "剩 "+strconv.Itoa(days)+" 天"
			case days <= 30:
				r.ExpireNote = "剩 " + strconv.Itoa(days) + " 天"
			}
		}
	}
	if sv.HasUsage {
		unit := store.BandwidthUnitOrDefault(sv.BandwidthUnit)
		used := float64(sv.UsedBytes) / store.UnitBytes(unit)
		if sv.Bandwidth != nil && *sv.Bandwidth > 0 {
			ratio := float64(sv.UsedBytes) / (float64(*sv.Bandwidth) * store.UnitBytes(unit))
			r.UsageText = serverUsageText(used, float64(*sv.Bandwidth), unit)
			r.UsagePct, r.UsageHigh = min(int(ratio*100), 100), ratio >= 0.85
		} else {
			r.UsageText = serverUsageText(used, 0, unit)
		}
	}
	return r
}

// serverUsageText 返回「已用 / 额度 单位」；无额度时为「已用 单位」。unit 为 GB 或 GiB，
// 额度（无额度时用已用值）达到 1 T（GB 为 1000，GiB 为 1024）时分子分母一起换成 TB/TiB，最多两位小数。
func serverUsageText(used float64, quota float64, unit string) string {
	base, tUnit := 1024.0, "TiB"
	if unit == store.UnitGB {
		base, tUnit = 1000, "TB"
	}
	ref := quota
	if ref <= 0 {
		ref = used
	}
	trim := func(v float64) string { return strconv.FormatFloat(math.Round(v*100)/100, 'f', -1, 64) }
	if ref >= base {
		used, quota, unit = used/base, quota/base, tUnit
		if quota > 0 {
			return trim(used) + " / " + trim(quota) + " " + unit
		}
		return trim(used) + " " + unit
	}
	if quota > 0 {
		return fmt.Sprintf("%.1f / %s %s", used, trim(quota), unit)
	}
	return fmt.Sprintf("%.1f %s", used, unit)
}

func (s *Server) serverList(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	filter := r.URL.Query().Get("filter")
	if filter != "external" && filter != "expiring" && filter != "abnormal" {
		filter = ""
	}
	sort, asc := r.URL.Query().Get("sort"), r.URL.Query().Get("dir") == "asc"
	if sort != "expire" && sort != "placements" {
		sort = ""
	}
	now := time.Now()
	list, total, err := store.ServerList(r.Context(), s.DB, q, filter, sort, asc, now, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	local := now.In(store.Shanghai)
	today := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, store.Shanghai)
	direct, err := s.loadIPOnly(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	rows := make([]serverRow, len(list))
	for i, sv := range list {
		rows[i] = newServerRow(sv, today)
		rows[i].FailoverWarn = direct.serverWarn(sv.ID)
	}
	d := s.pageData(r, "服务器", "servers")
	d["List"], d["Total"], d["Q"], d["Page"], d["Pages"] = rows, total, q, page, (total+size-1)/size
	d["Filter"], d["Sort"] = filter, sort
	d["Dir"] = "desc"
	if asc {
		d["Dir"] = "asc"
	}
	d["SortExpire"], d["SortPlacements"] = sortHeader(r.URL.Query(), "expire", sort, asc), sortHeader(r.URL.Query(), "placements", sort, asc)
	s.render(w, "servers", http.StatusOK, d)
}

func (s *Server) serverIdle(w http.ResponseWriter, r *http.Request) {
	now := time.Now().In(store.Shanghai)
	list, err := store.IdleServers(r.Context(), s.DB, now)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "下线建议", "servers")
	d["List"], d["Today"], d["Soon"] = list, now.Format("2006-01-02"), now.AddDate(0, 0, 15).Format("2006-01-02")
	s.render(w, "server_idle", http.StatusOK, d)
}

func (s *Server) serverEdit(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	srv, err := store.ServerByID(r.Context(), s.DB, id)
	if !s.recordFound(w, r, srv != nil, err) {
		return
	}
	s.serverFormPage(w, r, http.StatusOK, "编辑服务器", formFromServer(srv), map[string]string{})
}

func (s *Server) serverSave(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	f, srv, errs := parseServerForm(r)
	f.ID, srv.ID = id, id
	auth := r.PostFormValue("auth")
	msg := "服务器已保存"
	if id != 0 {
		old, err := store.ServerByID(r.Context(), s.DB, id)
		if !s.recordFound(w, r, old != nil, err) {
			return
		}
		f.HasAuth = old.HasAuth
		if old.IP != srv.IP {
			msg += "；指向原 IP 的 DNS 记录已改为新 IP，推送后生效"
		}
	}
	if len(errs) == 0 {
		sc := s.beginDeploy(r.Context())
		if id != 0 {
			sc.server(id, false) // 改 IP 会把引用其落地节点的代理与路由规则所在服务器置为待部署
		}
		saved, err := store.SaveServer(r.Context(), s.DB, s.Field, srv, auth)
		if err == nil && id != 0 {
			sc.server(id, false)
			msg += sc.finish("保存服务器 #"+r.PathValue("id"), "")
		}
		if err == nil && r.PostFormValue("test") == "1" {
			if err := s.Deployer.Ping(r.Context(), saved); err != nil {
				redirectMsg(w, r, "/admin/servers", "err", msg+"，但 SSH 连接失败："+err.Error())
				return
			}
			msg += "，SSH 连接正常"
		}
		if err == nil {
			redirectMsg(w, r, "/admin/servers", "msg", msg)
			return
		}
		if !errors.Is(err, store.ErrInvalid) {
			s.fail(w, r, err)
			return
		}
		errs["server_type"] = err.Error()
	}
	title := "新增服务器"
	if id != 0 {
		title = "编辑服务器"
	}
	s.serverFormPage(w, r, http.StatusUnprocessableEntity, title, f, errs)
}

type serverPlacementView struct {
	store.ServerPlacement
	Here      []string // 当前指向本部署位的域名
	Elsewhere []string // 节点域名当前指向的其他服务器
	CanSwitch bool
	Warn      string // 切到此处确认框追加的提示
}

// serverFormPage 带上已有地区供选择，避免与节点地区写法不一致导致部署位匹配不上。
func (s *Server) serverFormPage(w http.ResponseWriter, r *http.Request, status int, title string, f *ServerForm, errs map[string]string) {
	regions, err := store.Regions(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, title, "servers")
	d["Form"], d["Errors"], d["Regions"] = f, errs, regions
	if f.ID != 0 {
		pls, err := store.ServerPlacements(r.Context(), s.DB, f.ID)
		var pointed map[int64][]store.PointedPlacement
		if err == nil {
			pointed, err = store.ShownPointedPlacements(r.Context(), s.DB)
		}
		var direct ipOnly
		if err == nil {
			direct, err = s.loadIPOnly(r.Context())
		}
		if err != nil {
			s.fail(w, r, err)
			return
		}
		views := make([]serverPlacementView, len(pls))
		active := 0
		for i, p := range pls {
			v := serverPlacementView{ServerPlacement: p}
			for _, pp := range pointed[p.NodeID] {
				if pp.ID == p.ID {
					v.Here = pp.Domains
				} else {
					v.Elsewhere = append(v.Elsewhere, pp.Server)
				}
			}
			// 与节点编辑页「切到此处」一致：节点全部域名已指向这里时不再提供
			v.CanSwitch = p.Peers > 0 && p.Running && p.Deployed != 2 && !f.Disabled && (v.Here == nil || v.Elsewhere != nil)
			if v.CanSwitch {
				v.Warn = direct.switchWarn(p.NodeID, p.ID)
			}
			views[i] = v
			if p.Deployed != 2 {
				active++
			}
		}
		d["Placements"] = views
		targets, err := store.MigrationTargets(r.Context(), s.DB, f.ID)
		var opts []store.BatchServerOption
		if err == nil && active > 0 {
			opts, err = store.BatchServerOptions(r.Context(), s.DB)
		}
		if err != nil {
			s.fail(w, r, err)
			return
		}
		d["MigrationTargets"] = targets
		if active > 0 {
			d["MigrateOptions"] = slices.DeleteFunc(opts, func(o store.BatchServerOption) bool { return o.ID == f.ID })
		}
		blocked, err := store.BlockedPorts(r.Context(), s.DB, f.ID)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		d["BlockedPorts"] = blocked
	}
	s.render(w, "server_form", status, d)
}

func (s *Server) serverDelete(w http.ResponseWriter, r *http.Request) {
	id := idFrom(r)
	if s.redirectError(w, r, "/admin/servers", store.DeleteServer(r.Context(), s.DB, id), store.ErrConflict) {
		return
	}
	redirectMsg(w, r, "/admin/servers", "msg", "服务器已删除")
}
