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
	r.Scheduled = true
	r.Start, r.End, r.In, r.Out = "22:00", "06:00", "1.1.1.1", "2.2.2.2"
	cs[1].Known = true
	cs[1].Usage = .1
	if p = PlanRecord(r, cs, .85, at); p.Target != "1.1.1.1" {
		t.Fatal("便宜时段不能覆盖显式计划", p)
	}
	cs[0].Usage = .9
	if p = PlanRecord(r, cs, .85, at); p.Target != "2.2.2.2" {
		t.Fatal("时段超额回退", p)
	}
	r.In = "3.3.3.3"
	if p = PlanRecord(r, cs, .85, at); p.Target != "3.3.3.3" {
		t.Fatal("非节点计划正常执行", p)
	}
}
