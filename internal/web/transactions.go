package web

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"net/url"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

type transactionForm struct {
	ID              int64
	TransactionDate string
	Amount          string
	Type            string
	BusinessRef     string
	Description     string
	PaymentMethod   string
	Remark          string
}

func (s *Server) transactionRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/transactions", s.transactionList)
	m.HandleFunc("GET /admin/transactions/new", s.transactionNew)
	m.HandleFunc("GET /admin/transactions/{id}/edit", s.transactionEdit)
	m.HandleFunc("POST /admin/transactions", s.transactionSave)
	m.HandleFunc("POST /admin/transactions/{id}", s.transactionSave)
	m.HandleFunc("POST /admin/transactions/{id}/delete", s.transactionDelete)
}

func transactionID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	id, err := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err != nil || id < 1 {
		http.NotFound(w, r)
		return 0, false
	}
	return id, true
}

func transactionListURL(r *http.Request) string {
	q := r.URL.Query()
	q.Del("msg")
	q.Del("err")
	if len(q) == 0 {
		return "/admin/transactions"
	}
	return "/admin/transactions?" + q.Encode()
}

func transactionFilter(r *http.Request) (store.TransactionFilter, string, string, error) {
	f := store.TransactionFilter{Q: strings.TrimSpace(r.URL.Query().Get("q")), BusinessTable: r.URL.Query().Get("business")}
	if f.BusinessTable != "" && !slicesContains(store.TransactionBusinessTables, f.BusinessTable) {
		return f, "", "", fmt.Errorf("业务类型不正确")
	}
	if raw := r.URL.Query().Get("type"); raw != "" {
		n, err := strconv.Atoi(raw)
		if err != nil || (n != store.TransactionIncome && n != store.TransactionExpense) {
			return f, "", "", fmt.Errorf("收支类型不正确")
		}
		f.Type = &n
	}
	start, err := transactionFilterDate(r.URL.Query().Get("start_date"), false)
	if err != nil {
		return f, "", "", err
	}
	end, err := transactionFilterDate(r.URL.Query().Get("end_date"), true)
	if err != nil {
		return f, "", "", err
	}
	f.StartDate, f.EndDate = start, end
	return f, r.URL.Query().Get("start_date"), r.URL.Query().Get("end_date"), nil
}

func transactionFilterDate(raw string, end bool) (*time.Time, error) {
	if raw == "" {
		return nil, nil
	}
	t, err := time.ParseInLocation("2006-01-02", raw, store.Shanghai)
	if err != nil {
		return nil, fmt.Errorf("日期格式应为 YYYY-MM-DD")
	}
	if end {
		t = t.AddDate(0, 0, 1).Add(-time.Second)
	}
	return &t, nil
}

func (s *Server) transactionList(w http.ResponseWriter, r *http.Request) {
	f, start, end, err := transactionFilter(r)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	page, size := pageParams(r)
	list, total, err := store.TransactionList(r.Context(), s.DB, f, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	totals, err := store.TransactionSummary(r.Context(), s.DB, f)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "收支记账", "transactions")
	d["Totals"] = totals
	typeValue := ""
	if f.Type != nil {
		typeValue = strconv.Itoa(*f.Type)
	}
	d["List"], d["Q"], d["Type"], d["Business"], d["StartDate"], d["EndDate"] = list, f.Q, typeValue, f.BusinessTable, start, end
	d["BusinessTables"], d["Page"], d["Pages"], d["ListURL"] = store.TransactionBusinessTables, page, (total+size-1)/size, transactionListURL(r)
	d["BackQuery"] = url.QueryEscape(transactionListURL(r))
	s.render(w, "transactions", http.StatusOK, d)
}

func transactionToForm(t *store.Transaction) *transactionForm {
	f := &transactionForm{ID: t.ID, TransactionDate: t.TransactionDate.In(store.Shanghai).Format("2006-01-02T15:04:05"), Amount: store.FormatCents(t.Amount), Type: strconv.Itoa(t.Type), Description: t.Description, PaymentMethod: t.PaymentMethod, Remark: t.Remark}
	if t.BusinessID != nil {
		f.BusinessRef = t.BusinessTable + ":" + strconv.FormatInt(*t.BusinessID, 10)
	}
	return f
}

