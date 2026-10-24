// Package guard 保存单实例的跨节点在线快照；只有 Evaluate 推进连续超限计数。
package guard

import (
	"context"
	"database/sql"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

type AccountReport struct {
	AccountNo   string   `json:"accountNo"`
	Connections int64    `json:"connections"`
	IPs         []string `json:"ips"`
	// ActiveIPs 是本轮有流量的客户端 IP；nil 表示旧版 agent 未提供，按全部 IP 活跃处理。
	ActiveIPs []string `json:"activeIps"`
}
type Request struct {
	NodeIP                  string                     `json:"nodeIp"`
	GeneratedAtEpochSeconds int64                      `json:"generatedAtEpochSeconds"`
	Accounts                []AccountReport            `json:"accounts"`
	OnlineAccountIPs        []store.GuardOnlineAccount `json:"onlineAccountIps"`
}
type BlockedEntry struct {
	Reason string `json:"reason"`
	Total  int64  `json:"total"`
	Limit  int64  `json:"limit"`
}
type Response struct {
	SchemaVersion           int                     `json:"schemaVersion"`
	GeneratedAtEpochSeconds int64                   `json:"generatedAtEpochSeconds"`
	TTLSeconds              int64                   `json:"ttlSeconds"`
	BlockedAccounts         map[string]BlockedEntry `json:"blockedAccounts"`
}

// Stats 供统一通知任务使用，连接数和 IP 共用 Consecutive 与 Blocked。
type Stats struct {
	AccountNo        string
	Connections, IPs int64
	ActiveNodes      int
	ActiveIPs        int64 // 本轮有流量的去重 IP 数
	SustainedIPs     int64 // 连续活跃满重叠窗口的 IP 数，IP 超限只看它（或极端的 ActiveIPs）
	Consecutive      int
	Blocked          *BlockedEntry
}
type tagGroup struct {
	account   string
	tag       string
	ips       []string
	conns     int64
	connKnown bool
}

// serverTags 是某服务器最近一次按节点标签分组的在线快照；known=false 表示该次上报不含分组。
type serverTags struct {
	known  bool
	groups []tagGroup
}
type nodeStat struct {
	connections int64
	ips         []string
	active      []string
	at          time.Time
}

// ipSeen 记录某账户某 IP 连续活跃的起点和最近一次活跃时间。
type ipSeen struct{ since, last time.Time }

// ipGrace 容忍 IP 短暂无流量而不重置连续活跃起点。
const ipGrace = time.Minute

type Aggregator struct {
	mu          sync.Mutex
	db          *sql.DB
	now         func() time.Time
	table       map[string]map[string]nodeStat
	servers     map[string]time.Time // 每台服务器最近一次完整上报（含空报告）
	tags        map[string]serverTags
	seen        map[string]map[string]ipSeen
	consecutive map[string]int
	blocked     map[string]BlockedEntry
}

// New 不启动 goroutine；可选时钟在构造时注入，后续不得修改。
func New(db *sql.DB, clock ...func() time.Time) *Aggregator {
	now := time.Now
	if len(clock) > 0 && clock[0] != nil {
		now = clock[0]
	}
	return &Aggregator{db: db, now: now, table: map[string]map[string]nodeStat{}, servers: map[string]time.Time{}, tags: map[string]serverTags{}, seen: map[string]map[string]ipSeen{}, consecutive: map[string]int{}, blocked: map[string]BlockedEntry{}}
}
func (a *Aggregator) forgetUnknown(limits map[string]store.GuardLimit) {
	for key := range a.table {
		if _, ok := limits[key]; !ok {
			delete(a.table, key)
		}
	}
	for key := range a.seen {
		if _, ok := limits[key]; !ok {
			delete(a.seen, key)
		}
	}
	for key := range a.consecutive {
		if _, ok := limits[key]; !ok {
			delete(a.consecutive, key)
		}
	}
	for key := range a.blocked {
		if _, ok := limits[key]; !ok {
			delete(a.blocked, key)
		}
	}
}

// Report 覆盖该节点的完整快照，缺失账户从该节点删除，不改变连续计数。
func (a *Aggregator) Report(ctx context.Context, nodeIP string, reports []AccountReport) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	limits, _, err := store.LoadGuardState(ctx, a.db)
	if err != nil {
		return err
	}
	a.forgetUnknown(limits)
	for key, nodes := range a.table {
		delete(nodes, nodeIP)
		if len(nodes) == 0 {
			delete(a.table, key)
		}
	}
	now := a.now()
	a.servers[nodeIP] = now
	for _, r := range reports {
		key := strings.ToLower(r.AccountNo)
		if _, ok := limits[key]; !ok {
			continue
		}
		if a.table[key] == nil {
			a.table[key] = map[string]nodeStat{}
		}
		ips := make([]string, 0, len(r.IPs))
		for _, ip := range r.IPs {
			if ip != "" {
				ips = append(ips, ip)
			}
		}
		var active []string
		if r.ActiveIPs != nil {
			active = make([]string, 0, len(r.ActiveIPs))
			for _, ip := range r.ActiveIPs {
				if ip != "" {
					active = append(active, ip)
				}
			}
		} else {
			active = ips
		}
		a.table[key][nodeIP] = nodeStat{connections: max(0, r.Connections), ips: ips, active: active, at: now}
	}
	return nil
}

