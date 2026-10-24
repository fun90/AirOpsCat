package web

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"strconv"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) renewalRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/renewals", s.renewalList)
	m.HandleFunc("POST /admin/renewals", s.renewalSave)
}

func (s *Server) renewalList(w http.ResponseWriter, r *http.Request) {
	list, err := store.ListRenewals(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	now := time.Now().In(store.Shanghai)
	d := s.pageData(r, "续费", "renewals")
	d["List"], d["Methods"] = list, store.PaymentMethods
	d["Today"], d["Soon"] = now.Format("2006-01-02"), now.AddDate(0, 0, 30).Format("2006-01-02")
	s.render(w, "renewals", http.StatusOK, d)
}

func (s *Server) renewalSave(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(formValue(r, "id"), 10, 64)
	cents, err := store.ParseCents(formValue(r, "amount"))
	if err != nil {
		cents = 0 // 交给 store 统一报「请输入有效金额」
	}
	err = store.RenewResource(r.Context(), s.DB, formValue(r, "table"), id, formValue(r, "expire_date"), cents, formValue(r, "payment_method"))
	switch {
	case errors.Is(err, sql.ErrNoRows):
		http.NotFound(w, r)
	case errors.Is(err, store.ErrInvalid):
		redirectMsg(w, r, "/admin/renewals", "err", err.Error())
	case err != nil:
		s.fail(w, r, err)
	default:
		redirectMsg(w, r, "/admin/renewals", "msg", fmt.Sprintf("已续费至 %s，支出 %s 元", formValue(r, "expire_date"), store.FormatCents(cents)))
	}
}
