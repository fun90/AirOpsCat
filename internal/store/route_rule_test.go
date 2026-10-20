package store

import (
	"database/sql"
	"errors"
	"fmt"
	"testing"
)

func TestRouteRuleValueValidation(t *testing.T) {
	for _, tt := range []struct {
		kind, value string
		valid       bool
	}{
		{"domain", `["example.com","geosite:openai"]`, true}, {"domain", `"example.com"`, true},
		{"ip", `["192.0.2.1","2001:db8::/32","geoip:cn"]`, true}, {"source_ip", `"10.0.0.0/8"`, true},
		{"port", `[1,443,65535]`, true}, {"network", `["tcp","udp"]`, true}, {"inbound", `"node_12"`, true},
		{"custom", `{"protocol":"bittorrent"}`, true},
		{"domain", `null`, false}, {"domain", `[]`, false}, {"domain", `["x",1]`, false}, {"domain", `" "`, false}, {"domain", `"geosite:"`, false},
		{"domain", `"x" {}`, false}, {"ip", `"invalid"`, false}, {"source_ip", `"192.0.2.0/99"`, false},
		{"port", `0`, false}, {"port", `65536`, false}, {"port", `1.5`, false}, {"port", `"443"`, false}, {"network", `"icmp"`, false},
		{"inbound", `[null]`, false}, {"custom", `[]`, false}, {"custom", `{}`, false}, {"custom", `{"outbound":"node_2"}`, false}, {"oops", `"x"`, false},
	} {
		t.Run(tt.kind+tt.value, func(t *testing.T) {
			err := validateRouteValue(tt.kind, tt.value)
			if (err == nil) != tt.valid {
				t.Fatalf("valid=%v err=%v", tt.valid, err)
			}
		})
	}
}

func TestRouteRuleCRUDAtomicityAndDeploymentState(t *testing.T) {
	db, ctx := testDB(t)
	for _, q := range []string{
		`INSERT INTO server(id,name,ip) VALUES(1,'S1','192.0.2.1'),(2,'S2','192.0.2.2')`,
		`INSERT INTO node(id,name,type,protocol) VALUES(1,'landing',1,'socks'),(2,'proxy',0,'vless')`,
		`INSERT INTO node_server(node_id,server_id,deployed) VALUES(1,1,1),(2,1,0),(1,2,2)`,
	} {
		if _, err := db.Exec(q); err != nil {
			t.Fatal(err)
		}
	}
	unchanged := func() {
		t.Helper()
		var states string
		if err := db.QueryRow(`SELECT group_concat(deployed,'') FROM (SELECT deployed FROM node_server ORDER BY id)`).Scan(&states); err != nil || states != "102" {
			t.Fatalf("部署状态变化 %q %v", states, err)
		}
	}
	r := &RouteRule{Name: "route", RuleType: " DOMAIN ", RuleValue: "[\n  \"example.com\"\n]", OutboundNodeID: 1, Enabled: true, ServerIDs: []int64{1, 2, 1}}
	if err := SaveRouteRule(ctx, db, r); err != nil {
		t.Fatal(err)
	}
	unchanged()
	got, err := RouteRuleByID(ctx, db, r.ID)
	if err != nil || got.ServerCount != 2 || got.RuleValue != `["example.com"]` || got.RuleType != "domain" {
		t.Fatalf("读取 %+v %v", got, err)
	}
	for _, bad := range []RouteRule{
		{ID: r.ID, Name: "broken", RuleType: "domain", RuleValue: `"x"`, OutboundNodeID: 2, ServerIDs: []int64{1}},
		{ID: r.ID, Name: "broken", RuleType: "domain", RuleValue: `"x"`, OutboundNodeID: 1, ServerIDs: []int64{1, 999}},
	} {
		if err := SaveRouteRule(ctx, db, &bad); !errors.Is(err, ErrInvalid) {
			t.Fatal(err)
		}
		got, _ = RouteRuleByID(ctx, db, r.ID)
		if got.Name != "route" || got.ServerCount != 2 {
			t.Fatal("失败保存破坏旧数据")
		}
		unchanged()
	}
	r.Name = "edited"
	r.ServerIDs = nil
	if err := SaveRouteRule(ctx, db, r); err != nil {
		t.Fatal(err)
	}
	got, _ = RouteRuleByID(ctx, db, r.ID)
	if got.Name != "edited" || len(got.ServerIDs) != 0 {
		t.Fatal("未清空关联")
	}
	unchanged()
	if err := ToggleRouteRule(ctx, db, r.ID, false); err != nil {
		t.Fatal(err)
	}
	got, _ = RouteRuleByID(ctx, db, r.ID)
	if got.Enabled {
		t.Fatal("未停用")
	}
	unchanged()
	if err := ToggleRouteRule(ctx, db, r.ID, true); err != nil {
		t.Fatal(err)
	}
	unchanged()
	r.ServerIDs = []int64{2}
	if err := SaveRouteRule(ctx, db, r); err != nil {
		t.Fatal(err)
	}
	if err := DeleteRouteRule(ctx, db, r.ID); err != nil {
		t.Fatal(err)
	}
	unchanged()
	var count int
	db.QueryRow(`SELECT count(*) FROM route_rule_server`).Scan(&count)
	if count != 0 {
		t.Fatal("未级联清理")
	}
	for _, err := range []error{ToggleRouteRule(ctx, db, 999, true), DeleteRouteRule(ctx, db, 999)} {
		if !errors.Is(err, sql.ErrNoRows) {
			t.Fatal(err)
		}
	}
	r.ID = 999
	if err := SaveRouteRule(ctx, db, r); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal(err)
	}
}

func TestRouteRuleSearchPagination(t *testing.T) {
	db, ctx := testDB(t)
	if _, err := db.Exec(`INSERT INTO node(id,name,type) VALUES(1,'landing',1)`); err != nil {
		t.Fatal(err)
	}
	for i := 0; i < 21; i++ {
		r := &RouteRule{Name: fmt.Sprintf("rule-%02d", i), RuleType: "domain", RuleValue: `"example.com"`, OutboundNodeID: 1, Enabled: true}
		if err := SaveRouteRule(ctx, db, r); err != nil {
			t.Fatal(err)
		}
	}
	for _, tt := range []struct {
		q                 string
		page, want, total int
	}{{"", 1, 20, 21}, {"", 2, 1, 21}, {"rule-20", 1, 1, 1}, {"example.com", 1, 20, 21}, {"landing", 1, 20, 21}, {"missing", 1, 0, 0}} {
		rows, total, err := RouteRuleList(ctx, db, tt.q, tt.page, 20)
		if err != nil || len(rows) != tt.want || total != tt.total {
			t.Fatalf("%+v rows=%d total=%d err=%v", tt, len(rows), total, err)
		}
	}
}
