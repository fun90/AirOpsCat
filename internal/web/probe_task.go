package web

import (
	"context"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/store"
)

const (
	alertUnreachable = "placement-unreachable"
	probeInterval    = 5 * time.Minute
	probeParallel    = 8 // 每台探测机同时执行的检测数，低于 sshd 默认 MaxSessions 10
	probeAllFailMin  = 5 // 一台探测机本轮至少这么多目标全部失败，判定为探测机自身网络异常
)

// probePlacements 每轮从每台国内探测机检测全部部署位，再按服务器评估不可达告警。
func (s *Server) probePlacements(ctx context.Context) error {
	ids, err := store.ParseIDs(store.ConfigValue(ctx, s.DB, store.ProbeServersKey))
	if err != nil {
		return err
	}
	targets, err := store.ProbeTargets(ctx, s.DB)
	if err != nil {
		return err
	}
	var cmds []string
	var checked []store.ProbeTarget
	var placementIDs []int64
	for _, t := range targets {
		if cmd, err := probeCommand(t.IP, t.Port, t.Protocol, t.Inbound); err == nil {
			cmds, checked, placementIDs = append(cmds, cmd), append(checked, t), append(placementIDs, t.ID)
		}
	}
	if err := store.PrunePlacementProbes(ctx, s.DB, ids, placementIDs); err != nil {
		return err
	}
	var mu sync.Mutex
	var errs []error
	var wg sync.WaitGroup
	for _, pid := range ids {
		if len(checked) == 0 {
			break
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			res, err := s.probeRound(ctx, pid, checked, cmds)
			if err == nil {
				err = store.SavePlacementProbes(ctx, s.DB, pid, res, time.Now())
			} else if cerr := store.ClearPlacementProbes(ctx, s.DB, pid); cerr != nil {
				err = errors.Join(err, cerr)
			}
			if err != nil {
				mu.Lock()
				errs = append(errs, fmt.Errorf("探测机 %d：%w", pid, err))
				mu.Unlock()
			}
		}()
	}
	wg.Wait()
	return errors.Join(append(errs, s.alertUnreachablePlacements(ctx))...)
}

// probeRound 在一台探测机的同一个 SSH 连接上并发执行全部检测；单条命令执行失败只跳过该目标，保留其上轮结果。
func (s *Server) probeRound(ctx context.Context, probeID int64, targets []store.ProbeTarget, cmds []string) ([]store.ProbeOutcome, error) {
	ctx, cancel := context.WithTimeout(ctx, 3*time.Minute)
	defer cancel()
	r, closeFn, err := s.dialServer(ctx, probeID)
	if err != nil {
		return nil, err
	}
	defer closeFn()
	out := make([]*store.ProbeOutcome, len(targets))
	sem := make(chan struct{}, probeParallel)
	var wg sync.WaitGroup
	for i, t := range targets {
		wg.Add(1)
		sem <- struct{}{}
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			stdout, _, _, err := r.Run(ctx, cmds[i])
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
func (s *Server) alertUnreachablePlacements(ctx context.Context) error {
	list, err := store.UnreachablePlacements(ctx, s.DB)
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
		summary := fmt.Sprintf("%s %d 个部署位国内不可达", u.ServerName, j-i)
		body := fmt.Sprintf("%s\n所有国内探测机连续 %d 轮失败：\n%s", cause, store.ProbeFailRounds, strings.Join(lines, "\n"))
		key := store.AlertKey{Type: alertUnreachable, ResourceType: "server", ResourceID: u.ServerID, ResourceKey: u.ServerName, Fingerprint: strconv.FormatInt(u.ServerID, 10)}
		if err := s.raise(ctx, key, "critical", float64(j-i), 0, summary, time.Hour,
			notify.Message{Title: "AirOpsCat 部署位不可达 · " + u.ServerName, Body: body, Level: "timeSensitive"}); err != nil {
			return err
		}
		i = j
	}
	resolved, err := store.AlertResolveMissing(ctx, s.DB, alertUnreachable, "server", keep, now)
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
