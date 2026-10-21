package store

import (
	"database/sql"
	"errors"
	"math"
	"testing"
	"time"
)

func TestTransactionCRUDFiltersAndBusinessValidation(t *testing.T) {
	db, ctx := testDB(t)
	account := onboard(t, db, "customer@example.com", nil)
	server, err := db.Exec(`INSERT INTO server (ip,name) VALUES ('192.0.2.1','HK')`)
	if err != nil {
		t.Fatal(err)
	}
	serverID, _ := server.LastInsertId()
	at := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	income := &Transaction{TransactionDate: at, Amount: 12345, Type: TransactionIncome, BusinessTable: "account", BusinessID: &account.AccountID, Description: "开户", PaymentMethod: "WeChat"}
	if err := SaveTransaction(ctx, db, income); err != nil {
		t.Fatal(err)
	}
	expense := &Transaction{TransactionDate: at.AddDate(0, 0, -1), Amount: 500, Type: TransactionExpense, BusinessTable: "server", BusinessID: &serverID, Description: "服务器", PaymentMethod: "BankCard"}
	if err := SaveTransaction(ctx, db, expense); err != nil {
		t.Fatal(err)
	}
	list, total, err := TransactionList(ctx, db, TransactionFilter{Type: ptrInt(TransactionIncome), BusinessTable: "account"}, 1, 20)
	if err != nil || total != 1 || len(list) != 1 || list[0].Amount != 12345 || list[0].BusinessName == "" {
		t.Fatalf("list=%+v total=%d err=%v", list, total, err)
	}
	start := at.Add(-time.Hour)
	end := at.Add(time.Hour)
	list, total, err = TransactionList(ctx, db, TransactionFilter{Q: "开户", StartDate: &start, EndDate: &end}, 1, 20)
	if err != nil || total != 1 || list[0].ID != income.ID {
		t.Fatalf("日期/搜索筛选失败: %+v %d %v", list, total, err)
	}
	income.Amount, income.Type, income.Description = 99, TransactionExpense, "修改后"
	if err := SaveTransaction(ctx, db, income); err != nil {
		t.Fatal(err)
	}
	got, err := TransactionByID(ctx, db, income.ID)
	if err != nil || got.Amount != 99 || got.Type != TransactionExpense || got.Description != "修改后" {
		t.Fatalf("update=%+v err=%v", got, err)
	}
	badID := int64(999)
	for _, bad := range []*Transaction{
		{TransactionDate: at, Amount: 1, Type: 2},
		{TransactionDate: at, Amount: 0, Type: TransactionIncome},
		{TransactionDate: at, Amount: -1, Type: TransactionExpense},
		{TransactionDate: at, Amount: 1, Type: TransactionIncome, PaymentMethod: "wechat"},
		{TransactionDate: at, Amount: 1, Type: TransactionIncome, BusinessTable: "account", BusinessID: &badID},
		{TransactionDate: at, Amount: 1, Type: TransactionIncome, BusinessTable: "other", BusinessID: &badID},
	} {
		if err := SaveTransaction(ctx, db, bad); !errors.Is(err, ErrInvalid) {
			t.Errorf("bad=%+v err=%v", bad, err)
		}
	}
	if err := DeleteTransaction(ctx, db, expense.ID); err != nil {
		t.Fatal(err)
	}
	if err := DeleteTransaction(ctx, db, expense.ID); !errors.Is(err, sql.ErrNoRows) {
		t.Fatal(err)
	}
}

