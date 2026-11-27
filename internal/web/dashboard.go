package web

import (
	"cmp"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/geo"
	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/store"
)

// dashAlert 是概览中的一条告警，带处理入口与相对时间。
type dashAlert struct {
	alertView
	Link, LinkText, Ago string
}

// dashExpiry 是到期账户或续费项，Left 为剩余时间文本，Urgent 表示 24 小时内（续费项为今天）到期。
type dashExpiry struct {
	Left   string
	Urgent bool
}

type dashAccount struct {
	store.DashboardExpiry
	dashExpiry
}

type dashRenewal struct {
	store.DashboardRenewal
	dashExpiry
}

// dashFlowCountry 表示全球地图或下钻左列中的一个国家节点。
type dashFlowCountry struct {
	Code           string  `json:"code"`
	Name           string  `json:"name"`
	Lon            float64 `json:"lon"`
	Lat            float64 `json:"lat"`
	Accounts       int     `json:"accounts"`
	Connections    int64   `json:"connections"`
	PlacementCount int     `json:"placementCount,omitempty"`
	ServerCount    int     `json:"serverCount,omitempty"`
}

// dashCountryFlow 表示全球地图上「账户国家 → 服务器国家」的一条聚合连线。
type dashCountryFlow struct {
	From        string `json:"from"`
	To          string `json:"to"`
	Accounts    int    `json:"accounts"`
	Connections int64  `json:"connections"`
	Weight      int    `json:"weight"`
}

// dashFlowPlacement 表示下钻中列的一个在线部署位（节点 × 服务器）。
type dashFlowPlacement struct {
	Key             string   `json:"key"`
	NodeID          int64    `json:"nodeId"`
	ServerIP        string   `json:"serverIp"`
	SubName         string   `json:"subName"`
	Domain          string   `json:"domain"`
	DomainTitle     string   `json:"domainTitle"`
	Port            int      `json:"port"`
	Accounts        int      `json:"accounts"`
	Connections     int64    `json:"connections"`
	ConnKnown       bool     `json:"connKnown"`
	EditURL         string   `json:"editUrl"`
	ClientCountries []string `json:"clientCountries"`
}

// dashFlowServer 表示下钻右列的一台在线服务器。
type dashFlowServer struct {
	ID          int64  `json:"id"`
	Name        string `json:"name"`
	IP          string `json:"ip"`
	Country     string `json:"country"`
	Accounts    int    `json:"accounts"`
	Connections int64  `json:"connections"`
	EditURL     string `json:"editUrl"`
}

// dashClientPlacementLink 表示下钻视图左→中连线（账户国家 → 部署位）。
type dashClientPlacementLink struct {
	Country     string `json:"country"`
	Placement   string `json:"placement"`
	Accounts    int    `json:"accounts"`
	Connections int64  `json:"connections"`
	Weight      int    `json:"weight"`
}

// dashPlacementServerLink 表示下钻视图中→右连线（部署位 → 服务器）。
type dashPlacementServerLink struct {
	Placement   string `json:"placement"`
	ServerIP    string `json:"serverIp"`
	Accounts    int    `json:"accounts"`
	Connections int64  `json:"connections"`
	Weight      int    `json:"weight"`
}

// dashCountryDetail 是点击某服务器国家后的下钻视图数据。
type dashCountryDetail struct {
	Country         dashFlowCountry           `json:"country"`
	ClientCountries []dashFlowCountry         `json:"clientCountries"`
	Placements      []dashFlowPlacement       `json:"placements"`
	Servers         []dashFlowServer          `json:"servers"`
	ClientLinks     []dashClientPlacementLink `json:"clientLinks"`
	ServerLinks     []dashPlacementServerLink `json:"serverLinks"`
}

// dashTopology 是概览「运行情况」全球地图与国家下钻拓扑图的渲染模型。
type dashTopology struct {
	ClientCountries []dashFlowCountry            `json:"clientCountries"`
	ServerCountries []dashFlowCountry            `json:"serverCountries"`
	CountryFlows    []dashCountryFlow            `json:"countryFlows"`
	Details         map[string]dashCountryDetail `json:"details"`
	PlacementCount  int                          `json:"placementCount"`
	ServerCount     int                          `json:"serverCount"`
	DataJSON        string                       `json:"-"`
}