// ReportTags 覆盖该服务器按节点标签分组的在线快照；nil 视为旧版 agent 未提供分组。
func (a *Aggregator) ReportTags(ctx context.Context, nodeIP string, online []store.GuardOnlineAccount) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	limits, _, err := store.LoadGuardState(ctx, a.db)
	if err != nil {
		return err
	}
	st := serverTags{known: online != nil}
	for _, o := range online {
		key := strings.ToLower(o.AccountNo)
		if _, ok := limits[key]; !ok {
			continue
		}
		g := tagGroup{account: key, tag: o.NodeTag}
		ips := map[string]bool{}
		for _, ip := range o.ClientIPs {
			ips[ip] = true
		}
		for _, c := range o.Connections {
			if c.ClientIP != "" {
				ips[c.ClientIP] = true
			}
		}
		for ip := range ips {
			g.ips = append(g.ips, ip)
		}
		sort.Strings(g.ips)
		switch {
		case o.ConnectionCount != nil:
			g.conns, g.connKnown = max(0, *o.ConnectionCount), true
		case len(o.Connections) > 0:
			g.conns, g.connKnown = int64(len(o.Connections)), true
		}
		st.groups = append(st.groups, g)
	}
	a.tags[nodeIP] = st
	return nil
}

// Evaluate 每 10 秒由调度器调用一次；TTL 判定独立于 Evict 的执行顺序。
func (a *Aggregator) Evaluate(ctx context.Context) (map[string]Stats, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	limits, settings, err := store.LoadGuardState(ctx, a.db)
	if err != nil {
		return nil, err
	}
	a.forgetUnknown(limits)
	tracked := map[string]bool{}
	for key := range a.table {
		tracked[key] = true
	}
	for key := range a.consecutive {
		tracked[key] = true
	}
	for key := range a.blocked {
		tracked[key] = true
	}
	for key := range a.seen {
		tracked[key] = true
	}
	result := map[string]Stats{}
	now := a.now()
	for key := range tracked {
		l, exists := limits[key]
		if !exists {
			delete(a.consecutive, key)
			delete(a.blocked, key)
			delete(a.seen, key)
			continue
		}
		st := Stats{AccountNo: l.AccountNo}
		ips, active := map[string]bool{}, map[string]bool{}
		for _, n := range a.table[key] {
			if now.Sub(n.at) > settings.TTL {
				continue
			}
			st.Connections += n.connections
			st.ActiveNodes++
			for _, ip := range n.ips {
				ips[ip] = true
			}
			for _, ip := range n.active {
				active[ip] = true
			}
		}
		st.IPs, st.ActiveIPs = int64(len(ips)), int64(len(active))
		st.SustainedIPs = a.sustained(key, active, now, max(settings.TTL, ipGrace), settings.Overlap)
		var entry *BlockedEntry
		if l.MaxConnections > 0 && st.Connections > l.MaxConnections {
			entry = &BlockedEntry{"connections", st.Connections, l.MaxConnections}
		} else if l.MaxIPs > 0 && st.ActiveIPs >= 2*l.MaxIPs {
			// 远超上限不等重叠窗口，直接判定
			entry = &BlockedEntry{"ips", st.ActiveIPs, l.MaxIPs}
		} else if l.MaxIPs > 0 && st.SustainedIPs > l.MaxIPs {
			entry = &BlockedEntry{"ips", st.SustainedIPs, l.MaxIPs}
		}
		if entry == nil {
			delete(a.consecutive, key)
			delete(a.blocked, key)
		} else {
			a.consecutive[key] = min(settings.Consecutive, a.consecutive[key]+1)
			if a.consecutive[key] >= settings.Consecutive {
				a.blocked[key] = *entry
				st.Blocked = entry
			}
		}
		st.Consecutive = a.consecutive[key]
		result[l.AccountNo] = st
	}
	return result, nil
}

