package dns

import (
	"testing"
	"time"
)

func TestWindowAndPlan(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Shanghai")
	at := time.Date(2026, 10, 20, 23, 0, 0, 0, zone)
	for _, c := range []struct {
		s, e string
		hour int
		want bool
	}{{"22:00", "06:00", 23, true}, {"22:00", "06:00", 6, false}, {"22:00", "06:00", 22, true}, {"10:00", "10:00", 1, true}} {
		if got := Window(c.s, c.e, time.Date(2026, 10, 20, c.hour, 0, 0, 0, zone)); got != c.want {
			t.Fatal(c, got)
		}
	}
	cs := []Candidate{{ID: 1, NodeID: 1, ServerID: 1, IP: "1.1.1.1", Known: true, Usage: .9}, {ID: 2, NodeID: 1, ServerID: 2, IP: "2.2.2.2", Known: true, Usage: .3}}
	r := AutoRecord{ID: 1, NodeID: 1, ServerID: 1, Content: "1.1.1.1"}
	cs[0].Known = false
	if p := PlanRecord(r, cs, .85, at); p.Target != "" || p.Exhausted {
		t.Fatal("原服务器用量未知不应切走", p)
	}
	cs[0].Known = true
	p := PlanRecord(r, cs, .85, at)
	if p.Target != "2.2.2.2" || p.Home != "1.1.1.1" || p.Reason != "THRESHOLD" {
		t.Fatal(p)
	}
	r.Content, r.Home = p.Target, p.Home
	cs[0].Usage = .1
	p = PlanRecord(r, cs, .85, at)
	if p.Target != "1.1.1.1" || p.Home != "" {
		t.Fatal("未回切", p)
	}
	cs[1].CheapStart, cs[1].CheapEnd = "22:00", "06:00"
	if p = PlanRecord(r, cs, .85, at); p.Target != "2.2.2.2" || p.Reason != "CHEAP_WINDOW" {
		t.Fatal(p)
	}
	// 便宜时段外，便宜部署位不可用：原服务器超阈值时保持不动，不切到便宜位。
	out := time.Date(2026, 10, 20, 12, 0, 0, 0, zone)
	r2 := AutoRecord{ID: 1, NodeID: 1, ServerID: 1, Content: "1.1.1.1"}
	cs[0].Usage, cs[1].Usage = .9, .3
	if p = PlanRecord(r2, cs, .85, out); p.Target != "" {
		t.Fatal("便宜位不应在时段外使用", p)
	}
	cs3 := append([]Candidate{}, cs...)
	cs3 = append(cs3, Candidate{ID: 3, NodeID: 1, ServerID: 3, IP: "3.3.3.3", Known: true, Usage: .2})
	if p = PlanRecord(r2, cs3, .85, out); p.Target != "3.3.3.3" || p.Reason != "THRESHOLD" {
		t.Fatal("应改用非便宜部署位", p)
	}
	cs[0].Usage, cs[1].Usage = .9, .9
	if p = PlanRecord(r, cs, .85, at); p.Target != "" || !p.Exhausted {
		t.Fatal(p)
	}
	cs[1].Known = false
	if p = PlanRecord(r, cs, .85, at); p.Target != "" || p.Exhausted {
		t.Fatal("未知不等于超额", p)
	}
	r.Home = ""
	r.Content = "2.2.2.2"
	cs[0].Usage = .1
	if p = PlanRecord(r, cs, .85, at); p.Target != "" {
		t.Fatal("不能覆盖人工指定", p)
	}

	// 手动切走：即使原服务器用量正常、候选超阈值也切走；全部切走时保持不动。
	r = AutoRecord{ID: 1, NodeID: 1, ServerID: 1, Content: "1.1.1.1"}
	cs = []Candidate{{ID: 1, NodeID: 1, ServerID: 1, IP: "1.1.1.1", Known: true, Failover: true}, {ID: 2, NodeID: 1, ServerID: 2, IP: "2.2.2.2", Known: true, Usage: .9}}
	if p = PlanRecord(r, cs, .85, at); p.Target != "2.2.2.2" || p.Home != "1.1.1.1" || p.Reason != "MANUAL" {
		t.Fatal(p)
	}
	cs[1].Failover = true
	if p = PlanRecord(r, cs, .85, at); p.Target != "" || p.Exhausted {
		t.Fatal(p)
	}
}

// 原服务器禁用或到期（不可用）时应能切走，不可用的服务器不作为切换目标。
func TestPlanLeavesUnavailableOrigin(t *testing.T) {
	zone, _ := time.LoadLocation("Asia/Shanghai")
	at := time.Date(2026, 10, 20, 12, 0, 0, 0, zone)
	r := AutoRecord{ID: 1, NodeID: 1, ServerID: 1, Content: "1.1.1.1"}
	cs := []Candidate{{ID: 1, NodeID: 1, ServerID: 1, IP: "1.1.1.1", Known: true, Usage: .1, Unavailable: true},
		{ID: 2, NodeID: 1, ServerID: 2, IP: "2.2.2.2", Known: true, Usage: .9}}
	if p := PlanRecord(r, cs, .85, at); p.Target != "2.2.2.2" || p.Home != "1.1.1.1" || p.Reason != "UNAVAILABLE" || p.BlockedReason != "" {
		t.Fatal("原服务器不可用时应切走", p)
	}
	// 已切走后原服务器仍不可用：保持当前解析，不回切也不告警
	r.Content, r.Home = "2.2.2.2", "1.1.1.1"
	if p := PlanRecord(r, cs, .85, at); p.Target != "2.2.2.2" || p.Home != "1.1.1.1" || p.BlockedReason != "" {
		t.Fatal("原服务器不可用时不应回切", p)
	}
	// 不可用的服务器不作为目标：原服务器超阈值，另一候选不可用时保持不动
	r = AutoRecord{ID: 1, NodeID: 1, ServerID: 1, Content: "1.1.1.1"}
	cs = []Candidate{{ID: 1, NodeID: 1, ServerID: 1, IP: "1.1.1.1", Known: true, Usage: .9},
		{ID: 2, NodeID: 1, ServerID: 2, IP: "2.2.2.2", Known: true, Usage: .1, Unavailable: true}}
	if p := PlanRecord(r, cs, .85, at); p.Target != "" {
		t.Fatal("不可用服务器不应作为切换目标", p)
	}
	// 当前解析在不可用的非原服务器上，原服务器可用：回切
	r.Content, r.Home = "2.2.2.2", "1.1.1.1"
	cs[0].Usage = .1
	if p := PlanRecord(r, cs, .85, at); p.Target != "1.1.1.1" || p.Home != "" {
		t.Fatal("应回切到可用的原服务器", p)
	}
	// 全部不可用：保持当前解析
	r = AutoRecord{ID: 1, NodeID: 1, ServerID: 1, Content: "1.1.1.1"}
	cs[0].Unavailable = true
	if p := PlanRecord(r, cs, .85, at); p.Target != "" || p.BlockedReason != "" {
		t.Fatal("没有可用目标时保持不动", p)
	}
}
