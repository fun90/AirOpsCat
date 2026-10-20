package web

import (
	"encoding/json"
	"testing"
	"time"
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