func newDashAlert(a store.Alert, now time.Time) dashAlert {
	name := alertTypeNames[a.Type]
	if name == "" {
		name = a.Type
	}
	v := dashAlert{alertView: alertView{Alert: a, TypeName: name}, Link: "/admin/alerts?status=" + a.Status, LinkText: "查看"}
	id := strconv.FormatInt(a.ResourceID, 10)
	switch {
	case a.ResourceType == "account" && (a.Type == alertConnection || a.Type == alertQuota):
		v.Link, v.LinkText = "/admin/accounts/"+id+"/edit", "账户"
	case a.ResourceType == "server" && a.Type == alertMonitorLoad:
		v.Link, v.LinkText = "/admin/monitor/"+id, "监控"
	case a.ResourceType == "server":
		v.Link, v.LinkText = "/admin/servers/"+id+"/edit", "服务器"
	}
	if t, err := store.ParseTime(a.LastTime); err == nil {
		v.Ago = relativeTime(t, now)
	}
	return v
}

// accountLeft 描述账户距离到期的剩余时间。
func accountLeft(expires string, now time.Time) dashExpiry {
	t, err := store.ParseTime(expires)
	if err != nil {
		return dashExpiry{}
	}
	d := t.Sub(now)
	switch {
	case d < time.Hour:
		return dashExpiry{"1 小时内", true}
	case d < 24*time.Hour:
		return dashExpiry{fmt.Sprintf("%d 小时后", int(d/time.Hour)), true}
	}
	return dashExpiry{fmt.Sprintf("%d 天后", int(d/(24*time.Hour))), false}
}

// renewalLeft 按上海日期描述续费项剩余天数。
func renewalLeft(expire string, now time.Time) dashExpiry {
	t, err := time.ParseInLocation("2006-01-02", expire, store.Shanghai)
	if err != nil {
		return dashExpiry{}
	}
	local := now.In(store.Shanghai)
	today := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, store.Shanghai)
	days := int(t.Sub(today).Hours()+12) / 24
	if days <= 0 {
		return dashExpiry{"今天", true}
	}
	return dashExpiry{fmt.Sprintf("%d 天后", days), false}
}

func appendUniqueDash(list []string, v string) []string {
	if v == "" || slices.Contains(list, v) {
		return list
	}
	return append(list, v)
}

func formatDomainSummary(hosts []string) (display, title string) {
	if len(hosts) == 0 {
		return "", ""
	}
	if len(hosts) == 1 {
		return hosts[0], hosts[0]
	}
	return fmt.Sprintf("%s +%d", hosts[0], len(hosts)-1), strings.Join(hosts, "、")
}

func topologyWeight(accounts, maxAccounts int) int {
	if maxAccounts <= 1 || accounts <= 1 {
		return 1
	}
	r := float64(accounts) / float64(maxAccounts)
	switch {
	case r >= 0.75:
		return 4
	case r >= 0.45:
		return 3
	case r >= 0.2:
		return 2
	default:
		return 1
	}
}

// scale 按展示倍数缩放账户数与连接数；须在排序与连线权重计算之后调用，部署位与服务器数量不缩放。
func (t *dashTopology) scale(m store.Multiplier) {
	apply := func(accounts *int, connections *int64) {
		*accounts, *connections = m.ScaleInt(*accounts), m.Scale(*connections)
	}
	countries := func(list []dashFlowCountry) {
		for i := range list {
			apply(&list[i].Accounts, &list[i].Connections)
		}
	}
	countries(t.ClientCountries)
	countries(t.ServerCountries)
	for i := range t.CountryFlows {
		apply(&t.CountryFlows[i].Accounts, &t.CountryFlows[i].Connections)
	}
	for code, d := range t.Details {
		apply(&d.Country.Accounts, &d.Country.Connections)
		countries(d.ClientCountries)
		for i := range d.Placements {
			apply(&d.Placements[i].Accounts, &d.Placements[i].Connections)
		}
		for i := range d.Servers {
			apply(&d.Servers[i].Accounts, &d.Servers[i].Connections)
		}
		for i := range d.ClientLinks {
			apply(&d.ClientLinks[i].Accounts, &d.ClientLinks[i].Connections)
		}
		for i := range d.ServerLinks {
			apply(&d.ServerLinks[i].Accounts, &d.ServerLinks[i].Connections)
		}
		t.Details[code] = d
	}
}

