package web

import (
	"cmp"
	"fmt"
	"net/http"
	"slices"
	"strconv"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

// dashboardHealthLimit 是概览「服务器健康」最多展示的台数，其余到监控页查看。
const dashboardHealthLimit = 8

// dashAlert 是概览中的一条告警，带处理入口与相对时间。
type dashAlert struct {
	alertView
	Link, LinkText, Ago string
}

// dashExpiry 是到期账户或续费项，Left 为剩余时间文本，Urgent 表示 24 小时内（续费项为今天）到期。
type dashExpiry struct {
	Left   string
	Urgent bool
}

type dashAccount struct {
	store.DashboardExpiry
	dashExpiry
}

type dashRenewal struct {
	store.DashboardRenewal
	dashExpiry
}

// dashHealth 是概览的服务器健康行；Level 3 故障、2 警告、1 无数据、0 正常。
type dashHealth struct {
	statusView
	Level int
	Issue string
}

func newDashAlert(a store.Alert, now time.Time) dashAlert {
	name := alertTypeNames[a.Type]
	if name == "" {
		name = a.Type
	}
	v := dashAlert{alertView: alertView{Alert: a, TypeName: name}, Link: "/admin/alerts?status=" + a.Status, LinkText: "查看"}
	id := strconv.FormatInt(a.ResourceID, 10)
	switch {
	case a.ResourceType == "account" && (a.Type == alertConnection || a.Type == alertQuota):
		v.Link, v.LinkText = "/admin/accounts/"+id+"/edit", "账户"
	case a.ResourceType == "server" && a.Type == alertMonitorLoad:
		v.Link, v.LinkText = "/admin/monitor/"+id, "监控"
	case a.ResourceType == "server":
		v.Link, v.LinkText = "/admin/servers/"+id+"/edit", "服务器"
	}
	if t, err := store.ParseTime(a.LastTime); err == nil {
		v.Ago = relativeTime(t, now)
	}
	return v
}

// accountLeft 描述账户距离到期的剩余时间。
func accountLeft(expires string, now time.Time) dashExpiry {
	t, err := store.ParseTime(expires)
	if err != nil {
		return dashExpiry{}
	}
	d := t.Sub(now)
	switch {
	case d < time.Hour:
		return dashExpiry{"1 小时内", true}
	case d < 24*time.Hour:
		return dashExpiry{fmt.Sprintf("%d 小时后", int(d/time.Hour)), true}
	}
	return dashExpiry{fmt.Sprintf("%d 天后", int(d/(24*time.Hour))), false}
}

// renewalLeft 按上海日期描述续费项剩余天数。
func renewalLeft(expire string, now time.Time) dashExpiry {
	t, err := time.ParseInLocation("2006-01-02", expire, store.Shanghai)
	if err != nil {
		return dashExpiry{}
	}
	local := now.In(store.Shanghai)
	today := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, store.Shanghai)
	days := int(t.Sub(today).Hours()+12) / 24
	if days <= 0 {
		return dashExpiry{"今天", true}
	}
	return dashExpiry{fmt.Sprintf("%d 天后", days), false}
}

// healthOf 判定服务器健康等级；alert 为该服务器进行中的告警类型名。
// 指标阈值与监控页的视觉提示一致，告警仍以告警规则为准。
func healthOf(v statusView, alert string) dashHealth {
	h := dashHealth{statusView: v}
	switch {
	case v.SingboxDown != nil && *v.SingboxDown:
		h.Level, h.Issue = 3, "sing-box 已停止"
	case v.Stale:
		h.Level, h.Issue = 3, "上报中断"
	case alert != "":
		h.Level, h.Issue = 2, alert
	case v.UsagePct >= 85:
		h.Level, h.Issue = 2, "流量 "+v.UsagePctText
	case v.CPU != nil && *v.CPU >= 0.9:
		h.Level, h.Issue = 2, "CPU "+v.CPUText
	case v.Memory != nil && *v.Memory >= 0.95:
		h.Level, h.Issue = 2, "内存 "+v.MemText
	case v.Disk != nil && *v.Disk >= 0.9:
		h.Level, h.Issue = 2, "磁盘 "+v.DiskText
	case v.Sampled == "":
		h.Level, h.Issue = 1, "暂无监控数据"
	}
	return h
}

