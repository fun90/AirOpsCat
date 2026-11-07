package web

import (
	"net/http"
	"net/url"
	"regexp"
	"strings"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/auth"
	"github.com/fun90/airopscat/internal/store"
)

// expiryFixture 准备四个账户：1 已到期且手动禁用、2 长期有效、3 有效期内、4 有效期内且手动禁用。
func expiryFixture(t *testing.T) (http.Handler, *auth.Service, *http.Cookie) {
	t.Helper()
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	past := store.FormatTime(time.Now().Add(-time.Hour))
	future := store.FormatTime(time.Now().AddDate(0, 1, 0))
	if _, err := a.DB.Exec(`INSERT INTO account(id,uuid,account_no,auth_code,period_type,node_multiple,from_date,to_date,disabled,user_id,remark) VALUES
		(1,'00000000-0000-4000-8000-000000000001','E1','expired-code-0001','MONTHLY',1,'2020-01-01 00:00:00',?,1,1,'到期禁用'),
		(2,'00000000-0000-4000-8000-000000000002','E2','forever-code-0002','MONTHLY',1,'2020-01-01 00:00:00',NULL,0,1,'长期有效'),
		(3,'00000000-0000-4000-8000-000000000003','E3','active-code-00003','MONTHLY',1,'2020-01-01 00:00:00',?,0,1,'有效'),
		(4,'00000000-0000-4000-8000-000000000004','E4','banned-code-00004','MONTHLY',1,'2020-01-01 00:00:00',?,1,1,'手动禁用')`, past, future, future); err != nil {
		t.Fatal(err)
	}
	return h, a, c
}

// redirectQuery 返回 303 跳转地址中的提示参数。
func redirectQuery(t *testing.T, w interface {
	Header() http.Header
}) url.Values {
	t.Helper()
	u, err := url.Parse(w.Header().Get("Location"))
	if err != nil {
		t.Fatal(err)
	}
	return u.Query()
}

func TestAccountToggleExpiredHint(t *testing.T) {
	h, a, c := expiryFixture(t)
	csrf := a.CSRFToken(c.Value)
	w := do(h, "POST", "/admin/accounts/1/toggle", url.Values{"csrf": {csrf}, "disabled": {"0"}}, c, nil)
	if w.Code != 303 {
		t.Fatal(w.Code, w.Body.String())
	}
	if msg := redirectQuery(t, w).Get("msg"); msg != "已启用，但账户已到期，需续期后才可用" {
		t.Fatalf("启用已到期账户的提示: %q", msg)
	}
	var disabled bool
	a.DB.QueryRow(`SELECT disabled FROM account WHERE id=1`).Scan(&disabled)
	if disabled {
		t.Fatal("应已启用")
	}
	// 有效账户启停沿用原提示
	w = do(h, "POST", "/admin/accounts/4/toggle", url.Values{"csrf": {csrf}, "disabled": {"0"}}, c, nil)
	if msg := redirectQuery(t, w).Get("msg"); msg != "账户状态已更新，相关节点需重新部署" {
		t.Fatalf("有效账户提示: %q", msg)
	}
}

func TestAccountRenewHints(t *testing.T) {
	h, a, c := expiryFixture(t)
	csrf := a.CSRFToken(c.Value)
	to := time.Now().AddDate(0, 2, 0).Format("2006-01-02T15:04")
	// 长期有效账户拒绝续期
	w := do(h, "POST", "/admin/accounts/2/renew", url.Values{"csrf": {csrf}, "to_date": {to}}, c, nil)
	if q := redirectQuery(t, w); !strings.Contains(q.Get("err"), "该账户长期有效，如需设置到期日请编辑账户") {
		t.Fatalf("长期有效账户续期: %d %v", w.Code, q)
	}
	var toDate *string
	a.DB.QueryRow(`SELECT to_date FROM account WHERE id=2`).Scan(&toDate)
	if toDate != nil {
		t.Fatal("长期有效账户被写入到期日", *toDate)
	}
	// 手动禁用的账户续期后仍禁用，提示需启用
	w = do(h, "POST", "/admin/accounts/4/renew", url.Values{"csrf": {csrf}, "to_date": {to}}, c, nil)
	if q := redirectQuery(t, w); !strings.Contains(q.Get("msg"), "账户仍处于禁用状态") {
		t.Fatalf("禁用账户续期提示: %d %v", w.Code, q)
	}
	var disabled bool
	a.DB.QueryRow(`SELECT disabled FROM account WHERE id=4`).Scan(&disabled)
	if !disabled {
		t.Fatal("续期不应解除手动禁用")
	}
}

