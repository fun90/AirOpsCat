package web

import (
	"context"
	"fmt"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/store"
)

// onlineSnapshot 返回 guard 的实时在线视图；未初始化时为空，页面显示“暂无上报”。
func (s *Server) onlineSnapshot(ctx context.Context) (guard.Snapshot, error) {
	if s.Guard == nil {
		return guard.Snapshot{}, nil
	}
	return s.Guard.Snapshot(ctx)
}

// accountOnlineText 供列表使用；没有任何新鲜上报时不能说成“离线”。
func accountOnlineText(snap guard.Snapshot, accountNo string) string {
	if len(snap.Servers) == 0 {
		return "暂无上报"
	}
	a, ok := snap.Accounts[strings.ToLower(accountNo)]
	if !ok {
		return "未在线"
	}
	return fmt.Sprintf("在线 · %d 连接 · %d IP", a.Connections, a.IPs)
}

// nodeOnlineText 汇总节点标签在已部署服务器上的在线情况，并说明覆盖不全。
func nodeOnlineText(snap guard.Snapshot, id int64, ips []string) string {
	covered := 0
	for _, ip := range ips {
		if sv, ok := snap.Servers[ip]; ok && sv.TagsKnown {
			covered++
		}
	}
	if len(ips) == 0 || covered == 0 {
		return "暂无上报"
	}
	t := snap.Tags["node_"+strconv.FormatInt(id, 10)]
	text := fmt.Sprintf("%d 账户", t.Accounts)
	if t.Accounts == 0 || t.ConnKnown {
		text += fmt.Sprintf(" · %d 连接", t.Connections)
	}
	if covered < len(ips) {
		text += "（部分服务器无上报）"
	}
	return text
}

func serverOnlineText(snap guard.Snapshot, ip string) string {
	sv, ok := snap.Servers[ip]
	if !ok {
		return "暂无上报"
	}
	return fmt.Sprintf("%d 账户 · %d 连接", sv.Accounts, sv.Connections)
}

// relativeTime 把过去的时间说成“刚刚 / N 分钟前 / N 小时前 / N 天前”；未来时间（时钟偏差）按刚刚处理。
func relativeTime(t, now time.Time) string {
	d := now.Sub(t)
	switch {
	case d < time.Minute:
		return "刚刚"
	case d < time.Hour:
		return fmt.Sprintf("%d 分钟前", int(d/time.Minute))
	case d < 24*time.Hour:
		return fmt.Sprintf("%d 小时前", int(d/time.Hour))
	default:
		return fmt.Sprintf("%d 天前", int(d/(24*time.Hour)))
	}
}

// lastOnlineText 说明账户最后一次在线；在线记录只保留 30 天，无记录不等于从未上线。
func lastOnlineText(t *time.Time, now time.Time) string {
	if t == nil {
		return "30 天内无在线记录"
	}
	return "最后在线 " + relativeTime(*t, now)
}

type onlineRow struct {
	Server, Node, Conns, At string
	IPs                     string
}

// accountOnlineRows 把账户在线明细换成展示行；服务器与节点名称缺失时回退到 IP / 标签。
func (s *Server) accountOnlineRows(ctx context.Context, snap guard.Snapshot, accountNo string) ([]onlineRow, error) {
	a, ok := snap.Accounts[strings.ToLower(accountNo)]
	if !ok {
		return nil, nil
	}
	servers, nodes, err := store.DisplayNames(ctx, s.DB)
	if err != nil {
		return nil, err
	}
	serverName := func(ip string) string {
		if n := servers[ip]; n != "" {
			return n + "（" + ip + "）"
		}
		return ip
	}
	at := func(t time.Time) string { return store.FormatTime(t) }
	reported := map[string]time.Time{}
	for _, n := range a.Nodes {
		reported[n.NodeIP] = n.At
	}
	var rows []onlineRow
	tagged := map[string]bool{}
	for _, t := range a.Tags {
		node := t.Tag
		if id, err := strconv.ParseInt(strings.TrimPrefix(t.Tag, "node_"), 10, 64); err == nil && strings.HasPrefix(t.Tag, "node_") && nodes[id] != "" {
			node = nodes[id]
		} else if node == "" {
			node = "未知节点"
		}
		conns := "—"
		if t.ConnKnown {
			conns = strconv.FormatInt(t.Connections, 10)
		}
		rows = append(rows, onlineRow{serverName(t.ServerIP), node, conns, at(reported[t.ServerIP]), strings.Join(t.IPs, "、")})
		tagged[t.ServerIP] = true
	}
	for _, n := range a.Nodes {
		if !tagged[n.NodeIP] {
			rows = append(rows, onlineRow{serverName(n.NodeIP), "—", strconv.FormatInt(n.Connections, 10), at(n.At), strings.Join(n.IPs, "、")})
		}
	}
	return rows, nil
}

// onlineSummary 是概览用的实时汇总。
type onlineSummary struct {
	Accounts, Servers int
	Connections       int64
}

func (s *Server) onlineSummaryFor(snap guard.Snapshot) onlineSummary {
	return onlineSummary{len(snap.Accounts), len(snap.Servers), snap.TotalConnections}
}