func (s *Server) transactionFormPage(w http.ResponseWriter, r *http.Request, status int, f *transactionForm, message string) {
	options, err := store.TransactionBusinessOptions(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	title := "新增流水"
	if f.ID != 0 {
		title = "编辑流水"
	}
	back := r.FormValue("back")
	d := s.pageData(r, title, "transactions")
	d["Form"], d["FormErr"], d["BusinessOptions"], d["PaymentMethods"], d["Back"] = f, message, options, store.PaymentMethods, back
	s.render(w, "transaction_form", status, d)
}

func (s *Server) transactionNew(w http.ResponseWriter, r *http.Request) {
	s.transactionFormPage(w, r, http.StatusOK, &transactionForm{TransactionDate: time.Now().In(store.Shanghai).Format("2006-01-02T15:04:05"), Type: "0"}, "")
}

func (s *Server) transactionEdit(w http.ResponseWriter, r *http.Request) {
	id, ok := transactionID(w, r)
	if !ok {
		return
	}
	t, err := store.TransactionByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if t == nil {
		http.NotFound(w, r)
		return
	}
	s.transactionFormPage(w, r, http.StatusOK, transactionToForm(t), "")
}

func transactionFromForm(r *http.Request, id int64) (*transactionForm, *store.Transaction, error) {
	f := &transactionForm{ID: id, TransactionDate: formValue(r, "transaction_date"), Amount: formValue(r, "amount"), Type: formValue(r, "type"), BusinessRef: formValue(r, "business_ref"), Description: formValue(r, "description"), PaymentMethod: formValue(r, "payment_method"), Remark: formValue(r, "remark")}
	t := &store.Transaction{ID: id, Description: f.Description, PaymentMethod: f.PaymentMethod, Remark: f.Remark}
	var err error
	for _, layout := range []string{"2006-01-02T15:04:05", "2006-01-02T15:04"} {
		if t.TransactionDate, err = time.ParseInLocation(layout, f.TransactionDate, store.Shanghai); err == nil {
			break
		}
	}
	if err != nil {
		return f, nil, fmt.Errorf("发生时间格式不正确")
	}
	if t.Amount, err = store.ParseCents(f.Amount); err != nil {
		return f, nil, err
	}
	if t.Type, err = strconv.Atoi(f.Type); err != nil {
		return f, nil, fmt.Errorf("收支类型不正确")
	}
	if f.BusinessRef != "" {
		table, rawID, ok := strings.Cut(f.BusinessRef, ":")
		businessID, parseErr := strconv.ParseInt(rawID, 10, 64)
		if !ok || parseErr != nil || businessID < 1 || !slicesContains(store.TransactionBusinessTables, table) {
			return f, nil, fmt.Errorf("关联业务不正确")
		}
		t.BusinessTable, t.BusinessID = table, &businessID
	}
	return f, t, nil
}

func (s *Server) transactionSave(w http.ResponseWriter, r *http.Request) {
	id := int64(0)
	if r.PathValue("id") != "" {
		var ok bool
		id, ok = transactionID(w, r)
		if !ok {
			return
		}
	}
	f, t, err := transactionFromForm(r, id)
	if err == nil {
		err = store.SaveTransaction(r.Context(), s.DB, t)
	}
	if err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, sql.ErrNoRows) || t == nil {
			if errors.Is(err, sql.ErrNoRows) {
				http.NotFound(w, r)
				return
			}
			s.transactionFormPage(w, r, http.StatusUnprocessableEntity, f, err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/transactions", "msg", "流水已保存")
}

func (s *Server) transactionDelete(w http.ResponseWriter, r *http.Request) {
	id, ok := transactionID(w, r)
	if !ok {
		return
	}
	if err := store.DeleteTransaction(r.Context(), s.DB, id); err != nil {
		if errors.Is(err, sql.ErrNoRows) {
			http.NotFound(w, r)
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/transactions", "msg", "流水已删除")
}

func slicesContains(values []string, value string) bool {
	return slicesIndex(values, value) >= 0
}

func slicesIndex(values []string, value string) int {
	for i, v := range values {
		if v == value {
			return i
		}
	}
	return -1
}
