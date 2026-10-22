package web

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/dns"
	"github.com/fun90/airopscat/internal/monitor"
	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/store"
)

// autoDNS 默认关闭；只对显式允许的域名运行，包括手动触发任务。
func (s *Server) autoDNS(ctx context.Context) error {
	ids, err := store.AutoDNSDomains(ctx, s.DB, s.DNSAutoDomains)
	if err != nil {
		return err
	}
	pending, err := s.DB.QueryContext(ctx, `SELECT DISTINCT r.domain_id FROM dns_auto_operation o JOIN domain_dns_record r ON r.id=o.record_id`)
	if err != nil {
		return err
	}
	seen := map[int64]bool{}
	for _, id := range ids {
		seen[id] = true
	}
	for pending.Next() {
		var id int64
		if err := pending.Scan(&id); err != nil {
			pending.Close()
			return err
		}
		if !seen[id] {
			ids = append(ids, id)
			seen[id] = true
		}
	}
	err = pending.Err()
	pending.Close()
	if err != nil {
		return err
	}
	var failures []string
	for _, id := range ids {
		if err := s.autoDNSDomain(ctx, id); err != nil {
			failures = append(failures, fmt.Sprintf("域名 %d：%v", id, err))
		}
	}
	if len(failures) > 0 {
		return fmt.Errorf("%s", strings.Join(failures, "；"))
	}
	return nil
}
func (s *Server) autoDNSDomain(ctx context.Context, id int64) error {
	defer lockDomain(id)()
	dm, c, err := s.domainClient(ctx, id)
	if err != nil {
		return err
	}
	records, err := store.AutoDNSRecords(ctx, s.DB, id)
	if err != nil {
		return err
	}
	candidates, err := store.DNSCandidates(ctx, s.DB, time.Now())
	if err != nil {
		return err
	}
	raw, _ := strconv.ParseFloat(store.ConfigValue(ctx, s.DB, "airopscat.server.monitor.alert.traffic-threshold"), 64)
	threshold := monitor.NormalizeThreshold(raw, 0.85)
	enabled := strings.TrimSpace(s.DNSAutoDomains) == "all"
	for _, part := range strings.Split(s.DNSAutoDomains, ",") {
		if strings.TrimSpace(part) == strconv.FormatInt(id, 10) {
			enabled = true
		}
	}
	for _, r := range records {
		if r.Operation != nil {
			remote, err := c.List(ctx, dm.ZoneID)
			if err != nil {
				return err
			}
			found := false
			for _, v := range remote {
				if v.ID != r.ExternalID {
					continue
				}
				found = true
				if v.Content == r.Operation.Target {
					if err := store.FinishDNSOperation(ctx, s.DB, r.Operation, true); err != nil {
						return err
					}
					s.dnsAutoLog(ctx, r, *r.Operation)
					break
				}
				// 超时请求可能仍在远端执行，读到旧值或第三方值不能证明请求终止。
				// 保留未决状态，禁止自动重发及人工覆盖，需核查供应商后处理。
				return fmt.Errorf("%s 自动操作结果尚未确认，请核查 Cloudflare；不会自动重发", r.FullName)
			}
			if !found {
				return fmt.Errorf("%s 远端记录缺失，自动操作保持待核查", r.FullName)
			}
			continue
		}
		if !enabled {
			continue
		}
		p := dns.PlanRecord(r.PlanInput, candidates, threshold, time.Now())
		if p.BlockedReason != "" {
			summary := r.FullName + " 自动 DNS 无法回切：" + p.BlockedReason + "，保持当前解析，请核查部署状态"
			if err := s.raise(ctx, store.AlertKey{Type: "dns-reset-blocked", ResourceType: "dns", ResourceID: r.ID, ResourceKey: r.FullName, Fingerprint: r.FullName}, "warning", 1, 1, summary, 24*time.Hour,
				notify.Message{Title: "AirOpsCat DNS 回切受阻", Body: summary, Level: "timeSensitive"}); err != nil {
				return err
			}
			continue
		}
		if _, err := store.AlertResolve(ctx, s.DB, "dns-reset-blocked", "dns", r.ID, time.Now()); err != nil {
			return err
		}
		key := store.AlertKey{Type: "dns-candidates-exhausted", ResourceType: "dns", ResourceID: r.ID, ResourceKey: r.FullName, Fingerprint: r.FullName}
		if p.Exhausted {
			if err := s.raise(ctx, key, "warning", 1, 1, r.FullName+" 所有候选服务器流量均超过阈值", 24*time.Hour, notify.Message{Title: "AirOpsCat 服务器自动切换", Body: r.FullName + " 所有候选服务器流量均超过阈值，保持当前解析", Level: "timeSensitive"}); err != nil {
				return err
			}
		} else {
			if _, err := store.AlertResolve(ctx, s.DB, key.Type, key.ResourceType, key.ResourceID, time.Now()); err != nil {
				return err
			}
		}
		if p.Target == "" {
			continue
		}
		if p.Target == r.Content && p.Home == r.PlanInput.Home {
			continue
		}
		op, err := store.BeginDNSOperation(ctx, s.DB, r, p)
		if err != nil {
			return err
		}
		if p.Target != r.Content {
			callCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
			_, err = c.Push(callCtx, dm.ZoneID, dns.Batch{Patches: []dns.Change{{ID: r.ExternalID, Type: "A", Name: r.FullName, Content: p.Target, TTL: r.TTL, Proxied: r.Proxied}}})
			cancel()
			if err != nil {
				store.Log(ctx, s.DB, "SERVER_FAILOVER", "ERROR", &r.ID, r.FullName, "自动 DNS 请求结果待对账", "")
				return fmt.Errorf("%s 自动推送失败，下一轮核对远端结果", r.FullName)
			}
		}
		if err := store.FinishDNSOperation(ctx, s.DB, op, true); err != nil {
			return err
		}
		s.dnsAutoLog(ctx, r, *op)
	}
	return nil
}
func (s *Server) dnsAutoLog(ctx context.Context, r store.AutoDNSRecord, op store.DNSOperation) {
	if op.Old == op.Target {
		return
	}
	msg := fmt.Sprintf("%s：%s → %s（%s）", r.FullName, op.Old, op.Target, op.Reason)
	store.Log(ctx, s.DB, "SERVER_FAILOVER", "INFO", &r.ID, r.FullName, msg, fmt.Sprintf("old=%s target=%s reason=%s", op.Old, op.Target, op.Reason))
	s.bark().Send(ctx, notify.Message{Title: "AirOpsCat 服务器自动切换", Body: msg, Level: "timeSensitive"})
}
