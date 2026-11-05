package web

import (
	"context"
	"net/http"
	"net/url"
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
	"github.com/fun90/airopscat/internal/store"
)

func TestServerTrafficListAndCalibration(t *testing.T) {
	today := time.Date(2026, 11, 5, 0, 0, 0, 0, store.Shanghai)
	for date, want := range map[string]string{"2026-12-02": "12-02", "2027-07-29": "2027-07-29", "bad": "bad", "": ""} {
		if got := newServerRow(&store.Server{ExpireDate: date}, today).ExpireShort; got != want {
			t.Fatalf("日期 %q 显示为 %q，应为 %q", date, got, want)
		}
	}
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	if _, err := a.DB.Exec(`INSERT INTO server(id,ip,name,bandwidth,expire_date) VALUES(1,'192.0.2.1','traffic-test',100,'2027-01-02')`); err != nil {
		t.Fatal(err)
	}
	at := time.Now().In(store.Shanghai)
	if err := store.InsertVnstatSample(context.Background(), a.DB, 1, monitor.Vnstat{Iface: "eth0", Year: at.Year(), Month: int(at.Month()), RxBytes: 50 << 30}, at); err != nil {
		t.Fatal(err)
	}
	w := do(h, "GET", "/admin/servers?sort=expire&dir=asc", nil, c, nil)
	for _, want := range []string{"50.0 / 100 GiB", `aria-valuenow="50"`, `name="sort" value="expire"`, `name="dir" value="asc"`} {
		if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), want) {
			t.Fatalf("流量列表缺少 %q，状态 %d", want, w.Code)
		}
	}
	path := "/admin/servers/1/calibrate-traffic"
	if w := do(h, "POST", path, url.Values{"used": {"25"}}, c, nil); w.Code != http.StatusForbidden {
		t.Fatalf("缺少 CSRF 应拒绝: %d", w.Code)
	}
	for _, input := range []string{"NaN", "Inf", "-Inf", "1e400", "-1", "1000000001", ""} {
		w := do(h, "POST", path, url.Values{"csrf": {csrf}, "used": {input}}, c, nil)
		if w.Code != http.StatusSeeOther || !strings.Contains(w.Header().Get("Location"), "err=") {
			t.Fatalf("应拒绝无效流量 %q: %d", input, w.Code)
		}
	}
	w = do(h, "POST", path, url.Values{"csrf": {csrf}, "used": {"25"}}, c, nil)
	if w.Code != http.StatusSeeOther || !strings.Contains(w.Header().Get("Location"), "msg=") {
		t.Fatalf("校准失败: %d", w.Code)
	}
	w = do(h, "GET", "/admin/servers", nil, c, nil)
	if w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "25.0 / 100 GiB") {
		t.Fatal("校准后列表应立即显示新的用量")
	}
	if _, err := a.DB.Exec(`UPDATE server SET expire_date='bad' WHERE id=1`); err != nil {
		t.Fatal(err)
	}
	if w := do(h, "GET", "/admin/servers", nil, c, nil); w.Code != http.StatusOK || !strings.Contains(w.Body.String(), "bad") {
		t.Fatal("短日期不应中断列表渲染")
	}
}
