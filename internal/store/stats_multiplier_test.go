package store

import (
	"math"
	"testing"
)

func TestMultiplier(t *testing.T) {
	for _, bad := range []string{"", "0", "0.0", "-1", "abc", "1/3", "1e3", "0x10", ".5", "1.", "1.00001", "10000.1"} {
		if _, err := ParseMultiplier(bad); err == nil {
			t.Errorf("应拒绝 %q", bad)
		}
	}
	for _, tc := range []struct {
		m       string
		in, out int64
	}{
		{"1", 12345, 12345},
		{"10", 7, 70},
		{"1.5", 3, 5},
		{"0.7", 35, 25},
		{"0.7", -35, -25},
		{"0.1", 4, 0},
		{"10000", math.MaxInt64, math.MaxInt64},
	} {
		m, err := ParseMultiplier(tc.m)
		if err != nil {
			t.Fatal(err)
		}
		if got := m.Scale(tc.in); got != tc.out {
			t.Errorf("%d × %s = %d, want %d", tc.in, tc.m, got, tc.out)
		}
	}
	if (Multiplier{}).Scale(9) != 9 {
		t.Fatal("零值应等同于 1")
	}
	m, _ := ParseMultiplier("0.5")
	if got := m.Totals(TransactionTotals{Income: 3, Expense: 1, Balance: 2}); got != (TransactionTotals{2, 1, 1}) {
		t.Fatal(got)
	}
}