// buildDashTopology 从实时在线快照提取两级运行情况图数据：
// 层级一为全球地图（账户国家 → 服务器国家）；层级二为服务器国家下钻（账户国家 → 部署位 → 服务器）。
func (s *Server) buildDashTopology(ctx context.Context, snap guard.Snapshot, pageURL string, m store.Multiplier) (dashTopology, error) {
	type placementKey struct {
		nodeID   int64
		serverIP string
	}
	type tagEvent struct {
		account   string
		nodeID    int64
		serverIP  string
		conns     int64
		connKnown bool
		srcCodes  []string
	}

	var events []tagEvent
	nodeIDSet := map[int64]bool{}
	serverIPSet := map[string]bool{}
	for accKey, acc := range snap.Accounts {
		for _, t := range acc.Tags {
			if !strings.HasPrefix(t.Tag, "node_") || t.ServerIP == "" {
				continue
			}
			nodeID, err := strconv.ParseInt(strings.TrimPrefix(t.Tag, "node_"), 10, 64)
			if err != nil || nodeID <= 0 {
				continue
			}
			var srcCodes []string
			for _, cip := range t.IPs {
				srcCodes = appendUniqueDash(srcCodes, geo.LookupIP(cip).Code)
			}
			if len(srcCodes) == 0 {
				srcCodes = []string{geo.UnknownCountry.Code}
			}
			events = append(events, tagEvent{
				account:   accKey,
				nodeID:    nodeID,
				serverIP:  t.ServerIP,
				conns:     t.Connections,
				connKnown: t.ConnKnown,
				srcCodes:  srcCodes,
			})
			nodeIDSet[nodeID] = true
			serverIPSet[t.ServerIP] = true
		}
	}
	if len(events) == 0 {
		return dashTopology{}, nil
	}

	nodeIDs := make([]int64, 0, len(nodeIDSet))
	for id := range nodeIDSet {
		nodeIDs = append(nodeIDs, id)
	}
	serverIPs := make([]string, 0, len(serverIPSet))
	for ip := range serverIPSet {
		serverIPs = append(serverIPs, ip)
	}

	meta, err := store.OnlineTopologyMeta(ctx, s.DB, nodeIDs, serverIPs)
	if err != nil {
		return dashTopology{}, err
	}

	// 解析每台服务器所属国家
	serverCountry := make(map[string]geo.Country, len(serverIPs))
	serverByIP := make(map[string]dashFlowServer, len(serverIPs))
	for _, ip := range serverIPs {
		sm := meta.Servers[ip]
		sv := snap.Servers[ip]
		name := sm.Name
		if name == "" {
			name = ip
		}
		c := geo.ServerCountry(sm.Region, name, ip)
		serverCountry[ip] = c
		editURL := ""
		if sm.ID > 0 {
			editURL = navURL(fmt.Sprintf("/admin/servers/%d/edit", sm.ID), pageURL)
		}
		serverByIP[ip] = dashFlowServer{
			ID:          sm.ID,
			Name:        name,
			IP:          ip,
			Country:     c.Code,
			Accounts:    sv.Accounts,
			Connections: sv.Connections,
			EditURL:     editURL,
		}
	}

	type accAgg struct {
		accounts  map[string]bool
		conns     int64
		connKnown bool
	}
	addAgg := func(m map[string]*accAgg, key, acc string, conns int64, connKnown bool) {
		a := m[key]
		if a == nil {
			a = &accAgg{accounts: map[string]bool{}, connKnown: true}
			m[key] = a
		}
		a.accounts[acc] = true
		a.conns += conns
		a.connKnown = a.connKnown && connKnown
	}

	// 全局聚合：账户国家、服务器国家、国家间连线
	globalClients := map[string]*accAgg{}
	globalServers := map[string]*accAgg{}
	countryFlowKey := func(from, to string) string { return from + "->" + to }
	globalFlows := map[string]*accAgg{}

	// 按部署位聚合
	placementAgg := map[placementKey]*accAgg{}
	placementClients := map[placementKey]map[string]*accAgg{}

	// 按服务器国家分组：账户国家汇总、涉及的部署位和服务器集合
	detailClients := map[string]map[string]*accAgg{}
	detailPlacements := map[string]map[placementKey]bool{}
	detailServers := map[string]map[string]bool{}

	for _, ev := range events {
		dstCode := serverCountry[ev.serverIP].Code
		pk := placementKey{nodeID: ev.nodeID, serverIP: ev.serverIP}

		pa := placementAgg[pk]
		if pa == nil {
			pa = &accAgg{accounts: map[string]bool{}, connKnown: true}
			placementAgg[pk] = pa
		}
		pa.accounts[ev.account] = true
		pa.conns += ev.conns
		pa.connKnown = pa.connKnown && ev.connKnown

		addAgg(globalServers, dstCode, ev.account, ev.conns, ev.connKnown)
		if detailPlacements[dstCode] == nil {
			detailPlacements[dstCode] = map[placementKey]bool{}
			detailServers[dstCode] = map[string]bool{}
			detailClients[dstCode] = map[string]*accAgg{}
		}
		detailPlacements[dstCode][pk] = true
		detailServers[dstCode][ev.serverIP] = true

		if placementClients[pk] == nil {
			placementClients[pk] = map[string]*accAgg{}
		}
		for _, srcCode := range ev.srcCodes {
			addAgg(globalClients, srcCode, ev.account, ev.conns, ev.connKnown)
			addAgg(globalFlows, countryFlowKey(srcCode, dstCode), ev.account, ev.conns, ev.connKnown)
			addAgg(detailClients[dstCode], srcCode, ev.account, ev.conns, ev.connKnown)
			addAgg(placementClients[pk], srcCode, ev.account, ev.conns, ev.connKnown)
		}
	}

	// 组装全球源国家（账户国家）
	clientCountries := make([]dashFlowCountry, 0, len(globalClients))
	for code, a := range globalClients {
		c := geo.CountryByCode(code)
		clientCountries = append(clientCountries, dashFlowCountry{
			Code:        c.Code,
			Name:        c.Name,
			Lon:         c.Lon,
			Lat:         c.Lat,
			Accounts:    len(a.accounts),
			Connections: a.conns,
		})
	}
	slices.SortStableFunc(clientCountries, func(a, b dashFlowCountry) int {
		if c := cmp.Compare(b.Accounts, a.Accounts); c != 0 {
			return c
		}
		if c := cmp.Compare(b.Connections, a.Connections); c != 0 {
			return c
		}
		return cmp.Compare(a.Code, b.Code)
	})

	// 组装全球目标国家（服务器国家）
	serverCountries := make([]dashFlowCountry, 0, len(globalServers))
	serverCountryByCode := make(map[string]dashFlowCountry, len(globalServers))
	for code, a := range globalServers {
		c := geo.CountryByCode(code)
		item := dashFlowCountry{
			Code:           c.Code,
			Name:           c.Name,
			Lon:            c.Lon,
			Lat:            c.Lat,
			Accounts:       len(a.accounts),
			Connections:    a.conns,
			PlacementCount: len(detailPlacements[code]),
			ServerCount:    len(detailServers[code]),
		}
		serverCountries = append(serverCountries, item)
		serverCountryByCode[code] = item
	}
	slices.SortStableFunc(serverCountries, func(a, b dashFlowCountry) int {
		if c := cmp.Compare(b.Accounts, a.Accounts); c != 0 {
			return c
		}
		if c := cmp.Compare(b.Connections, a.Connections); c != 0 {
			return c
		}
		return cmp.Compare(a.Code, b.Code)
	})

	// 组装全球国家连线
	maxFlowAcc := 0
	for _, a := range globalFlows {
		if n := len(a.accounts); n > maxFlowAcc {
			maxFlowAcc = n
		}
	}
	countryFlows := make([]dashCountryFlow, 0, len(globalFlows))
	for key, a := range globalFlows {
		from, to, _ := strings.Cut(key, "->")
		countryFlows = append(countryFlows, dashCountryFlow{
			From:        from,
			To:          to,
			Accounts:    len(a.accounts),
			Connections: a.conns,
			Weight:      topologyWeight(len(a.accounts), maxFlowAcc),
		})
	}
	slices.SortStableFunc(countryFlows, func(a, b dashCountryFlow) int {
		if c := cmp.Compare(b.Accounts, a.Accounts); c != 0 {
			return c
		}
		if c := cmp.Compare(b.Connections, a.Connections); c != 0 {
			return c
		}
		if c := cmp.Compare(a.From, b.From); c != 0 {
			return c
		}
		return cmp.Compare(a.To, b.To)
	})

	// 组装每个服务器国家的下钻详情（账户国家 → 部署位 → 服务器）
	details := make(map[string]dashCountryDetail, len(serverCountries))
	for _, sc := range serverCountries {
		dstCode := sc.Code

		// 1) 右列：该国家的服务器
		srvs := make([]dashFlowServer, 0, len(detailServers[dstCode]))
		for ip := range detailServers[dstCode] {
			srvs = append(srvs, serverByIP[ip])
		}
		slices.SortStableFunc(srvs, func(a, b dashFlowServer) int {
			if c := cmp.Compare(b.Accounts, a.Accounts); c != 0 {
				return c
			}
			if c := cmp.Compare(b.Connections, a.Connections); c != 0 {
				return c
			}
			if c := cmp.Compare(a.Name, b.Name); c != 0 {
				return c
			}
			return cmp.Compare(a.IP, b.IP)
		})
		srvRank := make(map[string]int, len(srvs))
		for i, sv := range srvs {
			srvRank[sv.IP] = i
		}

		// 2) 左列：访问该服务器国家的账户国家
		cls := make([]dashFlowCountry, 0, len(detailClients[dstCode]))
		for srcCode, a := range detailClients[dstCode] {
			c := geo.CountryByCode(srcCode)
			cls = append(cls, dashFlowCountry{
				Code:        c.Code,
				Name:        c.Name,
				Lon:         c.Lon,
				Lat:         c.Lat,
				Accounts:    len(a.accounts),
				Connections: a.conns,
			})
		}
		slices.SortStableFunc(cls, func(a, b dashFlowCountry) int {
			if c := cmp.Compare(b.Accounts, a.Accounts); c != 0 {
				return c
			}
			if c := cmp.Compare(b.Connections, a.Connections); c != 0 {
				return c
			}
			return cmp.Compare(a.Code, b.Code)
		})
		clientRank := make(map[string]int, len(cls))
		for i, cl := range cls {
			clientRank[cl.Code] = i
		}

		// 3) 中列：该国家的在线部署位
		pls := make([]dashFlowPlacement, 0, len(detailPlacements[dstCode]))
		maxPLAcc := 0
		maxCLAcc := 0
		for pk := range detailPlacements[dstCode] {
			pa := placementAgg[pk]
			if len(pa.accounts) > maxPLAcc {
				maxPLAcc = len(pa.accounts)
			}
			for _, ca := range placementClients[pk] {
				if len(ca.accounts) > maxCLAcc {
					maxCLAcc = len(ca.accounts)
				}
			}
			nm := meta.Nodes[pk.nodeID]
			subName := nm.SubNamesByServer[pk.serverIP]
			if subName == "" && len(nm.FallbackSubNames) > 0 {
				subName = nm.FallbackSubNames[0]
			}
			if subName == "" {
				if nm.Name != "" {
					subName = store.NodeNameWithNumber(nm.Name)
				} else {
					subName = fmt.Sprintf("节点 #%d", pk.nodeID)
				}
			}
			var hosts []string
			for _, h := range nm.PointedHostsByServer[pk.serverIP] {
				hosts = appendUniqueDash(hosts, h)
			}
			if h := nm.HostsByServer[pk.serverIP]; h != "" {
				hosts = appendUniqueDash(hosts, h)
			}
			if len(hosts) == 0 {
				hosts = nm.FallbackHosts
			}
			domain, domainTitle := formatDomainSummary(hosts)

			cCodes := make([]string, 0, len(placementClients[pk]))
			for code := range placementClients[pk] {
				cCodes = append(cCodes, code)
			}
			slices.SortStableFunc(cCodes, func(a, b string) int {
				return cmp.Compare(clientRank[a], clientRank[b])
			})

			pls = append(pls, dashFlowPlacement{
				Key:             fmt.Sprintf("%d@%s", pk.nodeID, pk.serverIP),
				NodeID:          pk.nodeID,
				ServerIP:        pk.serverIP,
				SubName:         subName,
				Domain:          domain,
				DomainTitle:     domainTitle,
				Port:            nm.Port,
				Accounts:        len(pa.accounts),
				Connections:     pa.conns,
				ConnKnown:       pa.connKnown,
				EditURL:         navURL(fmt.Sprintf("/admin/nodes/%d/edit", pk.nodeID), pageURL),
				ClientCountries: cCodes,
			})
		}
		// 按目标服务器顺序 + 在线账号数降序排列，减少中→右连线交叉
		slices.SortStableFunc(pls, func(a, b dashFlowPlacement) int {
			if c := cmp.Compare(srvRank[a.ServerIP], srvRank[b.ServerIP]); c != 0 {
				return c
			}
			if c := cmp.Compare(b.Accounts, a.Accounts); c != 0 {
				return c
			}
			if c := cmp.Compare(b.Connections, a.Connections); c != 0 {
				return c
			}
			return cmp.Compare(a.NodeID, b.NodeID)
		})

		// 4) 两段连线：账户国家 → 部署位、部署位 → 服务器
		var clientLinks []dashClientPlacementLink
		var serverLinks []dashPlacementServerLink
		for _, pl := range pls {
			pk := placementKey{nodeID: pl.NodeID, serverIP: pl.ServerIP}
			for _, srcCode := range pl.ClientCountries {
				ca := placementClients[pk][srcCode]
				clientLinks = append(clientLinks, dashClientPlacementLink{
					Country:     srcCode,
					Placement:   pl.Key,
					Accounts:    len(ca.accounts),
					Connections: ca.conns,
					Weight:      topologyWeight(len(ca.accounts), maxCLAcc),
				})
			}
			serverLinks = append(serverLinks, dashPlacementServerLink{
				Placement:   pl.Key,
				ServerIP:    pl.ServerIP,
				Accounts:    pl.Accounts,
				Connections: pl.Connections,
				Weight:      topologyWeight(pl.Accounts, maxPLAcc),
			})
		}

		details[dstCode] = dashCountryDetail{
			Country:         sc,
			ClientCountries: cls,
			Placements:      pls,
			Servers:         srvs,
			ClientLinks:     clientLinks,
			ServerLinks:     serverLinks,
		}
	}

	topo := dashTopology{
		ClientCountries: clientCountries,
		ServerCountries: serverCountries,
		CountryFlows:    countryFlows,
		Details:         details,
		PlacementCount:  len(placementAgg),
		ServerCount:     len(serverIPs),
	}
	topo.scale(m)
	raw, _ := json.Marshal(topo)
	topo.DataJSON = string(raw)
	return topo, nil
}

