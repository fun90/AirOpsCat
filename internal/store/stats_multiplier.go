package store

import (
	"context"
	"database/sql"
	"math/big"
	"regexp"
	"strconv"
)

// StatsMultiplierKey 是概览页与收支记账页统计数的展示倍数；只影响页面展示，不改变存储数据。
const StatsMultiplierKey = "airopscat.stats.multiplier"

var multiplierPattern = regexp.MustCompile(`^[0-9]+(\.[0-9]{1,4})?$`)
var maxMultiplier = big.NewRat(10000, 1)

// Multiplier 是十进制展示倍数，用有理数计算以免浮点舍入误差；零值等同于 1。
type Multiplier struct{ r *big.Rat }

// ParseMultiplier 接受大于 0、不超过 10000、最多 4 位小数的十进制数。
func ParseMultiplier(s string) (Multiplier, error) {
	if multiplierPattern.MatchString(s) {
		if r, ok := new(big.Rat).SetString(s); ok && r.Sign() > 0 && r.Cmp(maxMultiplier) <= 0 {
			return Multiplier{r}, nil
		}
	}
	return Multiplier{}, invalid("倍数须大于 0、不超过 10000，最多 4 位小数")
}

// Scale 返回 v 乘以倍数后四舍五入的整数，超出 int64 时取边界值。
func (m Multiplier) Scale(v int64) int64 {
	if m.r == nil {
		return v
	}
	// ParseInt 越界时返回对应方向的边界值。
	n, _ := strconv.ParseInt(new(big.Rat).Mul(m.r, big.NewRat(v, 1)).FloatString(0), 10, 64)
	return n
}

// ScaleInt 是 Scale 的 int 版本，用于账户数等计数。
func (m Multiplier) ScaleInt(v int) int { return int(m.Scale(int64(v))) }

// Totals 缩放收支合计；净额由缩放后的收入与支出相减，保证页面上三者自洽。
func (m Multiplier) Totals(t TransactionTotals) TransactionTotals {
	t.Income, t.Expense = m.Scale(t.Income), m.Scale(t.Expense)
	t.Balance = t.Income - t.Expense
	return t
}

// StatsMultiplier 读取展示倍数；缺失或非法时为 1。
func StatsMultiplier(ctx context.Context, db *sql.DB) Multiplier {
	m, _ := ParseMultiplier(ConfigValue(ctx, db, StatsMultiplierKey))
	return m
}