func TestTransactionBusinessOptionsAndDeletedAccount(t *testing.T) {
	db, ctx := testDB(t)
	r := onboard(t, db, "customer@example.com", nil)
	id := r.AccountID
	if err := SaveTransaction(ctx, db, &Transaction{Amount: 1, Type: TransactionIncome, BusinessTable: "account", BusinessID: &id}); err != nil {
		t.Fatal(err)
	}
	if err := DeleteAccount(ctx, db, id); err != nil {
		t.Fatal(err)
	}
	list, _, err := TransactionList(ctx, db, TransactionFilter{}, 1, 20)
	if err != nil || len(list) != 1 || list[0].BusinessName != "未知账户" {
		t.Fatalf("deleted account transaction=%+v err=%v", list, err)
	}
	options, err := TransactionBusinessOptions(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	for _, option := range options {
		if option.Table == "account" && option.ID == id {
			t.Fatal("已删除账户不应再出现在关联选项")
		}
	}
}

func ptrInt(v int) *int { return &v }

func TestTransactionSummaryUsesFullFilter(t *testing.T) {
	db, ctx := testDB(t)
	at := time.Date(2026, 10, 20, 12, 0, 0, 0, Shanghai)
	for i := 0; i < 25; i++ {
		if err := SaveTransaction(ctx, db, &Transaction{TransactionDate: at, Amount: 123, Type: TransactionIncome, Description: "匹配"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := SaveTransaction(ctx, db, &Transaction{TransactionDate: at, Amount: 4000, Type: TransactionExpense, Description: "匹配"}); err != nil {
		t.Fatal(err)
	}
	if err := SaveTransaction(ctx, db, &Transaction{TransactionDate: at.AddDate(0, 0, -1), Amount: 10, Type: TransactionIncome, Description: "其他"}); err != nil {
		t.Fatal(err)
	}
	for _, tc := range []struct {
		filter          TransactionFilter
		income, expense int64
	}{
		{TransactionFilter{}, 3085, 4000},
		{TransactionFilter{Q: "匹配", StartDate: &at, EndDate: &at}, 3075, 4000},
		{TransactionFilter{Type: ptrInt(TransactionExpense)}, 0, 4000},
		{TransactionFilter{BusinessTable: "account"}, 0, 0},
		{TransactionFilter{Q: "无结果"}, 0, 0},
	} {
		got, err := TransactionSummary(ctx, db, tc.filter)
		if err != nil || got.Income != tc.income || got.Expense != tc.expense || got.Balance != tc.income-tc.expense {
			t.Fatal(got, err)
		}
	}
}

func TestTransactionSummaryPaginationAndBoundaries(t *testing.T) {
	db, ctx := testDB(t)
	account := onboard(t, db, "summary@example.com", nil)
	at := time.Date(2026, 10, 20, 0, 0, 0, 0, Shanghai)
	end := at.AddDate(0, 0, 1).Add(-time.Second)
	for i := 0; i < 25; i++ {
		stamp := at
		if i == 24 {
			stamp = end
		}
		if err := SaveTransaction(ctx, db, &Transaction{TransactionDate: stamp, Amount: 101, Type: TransactionIncome, BusinessTable: "account", BusinessID: &account.AccountID, Description: "相同"}); err != nil {
			t.Fatal(err)
		}
	}
	for _, stamp := range []time.Time{at.Add(-time.Second), end.Add(time.Second)} {
		if err := SaveTransaction(ctx, db, &Transaction{TransactionDate: stamp, Amount: 999, Type: TransactionIncome, BusinessTable: "account", BusinessID: &account.AccountID, Description: "相同"}); err != nil {
			t.Fatal(err)
		}
	}
	if err := SaveTransaction(ctx, db, &Transaction{TransactionDate: at, Amount: 777, Type: TransactionIncome, Description: "相同"}); err != nil {
		t.Fatal(err)
	}
	f := TransactionFilter{Q: "相同", BusinessTable: "account", StartDate: &at, EndDate: &end}
	list, total, err := TransactionList(ctx, db, f, 1, 20)
	if err != nil || len(list) != 20 || total != 25 {
		t.Fatal(len(list), total, err)
	}
	got, err := TransactionSummary(ctx, db, f)
	if err != nil || got.Income != 2525 || got.Balance != 2525 {
		t.Fatal(got, err)
	}
}

func TestTransactionSummaryOverflow(t *testing.T) {
	for _, tc := range []struct {
		name    string
		amounts []int64
		types   []int
	}{
		{"sum", []int64{math.MaxInt64, 1}, []int{0, 0}},
		{"balance", []int64{math.MaxInt64, -1}, []int{0, 1}},
	} {
		t.Run(tc.name, func(t *testing.T) {
			db, ctx := testDB(t)
			for i, amount := range tc.amounts {
				if _, err := db.Exec(`INSERT INTO transactions(transaction_date,amount,type) VALUES('2026-10-20 00:00:00',?,?)`, amount, tc.types[i]); err != nil {
					t.Fatal(err)
				}
			}
			if _, err := TransactionSummary(ctx, db, TransactionFilter{}); err == nil {
				t.Fatal("溢出未返回错误")
			}
		})
	}
}
