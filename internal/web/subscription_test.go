package web

import (
	"encoding/json"

	"testing"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func TestPublicSubscriptionAndDocs(t *testing.T) {
	h, a := newTestServer(t)
	db := a.DB
	expires := store.FormatTime(time.Now().AddDate(0, 1, 0))
	code := "subscription-code-123456789"
	_, err := db.Exec(`INSERT INTO account(id,uuid,account_no,auth_code,remark,period_type,to_date,bandwidth) VALUES(900,'uuid','A900',?,'测试 A*~','MONTHLY',?,500);
 INSERT INTO tag(id,name) VALUES(900,'sub');INSERT INTO account_tag VALUES(900,900);
 INSERT INTO server(id,ip) VALUES(900,'203.0.113.9');
 INSERT INTO node(id,name,type,port,protocol,inbound) VALUES(900,'测试',0,443,'vless','{"type":"vless"}');
 INSERT INTO node_tag VALUES(900,900);INSERT INTO node_server(id,node_id,server_id,deployed) VALUES(900,900,900,1);`, code, expires)
	if err != nil {
		t.Fatal(err)
	}
	_, err = db.Exec(`INSERT INTO node_deployment(node_id,node_server_id,server_id,version,snapshot_hash,snapshot_json) VALUES(900,900,900,1,'x','{"type":0,"disabled":0,"port":443,"protocol":"vless","coreType":"sing-box","inbound":{"type":"vless"}}')`)
	if err != nil {
		t.Fatal(err)
	}
	for _, app := range []string{"clash-meta", "clash-meta2", "clash-mi", "clash-verge", "clash-verge-mrs", "stash", "loon", "shadowrocket", "sing-box"} {
		p := "/subscribe/config/" + code + "/windows/" + app + "/"
		w := do(h, "GET", p, nil, nil, nil)
		if w.Code != 200 {
			t.Errorf("%s: %d %s", app, w.Code, w.Body.String())
			continue
		}
		if w.Header().Get("profile-update-interval") != "72" || w.Header().Get("subscription-userinfo") == "" {
			t.Fatal("订阅响应头缺失")
		}
		if app == "sing-box" && !json.Valid(w.Body.Bytes()) {
			t.Error("sing-box 不是合法JSON")
		}
	}
	w := do(h, "GET", "/api/open/docs-info/"+code+"/", nil, nil, nil)
	if w.Code != 200 || !json.Valid(w.Body.Bytes()) {
		t.Fatal(w.Code, w.Body.String())
	}
	for _, p := range []struct {
		path   string
		status int
	}{{"/subscribe/config/bad/windows/clash-meta/", 400}, {"/subscribe/nodes/bad/loon", 200}, {"/api/open/docs-info/bad", 400}} {
		w = do(h, "GET", p.path, nil, nil, nil)
		if w.Code != p.status {
			t.Fatal(p.path, w.Code)
		}
	}
	w = do(h, "GET", "/subscribe/config/"+code+"/windows/clash-meta/?view=1", nil, nil, nil)
	if w.Header().Get("Content-Disposition") != "" {
		t.Fatal("view=1不应下载")
	}
	if javaFormEncode("a *~中") != "a+*%7E%E4%B8%AD" {
		t.Fatal("Java URL编码错误")
	}

}
