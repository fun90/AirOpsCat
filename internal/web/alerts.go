package web

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"net/http"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/store"
)

const (
	alertQuota       = "account-traffic-over-quota"
	alertConnection  = "account-connection-limit"
	alertExpiring    = "-expiring"           // 前缀为资源类型：server / domain / account
	alertMonitorLoad = "server-monitor-load" // 指纹 cpu:ID / memory:ID / traffic:ID，与既有数据一致
	alertDeployFail  = "server-deploy-failed"
)

var alertTypeNames = map[string]string{
	alertQuota: "账户流量超额", alertConnection: "账户连接数超限",
	"server-expiring": "服务器到期", "domain-expiring": "域名到期", "account-expiring": "账户到期",
	alertMonitorLoad: "服务器负载与流量", alertDeployFail: "自动部署失败",
}

func (s *Server) alertRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/alerts", s.alertList)
	m.HandleFunc("POST /admin/alerts/test", s.alertTest)
	m.HandleFunc("POST /admin/alerts/{id}/ack", s.alertAck)
	m.HandleFunc("POST /admin/alerts/{id}/recover", s.alertRecover)
}

// barkConfig 每次发送都重新读取；敏感项解密失败视为未配置，不回退为明文。
func (s *Server) barkConfig(ctx context.Context) (notify.Config, error) {
	if s.NotifyDisabled {
		return notify.Config{}, nil
	}
	rows, err := s.DB.QueryContext(ctx, `SELECT config_key,coalesce(config_value,'') FROM system_config WHERE config_key LIKE 'airopscat.bark.%'`)
	if err != nil {
		return notify.Config{}, err
	}
	defer rows.Close()
	v := map[string]string{}
	for rows.Next() {
		var k, val string
		if err := rows.Scan(&k, &val); err != nil {
			return notify.Config{}, err
		}
		val = strings.TrimSpace(val)
		if store.SensitiveSetting(k) && val != "" {
			if s.Field == nil {
				return notify.Config{}, errors.New("敏感配置加密未初始化")
			}
			if val, err = s.Field.Decrypt(val); err != nil {
				return notify.Config{}, fmt.Errorf("Bark 配置解密失败，请在系统配置中重新保存")
			}
		}
		v[strings.TrimPrefix(strings.ToLower(k), "airopscat.bark.")] = val
	}
	return notify.Config{URL: v["url"], DeviceKey: v["device-key"], Encrypt: v["encrypt-enabled"] == "true", Key: v["encrypt-key"], IV: v["encrypt-iv"],
		Group: v["default-group"], Sound: v["default-sound"], Icon: v["default-icon"]}, rows.Err()
}

func (s *Server) bark() *notify.Client {
	return &notify.Client{Config: s.barkConfig}
}

func (s *Server) configInt(ctx context.Context, key string, def int) int {
	if n, err := strconv.Atoi(store.ConfigValue(ctx, s.DB, key)); err == nil && n > 0 {
		return n
	}
	return def
}

func (s *Server) configBool(ctx context.Context, key string, def bool) bool {
	switch strings.ToLower(store.ConfigValue(ctx, s.DB, key)) {
	case "true":
		return true
	case "false":
		return false
	}
	return def
}

// raise 记录触发并按节流发送 Bark；发送成功才记通知时间，未配置 Bark 时只留告警记录。
func (s *Server) raise(ctx context.Context, k store.AlertKey, severity string, value, threshold float64, summary string, interval time.Duration, msg notify.Message) error {
	now := time.Now()
	id, due, err := store.AlertTrigger(ctx, s.DB, k, severity, value, threshold, summary, interval, now)
	if err != nil || !due {
		return err
	}
	store.Log(ctx, s.DB, "ALERT", "WARN", &k.ResourceID, k.ResourceKey, summary, k.Type)
	if err := s.bark().Send(ctx, msg); err != nil {
		if !errors.Is(err, notify.ErrNotConfigured) {
			slog.Warn("告警通知发送失败", "type", k.Type, "resource", k.ResourceKey, "err", err)
		}
		return nil
	}
	return store.AlertMarkNotified(ctx, s.DB, id, now)
}

// ---------- 来源 ----------

// guardEvaluateSeconds 与 guard-evaluate 任务周期一致，用于累计每日超限时长。
const guardEvaluateSeconds = 10

// ipOverlapDigest 每日推送昨天 IP 超限累计时长最长的账户；没有则不发。
func (s *Server) ipOverlapDigest(ctx context.Context) error {
	if !s.configBool(ctx, "airopscat.account.connection-limit.alert.enabled", true) {
		return nil
	}
	top, err := store.GuardIPOverlapTop(ctx, s.DB, store.FormatTime(time.Now().AddDate(0, 0, -1))[:10], 10)
	if err != nil || len(top) == 0 {
		return err
	}
	var b strings.Builder
	for _, g := range top {
		fmt.Fprintf(&b, "%s（%s）累计 %d 分钟，峰值 %d/%d，近 7 天 %d 天\n", g.AccountNo, g.Remark, (g.Seconds+59)/60, g.Peak, g.Limit, g.Days)
	}
	err = s.bark().Send(ctx, notify.Message{Title: "AirOpsCat 昨日 IP 超限摘要", Body: strings.TrimSpace(b.String())})
	if errors.Is(err, notify.ErrNotConfigured) {
		return nil
	}
	return err
}

