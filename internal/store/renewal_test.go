package store

import (
	"errors"
	"testing"
)

func TestRenewResource(t *testing.T) {
	db, ctx := testDB(t)
	res, _ := db.Exec(`INSERT INTO server (ip,expire_date) VALUES ('192.0.2.1','2030-01-31')`)
	sid, _ := res.LastInsertId()
	res, _ = db.Exec(`INSERT INTO domain (domain,expire_date) VALUES ('example.com','2000-01-01')`)
	did, _ := res.LastInsertId()

	if err := RenewResource(ctx, db, "server", sid, "2031-01-31", 35200, "WeChat"); err != nil {
		t.Fatal(err)
	}
	if err := RenewResource(ctx, db, "domain", did, "2031-06-01", 12500, "AliPay"); err != nil { // 已过期，从今天起算
		t.Fatal(err)
	}
	var got, desc string
	var amount int64
	db.QueryRow(`SELECT expire_date FROM server WHERE id=?`, sid).Scan(&got)
	db.QueryRow(`SELECT amount,description FROM transactions WHERE business_table='server' AND business_id=?`, sid).Scan(&amount, &desc)
	if got != "2031-01-31" || amount != 35200 || desc != "服务器续费：192.0.2.1 12个月" {
		t.Fatalf("expire=%s amount=%d desc=%q", got, amount, desc)
	}
	// 非法参数都不改数据
	for name, f := range map[string]func() error{
		"不晚于原到期日": func() error { return RenewResource(ctx, db, "server", sid, "2031-01-31", 1, "WeChat") },
		"不晚于今天":   func() error { return RenewResource(ctx, db, "domain", did, "2001-01-01", 1, "WeChat") },
		"日期格式":    func() error { return RenewResource(ctx, db, "server", sid, "x", 1, "WeChat") },
		"金额":      func() error { return RenewResource(ctx, db, "server", sid, "2040-01-01", 0, "WeChat") },
		"付款方式":    func() error { return RenewResource(ctx, db, "server", sid, "2040-01-01", 1, "Cash") },
		"对象":      func() error { return RenewResource(ctx, db, "user", sid, "2040-01-01", 1, "WeChat") },
	} {
		if err := f(); !errors.Is(err, ErrInvalid) {
			t.Fatalf("%s: err=%v", name, err)
		}
	}
	var n int
	db.QueryRow(`SELECT count(*) FROM transactions`).Scan(&n)
	if n != 2 {
		t.Fatalf("失败后流水数=%d", n)
	}
}
