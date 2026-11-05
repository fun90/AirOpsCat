package web

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/deploy"
	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/store"
)

const (
	alertUnreachable        = "placement-unreachable"
	alertLandingUnreachable = "landing-unreachable"
	probeInterval           = 5 * time.Minute
	probeParallel           = 8 // 每台探测机同时执行的检测数，低于 sshd 默认 MaxSessions 10
	probeAllFailMin         = 5 // 一台探测机本轮至少这么多目标全部失败，判定为探测机自身网络异常
)

var errProbeSetup = errors.New("探测程序准备失败")

// probeJob 是一台探测机本轮要执行的检测。
type probeJob struct {
	targets  []store.ProbeTarget
	requests []string
}

// probePlacements 每轮从每台国内探测机检测全部代理部署位，并从引用落地的代理服务器检测落地部署位（仅 TCP），再按服务器评估不可达告警。
func (s *Server) probePlacements(ctx context.Context) error {
	ids, err := store.ParseIDs(store.ConfigValue(ctx, s.DB, store.ProbeServersKey))
	if err != nil {
		return err
	}
	targets, err := store.ProbeTargets(ctx, s.DB)
	if err != nil {
		return err
	}
	landings, err := store.LandingProbeTargets(ctx, s.DB)
	if err != nil {
		return err
	}
	jobs := map[int64]*probeJob{}
	valid := map[[2]int64]bool{}
	add := func(prober int64, t store.ProbeTarget, request string) {
		j := jobs[prober]
		if j == nil {
			j = &probeJob{}
			jobs[prober] = j
		}
		j.targets, j.requests = append(j.targets, t), append(j.requests, request)
		valid[[2]int64{t.ID, prober}] = true
	}
	for _, t := range targets {
		if request, err := probeRequest(t.IP, t.Port, t.Protocol, t.Inbound); err == nil {
			for _, pid := range ids {
				add(pid, t, request)
			}
		}
	}
	for _, l := range landings {
		if request, err := probeRequest(l.IP, l.Port, "tcp", ""); err == nil {
			for _, pid := range l.Probers {
				add(pid, l.ProbeTarget, request)
			}
		}
	}
	if err := store.PrunePlacementProbes(ctx, s.DB, valid); err != nil {
		return err
	}
	var mu sync.Mutex
	var errs []error
	var wg sync.WaitGroup
	for pid, job := range jobs {
		wg.Add(1)
		go func() {
			defer wg.Done()
			res, err := s.probeRound(ctx, pid, job.targets, job.requests)
			if err == nil {
				err = store.SavePlacementProbes(ctx, s.DB, pid, res, time.Now())
			} else if !errors.Is(err, errProbeSetup) {
				if cerr := store.ClearPlacementProbes(ctx, s.DB, pid); cerr != nil {
					err = errors.Join(err, cerr)
				}
			}
			if err != nil {
				mu.Lock()
				errs = append(errs, fmt.Errorf("探测机 %d：%w", pid, err))
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return errors.Join(append(errs, s.alertUnreachablePlacements(ctx, false), s.alertUnreachablePlacements(ctx, true))...)
}

// probeRound 在一台探测机的同一个 SSH 连接上并发执行全部检测；单条命令执行失败只跳过该目标，保留其上轮结果。
func (s *Server) probeRound(ctx context.Context, probeID int64, targets []store.ProbeTarget, requests []string) ([]store.ProbeOutcome, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	defer cancel()
	r, closeFn, err := s.dialServer(ctx, probeID)
	if err != nil {
		return nil, err
	}
	defer closeFn()
	remote, ok := r.(deploy.Remote)
	if !ok {
		return nil, fmt.Errorf("%w：连接不支持文件分发", errProbeSetup)
	}
	probe, err := s.prepareProbe(ctx, remote)
	if err != nil {
		return nil, fmt.Errorf("%w：%w", errProbeSetup, err)
	}
	out := make([]*store.ProbeOutcome, len(targets))
	sem := make(chan struct{}, probeParallel)
	var wg sync.WaitGroup
	for i, t := range targets {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			stdout, err := probe.Run(ctx, []byte(requests[i]))
			if err != nil {
				return
			}
			p := parseProbe(probeResult{}, stdout)
			if p.Msg == "探测输出无法解析" {
				return
			}
			out[i] = &store.ProbeOutcome{PlacementID: t.ID, OK: p.OK, Ms: p.Ms, Msg: p.Msg}
		}()
	}
	wg.Wait()
	var res []store.ProbeOutcome
	anyOK := false
	for _, o := range out {
		if o != nil {
			res = append(res, *o)
			anyOK = anyOK || o.OK
		}
	}
	if len(res) >= probeAllFailMin && !anyOK {
		return nil, fmt.Errorf("本轮 %d 个部署位全部失败，疑似探测机自身网络异常，结果已丢弃", len(res))
	}
	return res, nil
}

// alertUnreachablePlacements 按服务器聚合告警：同一台服务器被墙时其部署位会一起失败，只推一条。
func (s *Server) alertUnreachablePlacements(ctx context.Context, landing bool) error {
	alertType, scope, probers := alertUnreachable, "国内", "国内探测机"
	if landing {
		alertType, scope, probers = alertLandingUnreachable, "落地", "引用它的代理服务器"
	}
	list, err := store.UnreachablePlacements(ctx, s.DB, landing)
	if err != nil {
		return err
	}
	now := time.Now()
	keep := map[int64]bool{}
	for i := 0; i < len(list); {
		j := i
		var lines []string
		for ; j < len(list) && list[j].ServerID == list[i].ServerID; j++ {
			u := list[j]
			lines = append(lines, fmt.Sprintf("%s %s:%d %s", u.NodeName, probeNetwork(u.Protocol), u.Port, u.Msg))
		}
		u := list[i]
		keep[u.ServerID] = true
		cause := "未采到 sing-box 状态"
		if u.SingboxDown != nil && *u.SingboxDown {
			cause = "sing-box 未运行，疑似服务故障"
		} else if u.SingboxDown != nil {
			cause = "sing-box 运行正常，疑似被墙或端口被封"
		}
		summary := fmt.Sprintf("%s %d 个部署位%s不可达", u.ServerName, j-i, scope)
		body := fmt.Sprintf("%s\n所有%s连续 %d 轮失败：\n%s", cause, probers, store.ProbeFailRounds, strings.Join(lines, "\n"))
		key := store.AlertKey{Type: alertType, ResourceType: "server", ResourceID: u.ServerID, ResourceKey: u.ServerName, Fingerprint: strconv.FormatInt(u.ServerID, 10)}
		if err := s.raise(ctx, key, "critical", float64(j-i), 0, summary, time.Hour,
			notify.Message{Title: "AirOpsCat " + scope + "部署位不可达 · " + u.ServerName, Body: body}); err != nil {
			return err
		}
		i = j
	}
	resolved, err := store.AlertResolveMissing(ctx, s.DB, alertType, "server", keep, now)
	if err != nil {
		return err
	}
	for _, a := range resolved {
		s.bark().Send(ctx, notify.Message{Title: "AirOpsCat 部署位已恢复 · " + a.ResourceKey, Body: a.Summary + "，现已恢复"})
	}
	return nil
}

func probeNetwork(protocol string) string {
	if protocol == "hysteria2" {
		return "UDP"
	}
	return "TCP"
}
