package web

import (
	"context"
	"fmt"
	"log/slog"
	"slices"

	"github.com/fun90/airopscat/internal/store"
)

// autoDeployDefault 是配置行缺失时的取值，与 store.SettingDefaults 一致；仅测试改为 false，避免旧用例触发后台部署。
var autoDeployDefault = store.SettingDefaults[store.AutoDeployKey].Value == "true"

// autoDeployOn 读取「操作后自动部署」开关。
func (s *Server) autoDeployOn(ctx context.Context) bool {
	return s.configBool(ctx, store.AutoDeployKey, autoDeployDefault)
}

// autoDeploy 在开关开启时，把候选服务器中确实需要部署（PendingDeployServers）的提交后台部署，返回任务 ID 与台数；
// 关闭、没有需要部署的服务器或读取失败时返回 0。候选应为操作前后相关服务器的并集，避免重启无关服务器。
func (s *Server) autoDeploy(ctx context.Context, reason string, candidates []int64) (jobID int64, n int) {
	if len(candidates) == 0 || !s.autoDeployOn(ctx) {
		return 0, 0
	}
	ids, err := s.pendingAmong(ctx, candidates)
	if err != nil {
		slog.Warn("自动部署读取待部署服务器失败", "reason", reason, "err", err)
		return 0, 0
	}
	return s.enqueueAuto(reason, ids)
}

// pendingAmong 返回候选中需要部署的启用服务器。
func (s *Server) pendingAmong(ctx context.Context, candidates []int64) ([]int64, error) {
	pending, err := store.PendingDeployServers(ctx, s.DB)
	if err != nil {
		return nil, err
	}
	var ids []int64
	for _, id := range pending {
		if slices.Contains(candidates, id) {
			ids = append(ids, id)
		}
	}
	return ids, nil
}

func (s *Server) enqueueAuto(reason string, ids []int64) (int64, int) {
	if len(ids) == 0 {
		return 0, 0
	}
	return s.EnqueueDeploy(reason, ids), len(ids)
}

// deployScope 收集一次操作前后相关服务器的并集，结束时只部署其中确实需要部署的服务器。
type deployScope struct {
	s          *Server
	ctx        context.Context
	on         bool
	candidates []int64
}

// beginDeploy 开始收集候选；开关关闭时只返回原有的手动部署提示。
func (s *Server) beginDeploy(ctx context.Context) *deployScope {
	return &deployScope{s: s, ctx: ctx, on: s.autoDeployOn(ctx)}
}

// add 加入候选服务器；fetch 出错只记日志，最终仍以待部署服务器为准。
func (sc *deployScope) add(ids []int64, err error) {
	if err != nil {
		slog.Warn("自动部署读取相关服务器失败", "err", err)
		return
	}
	sc.candidates = append(sc.candidates, ids...)
}

// account 加入账户经标签可用的服务器。
func (sc *deployScope) account(id int64) {
	if sc.on {
		sc.add(store.AccountDeployServers(sc.ctx, sc.s.DB, id))
	}
}

// node 加入节点及其引用方所在的服务器。
func (sc *deployScope) node(id int64) {
	if sc.on {
		sc.add(store.NodeRelatedServers(sc.ctx, sc.s.DB, id))
	}
}

// nodes 批量加入节点及其引用方所在的服务器。
func (sc *deployScope) nodes(ids []int64) {
	for _, id := range ids {
		sc.node(id)
	}
}

// server 加入引用该服务器上落地节点的服务器，self 为 true 时含服务器自身（迁移、报废）。
func (sc *deployScope) server(id int64, self bool) {
	if sc.on {
		sc.add(store.ServerRelatedServers(sc.ctx, sc.s.DB, id, self))
	}
}

// finish 提交后台部署并返回追加到提示末尾的文本：已入队时为「，已提交后台部署 N 台（任务 #ID）」；
// 开关开启但没有需要部署的服务器时为空；开关关闭时为 offHint（沿用原有的「需重新部署」提示）。
func (sc *deployScope) finish(reason, offHint string) string {
	if !sc.on {
		return offHint
	}
	pending, err := store.PendingDeployServers(sc.ctx, sc.s.DB)
	if err != nil {
		slog.Warn("自动部署读取待部署服务器失败", "reason", reason, "err", err)
		return offHint
	}
	var ids []int64
	for _, id := range pending {
		if slices.Contains(sc.candidates, id) {
			ids = append(ids, id)
		}
	}
	return deployNote(sc.s.enqueueAuto(reason, ids))
}

// deployNote 是自动部署入队后的提示后缀；未入队为空。
func deployNote(jobID int64, n int) string {
	if jobID == 0 {
		return ""
	}
	return fmt.Sprintf("，已提交后台部署 %d 台（任务 #%d）", n, jobID)
}
