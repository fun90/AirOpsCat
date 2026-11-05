package web

import (
	"net/http"
	"net/url"
	"strings"
	"testing"
)

func TestServerSupplierURL(t *testing.T) {
	h, a := newTestServer(t)
	cookie := login(t, h, "admin@example.com")
	form := url.Values{"csrf": {a.CSRFToken(cookie.Value)}, "name": {"网址测试"}, "ip": {"192.0.2.1"}, "ssh_port": {"22"}, "auth_type": {"PASSWORD"}, "server_type": {"0"}, "multiple": {"1"}, "price_months": {"12"}, "supplier": {"示例供应商"}}
	for _, address := range []string{"javascript:alert(1)", "//example.com", "ftp://example.com", "https://", "https://user:password@example.com", "https://example.com/" + strings.Repeat("a", 2048)} {
		form.Set("supplier_url", address)
		w := do(h, "POST", "/admin/servers", form, cookie, nil)
		if w.Code != http.StatusUnprocessableEntity || !strings.Contains(w.Body.String(), "请填写有效的 HTTP 或 HTTPS 网址") {
			t.Fatalf("无效网址应被拒绝: %q status=%d", address, w.Code)
		}
	}
	for _, address := range []string{" https://example.com/panel?lang=zh ", "http://example.com", ""} {
		form.Set("supplier_url", address)
		path := "/admin/servers/1"
		if strings.HasPrefix(address, " ") {
			path = "/admin/servers"
		}
		if w := do(h, "POST", path, form, cookie, nil); w.Code != http.StatusSeeOther {
			t.Fatalf("保存网址: %d", w.Code)
		}
		var saved string
		if err := a.DB.QueryRow(`SELECT coalesce(supplier_url,'') FROM server WHERE id=1`).Scan(&saved); err != nil || saved != strings.TrimSpace(address) {
			t.Fatalf("网址保存不正确: %q %v", saved, err)
		}
		for _, page := range []string{"/admin/servers", "/admin/servers/idle", "/admin/renewals", "/admin/servers/1/edit"} {
			w := do(h, "GET", page, nil, cookie, nil)
			if w.Code != http.StatusOK {
				t.Fatalf("%s: %d", page, w.Code)
			}
			link := `href="` + strings.TrimSpace(address) + `" target="_blank" rel="noopener noreferrer"`
			if address != "" && !strings.Contains(w.Body.String(), link) {
				t.Fatalf("%s 缺少新标签页网址链接", page)
			}
			if address == "" && (strings.Contains(w.Body.String(), `href="" target="_blank"`) || !strings.Contains(w.Body.String(), "示例供应商")) {
				t.Fatalf("%s 无网址时应保留供应商文本", page)
			}
		}
	}
}
