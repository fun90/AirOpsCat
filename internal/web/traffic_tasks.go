package web

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"sort"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
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
	var failed []string
	for _, id := range ids {
		if err := s.collectServerTraffic(ctx, id); err != nil {
			failed = append(failed, fmt.Sprintf("服务器 %d：%v", id, err))
		}
	}
	if len(failed) > 0 {
		return fmt.Errorf("%s", strings.Join(failed, "；"))
	}
	if err := s.syncLimits(ctx); err != nil {
		return err
	}
	return s.autoDNS(ctx)
}

// limitsKey 是配置中各节点账户限速的指纹，用于判断服务器已应用的限速是否过期。
func limitsKey(in *singbox.ServerConfigInput) string {
	var lines []string
	for _, n := range in.Nodes {
		for _, c := range n.Clients {
			lines = append(lines, fmt.Sprintf("%d|%s|%d|%d", n.ID, c.Name, c.DownloadMbps, c.UploadMbps))
		}
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

// syncLimits 对比每台服务器当前应有限速与最近一次成功应用的指纹，不一致才 reload。
// 失败不记录指纹，下一轮继续重试；进程重启后无记录，首轮会各 reload 一次。
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