func (s *Server) dashboard(w http.ResponseWriter, r *http.Request) {
	days, _ := strconv.Atoi(r.URL.Query().Get("days"))
	if days != 3 && days != 7 {
		days = 1
	}
	now := time.Now()
	ctx := r.Context()
	stats, err := store.DashboardLoad(ctx, s.DB, now, days)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	snap, err := s.onlineSnapshot(ctx)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	local := now.In(store.Shanghai)
	monthStart := time.Date(local.Year(), local.Month(), 1, 0, 0, 0, 0, store.Shanghai)
	monthEnd := monthStart.AddDate(0, 1, 0).Add(-time.Second)
	money, err := store.TransactionSummary(ctx, s.DB, store.TransactionFilter{StartDate: &monthStart, EndDate: &monthEnd})
	if err != nil {
		s.fail(w, r, err)
		return
	}

	active := make([]dashAlert, len(stats.ActiveAlerts))
	for i, a := range stats.ActiveAlerts {
		active[i] = newDashAlert(a, now)
	}
	accounts := make([]dashAccount, len(stats.ExpiringAccounts))
	for i, a := range stats.ExpiringAccounts {
		accounts[i] = dashAccount{a, accountLeft(a.Expires, now)}
	}
	renewals := make([]dashRenewal, len(stats.ExpiringRenewals))
	for i, e := range stats.ExpiringRenewals {
		renewals[i] = dashRenewal{e, renewalLeft(e.Expire, now)}
	}
	todo := len(active) + len(accounts) + len(renewals)
	if stats.PendingPlacements > 0 {
		todo++
	}
	if stats.AlertsAcknowledged > 0 {
		todo++
	}

	var latest time.Time
	for _, sv := range snap.Servers {
		if sv.At.After(latest) {
			latest = sv.At
		}
	}
	d := s.pageData(r, "概览", "dashboard")
	pageURL, _ := d["PageURL"].(string)
	m := store.StatsMultiplier(ctx, s.DB)
	topo, err := s.buildDashTopology(ctx, snap, pageURL, m)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	for _, n := range []*int{&stats.AccountsActive, &stats.AccountsTotal, &stats.AccountsDisabled, &stats.AccountsExpired} {
		*n = m.ScaleInt(*n)
	}
	d["Stats"], d["Days"], d["Online"] = stats, days, s.onlineSummaryFor(snap, m)
	d["ActiveAlerts"], d["Accounts"], d["Renewals"], d["Todo"] = active, accounts, renewals, todo
	d["Topology"] = topo
	d["Money"], d["Month"] = m.Totals(money), int(local.Month())
	if !latest.IsZero() {
		d["OnlineAt"] = latest.In(store.Shanghai).Format("15:04")
	}
	s.render(w, "dashboard", http.StatusOK, d)
}
