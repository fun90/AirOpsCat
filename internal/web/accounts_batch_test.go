package web

import (
	"context"
	"net/url"
	"strconv"
	"testing"

	"github.com/fun90/airopscat/internal/store"
)

func TestAccountBatchTagsAndDeploy(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	csrf := a.CSRFToken(c.Value)
	form := url.Values{"csrf": {csrf}, "user_source": {"new"}, "email": {"batch@example.com"}, "new_password": {"secret123"}, "remark": {"批量"}, "period_type": {"MONTHLY"}, "node_multiple": {"2"}, "max_connections": {"100"}, "max_ips": {"2"}, "bandwidth": {"500"}}
	if w := do(h, "POST", "/admin/accounts", form, c, nil); w.Code != 303 {
		t.Fatalf("开户: %d", w.Code)
	}
	var id int64
	if err := a.DB.QueryRow(`SELECT id FROM account WHERE remark='批量'`).Scan(&id); err != nil {
		t.Fatal(err)
	}
	sid := strconv.FormatInt(id, 10)
	tag := &store.Tag{Name: "批量标签"}
	if err := store.SaveTag(context.Background(), a.DB, tag); err != nil {
		t.Fatal(err)
	}
	post := func(vals url.Values) int {
		vals.Set("csrf", csrf)
		return do(h, "POST", "/admin/accounts/batch-tags", vals, c, nil).Code
	}
	tagCount := func() (n int) {
		a.DB.QueryRow(`SELECT count(*) FROM account_tag WHERE account_id=? AND tag_id=?`, id, tag.ID).Scan(&n)
		return
	}
	if code := post(url.Values{"mode": {"APPEND"}, "account_ids": {sid}, "tag_ids": {strconv.FormatInt(tag.ID, 10)}}); code != 303 || tagCount() != 1 {
		t.Fatalf("追加标签失败: %d", code)
	}
	if code := post(url.Values{"mode": {"REPLACE"}, "account_ids": {sid}}); code != 303 || tagCount() != 0 {
		t.Fatalf("替换为空应清空标签: %d", code)
	}
	if code := post(url.Values{"account_ids": {"x"}}); code != 400 {
		t.Fatalf("非法 ID 应 400，得到 %d", code)
	}
	if w := do(h, "POST", "/admin/accounts/batch-deploy", url.Values{"csrf": {csrf}, "account_ids": {sid}}, c, nil); w.Code != 303 {
		t.Fatalf("批量部署: %d", w.Code)
	}
}
