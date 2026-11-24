package store

import (
	"database/sql"
	"fmt"
	"time"
)

// timeScanner 让时间解析错误由 database/sql 附上列名和列序号。
type timeScanner func(any) error

func (s timeScanner) Scan(src any) error { return s(src) }

// scanTime 读取必填的 TEXT 时间列，沿用 ParseTime 的格式和上海时区。
// 不接受 NULL 或驱动预解析的 time.Time，避免掩盖数据或时区差异。
func scanTime(dst *time.Time) sql.Scanner {
	return timeScanner(func(src any) error {
		var text string
		switch v := src.(type) {
		case string:
			text = v
		case []byte:
			text = string(v)
		default:
			return fmt.Errorf("时间列应为文本，实际类型为 %T", src)
		}
		t, err := ParseTime(text)
		if err != nil {
			return err
		}
		*dst = t
		return nil
	})
}

// scanOptionalTime 读取可空时间列；NULL 对应 nil，空字符串仍视为非法时间。
func scanOptionalTime(dst **time.Time) sql.Scanner {
	return timeScanner(func(src any) error {
		if src == nil {
			*dst = nil
			return nil
		}
		var t time.Time
		if err := scanTime(&t).Scan(src); err != nil {
			return err
		}
		*dst = &t
		return nil
	})
}

// AddMonths 在上海时区按日历月加减 n 个月，目标月没有该日时取月末（1/31 加 1 月为 2 月末），时分秒不变。
// 与前端续期快捷按钮的月末钳制口径一致；Go 的 AddDate 会溢出到下月（1/31 加 1 月得 3/3）。
func AddMonths(t time.Time, n int) time.Time {
	t = t.In(Shanghai)
	first := time.Date(t.Year(), t.Month()+time.Month(n), 1, t.Hour(), t.Minute(), t.Second(), t.Nanosecond(), Shanghai)
	last := time.Date(first.Year(), first.Month()+1, 0, 0, 0, 0, 0, Shanghai).Day()
	return first.AddDate(0, 0, min(t.Day(), last)-1)
}

// AccountExpireCutoff 返回在时刻 at 判定账户是否已到期的基准时间（用于 to_date <= cutoff 比较）：
// 上海时间当天 12:00 前允许当天 00:00~12:00 到期的账户继续使用，基准为前一天 23:59:59；12:00 及之后以实际时刻 at 为准。
func AccountExpireCutoff(at time.Time) time.Time {
	local := at.In(Shanghai)
	noon := time.Date(local.Year(), local.Month(), local.Day(), 12, 0, 0, 0, Shanghai)
	if local.Before(noon) {
		return time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, Shanghai).Add(-time.Second)
	}
	return at
}

// IsAccountExpired 判断账户在时刻 at 是否已到期。
// 上海时间当天 12:00 前允许当天 00:00~12:00 到期的账户继续使用，仅早于当天 00:00 的视为已到期；12:00 及之后以实际时刻 at 为准。
func IsAccountExpired(toDate *time.Time, at time.Time) bool {
	if toDate == nil {
		return false
	}
	local := at.In(Shanghai)
	noon := time.Date(local.Year(), local.Month(), local.Day(), 12, 0, 0, 0, Shanghai)
	if local.Before(noon) {
		today := time.Date(local.Year(), local.Month(), local.Day(), 0, 0, 0, 0, Shanghai)
		return toDate.Before(today)
	}
	return !toDate.After(at)
}

// ServerUsableMinDay 返回在时刻 at 仍可用的服务器最小到期日期（YYYY-MM-DD）。
// 上海时间当天 12:00 前含当天到期服务器（允许 0~12 点过期的继续用）；12:00 及之后当天到期服务器不再可用。
func ServerUsableMinDay(at time.Time) string {
	local := at.In(Shanghai)
	noon := time.Date(local.Year(), local.Month(), local.Day(), 12, 0, 0, 0, Shanghai)
	if local.Before(noon) {
		return local.Format("2006-01-02")
	}
	return local.AddDate(0, 0, 1).Format("2006-01-02")
}