func (s *Server) dashboard(w http.ResponseWriter, r *http.Request) {
	days, _ := strconv.Atoi(r.URL.Query().Get("days"))
	if days != 3 && days != 7 {
		days = 1
	}
	now := time.Now()
	ctx := r.Context()
	stats, err := store.DashboardLoad(ctx, s.DB, now, days)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	snap, err := s.onlineSnapshot(ctx)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	statuses, _, err := store.ServerStatuses(ctx, s.DB, "", 1, 100000, now)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	local := now.In(store.Shanghai)
	monthStart := time.Date(local.Year(), local.Month(), 1, 0, 0, 0, 0, store.Shanghai)
	monthEnd := monthStart.AddDate(0, 1, 0).Add(-time.Second)
	money, err := store.TransactionSummary(ctx, s.DB, store.TransactionFilter{StartDate: &monthStart, EndDate: &monthEnd})
	if err != nil {
		s.fail(w, r, err)
		return
	}

	active := make([]dashAlert, len(stats.ActiveAlerts))
	for i, a := range stats.ActiveAlerts {
		active[i] = newDashAlert(a, now)
	}
	recent := make([]dashAlert, len(stats.RecentAlerts))
	for i, a := range stats.RecentAlerts {
		recent[i] = newDashAlert(a, now)
	}
	accounts := make([]dashAccount, len(stats.ExpiringAccounts))
	for i, a := range stats.ExpiringAccounts {
		accounts[i] = dashAccount{a, accountLeft(a.Expires, now)}
	}
	renewals := make([]dashRenewal, len(stats.ExpiringRenewals))
	for i, e := range stats.ExpiringRenewals {
		renewals[i] = dashRenewal{e, renewalLeft(e.Expire, now)}
	}
	todo := len(active) + len(accounts) + len(renewals)
	if stats.PendingPlacements > 0 {
		todo++
	}
	if stats.AlertsAcknowledged > 0 {
		todo++
	}

	serverAlert := map[int64]string{}
	for _, a := range active {
		if a.ResourceType == "server" && serverAlert[a.ResourceID] == "" {
			serverAlert[a.ResourceID] = a.TypeName
		}
	}
	health := make([]dashHealth, len(statuses))
	var healthBad int
	for i, st := range statuses {
		v := newStatusView(st, now)
		if _, ok := snap.Servers[st.IP]; ok {
			v.Online = serverOnlineText(snap, st.IP)
		}
		health[i] = healthOf(v, serverAlert[st.ID])
		if health[i].Level >= 2 {
			healthBad++
		}
	}
	slices.SortStableFunc(health, func(a, b dashHealth) int {
		if c := cmp.Compare(b.Level, a.Level); c != 0 {
			return c
		}
		return cmp.Compare(b.UsagePct, a.UsagePct)
	})
	healthTotal := len(health)
	health = health[:min(len(health), dashboardHealthLimit)]

	var latest time.Time
	for _, sv := range snap.Servers {
		if sv.At.After(latest) {
			latest = sv.At
		}
	}
	d := s.pageData(r, "概览", "dashboard")
	d["Stats"], d["Days"], d["Online"] = stats, days, s.onlineSummaryFor(snap)
	d["ActiveAlerts"], d["RecentAlerts"], d["Accounts"], d["Renewals"], d["Todo"] = active, recent, accounts, renewals, todo
	d["Health"], d["HealthTotal"], d["HealthBad"] = health, healthTotal, healthBad
	d["Money"], d["Month"] = money, int(local.Month())
	if !latest.IsZero() {
		d["OnlineAt"] = latest.In(store.Shanghai).Format("15:04")
	}
	s.render(w, "dashboard", http.StatusOK, d)
}
