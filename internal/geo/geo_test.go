package geo

import "testing"

func TestServerCountry(t *testing.T) {
	cases := []struct {
		region, name, ip string
		want             string
	}{
		{"香港（外部）", "hk-ext", "203.0.113.1", "HK"},
		{"国内", "relay-1", "203.0.113.1", "CN"},
		{"美国", "us-1", "203.0.113.1", "US"},
		{"US", "node", "203.0.113.1", "US"},
		{"UK", "node", "203.0.113.1", "GB"},
		{"", "日本2🇯🇵", "203.0.113.1", "JP"},
		{"其他", "新加坡🇸🇬", "203.0.113.1", "SG"},
		{"", "unnamed", "114.114.114.114", "CN"},
		{"", "unnamed", "8.8.8.8", "US"},
	}
	for _, c := range cases {
		if got := ServerCountry(c.region, c.name, c.ip).Code; got != c.want {
			t.Errorf("ServerCountry(%q, %q, %q) = %s, want %s", c.region, c.name, c.ip, got, c.want)
		}
	}
}

func TestLookupIP(t *testing.T) {
	cases := map[string]string{
		"114.114.114.114": "CN",
		"223.5.5.5":       "CN",
		"8.8.8.8":         "US",
		"104.16.0.1":      "US",
		"144.0.0.1":       "US",
		"47.128.0.1":      "SG",
		"126.1.2.3":       "JP",
		"81.2.69.142":     "GB",
		"5.1.2.3":         "DE",
		"192.168.1.1":     "ZZ",
		"240e:123::1":     "CN",
		"2606:4700::1":    "US",
	}
	for ip, want := range cases {
		if got := LookupIP(ip).Code; got != want {
			t.Errorf("LookupIP(%q) = %s, want %s", ip, got, want)
		}
	}
}
