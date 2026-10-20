package web

import (
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/guard"
)

func TestOnlineTexts(t *testing.T) {
	empty := guard.Snapshot{}
	if got := accountOnlineText(empty, "A"); got != "暂无上报" {
		t.Fatal(got)
	}
	snap := guard.Snapshot{
		Accounts: map[string]guard.AccountOnline{"a": {Connections: 5, IPs: 3}},
		Servers:  map[string]guard.ServerOnline{"s1": {Accounts: 1, Connections: 5, TagsKnown: true}, "s2": {}},
		Tags:     map[string]guard.TagOnline{"node_7": {Accounts: 1, Connections: 5, ConnKnown: true}},
	}
	if got := accountOnlineText(snap, "A"); got != "在线 · 5 连接 · 3 IP" {
		t.Fatal(got)
	}
	if got := accountOnlineText(snap, "B"); got != "未在线" {
		t.Fatal(got)
	}
	if got := nodeOnlineText(snap, 7, []string{"s1"}); got != "1 账户 · 5 连接" {
		t.Fatal(got)
	}
	// s2 无标签分组、s3 无上报：覆盖不全要提示，且没有任何覆盖时不能显示 0。
	if got := nodeOnlineText(snap, 7, []string{"s1", "s2"}); !strings.Contains(got, "部分服务器无上报") {
		t.Fatal(got)
	}
	if got := nodeOnlineText(snap, 7, []string{"s2", "s3"}); got != "暂无上报" {
		t.Fatal(got)
	}
	if got := serverOnlineText(snap, "s9"); got != "暂无上报" {
		t.Fatal(got)
	}
}

func TestRelativeAndLastOnlineText(t *testing.T) {
	now := time.Date(2026, 10, 21, 12, 0, 0, 0, time.UTC)
	for _, c := range []struct {
		ago  time.Duration
		want string
	}{
		{-time.Minute, "刚刚"}, // 时钟偏差
		{0, "刚刚"},
		{59 * time.Second, "刚刚"},
		{time.Minute, "1 分钟前"},
		{59*time.Minute + 59*time.Second, "59 分钟前"},
		{time.Hour, "1 小时前"},
		{23*time.Hour + 59*time.Minute, "23 小时前"},
		{24 * time.Hour, "1 天前"},
		{30 * 24 * time.Hour, "30 天前"},
	} {
		if got := relativeTime(now.Add(-c.ago), now); got != c.want {
			t.Errorf("%v: got %q want %q", c.ago, got, c.want)
		}
	}
	at := now.Add(-5 * time.Minute)
	if got := lastOnlineText(&at, now); got != "最后在线 5 分钟前" {
		t.Fatal(got)
	}
	if got := lastOnlineText(nil, now); got != "30 天内无在线记录" {
		t.Fatal(got)
	}
}

func TestDBMinuteFunc(t *testing.T) {
	f := funcs["dbminute"].(func(any) string)
	at := time.Date(2055, 4, 16, 4, 55, 59, 0, time.UTC) // 北京时间 12:55:59
	if got := f(&at); got != "2055-04-16 12:55" {
		t.Fatal(got)
	}
	var none *time.Time
	if f(none) != "" || f(nil) != "" {
		t.Fatal("空值应返回空串")
	}
}

func TestAccountSortHeader(t *testing.T) {
	if s, asc := accountSort("evil; drop", "asc"); s != "" || asc {
		t.Fatal("非白名单列应回到默认排序", s, asc)
	}
	if s, asc := accountSort("to_date", "asc"); s != "to_date" || !asc {
		t.Fatal(s, asc)
	}
	if _, asc := accountSort("last_online", "bogus"); asc {
		t.Fatal("非法方向按降序")
	}
	q := url.Values{"q": {"a b"}, "status": {"active"}, "page": {"3"}, "msg": {"x"}}
	// 未选中 → 降序 → 升序 → 恢复默认；筛选条件保留，页码与提示清除。
	h := sortHeader(q, "last_online", "", false)
	if h["URL"] != "?q=a+b&sort=last_online&dir=desc&status=active" && h["URL"] != "?dir=desc&q=a+b&sort=last_online&status=active" || h["Aria"] != "none" {
		t.Fatal(h)
	}
	h = sortHeader(q, "last_online", "last_online", false)
	if !strings.Contains(h["URL"], "dir=asc") || h["Aria"] != "descending" {
		t.Fatal(h)
	}
	h = sortHeader(q, "last_online", "last_online", true)
	if strings.Contains(h["URL"], "sort=") || strings.Contains(h["URL"], "page=") || strings.Contains(h["URL"], "msg=") || !strings.Contains(h["URL"], "status=active") || h["Aria"] != "ascending" {
		t.Fatal(h)
	}
	// 另一列激活时点击本列：从降序开始，且不带另一列。
	h = sortHeader(q, "to_date", "last_online", true)
	if !strings.Contains(h["URL"], "sort=to_date") || !strings.Contains(h["URL"], "dir=desc") || h["Aria"] != "none" {
		t.Fatal(h)
	}
}