// alertConnections 在每次 guard 评估后调用：被拒绝（连接数或 IP 超限）的账户告警，其余恢复。
func (s *Server) alertConnections(ctx context.Context, stats map[string]guard.Stats) error {
	if !s.configBool(ctx, "airopscat.account.connection-limit.alert.enabled", true) {
		return nil
	}
	interval := time.Duration(s.configInt(ctx, "airopscat.account.connection-limit.alert.min-interval-minutes", 60)) * time.Minute
	keep := map[int64]bool{}
	for no, st := range stats {
		if st.Blocked == nil {
			continue
		}
		var id int64
		var remark string
		if err := s.DB.QueryRowContext(ctx, `SELECT id,coalesce(remark,'') FROM account WHERE account_no=? AND disabled=0 AND (to_date IS NULL OR to_date>?)`, no, store.FormatTime(time.Now())).Scan(&id, &remark); err != nil {
			continue
		}
		keep[id] = true
		kind, unit := "连接数", "个"
		key := store.AlertKey{Type: alertConnection, ResourceType: "account", ResourceID: id, ResourceKey: no, Fingerprint: no}
		severity, quiet := "warning", false
		if st.Blocked.Reason == "ips" {
			kind, unit = "IP 数", "个"
			// 极端超限（≥2 倍）立即推送；其余只记录并进每日摘要
			if st.Blocked.Total >= 2*st.Blocked.Limit {
				severity = "critical"
			}
			quiet = severity != "critical"
			if err := store.GuardIPOverlapAdd(ctx, s.DB, store.FormatTime(time.Now())[:10], no, guardEvaluateSeconds, st.Blocked.Total, st.Blocked.Limit); err != nil {
				return err
			}
		}
		summary := fmt.Sprintf("%s %s超限 %d/%d", no, kind, st.Blocked.Total, st.Blocked.Limit)
		if quiet {
			if _, _, err := store.AlertTrigger(ctx, s.DB, key, severity, float64(st.Blocked.Total), float64(st.Blocked.Limit), summary, interval, time.Now()); err != nil {
				return err
			}
			continue
		}
		// 超限项放在首行；另一项和节点数作参照
		other := fmt.Sprintf("IP %d · 节点 %d", st.ActiveIPs, st.ActiveNodes)
		if kind == "IP 数" {
			other = fmt.Sprintf("连接 %d · 节点 %d", st.Connections, st.ActiveNodes)
		}
		body := fmt.Sprintf("%s（%s）\n%s %d %s，上限 %d\n%s", no, remark, kind, st.Blocked.Total, unit, st.Blocked.Limit, other)
		if err := s.raise(ctx, key, severity, float64(st.Blocked.Total), float64(st.Blocked.Limit), summary, interval,
			notify.Message{Title: "AirOpsCat " + kind + "超限 · " + no, Body: body, Level: "timeSensitive"}); err != nil {
			return err
		}
	}
	resolved, err := store.AlertResolveMissing(ctx, s.DB, alertConnection, "account", keep, time.Now())
	if err != nil {
		return err
	}
	if s.configBool(ctx, "airopscat.account.connection-limit.alert.recovery-notify-enabled", false) {
		for _, a := range resolved {
			s.bark().Send(ctx, notify.Message{Title: "AirOpsCat 超限已恢复 · " + a.ResourceKey, Body: a.Summary})
		}
	}
	return nil
}

