package store

import (
	"fmt"
	"strconv"
	"strings"
)

// ParseCents 把「元」的十进制文本转为整数分，不经浮点；小数超过 2 位报错。
func ParseCents(s string) (int64, error) {
	s = strings.TrimSpace(s)
	neg := strings.HasPrefix(s, "-")
	s = strings.TrimPrefix(s, "-")
	whole, frac, _ := strings.Cut(s, ".")
	if whole == "" {
		whole = "0"
	}
	if len(frac) > 2 {
		return 0, fmt.Errorf("金额最多 2 位小数")
	}
	frac += strings.Repeat("0", 2-len(frac))
	n, err := strconv.ParseInt(whole+frac, 10, 64)
	if err != nil {
		return 0, fmt.Errorf("金额格式不正确")
	}
	if neg {
		n = -n
	}
	return n, nil
}

// FormatCents 把整数分格式化为「元」文本，如 35200 → 352.00。
func FormatCents(c int64) string {
	sign := ""
	if c < 0 {
		sign, c = "-", -c
	}
	return fmt.Sprintf("%s%d.%02d", sign, c/100, c%100)
}