func TestAccountEditRequiresUser(t *testing.T) {
	h, a, c := expiryFixture(t)
	body := do(h, "GET", "/admin/accounts/3/edit", nil, c, nil).Body.String()
	if !regexp.MustCompile(`<select name="user_id" id="user_id" class="form-select"[^>]* required`).MatchString(body) {
		t.Fatal("编辑页所属用户应为必填")
	}
	form := url.Values{"csrf": {a.CSRFToken(c.Value)}, "user_id": {""}, "user_source": {"existing"}, "remark": {"有效"},
		"account_no": {"E3"}, "uuid": {"00000000-0000-4000-8000-000000000003"}, "period_type": {"MONTHLY"}, "node_multiple": {"1"},
		"max_connections": {"0"}, "max_ips": {"0"}, "bandwidth": {"0"}, "from_date": {"2020-01-01T00:00"}}
	w := do(h, "POST", "/admin/accounts/3", form, c, nil)
	if w.Code != 422 || !strings.Contains(w.Body.String(), "请选择所属用户") {
		t.Fatalf("空所属用户应被拒绝: %d", w.Code)
	}
	var uid *int64
	a.DB.QueryRow(`SELECT user_id FROM account WHERE id=3`).Scan(&uid)
	if uid == nil || *uid != 1 {
		t.Fatal("user_id 被清空")
	}
}

func TestPermanentAccountHidesRenew(t *testing.T) {
	h, _, c := expiryFixture(t)
	forever := do(h, "GET", "/admin/accounts/2/edit", nil, c, nil).Body.String()
	if strings.Contains(forever, `id="renew"`) || strings.Contains(forever, `href="#renew"`) {
		t.Fatal("长期有效账户不应显示续期入口")
	}
	dated := do(h, "GET", "/admin/accounts/3/edit", nil, c, nil).Body.String()
	if !strings.Contains(dated, `id="renew"`) || !strings.Contains(dated, `href="#renew"`) {
		t.Fatal("有到期日的账户应显示续期入口")
	}
	list := do(h, "GET", "/admin/accounts", nil, c, nil).Body.String()
	if strings.Contains(list, `data-renew-id="2"`) || !strings.Contains(list, `data-renew-id="3"`) || !strings.Contains(list, `id="accountRenewModal"`) {
		t.Fatal("列表续期入口应只给有到期日的账户")
	}
}

func TestAccountListShowsExpiredAndDisabledSeparately(t *testing.T) {
	h, _, c := expiryFixture(t)
	body := do(h, "GET", "/admin/accounts", nil, c, nil).Body.String()
	if !strings.Contains(body, `<option value="expired"`) {
		t.Fatal("状态筛选缺少「已到期」")
	}
	row := body[strings.Index(body, `>到期禁用</a>`):]
	row = row[:strings.Index(row, "</td>")]
	if !strings.Contains(row, ">已禁用</span>") || !strings.Contains(row, ">已过期</span>") {
		t.Fatalf("已到期且禁用的账户应同时显示两个徽标: %s", row)
	}
	expired := do(h, "GET", "/admin/accounts?status=expired", nil, c, nil).Body.String()
	if !strings.Contains(expired, ">到期禁用</a>") || strings.Contains(expired, ">手动禁用</a>") {
		t.Fatal("已到期筛选应与禁用无关")
	}
	dash := do(h, "GET", "/admin", nil, c, nil).Body.String()
	if !strings.Contains(dash, `href="/admin/accounts?status=expired"`) {
		t.Fatal("概览过期数应链接到已到期筛选")
	}
}
