package store

import (
	"errors"
	"strings"
	"testing"
	"time"
)

func TestTimeScannersWithSQLite(t *testing.T) {
	db, _ := testDB(t)
	want := time.Date(2026, 10, 19, 8, 30, 0, 0, Shanghai)
	for _, tt := range []struct {
		name, query string
		null, bad   bool
	}{
		{"文本", `SELECT '2026-10-19 08:30:00' AS stored_time`, false, false},
		{"字节", `SELECT CAST('2026-10-19 08:30:00' AS BLOB) AS stored_time`, false, false},
		{"空值", `SELECT NULL AS stored_time`, true, false},
		{"空字符串", `SELECT '' AS stored_time`, false, true},
		{"非法日期", `SELECT '2026-02-30 08:30:00' AS stored_time`, false, true},
		{"缺少时分秒", `SELECT '2026-10-19' AS stored_time`, false, true},
		{"数字", `SELECT 42 AS stored_time`, false, true},
	} {
		t.Run(tt.name, func(t *testing.T) {
			var required time.Time
			err := db.QueryRow(tt.query).Scan(scanTime(&required))
			if tt.null || tt.bad {
				if err == nil || !strings.Contains(err.Error(), "stored_time") {
					t.Fatalf("必填时间应返回带列名的错误: %v", err)
				}
			} else if err != nil || !required.Equal(want) || required.Location() != Shanghai {
				t.Fatalf("必填时间 = %v, err = %v", required, err)
			}

			previous := want.Add(-time.Hour)
			optional := &previous
			err = db.QueryRow(tt.query).Scan(scanOptionalTime(&optional))
			switch {
			case tt.bad:
				if err == nil || optional != &previous {
					t.Fatalf("非法时间应报错且保留原值: %v, %v", optional, err)
				}
			case tt.null:
				if err != nil || optional != nil {
					t.Fatalf("NULL 应清除原值: %v, %v", optional, err)
				}
			default:
				if err != nil || optional == nil || !optional.Equal(want) || optional.Location() != Shanghai {
					t.Fatalf("可空时间 = %v, err = %v", optional, err)
				}
			}
		})
	}

	// 写入仍使用现有转换，跨时区的输入应按上海时间存储和读回。
	var got time.Time
	utc := want.UTC().Add(123 * time.Millisecond)
	if err := db.QueryRow(`SELECT ?`, dateArg(&utc)).Scan(scanTime(&got)); err != nil || !got.Equal(want) {
		t.Fatalf("时间往返 = %v, err = %v", got, err)
	}
	if err := db.QueryRow(`SELECT ?`, dateArg(nil)).Scan(scanTime(&got)); err == nil {
		t.Fatal("必填时间不应接受写入的 NULL")
	}
	var parseErr *time.ParseError
	if err := db.QueryRow(`SELECT 'bad'`).Scan(scanTime(&got)); !errors.As(err, &parseErr) {
		t.Fatalf("扫描错误应保留时间解析错误: %v", err)
	}
	if err := scanTime(&got).Scan(want); err == nil {
		t.Fatal("不应接受驱动预解析的时间")
	}
}
