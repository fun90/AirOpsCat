package web

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/singbox"
	"github.com/fun90/airopscat/internal/store"
	"github.com/fun90/airopscat/internal/traffic"
)

// collectTraffic 只采集显式启用的服务器集合，开发导入库不会自动操作生产服务器。
// AIROPSCAT_TRAFFIC_SERVERS 为服务器 ID 逗号列表；all 表示按运行状态选取全部服务器。
func (s *Server) collectTraffic(ctx context.Context) error {
	ids, err := store.RuntimeTrafficServers(ctx, s.DB, s.TrafficServers, time.Now())
	if err != nil {
		return err
	}
	if len(ids) == 0 {
		return nil
	}
	var failed []error
	interval := time.Duration(s.configInt(ctx, "airopscat.server.monitor.alert.min-interval-minutes", 60)) * time.Minute
	for _, id := range ids {
		err := s.collectServerTraffic(ctx, id)
		if err != nil {
			failed = append(failed, fmt.Errorf("服务器 %d：%w", id, err))
		}
		if aerr := s.trackCollectAlert(ctx, id, err, interval); aerr != nil {
			slog.Warn("流量采集告警处理失败", "server", id, "err", aerr)
		}
	}
	// 单台采集失败不阻断基于已有数据的限速和独立的 vnstat DNS 检查。
	if err := s.syncLimits(ctx); err != nil {
		failed = append(failed, err)
	}
	if err := s.autoDNS(ctx); err != nil {
		failed = append(failed, err)
	}
	return errors.Join(failed...)
}

// limitsKey 只记录实际生效的超额限速，同一账户跨节点去重；无超额限速时返回空串。
func limitsKey(in *singbox.ServerConfigInput) string {
	entries := map[string]bool{}
	for _, n := range in.Nodes {
		for _, c := range n.Clients {
			if c.QuotaLimited {
				entries[fmt.Sprintf("%s|%d|%d", c.Name, c.DownloadMbps, c.UploadMbps)] = true
			}
		}
	}
	if len(entries) == 0 {
		return ""
	}
	lines := make([]string, 0, len(entries))
	for line := range entries {
		lines = append(lines, line)
	}
	sort.Strings(lines)
	sum := sha256.Sum256([]byte(strings.Join(lines, "\n")))
	return hex.EncodeToString(sum[:])
}

func (s *Server) appliedLimits(id int64) string {
	s.limitMu.Lock()
	defer s.limitMu.Unlock()
	return s.limitApplied[id]
}

func (s *Server) markLimits(id int64, key string) {
	s.limitMu.Lock()
	defer s.limitMu.Unlock()
	if s.limitApplied == nil {
		s.limitApplied = map[int64]string{}
	}
	s.limitApplied[id] = key
}

// syncLimits 只在超额限速生效、变化或解除时 reload；普通配置变化不触发下发。
// 失败不记录指纹，下一轮继续重试；进程重启后仅对有超额限速的服务器重新应用。
func (s *Server) syncLimits(ctx context.Context) error {
	ids, err := store.RuntimeTrafficServers(ctx, s.DB, s.TrafficServers, time.Now())
	if err != nil {
		return err
	}
	var failed []string
	for _, id := range ids {
		p, err := s.Deployer.Prepare(ctx, id)
		if err == nil && p.LimitsKey == s.appliedLimits(id) {
			continue
		}
		msg := ""
		if err != nil {
			msg = err.Error()
		} else if res := s.Deployer.Reload(ctx, id); !res.Success {
			msg = res.Message
		}
		if msg != "" {
			failed = append(failed, fmt.Sprintf("服务器 %d：%s", id, msg))
		}
	}
	if len(failed) > 0 {
		return fmt.Errorf("超额限速同步失败：%s", strings.Join(failed, "；"))
	}
	return nil
}

func (s *Server) collectServerTraffic(ctx context.Context, id int64) error {
	defer deploy.Lock(id)()
	return s.collectServerTrafficLocked(ctx, id)
}

// trafficBeforeRestart 是 Deployer.BeforeApply：restart 会清零内核计数，部署持锁推送前先采集一次。
// 只采集流量采集白名单内且有运行中部署位的服务器；reload 由限速同步在刚采集后触发，不重复采集。
func (s *Server) trafficBeforeRestart(ctx context.Context, id int64, action string) error {
	if action != "restart" {
		return nil
	}
	ids, err := store.RuntimeTrafficServers(ctx, s.DB, s.TrafficServers, time.Now())
	if err != nil {
		return err
	}
	for _, v := range ids {
		if v == id {
			return s.collectServerTrafficLocked(ctx, id)
		}
	}
	return nil
}

// collectServerTrafficLocked 要求调用方已持有该服务器的 deploy.Lock（锁不可重入）。
func (s *Server) collectServerTrafficLocked(ctx context.Context, id int64) error {
	collect := traffic.CollectSSH
	if s.trafficCollect != nil {
		collect = s.trafficCollect
	}
	readings, err := collect(ctx, s.DB, s.Field, id)
	if err != nil {
		return err
	}
	var samples []store.TrafficReading
	for no, c := range readings {
		samples = append(samples, store.TrafficReading{AccountNo: no, Direction: "uplink", Value: c.UploadBytes}, store.TrafficReading{AccountNo: no, Direction: "downlink", Value: c.DownloadBytes})
	}
	// 静态入站用户没有账户记录，跳过而不影响同批其他账户。
	known, err := store.AccountNumbers(ctx, s.DB)
	if err != nil {
		return err
	}
	filtered := samples[:0]
	for _, s := range samples {
		if known[s.AccountNo] {
			filtered = append(filtered, s)
		}
	}
	if err := store.CollectAccountTraffic(ctx, s.DB, id, time.Now(), filtered); err != nil {
		return err
	}
	return nil
}

// trackCollectAlert 按服务器维护流量采集失败告警：成功即恢复，失败按节流通知；
// 服务器已处于失联告警时不重复通知，失联本身已说明采集必然失败。
func (s *Server) trackCollectAlert(ctx context.Context, id int64, collectErr error, interval time.Duration) error {
	sid := strconv.FormatInt(id, 10)
	fp := "collect:" + sid
	now := time.Now()
	if collectErr == nil {
		_, err := store.AlertResolveFingerprint(ctx, s.DB, alertMonitorLoad, "server", id, fp, now)
		return err
	}
	var offline int
	if err := s.DB.QueryRowContext(ctx, `SELECT count(*) FROM alert_state WHERE alert_type=? AND resource_type='server' AND resource_id=? AND fingerprint=? AND status<>'RECOVERED'`,
		alertMonitorLoad, id, "offline:"+sid).Scan(&offline); err != nil {
		return err
	}
	if offline > 0 {
		return nil
	}
	var ip string
	_ = s.DB.QueryRowContext(ctx, `SELECT ip FROM server WHERE id=?`, id).Scan(&ip)
	summary := fmt.Sprintf("服务器 %s (ID %d) 流量统计收集失败，账户流量与超额限速可能停止更新", ip, id)
	return s.raise(ctx, store.AlertKey{Type: alertMonitorLoad, ResourceType: "server", ResourceID: id, ResourceKey: ip, Fingerprint: fp},
		"warning", 1, 1, summary, interval, notify.Message{Title: "AirOpsCat 流量统计失败", Body: summary})
}
