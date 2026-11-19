package web

import (
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"
)

func TestBackTargetAnyAdminPage(t *testing.T) {
	cases := map[string]string{
		"/admin/accounts/3/edit?x=1&msg=a": "/admin/accounts/3/edit?x=1",
		"/admin/servers?page=2#row":        "/admin/servers?page=2",
		"//evil.com/admin/x":               "",
		"https://evil.com/admin/x":         "",
		"/other":                           "",
		"/admin/\\evil":                    "",
	}
	for in, want := range cases {
		r := httptest.NewRequest("POST", "/admin/nodes/1", strings.NewReader(url.Values{"back": {in}}.Encode()))
		r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
		if got := backTarget(r, "/admin/nodes"); got != want {
			t.Errorf("%q: got %q want %q", in, got, want)
		}
	}
}
