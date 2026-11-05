package web

import "testing"

func TestServerUsageText(t *testing.T) {
	for _, c := range []struct {
		used, quota float64
		unit, want  string
	}{
		{50, 100, "GiB", "50.0 / 100 GiB"},
		{740.94, 1000, "GiB", "740.9 / 1000 GiB"},
		{1500, 2048, "GiB", "1.46 / 2 TiB"},
		{2001.1, 2000, "GB", "2 / 2 TB"},
		{22.5, 2000, "GB", "0.02 / 2 TB"},
		{22.5, 0, "GB", "22.5 GB"},
		{1536, 0, "GiB", "1.5 TiB"},
	} {
		if got := serverUsageText(c.used, c.quota, c.unit); got != c.want {
			t.Errorf("serverUsageText(%v,%v,%s)=%q 期望 %q", c.used, c.quota, c.unit, got, c.want)
		}
	}
}
