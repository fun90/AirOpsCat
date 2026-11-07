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
