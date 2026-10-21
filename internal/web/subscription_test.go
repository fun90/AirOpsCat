package web

import (
	"bytes"
	"compress/gzip"
	"encoding/json"
	"io"
	"math"
	"math/big"
	"net/http"
	"net/http/httptest"
	"strings"

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
		if w.Header().Get("Content-Type") != "text/plain; charset=utf-8" || w.Header().Get("Referrer-Policy") != "strict-origin-when-cross-origin" {
			t.Fatal("订阅类型或安全头缺失", w.Header())
		}
		if w.Header().Get("profile-update-interval") != "72" || w.Header().Get("subscription-userinfo") == "" {
			t.Fatal("订阅响应头缺失")
		}
		if app == "sing-box" && !json.Valid(w.Body.Bytes()) {
			t.Error("sing-box 不是合法JSON")
		}
	}
	w := do(h, "GET", "/api/open/docs-info/"+code+"/", nil, nil, nil)
	if w.Header().Get("Content-Type") != "application/json; charset=utf-8" {
		t.Fatal("docs-info 类型缺失", w.Header())
	}
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
	for _, p := range []string{"/subscribe/config/" + code + "/windows/clash-meta/", "/api/open/docs-info/" + code, "/subscribe/nodes/" + code + "/loon", "/subscribe/rules/clash/myreject.yaml"} {
		plain := do(h, "GET", p, nil, nil, nil)
		compressed := do(h, "GET", p, nil, nil, map[string]string{"Accept-Encoding": "gzip"})
		if compressed.Header().Get("Content-Encoding") != "gzip" {
			t.Fatal("未压缩", p)
		}
		z, err := gzip.NewReader(compressed.Body)
		if err != nil {
			t.Fatal(err)
		}
		decoded, err := io.ReadAll(z)
		if err != nil {
			t.Fatal(err)
		}
		z.Close()
		// 模板含生成时间，逐请求时间字段不参与比较。
		normalize := func(body []byte) string {
			lines := strings.Split(string(body), "\n")
			for i, line := range lines {
				if strings.HasPrefix(line, "# Generated at:") {
					lines[i] = ""
				}
			}
			return strings.Join(lines, "\n")
		}
		if normalize(decoded) != normalize(plain.Body.Bytes()) {
			t.Fatal("docs-info 压缩正文变化")
		}
		if len(decoded) == 0 {
			t.Fatal("压缩响应为空")
		}
		head := do(h, "HEAD", p, nil, nil, map[string]string{"Accept-Encoding": "gzip"})
		if head.Code != 200 || head.Body.Len() != 0 || head.Header().Get("Content-Encoding") != "gzip" {
			t.Fatal("HEAD 兼容失败", head.Code, head.Header())
		}
	}
	for _, p := range []string{
		"/subscribe/config/" + code + "/windows/clash-meta/?dns=",
		"/subscribe/config/" + code + "/windows/clash-meta/?version=1&dns2=",
	} {
		if got := do(h, "GET", p, nil, nil, nil); got.Code != 200 {
			t.Fatal("空 DNS 参数", got.Code)
		}
	}
	if got := do(h, "GET", "/subscribe/rules/clash/myreject%2Fother.yaml", nil, nil, nil); got.Code != 404 {
		t.Fatal("编码斜杠未拒绝", got.Code)
	}
	if got := do(h, "GET", "/subscribe/config/"+code+"/windows/clash-meta%2Fother/", nil, nil, nil); got.Code != 400 {
		t.Fatal("应用名编码斜杠未拒绝", got.Code)
	}
	if _, err := db.Exec(`UPDATE account SET remark='' WHERE id=900`); err != nil {
		t.Fatal(err)
	}
	blank := do(h, "GET", "/api/open/docs-info/"+code, nil, nil, nil)
	var info map[string]any
	if err := json.Unmarshal(blank.Body.Bytes(), &info); err != nil {
		t.Fatal(err)
	}
	if _, exists := info["nickName"]; exists {
		t.Fatal("空昵称应省略")
	}
	if javaFormEncode("a *~中") != "a+*%7E%E4%B8%AD" {
		t.Fatal("Java URL编码错误")
	}

}