// sustained 刷新账户各活跃 IP 的连续活跃起点，返回连续活跃满 overlap 的 IP 数。
// 换网时新旧 IP 只重叠很短时间，不会计入；共享账号的多个 IP 会长期并行。
func (a *Aggregator) sustained(key string, active map[string]bool, now time.Time, grace, overlap time.Duration) (n int64) {
	seen := a.seen[key]
	if seen == nil {
		seen = map[string]ipSeen{}
		a.seen[key] = seen
	}
	for ip := range active {
		s, ok := seen[ip]
		if !ok || now.Sub(s.last) > grace {
			s.since = now
		}
		s.last = now
		seen[ip] = s
	}
	for ip, s := range seen {
		if now.Sub(s.last) > grace {
			delete(seen, ip)
		} else if active[ip] && now.Sub(s.since) >= overlap {
			n++
		}
	}
	if len(seen) == 0 {
		delete(a.seen, key)
	}
	return n
}

// Response 仅返回本次报告出现且仍存在的账户，不暴露其他节点账户。
func (a *Aggregator) Response(ctx context.Context, reports []AccountReport) (Response, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	limits, settings, err := store.LoadGuardState(ctx, a.db)
	response := Response{SchemaVersion: 1, GeneratedAtEpochSeconds: a.now().Unix(), TTLSeconds: int64(settings.TTL / time.Second), BlockedAccounts: map[string]BlockedEntry{}}
	if err != nil {
		return response, err
	}
	a.forgetUnknown(limits)
	for _, r := range reports {
		key := strings.ToLower(r.AccountNo)
		if _, ok := limits[key]; !ok {
			continue
		}
		if entry, ok := a.blocked[key]; ok {
			response.BlockedAccounts[r.AccountNo] = entry
		}
	}
	return response, nil
}

// Evict 回收过期快照；计数和黑名单由下一次 Evaluate 统一恢复。
func (a *Aggregator) Evict(ctx context.Context) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	limits, settings, err := store.LoadGuardState(ctx, a.db)
	if err != nil {
		return err
	}
	a.forgetUnknown(limits)
	now := a.now()
	for ip, at := range a.servers {
		if now.Sub(at) > settings.TTL {
			delete(a.servers, ip)
			delete(a.tags, ip)
		}
	}
	for key, nodes := range a.table {
		for ip, n := range nodes {
			if now.Sub(n.at) > settings.TTL {
				delete(nodes, ip)
			}
		}
		if len(nodes) == 0 {
			delete(a.table, key)
		}
	}
	return nil
}

