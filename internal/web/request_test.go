package web

import (
	"database/sql"
	"errors"
	"fmt"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strings"
	"testing"

	"github.com/fun90/airopscat/internal/store"
)

func TestRedirectErrorPolicy(t *testing.T) {
	invalid := fmt.Errorf("%w：名称含空格 & /", store.ErrInvalid)
	conflict := fmt.Errorf("%w：已被使用", store.ErrConflict)
	for _, tt := range []struct {
		name    string
		err     error
		visible []error
		status  int
	}{
		{"成功", nil, nil, 200},
		{"允许输入错误", invalid, []error{store.ErrInvalid}, 303},
		{"允许冲突", conflict, []error{store.ErrInvalid, store.ErrConflict}, 303},
		{"未允许的冲突", conflict, []error{store.ErrInvalid}, 500},
		{"未允许的输入错误", invalid, nil, 500},
		{"不存在仍按内部错误处理", sql.ErrNoRows, []error{store.ErrInvalid}, 500},
		{"内部错误", errors.New("数据库内部详情"), []error{store.ErrInvalid, store.ErrConflict}, 500},
	} {
		t.Run(tt.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPost, "/admin/items/1", nil)
			r.PostForm = url.Values{"back": {"/admin/items?page=2&q=hello&msg=old"}}
			w := httptest.NewRecorder()
			handled := new(Server).redirectError(w, r, "/admin/items", tt.err, tt.visible...)
			if handled != (tt.err != nil) || w.Code != tt.status {
				t.Fatalf("handled=%v status=%d", handled, w.Code)
			}
			if tt.status == 303 {
				u, err := url.Parse(w.Header().Get("Location"))
				if err != nil {
					t.Fatal(err)
				}
				if u.Path != "/admin/items" || u.Query().Get("err") != tt.err.Error() || u.Query().Get("page") != "2" || u.Query().Get("q") != "hello" || u.Query().Has("msg") {
					t.Fatalf("跳转未保留筛选或错误编码不正确：%s", u)
				}
			} else if w.Header().Get("Location") != "" {
				t.Fatal("不应跳转")
			}
			if tt.status == 500 && w.Body.String() != "系统内部错误\n" {
				t.Fatalf("内部错误不应回显：%q", w.Body.String())
			}
			if tt.err == nil && w.Body.Len() != 0 {
				t.Fatal("成功时不应写响应")
			}
		})
	}
}

func TestRecordAndRequestErrorPolicies(t *testing.T) {
	for _, tt := range []struct {
		name          string
		found         bool
		err           error
		recordStatus  int
		requestStatus int
	}{
		{"查到记录", true, nil, 200, 200},
		{"空结果", false, nil, 404, 200},
		{"无行错误", false, sql.ErrNoRows, 500, 404},
		{"包装无行错误", false, fmt.Errorf("查询：%w", sql.ErrNoRows), 500, 404},
		{"查询失败", false, errors.New("内部查询详情"), 500, 500},
		{"有结果但仍有错误", true, errors.New("内部查询详情"), 500, 500},
	} {
		t.Run(tt.name, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodGet, "/admin/items/1", nil)
			s := new(Server)
			w := httptest.NewRecorder()
			if ok := s.recordFound(w, r, tt.found, tt.err); ok != (tt.found && tt.err == nil) || w.Code != tt.recordStatus {
				t.Fatalf("recordFound=%v status=%d", ok, w.Code)
			}
			w = httptest.NewRecorder()
			if handled := s.requestError(w, r, tt.err); handled != (tt.err != nil) || w.Code != tt.requestStatus {
				t.Fatalf("requestError=%v status=%d", handled, w.Code)
			}
			if tt.requestStatus == 500 && w.Body.String() != "系统内部错误\n" {
				t.Fatal("内部错误不应回显")
			}
		})
	}
}

func TestOptionalPathID(t *testing.T) {
	for _, raw := range []string{"", "1", "9223372036854775807", "0", "-1", "abc", " 1", "9223372036854775808"} {
		t.Run("id="+raw, func(t *testing.T) {
			r := httptest.NewRequest(http.MethodPost, "/admin/items", nil)
			r.SetPathValue("id", raw)
			w := httptest.NewRecorder()
			id, ok := optionalPathID(w, r)
			valid := raw == "" || raw == "1" || raw == "9223372036854775807"
			if ok != valid || (!valid && w.Code != 404) || (raw == "" && id != 0) || (valid && raw != "" && id < 1) {
				t.Fatalf("id=%d ok=%v status=%d", id, ok, w.Code)
			}
		})
	}
}

func TestAccountAndTransactionFormTime(t *testing.T) {
	for _, tt := range []struct {
		raw, want string
	}{
		{"2026-02-28T12:34", "2026-02-28 12:34:00"},
		{"2026-02-28T12:34:56", "2026-02-28 12:34:56"},
		{"2024-02-29T00:00:00", "2024-02-29 00:00:00"},
		{"", ""},
		{"2026-02-29T12:34", ""},
		{"2026-02-28T24:00", ""},
		{"2026-02-28", ""},
		{"2026-02-28T12:34:56Z", ""},
	} {
		t.Run("日期="+tt.raw, func(t *testing.T) {
			got, err := sqlDate(tt.raw)
			if got != tt.want || (err != nil) != (tt.want == "" && tt.raw != "") {
				t.Fatalf("账户日期=%q err=%v", got, err)
			}
			values := url.Values{"transaction_date": {tt.raw}, "amount": {"1.00"}, "type": {"0"}}
			r := httptest.NewRequest(http.MethodPost, "/admin/transactions", strings.NewReader(values.Encode()))
			r.Header.Set("Content-Type", "application/x-www-form-urlencoded")
			f, transaction, err := transactionFromForm(r, 0)
			if f.TransactionDate != tt.raw {
				t.Fatal("校验后应保留输入供回显")
			}
			if tt.want == "" {
				if err == nil || err.Error() != "发生时间格式不正确" {
					t.Fatalf("流水必须填写有效时间：%v", err)
				}
				return
			}
			if err != nil {
				t.Fatal(err)
			}
			_, offset := transaction.TransactionDate.Zone()
			if transaction.TransactionDate.Format(store.TimeLayout) != tt.want || offset != 8*60*60 {
				t.Fatalf("时间或时区不正确：%v", transaction.TransactionDate)
			}
		})
	}
}
