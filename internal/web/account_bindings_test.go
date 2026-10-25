package web

import (
	"net/url"
	"os"
	"strings"
	"testing"
)

func TestBindingPageListAndEdit(t *testing.T) {
	h, a := newTestServer(t)
	c := login(t, h, "admin@example.com")
	for _, q := range []string{
		`INSERT INTO tag(id,name) VALUES(1,'t')`,
		`INSERT INTO account(id,uuid,auth_code,account_no,period_type,bandwidth,remark)VALUES(7,'u7','code7xxxxxxxxxxxxxxx','a7','MONTHLY',0,'李四')`,
		`INSERT INTO account_tag(account_id,tag_id) VALUES(7,1)`,
		`INSERT INTO server(id,name,ip) VALUES(1,'hk-1','192.0.2.1'),(2,'jp-1','192.0.2.2')`,
		`INSERT INTO node(id,name,type,protocol,port) VALUES(1,'香港',0,'vless',443),(2,'日本',0,'vless',443)`,
		`INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(1,1,1,1),(2,2,2,1)`,
		`INSERT INTO node_tag(node_id,tag_id) VALUES(1,1),(2,1)`,
		`INSERT INTO domain(id,domain) VALUES(1,'example.com')`,
		`INSERT INTO domain_dns_record(id,domain_id,name,full_name,type,content,status,server_id) VALUES(1,1,'hk','hk.example.com','A','192.0.2.1','SYNCED',1)`,
		`INSERT INTO account_node_subscription_domain_binding(id,account_id,node_id,domain_dns_record_id,enabled,remark) VALUES(5,7,1,1,1,'主线路'),(6,7,2,NULL,0,NULL)`,
		`INSERT INTO account_node_subscription_placement(binding_id,node_server_id) VALUES(5,1),(6,2)`,
	} {
		if _, err := a.DB.Exec(q); err != nil {
			t.Fatal(q, err)
		}
	}
	body := do(h, "GET", "/admin/accounts/7/bindings", nil, c, nil).Body.String()
	if !strings.Contains(body, "hk.example.com") || !strings.Contains(body, "已停用") || !strings.Contains(body, "新增定制") || !strings.Contains(body, `href="?edit=5"`) {
		t.Fatal("列表或新增表单缺失", body[strings.Index(body, "<tbody>"):])
	}
	if os.Getenv("BINDING_SHOT") != "" {
		os.WriteFile(os.Getenv("BINDING_SHOT")+"list.html", []byte(body), 0o644)
	}
	body = do(h, "GET", "/admin/accounts/7/bindings?from=edit&edit=5", nil, c, nil).Body.String()
	if !strings.Contains(body, "编辑 · 香港") || !strings.Contains(body, `href="?from=edit&amp;edit=6"`) || !strings.Contains(body, `href="/admin/accounts/7/bindings?from=edit" class="btn btn-link">取消`) || !strings.Contains(body, `name="binding_id" value="5"`) || !strings.Contains(body, `data-current="1"`) || strings.Contains(body, "日本 →") || !strings.Contains(body, `href="/admin/accounts/7/edit" class="btn btn-outline-secondary" data-back>返回`) {
		t.Fatal("编辑表单未带入定制或返回地址错误")
	}
	if os.Getenv("BINDING_SHOT") != "" {
		os.WriteFile(os.Getenv("BINDING_SHOT")+"edit.html", []byte(body), 0o644)
	}
	// 编辑或新增保存后，无论入口都停留在该定制的编辑表单。
	csrf := a.CSRFToken(c.Value)
	for _, tc := range []struct{ from, binding, placement, want string }{
		{"", "5", "1", "/admin/accounts/7/bindings?edit=5&msg="},
		{"edit", "5", "1", "/admin/accounts/7/bindings?from=edit&edit=5&msg="},
		{"edit", "", "2", "/admin/accounts/7/bindings?from=edit&edit=6&msg="},
	} {
		form := url.Values{"csrf": {csrf}, "from": {tc.from}, "binding_id": {tc.binding}, "placement_id": {tc.placement}, "enabled": {"1"}}
		w := do(h, "POST", "/admin/accounts/7/bindings", form, c, map[string]string{"Referer": "http://example.com/admin/accounts/7/bindings"})
		if w.Code != 303 || !strings.HasPrefix(w.Header().Get("Location"), tc.want) {
			t.Fatal(tc, w.Code, w.Header().Get("Location"))
		}
	}
}