func TestUsedGiBHalfUp(t *testing.T) {
	for _, bytes := range []int64{0, 5368709, 5368710, 67108863, 67108864, 67108865, 1068373114, 1068373115, 1 << 30, math.MaxInt64} {
		// 独立精确有理数计算舍入结果，覆盖中点、进位及最大计数。
		n := new(big.Int).Mul(big.NewInt(bytes), big.NewInt(100))
		n.Add(n, big.NewInt(1<<29))
		n.Quo(n, big.NewInt(1<<30))
		q, r := new(big.Int), new(big.Int)
		q.QuoRem(n, big.NewInt(100), r)
		want := q.String() + "."
		if r.Int64() < 10 {
			want += "0"
		}
		want += r.String()
		if got := usedGiB(bytes); got != want {
			t.Errorf("%d: got %s want %s", bytes, got, want)
		}
	}
}

func TestSubscriptionCompression(t *testing.T) {
	body := []byte(strings.Repeat("订阅配置\n", 100))
	for _, tc := range []struct {
		accept string
		gzip   bool
	}{
		{"", false}, {"br", false}, {"gzip", true}, {"br, gzip;q=0.5", true}, {"*", true}, {"gzip;q=0, *;q=1", false}, {"gzip;q=invalid", false}, {"gzip;q=NaN", false}, {"gzip;q=2", false},
	} {
		t.Run(tc.accept, func(t *testing.T) {
			for _, method := range []string{http.MethodGet, http.MethodHead} {
				req := httptest.NewRequest(method, "/subscribe/rules/clash/test", nil)
				req.Header.Set("Accept-Encoding", tc.accept)
				w := httptest.NewRecorder()
				w.Header().Set("Content-Type", "text/plain; charset=utf-8")
				writeSubscription(w, req, body)
				if (w.Header().Get("Content-Encoding") == "gzip") != tc.gzip || w.Header().Get("Vary") != "Accept-Encoding" {
					t.Fatal(w.Header())
				}
				if method == http.MethodHead {
					if w.Body.Len() != 0 {
						t.Fatal("HEAD 响应有正文")
					}
					continue
				}
				got := w.Body.Bytes()
				if tc.gzip {
					z, err := gzip.NewReader(bytes.NewReader(got))
					if err != nil {
						t.Fatal(err)
					}
					got, err = io.ReadAll(z)
					if err != nil {
						t.Fatal(err)
					}
					z.Close()
				}
				if !bytes.Equal(got, body) {
					t.Fatal("解压后正文不匹配")
				}
			}
		})
	}
}

func TestSubscriptionCompressionHeaders(t *testing.T) {
	for _, encoded := range []bool{false, true} {
		req := httptest.NewRequest("GET", "/", nil)
		req.Header.Set("Accept-Encoding", "gzip")
		w := httptest.NewRecorder()
		w.Header().Set("Vary", "Origin")
		w.Header().Set("Content-Length", "4")
		if encoded {
			w.Header().Set("Content-Encoding", "br")
		}
		writeSubscription(w, req, []byte("body"))
		vary := strings.Join(w.Header().Values("Vary"), ",")
		if !strings.Contains(vary, "Origin") || !strings.Contains(vary, "Accept-Encoding") {
			t.Fatal(w.Header())
		}
		if encoded {
			if w.Header().Get("Content-Encoding") != "br" || w.Body.String() != "body" {
				t.Fatal("重复编码")
			}
		} else if w.Header().Get("Content-Length") != "" {
			t.Fatal("残留原始长度")
		}
	}
}

// 安装手册站点跨域读取 docs-info：只放行 airopscat.docs.url 的源，错误响应同样带头便于页面区分无效码。
func TestDocsInfoCORS(t *testing.T) {
	h, a := newTestServer(t)
	if _, err := a.DB.Exec(`INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.docs.url','https://docs.example.com/v2/','open')`); err != nil {
		t.Fatal(err)
	}
	for _, c := range []struct{ origin, want string }{
		{"https://docs.example.com", "https://docs.example.com"},
		{"https://evil.example.com", ""},
		{"", ""},
	} {
		hdr := map[string]string{}
		if c.origin != "" {
			hdr["Origin"] = c.origin
		}
		w := do(h, "GET", "/api/open/docs-info/bad", nil, nil, hdr)
		if w.Code != 400 || w.Header().Get("Access-Control-Allow-Origin") != c.want {
			t.Fatal(c.origin, w.Code, w.Header())
		}
		if !strings.Contains(strings.Join(w.Header().Values("Vary"), ","), "Origin") {
			t.Fatal("缺少 Vary: Origin", w.Header())
		}
	}
}
