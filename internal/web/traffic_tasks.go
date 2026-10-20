package web

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
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
	return s.autoDNS(ctx)
}
func (s *Server) collectServerTraffic(ctx context.Context, id int64) error {
	unlock := deploy.Lock(id)
	defer func() { unlock() }()
	before, err := store.LoadEffectiveLimits(ctx, s.DB, time.Now())
	if err != nil {
		return err
	}
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
	after, err := store.LoadEffectiveLimits(ctx, s.DB, time.Now())
	if err != nil {
		return err
	}
	changed := false
	for no, a := range after {
		b, ok := before[no]
		if ok && (a.DownloadMbps != b.DownloadMbps || a.UploadMbps != b.UploadMbps) {
			changed = true
			break
		}
	}
	// Reload 自身获取服务器互斥，先释放采集锁；部署器会重新读取当前数据。
	if changed {
		unlock()
		unlock = func() {}
		res := s.Deployer.Reload(ctx, id)
		if !res.Success {
			return fmt.Errorf("超额限速同步失败：%s", res.Message)
		}
	}
	return nil
}
