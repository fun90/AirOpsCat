package web

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"sort"
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
	for _, id := range ids {
		if err := s.collectServerTraffic(ctx, id); err != nil {
			failed = append(failed, fmt.Errorf("服务器 %d：%w", id, err))
		}
	}
	if len(failed) > 0 {
		body := fmt.Sprintf("流量统计收集完成，成功: %d，失败: %d", len(ids)-len(failed), len(failed))
		if err := s.bark().Send(ctx, notify.Message{Title: "AirOpsCat 流量统计", Body: body, Level: "timeSensitive"}); err != nil && !errors.Is(err, notify.ErrNotConfigured) {
			slog.Warn("流量采集汇总通知失败")
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
	readings, err := traffic.CollectSSH(ctx, s.DB, s.Field, id)
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