// NodeOnline 是某账户在一台服务器上的最近上报。
type NodeOnline struct {
	NodeIP      string
	Connections int64
	IPs         []string
	At          time.Time
}
type AccountTag struct {
	ServerIP, Tag string
	IPs           []string
	Connections   int64
	ConnKnown     bool
}
type AccountOnline struct {
	AccountNo        string
	Connections, IPs int64
	Nodes            []NodeOnline
	Tags             []AccountTag
}

// TagOnline 按节点标签（node_<ID>）汇总；ConnKnown 为 false 表示至少一个来源未上报连接数。
type TagOnline struct {
	Accounts    int
	Connections int64
	ConnKnown   bool
}
type ServerOnline struct {
	Accounts    int
	Connections int64
	At          time.Time
	TagsKnown   bool // 该次上报含按节点标签的分组；旧版 agent 为 false
}

// Snapshot 是实时在线视图：只含 TTL 内上报的服务器；某台服务器不在 Servers 中表示“无新鲜上报”，不等于 0 在线。
type Snapshot struct {
	Accounts         map[string]AccountOnline // 键为小写账户编号
	Servers          map[string]ServerOnline  // 键为服务器 IP
	Tags             map[string]TagOnline     // 键为节点标签
	TotalConnections int64
}

// Snapshot 只读，不推进连续超限计数，也不回收数据。
func (a *Aggregator) Snapshot(ctx context.Context) (Snapshot, error) {
	a.mu.Lock()
	defer a.mu.Unlock()
	limits, settings, err := store.LoadGuardState(ctx, a.db)
	if err != nil {
		return Snapshot{}, err
	}
	now := a.now()
	out := Snapshot{Accounts: map[string]AccountOnline{}, Servers: map[string]ServerOnline{}, Tags: map[string]TagOnline{}}
	for ip, at := range a.servers {
		if now.Sub(at) <= settings.TTL {
			out.Servers[ip] = ServerOnline{At: at, TagsKnown: a.tags[ip].known}
		}
	}
	for key, nodes := range a.table {
		l, ok := limits[key]
		if !ok {
			continue
		}
		acc := AccountOnline{AccountNo: l.AccountNo}
		ips := map[string]bool{}
		for ip, n := range nodes {
			if now.Sub(n.at) > settings.TTL {
				continue
			}
			acc.Connections += n.connections
			acc.Nodes = append(acc.Nodes, NodeOnline{ip, n.connections, n.ips, n.at})
			for _, c := range n.ips {
				ips[c] = true
			}
			if sv, ok := out.Servers[ip]; ok {
				sv.Accounts++
				sv.Connections += n.connections
				out.Servers[ip] = sv
			}
		}
		if len(acc.Nodes) == 0 {
			continue
		}
		acc.IPs = int64(len(ips))
		sort.Slice(acc.Nodes, func(i, j int) bool { return acc.Nodes[i].NodeIP < acc.Nodes[j].NodeIP })
		out.Accounts[key] = acc
		out.TotalConnections += acc.Connections
	}
	tagAccounts := map[string]map[string]bool{}
	for ip, st := range a.tags {
		if _, fresh := out.Servers[ip]; !fresh || !st.known {
			continue
		}
		for _, g := range st.groups {
			t, seen := out.Tags[g.tag]
			if !seen {
				t.ConnKnown = true
				tagAccounts[g.tag] = map[string]bool{}
			}
			tagAccounts[g.tag][g.account] = true
			t.Connections += g.conns
			t.ConnKnown = t.ConnKnown && g.connKnown
			out.Tags[g.tag] = t
			if acc, ok := out.Accounts[g.account]; ok {
				acc.Tags = append(acc.Tags, AccountTag{ip, g.tag, g.ips, g.conns, g.connKnown})
				out.Accounts[g.account] = acc
			}
		}
	}
	for tag, accounts := range tagAccounts {
		t := out.Tags[tag]
		t.Accounts = len(accounts)
		out.Tags[tag] = t
	}
	return out, nil
}
