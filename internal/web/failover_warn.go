package web

import (
	"context"
	"fmt"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/notify"
	"github.com/fun90/airopscat/internal/store"
)

// ipOnly 索引订阅里直连服务器 IP 或外部自定义域名的部署位，供切换确认框提示：DNS 切换改不到这些订阅条目。
type ipOnly struct {
	byID     map[int64]store.IPOnlyPlacement
	byServer map[int64][]store.IPOnlyPlacement
	byNode   map[int64][]store.IPOnlyPlacement
}

func (s *Server) loadIPOnly(ctx context.Context) (ipOnly, error) {
	list, err := store.IPOnlyPlacements(ctx, s.DB)
	x := ipOnly{byID: map[int64]store.IPOnlyPlacement{}, byServer: map[int64][]store.IPOnlyPlacement{}, byNode: map[int64][]store.IPOnlyPlacement{}}
	for _, p := range list {
		x.byID[p.ID] = p
		x.byServer[p.ServerID] = append(x.byServer[p.ServerID], p)
		x.byNode[p.NodeID] = append(x.byNode[p.NodeID], p)
	}
	return x, err
}

// serverWarn 是切走整台服务器时的提示：列出在订阅中直连该服务器 IP 的节点。
func (x ipOnly) serverWarn(serverID int64) string {
	var names []string
	for _, p := range x.byServer[serverID] {
		names = append(names, p.NodeName)
	}
	if names == nil {
		return ""
	}
	return "以下节点在订阅中直连本服务器 IP 或外部自定义域名，切走后仍会连到本服务器：" + strings.Join(names, "、")
}

// switchWarn 是把节点切到 target 部署位时的提示：其余部署位中在订阅里直连 IP 的服务器。
func (x ipOnly) switchWarn(nodeID, target int64) string {
	var names []string
	for _, p := range x.byNode[nodeID] {
		if p.ID != target {
			names = append(names, p.Server)
		}
	}
	if names == nil {
		return ""
	}
	return "以下部署位在订阅中直连服务器 IP 或外部自定义域名，切换后仍会连原服务器：" + strings.Join(names, "、")
}

// pointedWarn 是批量切换的提示：节点当前指向的部署位里在订阅中直连 IP 的服务器。
func (x ipOnly) pointedWarn(pointed []store.PointedPlacement) string {
	var names []string
	for _, p := range pointed {
		if v, ok := x.byID[p.ID]; ok {
			names = append(names, v.Server)
		}
	}
	return strings.Join(names, "、")
}

// alertIPOnly 在服务器被自动切走后，告警仍在订阅中直连其 IP 的部署位：这些流量不随 DNS 切走。
// 手动切走已在操作时确认，不再告警。
func (s *Server) alertIPOnly(ctx context.Context) error {
	servers, err := store.AutoSwitchedServers(ctx, s.DB)
	if err != nil {
		return err
	}
	x, err := s.loadIPOnly(ctx)
	if err != nil {
		return err
	}
	keep := map[int64]bool{}
	for sid := range servers {
		pls := x.byServer[sid]
		if len(pls) == 0 {
			continue
		}
		keep[sid] = true
		names := make([]string, len(pls))
		for i, p := range pls {
			names[i] = p.NodeName
		}
		summary := fmt.Sprintf("%s 已自动切走，但 %d 个部署位在订阅中直连服务器 IP 或外部自定义域名，流量未切走：%s", pls[0].Server, len(pls), strings.Join(names, "、"))
		key := store.AlertKey{Type: "dns-ip-only", ResourceType: "server", ResourceID: sid, ResourceKey: pls[0].Server, Fingerprint: pls[0].Server}
		if err := s.raise(ctx, key, "warning", float64(len(pls)), 1, summary, 24*time.Hour, notify.Message{Title: "AirOpsCat 自动切换未覆盖直连 IP", Body: summary}); err != nil {
			return err
		}
	}
	_, err = store.AlertResolveMissing(ctx, s.DB, "dns-ip-only", "server", keep, time.Now())
	return err
}
