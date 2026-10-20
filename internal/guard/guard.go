// Package guard 保存单实例的跨节点在线快照；只有 Evaluate 推进连续超限计数。
package guard

import (
	"context"
	"database/sql"
	"strings"
	"sync"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

type AccountReport struct {
	AccountNo   string   `json:"accountNo"`
	Connections int64    `json:"connections"`
	IPs         []string `json:"ips"`
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
	Consecutive      int
	Blocked          *BlockedEntry
}
type nodeStat struct {
	connections int64
	ips         []string
	at          time.Time
}
type Aggregator struct {
	mu          sync.Mutex
	db          *sql.DB
	now         func() time.Time
	table       map[string]map[string]nodeStat
	consecutive map[string]int
	blocked     map[string]BlockedEntry
}

// New 不启动 goroutine；可选时钟在构造时注入，后续不得修改。
func New(db *sql.DB, clock ...func() time.Time) *Aggregator {
	now := time.Now
	if len(clock) > 0 && clock[0] != nil {
		now = clock[0]
	}
	return &Aggregator{db: db, now: now, table: map[string]map[string]nodeStat{}, consecutive: map[string]int{}, blocked: map[string]BlockedEntry{}}
}
func (a *Aggregator) forgetUnknown(limits map[string]store.GuardLimit) {
	for key := range a.table {
		if _, ok := limits[key]; !ok {
			delete(a.table, key)
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
		a.table[key][nodeIP] = nodeStat{connections: max(0, r.Connections), ips: ips, at: now}
	}
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
	result := map[string]Stats{}
	now := a.now()
	for key := range tracked {
		l, exists := limits[key]
		if !exists {
			delete(a.consecutive, key)
			delete(a.blocked, key)
			continue
		}
		st := Stats{AccountNo: l.AccountNo}
		ips := map[string]bool{}
		for _, n := range a.table[key] {
			if now.Sub(n.at) > settings.TTL {
				continue
			}
			st.Connections += n.connections
			st.ActiveNodes++
			for _, ip := range n.ips {
				ips[ip] = true
			}
		}
		st.IPs = int64(len(ips))
		var entry *BlockedEntry
		if l.MaxConnections > 0 && st.Connections > l.MaxConnections {
			entry = &BlockedEntry{"connections", st.Connections, l.MaxConnections}
		} else if l.MaxIPs > 0 && st.IPs > l.MaxIPs {
			entry = &BlockedEntry{"ips", st.IPs, l.MaxIPs}
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
