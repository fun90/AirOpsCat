package web

import (
	"encoding/json"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func TestAccountUsageEndpoint(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	_, err := a.DB.Exec(`INSERT INTO account(id,uuid,auth_code,account_no,period_type,bandwidth)VALUES(99,'usage-u','usage-c','usage-a','MONTHLY',10)`)
	if err != nil {
		t.Fatal(err)
	}
	start := time.Now().Add(-time.Hour).Format("2006-01-02 15:04:05")
	end := time.Now().Add(24 * time.Hour).Format("2006-01-02 15:04:05")
	_, err = a.DB.Exec(`INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes)VALUES(99,?,?,20,1073741824,1073741824)`, start, end)
	if err != nil {
		t.Fatal(err)
	}
	w := do(h, "GET", "/admin/accounts/99/usage", nil, c, nil)
	var v map[string]string
	if w.Code != 200 || json.Unmarshal(w.Body.Bytes(), &v) != nil || v["percent"] != "10.0%" || v["used"] != "2.00 GB" || v["start"] != start[:10] || v["end"] != end[:10] {
		t.Fatal(w.Code, w.Body.String())
	}
	a.DB.Exec(`UPDATE account_traffic_stats SET bandwidth_quota=0 WHERE account_id=99`)
	w = do(h, "GET", "/admin/accounts/99/usage", nil, c, nil)
	json.Unmarshal(w.Body.Bytes(), &v)
	if v["percent"] != "不限量" {
		t.Fatal(v)
	}
	if w := do(h, "GET", "/admin/accounts/999/usage", nil, c, nil); w.Code != 404 {
		t.Fatal(w.Code)
	}
	if w := do(h, "GET", "/admin/accounts/99/usage", nil, nil, nil); w.Code != 302 {
		t.Fatal(w.Code)
	}
	vip := login(t, h, "vip@example.com")
	if w := do(h, "GET", "/admin/accounts/99/usage", nil, vip, nil); w.Code != 403 {
		t.Fatal(w.Code)
	}
}

func TestAccountRowView(t *testing.T) {
	now := time.Date(2026, 10, 25, 12, 0, 0, 0, time.UTC)
	at := func(d time.Duration) *time.Time { t := now.Add(d); return &t }
	day := 24 * time.Hour
	for _, c := range []struct {
		name                    string
		row                     store.AccountRow
		badge, left, usage, bar string
		percent                 int
	}{
		{"正常", store.AccountRow{Account: store.Account{ToDate: at(26*day - time.Hour)}, UsedBytes: 1 << 30, QuotaGB: 10}, "", "剩 26 天", "1.0 / 10 GB", "bg-primary", 10},
		{"即将到期", store.AccountRow{Account: store.Account{ToDate: at(4 * day)}, UsedBytes: 9 << 30, QuotaGB: 10}, "即将到期", "剩 4 天", "9.0 / 10 GB", "bg-orange", 90},
		{"不足一天", store.AccountRow{Account: store.Account{ToDate: at(time.Hour)}}, "即将到期", "剩 1 天", "0 GB · 不限量", "", 0},
		{"已过期", store.AccountRow{Account: store.Account{ToDate: at(-3*day - time.Hour)}, UsedBytes: 20 << 30, QuotaGB: 10}, "已过期", "已过期 3 天", "20.0 / 10 GB", "bg-red", 100},
		{"禁用优先", store.AccountRow{Account: store.Account{Disabled: true, ToDate: at(-time.Hour)}}, "已禁用", "刚过期", "0 GB · 不限量", "", 0},
		{"长期有效", store.AccountRow{}, "", "", "0 GB · 不限量", "", 0},
		{"配额满 1 TB", store.AccountRow{UsedBytes: 125 << 30, QuotaGB: 1024}, "", "", "0.12 / 1 TB", "bg-primary", 12},
		{"用量满 1 TB", store.AccountRow{UsedBytes: 1536 << 30, QuotaGB: 2048}, "", "", "1.5 / 2 TB", "bg-primary", 75},
		{"额度满 1 TB 未使用", store.AccountRow{QuotaGB: 2048}, "", "", "0 / 2 TB", "bg-primary", 0},
		{"不限量满 1 TB", store.AccountRow{UsedBytes: 2048 << 30}, "", "", "2 TB · 不限量", "", 0},
	} {
		v := accountRowView(&c.row, now)
		if v.Badge != c.badge || v.Left != c.left || v.Usage != c.usage || v.BarClass != c.bar || v.Percent != c.percent {
			t.Errorf("%s: %+v", c.name, v)
		}
	}
}
