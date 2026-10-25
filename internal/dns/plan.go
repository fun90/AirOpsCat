package dns

import (
	"sort"
	"time"
)

// Window 使用上海时区的左闭右开时间窗；相等视为全天。
func Window(start, end string, at time.Time) bool {
	a, e := time.Parse("15:04", start)
	if e != nil {
		return false
	}
	b, e := time.Parse("15:04", end)
	if e != nil {
		return false
	}
	zone, _ := time.LoadLocation("Asia/Shanghai")
	t := at.In(zone).Format("15:04")
	s, f := a.Format("15:04"), b.Format("15:04")
	if s == f {
		return true
	}
	if s < f {
		return t >= s && t < f
	}
	return t >= s || t < f
}

type Candidate struct {
	ID, NodeID, ServerID     int64
	IP, CheapStart, CheapEnd string
	Priority                 *int64
	Usage                    float64
	Known                    bool
}
type AutoRecord struct {
	ID            int64
	Content, Home string
	// 引用必须唯一指向一个节点/归属服务器。
	NodeID, ServerID int64
	Ambiguous        bool
}
type Plan struct {
	Target, Home, Reason string
	BlockedReason        string
	Exhausted            bool
}

// PlanRecord 不会把缺失或陈旧的流量视为零；有额度的候选需要新鲜用量。
func PlanRecord(r AutoRecord, all []Candidate, threshold float64, at time.Time) Plan {
	byNode := map[int64][]Candidate{}
	for _, c := range all {
		byNode[c.NodeID] = append(byNode[c.NodeID], c)
	}
	nodeID := r.NodeID
	home := r.Home
	blocked := func(reason string) Plan {
		if home != "" {
			return Plan{BlockedReason: reason}
		}
		return Plan{}
	}
	if r.Ambiguous || nodeID == 0 || r.ServerID == 0 {
		return blocked("归属节点或服务器缺失，或存在冲突引用")
	}
	cs := byNode[nodeID]
	sort.Slice(cs, func(i, j int) bool {
		a, b := cs[i], cs[j]
		if (a.Priority == nil) != (b.Priority == nil) {
			return a.Priority != nil
		}
		if a.Priority != nil && *a.Priority != *b.Priority {
			return *a.Priority < *b.Priority
		}
		return a.ID < b.ID
	})
	var origin *Candidate
	current := false
	for i := range cs {
		c := &cs[i]
		if c.IP == r.Content {
			current = true
		}
		if c.ServerID == r.ServerID {
			origin = c
		}
	}
	if origin == nil {
		return blocked("原服务器没有可用的运行部署位")
	}
	if !current {
		return blocked("当前解析不属于可用候选服务器")
	}
	if home == "" && r.Content != origin.IP {
		return Plan{}
	}
	if home != "" && home != origin.IP {
		return blocked("保存的原始解析与当前归属服务器不一致")
	}
	var target *Candidate
	for i := range cs {
		c := &cs[i]
		if c.Known && c.Usage < threshold && Window(c.CheapStart, c.CheapEnd, at) {
			target = c
			break
		}
	}
	if target == nil && !origin.Known {
		// 原服务器无新鲜用量时无法判定超阈值，保持当前解析，避免误切后又回切。
		return Plan{}
	}
	if target == nil && origin.Usage < threshold {
		target = origin
	}
	if target == nil {
		for i := range cs {
			c := &cs[i]
			if c.Known && c.Usage < threshold {
				target = c
				break
			}
		}
	}
	if target == nil {
		known := true
		for _, c := range cs {
			known = known && c.Known
		}
		return Plan{Exhausted: known}
	}
	reason := "RESET"
	if target.ServerID != origin.ServerID {
		home = origin.IP
		reason = "THRESHOLD"
		if Window(target.CheapStart, target.CheapEnd, at) {
			reason = "CHEAP_WINDOW"
		}
	} else {
		home = ""
	}
	return Plan{Target: target.IP, Home: home, Reason: reason}
}
