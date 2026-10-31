package web

import (
	"fmt"
	"math"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

// usageText 把 GB 数换成展示文字，满 1024 GB 改用 TB，整数不带小数。
func usageText(gb float64) string {
	unit := "GB"
	if gb >= 1024 {
		gb, unit = gb/1024, "TB"
	}
	return strings.TrimSuffix(fmt.Sprintf("%.1f", gb), ".0") + " " + unit
}

// accountView 是列表行的展示状态：Badge 只给异常状态，口径与 store 的状态筛选一致。
type accountView struct {
	Badge, BadgeClass string
	Left, LeftClass   string
	Usage, BarClass   string
	Percent           int
}

func accountRowView(a *store.AccountRow, now time.Time) accountView {
	var v accountView
	soon := now.AddDate(0, 0, 7)
	if t := a.ToDate; t != nil {
		left := t.Sub(now)
		// 剩余天数向上取整（不足一天也算 1 天），过期天数向下取整。
		days := int((left + 24*time.Hour - 1) / (24 * time.Hour))
		switch {
		case t.Before(now) && left > -24*time.Hour:
			v.Left, v.LeftClass = "刚过期", "text-red"
		case t.Before(now):
			v.Left, v.LeftClass = fmt.Sprintf("已过期 %d 天", int(-left/(24*time.Hour))), "text-red"
		case t.After(soon):
			v.Left, v.LeftClass = fmt.Sprintf("剩 %d 天", days), "text-secondary"
		default:
			v.Left, v.LeftClass = fmt.Sprintf("剩 %d 天", days), "text-orange"
		}
	}
	switch t := a.ToDate; {
	case a.Disabled:
		v.Badge, v.BadgeClass, v.LeftClass = "已禁用", "bg-secondary-lt", "text-secondary"
	case t != nil && t.Before(now):
		v.Badge, v.BadgeClass = "已过期", "bg-red-lt"
	case t != nil && t.After(now) && !t.After(soon):
		v.Badge, v.BadgeClass = "即将到期", "bg-orange-lt"
	}
	used := float64(a.UsedBytes) / (1 << 30)
	if a.QuotaGB <= 0 {
		v.Usage = usageText(used) + " · 不限量"
		return v
	}
	if a.QuotaGB < 1024 {
		v.Usage = fmt.Sprintf("%.1f / %d GB", used, a.QuotaGB)
	} else {
		// 额度满 1 TB 时分子分母都用 TB，分子保留两位小数以免小用量显示成 0
		v.Usage = strconv.FormatFloat(math.Round(used/1024*100)/100, 'f', -1, 64) + " / " + usageText(float64(a.QuotaGB))
	}
	v.Percent = min(100, int(used/float64(a.QuotaGB)*100))
	switch {
	case v.Percent >= 100:
		v.BarClass = "bg-red"
	case v.Percent >= 80:
		v.BarClass = "bg-orange"
	default:
		v.BarClass = "bg-primary"
	}
	return v
}

// accountSort 只接受白名单列；其他值回到默认排序（创建时间倒序）。
func accountSort(sort, dir string) (string, bool) {
	if sort != "last_online" && sort != "to_date" && sort != "used" {
		return "", false
	}
	return sort, dir == "asc"
}

// sortHeader 生成表头链接：未选中 → 降序 → 升序 → 恢复默认；保留筛选条件，页码回到第一页。
func sortHeader(q url.Values, col, cur string, asc bool) map[string]string {
	next := url.Values{}
	for k, v := range q {
		if k != "page" && k != "sort" && k != "dir" && k != "msg" && k != "err" {
			next[k] = v
		}
	}
	state, icon := "none", "ti-arrows-sort"
	switch {
	case cur != col:
		next.Set("sort", col)
		next.Set("dir", "desc")
	case !asc:
		state, icon = "descending", "ti-arrow-down"
		next.Set("sort", col)
		next.Set("dir", "asc")
	default:
		state, icon = "ascending", "ti-arrow-up"
	}
	return map[string]string{"URL": "?" + next.Encode(), "Aria": state, "Icon": icon}
}

type copyLink struct{ Label, URL string }

// installText 是发给客户的安装说明：账户备注、到期日与安装链接。
func installText(docs string, a store.Account) string {
	expire := "长期有效"
	if a.ToDate != nil {
		expire = a.ToDate.Format("2006-01-02")
	}
	return fmt.Sprintf("账户：%s\n到期日：%s\n点击打开安装手册：%s/?code=%s", a.Remark, expire, docs, a.AuthCode)
}

// accountLinks 生成给客户的链接：安装/使用手册（文档站）与各平台订阅地址（与 docs-info 一致）。
func (s *Server) accountLinks(r *http.Request, a *store.Account) []copyLink {
	var out []copyLink
	if docs := store.ConfigValue(r.Context(), s.DB, "airopscat.docs.url"); docs != "" {
		out = append(out,
			copyLink{"安装手册", docs + "/?code=" + a.AuthCode},
			copyLink{"使用手册", docs + "/manual.html?code=" + a.AuthCode})
	}
	if base := store.ConfigValue(r.Context(), s.DB, "airopscat.subscription.url"); base != "" {
		for _, p := range [][2]string{{"Windows", "windows/clash-verge"}, {"macOS", "macos/clash-verge"}, {"Linux", "linux/clash-verge"}, {"Android", "android/clash-meta"}, {"iOS", "ios/shadowrocket/" + url.QueryEscape(a.Remark)}} {
			out = append(out, copyLink{p[0] + " 订阅", base + "/config/" + a.AuthCode + "/" + p[1] + "/"})
		}
	}
	return out
}
