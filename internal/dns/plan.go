package dns

import (
	"net"
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
func ValidWindow(start, end string) bool {
	_, a := time.Parse("15:04", start)
	_, b := time.Parse("15:04", end)
	return a == nil && b == nil
}
func IPv4(s string) bool { p := net.ParseIP(s); return p != nil && p.To4() != nil }

type Candidate struct {
	ID, NodeID, ServerID     int64
	IP, CheapStart, CheapEnd string
	Priority                 *int64
	Usage                    float64
	Known                    bool
}
type AutoRecord struct {
	ID                  int64
	Content, Home       string
	Scheduled           bool
	Start, End, In, Out string
	// 引用必须唯一指向一个节点/归属服务器。
	NodeID, ServerID int64
	Ambiguous        bool
}
type Plan struct {
	Target, Home, Reason string
	Exhausted            bool
}

// PlanRecord 不会把缺失或陈旧的流量视为零；有额度的候选需要新鲜用量。
func PlanRecord(r AutoRecord, all []Candidate, threshold float64, at time.Time) Plan {
	byNode := map[int64][]Candidate{}
	for _, c := range all {
		byNode[c.NodeID] = append(byNode[c.NodeID], c)
	}
	nodeID := r.NodeID
	wanted := ""
	home := r.Home
	if r.Scheduled {
		wanted = r.Out
		if Window(r.Start, r.End, at) {
			wanted = r.In
		}
		matches := 0
		for id, cs := range byNode {
			a, b := false, false
			for _, c := range cs {
				a = a || c.IP == r.In
				b = b || c.IP == r.Out
			}
			if a && b {
				nodeID = id
				matches++
			}
		}
		if matches != 1 {
			return Plan{Target: wanted, Reason: "SCHEDULE"}
		}
	} else if r.Ambiguous || nodeID == 0 || r.ServerID == 0 {
		return Plan{}
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
		if (!r.Scheduled && c.ServerID == r.ServerID) || (r.Scheduled && c.IP == wanted) {
			origin = c
		}
	}
	if origin == nil {
		return Plan{}
	}
	if !r.Scheduled {
		if !current {
			return Plan{}
		}
		if home == "" && r.Content != origin.IP {
			return Plan{}
		}
		if home != "" && home != origin.IP {
			return Plan{}
		}
	} else {
		home = ""
	}
	var target *Candidate
	if !r.Scheduled {
		for i := range cs {
			c := &cs[i]
			if c.Known && c.Usage < threshold && Window(c.CheapStart, c.CheapEnd, at) {
				target = c
				break
			}
		}
	}
	if target == nil && origin.Known && origin.Usage < threshold {
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
		p := Plan{Exhausted: known}
		if r.Scheduled && known {
			p.Target = wanted
			p.Reason = "SCHEDULE"
		}
		return p
	}
	reason := "RESET"
	if r.Scheduled {
		reason = "SCHEDULE"
	} else if target.ServerID != origin.ServerID {
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