// scanAlerts 每 5 分钟扫描账户流量超额与资源到期；条件消失即恢复且不发通知。
func (s *Server) scanAlerts(ctx context.Context) error {
	now := time.Now()
	quotaInterval := time.Duration(s.configInt(ctx, "airopscat.account.connection-limit.alert.min-interval-minutes", 60)) * time.Minute
	over, err := store.OverQuotaAccounts(ctx, s.DB, now)
	if err != nil {
		return err
	}
	keep := map[int64]bool{}
	limited := s.configBool(ctx, "airopscat.ratelimit.enabled", false)
	for _, q := range over {
		keep[q.ID] = true
		body := fmt.Sprintf("账户: %s（%s）\n当前用量: %.2f GB\n有效配额: %.0f GB", q.AccountNo, q.Remark, q.UsedGB, q.QuotaGB)
		if limited {
			body += "\n已启用 sing-box 原生超额限速，限速同步任务会持续对账下发"
		}
		if err := s.raise(ctx, store.AlertKey{Type: alertQuota, ResourceType: "account", ResourceID: q.ID, ResourceKey: q.AccountNo, Fingerprint: q.AccountNo + ":" + q.PeriodStart}, "warning",
			q.UsedGB, q.QuotaGB, fmt.Sprintf("%s 已用 %.2f / %.0f GB", q.AccountNo, q.UsedGB, q.QuotaGB), quotaInterval,
			notify.Message{Title: "AirOpsCat 账户流量超额", Body: body, Level: "timeSensitive"}); err != nil {
			return err
		}
	}
	if _, err := store.AlertResolveMissing(ctx, s.DB, alertQuota, "account", keep, now); err != nil {
		return err
	}

	items, err := store.ExpiringResources(ctx, s.DB, now)
	if err != nil {
		return err
	}
	kept := map[string]map[int64]bool{"server": {}, "domain": {}, "account": {}}
	label := map[string]string{"server": "服务器", "domain": "域名", "account": "账户"}
	for _, it := range items {
		kept[it.Kind][it.ID] = true
		interval := time.Duration(s.configInt(ctx, "airopscat."+it.Kind+".expiring.alert.min-interval-hours", 23)) * time.Hour
		if err := s.raise(ctx, store.AlertKey{Type: it.Kind + alertExpiring, ResourceType: it.Kind, ResourceID: it.ID, ResourceKey: it.Name, Fingerprint: expiringFingerprint(it)}, "info",
			0, 0, label[it.Kind]+"到期: "+it.Name, interval,
			notify.Message{Title: "AirOpsCat 到期提醒", Body: fmt.Sprintf("今日到期%s: %s", label[it.Kind], it.Name)}); err != nil {
			return err
		}
	}
	for kind, ids := range kept {
		if _, err := store.AlertResolveMissing(ctx, s.DB, kind+alertExpiring, kind, ids, now); err != nil {
			return err
		}
	}
	return nil
}

// ---------- 页面 ----------

type alertView struct {
	store.Alert
	TypeName string
}

func (s *Server) alertList(w http.ResponseWriter, r *http.Request) {
	status := r.URL.Query().Get("status")
	if status != store.AlertActive && status != store.AlertAcknowledged && status != store.AlertRecovered {
		status = ""
	}
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	list, total, err := store.AlertList(r.Context(), s.DB, status, q, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	views := make([]alertView, len(list))
	for i, a := range list {
		name := alertTypeNames[a.Type]
		if name == "" {
			name = a.Type
		}
		views[i] = alertView{a, name}
	}
	d := s.pageData(r, "告警", "alerts")
	d["List"], d["Status"], d["Q"], d["Page"], d["Pages"], d["ListURL"] = views, status, q, page, (total+size-1)/size, r.URL.RequestURI()
	s.render(w, "alerts", http.StatusOK, d)
}

func (s *Server) alertAck(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := store.AlertAcknowledge(r.Context(), s.DB, id, r.Context().Value(userKey).(*store.User).Email, time.Now()); err != nil {
		if msg, ok := s.saveErr(w, r, err); ok {
			redirectMsg(w, r, "/admin/alerts", "err", msg)
		}
		return
	}
	redirectMsg(w, r, "/admin/alerts", "msg", "已确认，条件持续期间不再通知")
}

func (s *Server) alertRecover(w http.ResponseWriter, r *http.Request) {
	id, ok := pathID(w, r, "id")
	if !ok {
		return
	}
	if err := store.AlertRecoverManual(r.Context(), s.DB, id, time.Now()); err != nil {
		if msg, ok := s.saveErr(w, r, err); ok {
			redirectMsg(w, r, "/admin/alerts", "err", msg)
		}
		return
	}
	redirectMsg(w, r, "/admin/alerts", "msg", "已标记恢复；条件仍满足时会再次触发")
}

func (s *Server) alertTest(w http.ResponseWriter, r *http.Request) {
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	err := s.bark().Send(ctx, notify.Message{Title: "AirOpsCat 测试通知", Body: "如果你收到这条消息，说明 Bark 配置正确。"})
	switch {
	case errors.Is(err, notify.ErrNotConfigured):
		redirectMsg(w, r, "/admin/alerts", "err", "Bark 未配置：请在系统配置的「Bark 通知」中填写服务地址和设备 Key")
	case err != nil:
		redirectMsg(w, r, "/admin/alerts", "err", err.Error())
	default:
		redirectMsg(w, r, "/admin/alerts", "msg", "测试通知已发送")
	}
}

// expiringFingerprint 与既有告警记录一致：服务器用 IP、账户用账户号、域名用域名，续期后条件消失即恢复。
func expiringFingerprint(it store.ExpiringItem) string {
	if it.Kind == "domain" {
		return it.Name
	}
	return it.Extra
}
