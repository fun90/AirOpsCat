package store

import (
	"database/sql"
	"errors"
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
