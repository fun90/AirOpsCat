package subscribe

import (
	"bytes"
	"crypto/sha256"
	"encoding/json"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"testing/fstest"
)

func fixture(scenario string) map[string]any {
	node := func(protocol, name string) map[string]any {
		return map[string]any{
			"protocol": protocol, "name": name, "serverHost": "example.com", "port": 443, "coreType": "sing-box",
			"inbound": map[string]any{"tls": map[string]any{"server_name": "tls.example.com", "reality": map[string]any{"public_key": "public-key", "short_id": []any{"abcd"}}}, "obfs": map[string]any{"password": "obfs +/*~中"}},
		}
	}
	a := node("vless-reality", "日本-1")
	if scenario == "special" {
		a["name"] = "日本 A+/*~&\"\\中"
	}
	if scenario == "null" {
		a["serverHost"] = nil
	}
	nodes := []map[string]any{a}
	if scenario == "multi" || scenario == "dns" {
		nodes = append(nodes, node("hysteria2", "新加坡-2"), node("vless", "美国-3"))
	}
	data := map[string]any{"nodes": nodes, "account": map[string]any{"uuid": "account-uuid", "authCode": "test-auth"}, "subscriptionUrl": "https://example.com/subscribe", "timestamp": 123456789, "osName": "ios", "appName": "test"}
	if scenario == "dns" {
		data["dns"] = "8.8.8.8"
	}
	return data
}

// 金样摘要由生产 Qute 3.24.4 独立渲染生成：strict、removeStandaloneLines、ReflectionValueResolver、capture、urlEncode 和列表索引解析器，无内容类型变体。
func TestProductionQuteGolden(t *testing.T) {
	for key, want := range quteGolden {
		scenario, path, _ := strings.Cut(key, "/")
		t.Run(key, func(t *testing.T) {
			out, err := Render(Templates, path, fixture(scenario))
			if err != nil {
				t.Fatal(err)
			}
			if got := fmt.Sprintf("%x", sha256.Sum256(out)); got != want {
				t.Fatalf("Qute 金样不符: got %s want %s\n%s", got, want, out)
			}
			if path == "sing-box.json" && scenario != "special" && !json.Valid(out) {
				t.Fatal("JSON 无效")
			}
		})
	}
}

func TestRenderContract(t *testing.T) {
	for _, tc := range []struct {
		source, want string
		data         map[string]any
		fail         bool
	}{
		{`{{text .required}}`, "", map[string]any{}, true},
		{`{{text .required}}`, "", map[string]any{"required": nil}, false},
		{`{{default "fallback" (get . "dns")}}`, "fallback", map[string]any{}, false},
		{`{{default "fallback" (get . "dns")}}`, "", map[string]any{"dns": ""}, false},
		{`{{default "fallback" (get . "dns")}}`, "false", map[string]any{"dns": false}, false},
		{`{{urlEncode .v}}`, "A%20%2B%2F*%7E%26%E4%B8%AD", map[string]any{"v": "A +/*~&中"}, false},
		{`{{urlEncode .v}}`, "", map[string]any{"v": nil}, false},
		{`{{text .v}}`, `<&"`, map[string]any{"v": `<&"`}, false},
		{`{{text (index .v 0)}}`, "", map[string]any{"v": []any{}}, true},
	} {
		out, err := Render(fstest.MapFS{"t": {Data: []byte(tc.source)}}, "t", tc.data)
		if (err != nil) != tc.fail {
			t.Fatalf("%s: %v", tc.source, err)
		}
		if !tc.fail && string(out) != tc.want {
			t.Fatalf("got %q want %q", out, tc.want)
		}
	}
	if _, err := Render(Templates, "../sing-box.json", nil); err == nil {
		t.Fatal("接受非法 FS 路径")
	}
	for _, path := range []string{"sing-box.json", "clash-meta.yaml", "nodes/loon.html", "nodes/shadowrocket.html"} {
		d := fixture("single")
		delete(d["nodes"].([]map[string]any)[0]["inbound"].(map[string]any)["tls"].(map[string]any)["reality"].(map[string]any), "public_key")
		if _, err := Render(Templates, path, d); err == nil {
			t.Fatalf("%s 未拒绝缺失必填键", path)
		}
	}
}

func TestTemplateTrees(t *testing.T) {
	external := os.DirFS("../../../profile/airopscat/subscription-go")
	original := os.DirFS("../../../profile/airopscat/subscription")
	if _, err := fs.Stat(external, "sing-box.json"); err != nil {
		t.Skip("同级 profile 仓库未检出")
	}
	count := 0
	err := fs.WalkDir(Templates, ".", func(path string, e fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if e.IsDir() {
			return nil
		}
		count++
		a, err := fs.ReadFile(Templates, path)
		if err != nil {
			return err
		}
		b, err := fs.ReadFile(external, path)
		if err != nil {
			return err
		}
		if !bytes.Equal(a, b) {
			t.Errorf("两份模板不同: %s", path)
		}
		if strings.HasPrefix(path, "rules/") {
			old, err := fs.ReadFile(original, path)
			if err != nil {
				return err
			}
			if !bytes.Equal(a, old) {
				t.Errorf("规则被更改: %s", path)
			}
		}
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if count != 28 {
		t.Fatalf("文件数 %d", count)
	}
	_ = fs.WalkDir(external, ".", func(path string, e fs.DirEntry, err error) error {
		if err != nil {
			t.Fatal(err)
		}
		if !e.IsDir() {
			if _, err := fs.Stat(Templates, path); err != nil {
				t.Error(err)
			}
		}
		return nil
	})
}

func TestShadowTLS(t *testing.T) {
	data := fixture("multi")
	nodes := data["nodes"].([]map[string]any)
	st := map[string]any{"name": "日本 ShadowTLS", "protocol": "shadowtls", "coreType": "sing-box", "serverHost": "shadow.example.com", "port": 8443, "inbound": map[string]any{"version": 3, "handshake": map[string]any{"server": "www.apple.com"}, "users": []any{map[string]any{"password": "outer-secret"}}}}
	data["nodes"] = append(nodes, st)
	out, err := Render(Templates, "sing-box.json", data)
	if err != nil {
		t.Fatal(err)
	}
	var config struct {
		Outbounds []map[string]any `json:"outbounds"`
	}
	if err = json.Unmarshal(out, &config); err != nil {
		t.Fatal(err)
	}
	byTag := map[string]map[string]any{}
	for _, o := range config.Outbounds {
		tag := o["tag"].(string)
		if byTag[tag] != nil {
			t.Fatal("重复 tag")
		}
		byTag[tag] = o
	}
	ss := byTag["日本 ShadowTLS"]
	if ss["type"] != "shadowsocks" || ss["method"] != "chacha20-ietf-poly1305" || ss["password"] != "account-uuid" || ss["network"] != "tcp" || ss["server"] != "shadow.example.com" || ss["server_port"] != float64(8443) {
		t.Fatalf("内层错误: %v", ss)
	}
	aux := byTag[ss["detour"].(string)]
	if aux["type"] != "shadowtls" || aux["password"] != "outer-secret" || aux["version"] != float64(3) {
		t.Fatalf("外层错误: %v", aux)
	}
	for _, o := range config.Outbounds {
		for _, n := range asList(o["outbounds"]) {
			if n == aux["tag"] {
				t.Fatal("策略组引用辅助出站")
			}
			if byTag[n.(string)] == nil {
				t.Fatalf("悬空引用 %s", n)
			}
		}
	}
	for _, path := range []string{"clash-meta.yaml", "clash-meta2.yaml", "clash-mi.yaml", "clash-verge.yaml", "clash-verge-mrs.yaml", "stash.yaml", "nodes/loon.html"} {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatal(err)
		}
		for _, s := range []string{"chacha20-ietf-poly1305", "outer-secret", "account-uuid", "www.apple.com", "udp: false"} {
			if filepath.Ext(path) == ".html" && s == "udp: false" {
				s = "udp=false"
			}
			if !strings.Contains(string(out), s) {
				t.Errorf("%s 缺少 %s", path, s)
			}
		}
	}
	for _, path := range []string{"shadowrocket.conf", "nodes/shadowrocket.html"} {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatal(err)
		}
		if strings.Contains(string(out), "日本 ShadowTLS") {
			t.Errorf("%s 输出未知支持节点", path)
		}
	}
	// 辅助标签需避让真实节点名。
	nodes[0]["name"] = "__airopscat_shadowtls_3"
	if tag := shadowTag(3, append(nodes, st)); tag != "__airopscat_shadowtls_3_" {
		t.Fatal(tag)
	}
	delete(st["inbound"].(map[string]any), "users")
	if _, err := Render(Templates, "sing-box.json", data); err == nil {
		t.Fatal("外层密码缺失未报错")
	}
}
func TestAnyTLS(t *testing.T) {
	data := fixture("multi")
	at := map[string]any{"name": "日本 AnyTLS", "protocol": "anytls", "coreType": "sing-box", "serverHost": "any.example.com", "port": 8443, "inbound": map[string]any{"tls": map[string]any{"server_name": "any.example.com"}}}
	data["nodes"] = append(data["nodes"].([]map[string]any), at)
	out, err := Render(Templates, "sing-box.json", data)
	if err != nil {
		t.Fatal(err)
	}
	var config struct {
		Outbounds []map[string]any `json:"outbounds"`
	}
	if err = json.Unmarshal(out, &config); err != nil {
		t.Fatal(err)
	}
	var found bool
	for _, o := range config.Outbounds {
		if o["tag"] == "日本 AnyTLS" {
			found = o["type"] == "anytls" && o["password"] == "account-uuid" && o["server_port"] == float64(8443)
		}
	}
	if !found {
		t.Fatal("sing-box 缺少 AnyTLS 出站")
	}
	for _, path := range []string{"clash-meta.yaml", "clash-meta2.yaml", "clash-mi.yaml", "clash-verge.yaml", "clash-verge-mrs.yaml", "stash.yaml"} {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatal(err)
		}
		// 一次代理定义 + 至少一个策略组引用。
		if s := string(out); !strings.Contains(s, "type: anytls") || strings.Count(s, "- 日本 AnyTLS") < 1 || !strings.Contains(s, "- name: 日本 AnyTLS") {
			t.Errorf("%s 缺少 AnyTLS 节点或分组", path)
		}
	}
	for path, want := range map[string]string{"nodes/loon.html": "日本 AnyTLS=AnyTLS,any.example.com,8443,\"account-uuid\"", "nodes/shadowrocket.html": "anytls://account-uuid@any.example.com:8443?sni=any.example.com"} {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatal(err)
		}
		if !strings.Contains(string(out), want) {
			t.Errorf("%s 缺少 %s\n%s", path, want, out)
		}
	}
	out, err = Render(Templates, "shadowrocket.conf", data)
	if s := string(out); err != nil || !strings.Contains(s, "日本 AnyTLS = anytls, any.example.com, 8443, password=account-uuid, sni=any.example.com") || !strings.Contains(s, "代理 = select, 自动, 日本-1, 新加坡-2, 美国-3, 日本 AnyTLS, DIRECT") {
		t.Errorf("shadowrocket.conf 缺少 AnyTLS 节点或分组: %v\n%s", err, out)
	}
}
func asList(v any) []any { a, _ := v.([]any); return a }

func TestProductionEdges(t *testing.T) {
	for _, scenario := range []string{"empty", "xray", "nil-core", "null-dns"} {
		data := fixture("single")
		node := data["nodes"].([]map[string]any)[0]
		switch scenario {
		case "empty":
			data["nodes"] = []map[string]any{}
		case "xray":
			node["coreType"] = "xray"
			node["inbound"].(map[string]any)["streamSettings"] = map[string]any{"realitySettings": map[string]any{"serverNames": []any{"tls.example.com"}, "publicKey": "public-key", "shortIds": []any{"abcd"}}}
		case "nil-core":
			node["coreType"] = nil
		case "null-dns":
			data["dns"] = nil
		}
		for key := range quteGolden {
			if !strings.HasPrefix(key, "single/") {
				continue
			}
			path := strings.TrimPrefix(key, "single/")
			out, err := Render(Templates, path, data)
			if err != nil {
				t.Fatalf("%s/%s: %v", scenario, path, err)
			}
			if scenario != "empty" && path == "sing-box.json" && !json.Valid(out) {
				t.Fatalf("%s JSON 无效", scenario)
			}
		}
	}
}

var quteGolden = map[string]string{
	"dns/clash-meta.yaml":             "8cd2915481015848476951d6ac90d3e9be3b87b975aea39e3c002b518785565e",
	"dns/clash-meta2.yaml":            "07a8b267c8bfa3f78faacc6ccdc9a8ec707e6d944e71724cef896a66f29a031e",
	"dns/clash-mi.yaml":               "d0779e769a5fa4e7a30f3118b31e6c4278d5303d6972b4e1ff6e8f4cae0e8e8a",
	"dns/clash-verge-mrs.yaml":        "b1f9bad5d5915a3340fce1a3f2be604c558e0e5e2525b45cec0791d9843f1650",
	"dns/clash-verge.yaml":            "d9670c7a86a8878f7c1478bc004d09d672c585f1383850d67359fe862ddafc42",
	"dns/loon.conf":                   "fc977ac4494741e847bbfe3dd97c82abd4dd283447acc27820ab90bf3e8f5f10",
	"dns/nodes/loon.html":             "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"dns/nodes/shadowrocket.html":     "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"dns/shadowrocket.conf":           "43f5cf43773879df83c5b77dc7976dbb260dc8ae2139be422b25c1ef4d63846c",
	"dns/sing-box.json":               "28d784c4d1fb3e699b8821d3ea63a57f85cbc21eab3570a3e6a0602105125286",
	"dns/stash.yaml":                  "fcd7ea7495db91cb0fd5249df13edffa24a4d40c6aab80305e24e45ceb2acf65",
	"multi/clash-meta.yaml":           "8cd2915481015848476951d6ac90d3e9be3b87b975aea39e3c002b518785565e",
	"multi/clash-meta2.yaml":          "07a8b267c8bfa3f78faacc6ccdc9a8ec707e6d944e71724cef896a66f29a031e",
	"multi/clash-mi.yaml":             "d0779e769a5fa4e7a30f3118b31e6c4278d5303d6972b4e1ff6e8f4cae0e8e8a",
	"multi/clash-verge-mrs.yaml":      "71cf9e2cedb66eb3b5e86edb36ebc0654112046ef04bf6f198be76f3209fea75",
	"multi/clash-verge.yaml":          "656295bc38996cdaf7305c863b2e02b3b067d9522ddd50742cb6f483d0f0a67b",
	"multi/loon.conf":                 "fc977ac4494741e847bbfe3dd97c82abd4dd283447acc27820ab90bf3e8f5f10",
	"multi/nodes/loon.html":           "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"multi/nodes/shadowrocket.html":   "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"multi/shadowrocket.conf":         "43f5cf43773879df83c5b77dc7976dbb260dc8ae2139be422b25c1ef4d63846c",
	"multi/sing-box.json":             "b12cc60b9e904613d04e6ed43d72410da22fe19f0bccfc0b630718c69b6dca99",
	"multi/stash.yaml":                "fcd7ea7495db91cb0fd5249df13edffa24a4d40c6aab80305e24e45ceb2acf65",
	"null/clash-meta.yaml":            "019d92e54dcfeee923ad5c61a6dd2f9f08ebb104cfea1c322ea4f1bc77f49d8a",
	"null/clash-meta2.yaml":           "3e4d3b4a09edc9ec4e3d57053314c085567510c2d43ea1ef9cb966b145efbb7f",
	"null/clash-mi.yaml":              "1c6cbeae4a897b3b3f1660d7a14b43d5bd30d13a621bb272292a250a2e235bcd",
	"null/clash-verge-mrs.yaml":       "9bada341b8d9cdedb9f2cb44cbcc5621e88847ad192a5d0586415e1d1f652cf7",
	"null/clash-verge.yaml":           "f6b8acb8f6b2bc12ffe8c48488e6f82510b2d33001fab6b4bfba08590dd45f70",
	"null/loon.conf":                  "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"null/nodes/loon.html":            "77a6b45409e70241eb7aacaf48b1613c6998b7673d36b7a3465c3134799f4682",
	"null/nodes/shadowrocket.html":    "81a458c3c00736c3194bf1664054c7b6bf4bee2554942f0e5e8995b498d38359",
	"null/shadowrocket.conf":          "f0dd8c29ea469ec43d2999ae8948703314940421b8def62502dd5d350738af89",
	"null/sing-box.json":              "1441d6ff89fe7992c00fb8749db8ba665c0450b5e4fe418935cb37015262b24a",
	"null/stash.yaml":                 "215b46b3ae8c7715c2bba1ccd3733af2d9164e7958dce1e8c3bd1ea134d8767e",
	"single/clash-meta.yaml":          "a652c9d39f3043a4c5398e74ef4e72c8953e24e5ee2e7a843a4dfced9e8c90f8",
	"single/clash-meta2.yaml":         "b5d5adac99558fe6b3dfa09dc878749391358a98e5331f957135abc54cba5d44",
	"single/clash-mi.yaml":            "841e4c415d3fa8fb753a94dadd6afb378664669ab13708dc0b4e0b055133733d",
	"single/clash-verge-mrs.yaml":     "a6f8a6ebca19e18d9a5facaa7a9b4bc05f6669ceed84809c4e2fedb028fd17bb",
	"single/clash-verge.yaml":         "8ae39ab9b3d4fa10cba1546e06ae114b5ce2e9202e05e0a10279604887bf077f",
	"single/loon.conf":                "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"single/nodes/loon.html":          "a0da00b70c298766d4d3d36f491ef5be694899924c4087160a18940c3c03dc7b",
	"single/nodes/shadowrocket.html":  "0ec9ee429d61c40df34f1a4565b1f4dbc3a9ebed9109299e68797954048685f7",
	"single/shadowrocket.conf":        "6946e7315a6e38e3edb21a69a44ed9af92ea6eb4358cd489e45864e76c4e0d58",
	"single/sing-box.json":            "039d18867700afb2e4866e5b6256df8556baef66bb92bc1c75ca78783bdbc447",
	"single/stash.yaml":               "17a980f85429686b9a0e36fec8e65ee0e85dd9c2d95d3c5575034e3c709e15a8",
	"special/clash-meta.yaml":         "63d6d06cf00e09466e3e5a54d6553f32149223bd9a98e20645b7b1b9c80dae71",
	"special/clash-meta2.yaml":        "e2d1838b229eb0ab768ddf70f69b50a874487b3c8008eb98dda3d71c17900b4b",
	"special/clash-mi.yaml":           "4e8c0a206ad7e1e86370c46f20fa8e69000cf6c03f09fcbecb6616e50a60b88f",
	"special/clash-verge-mrs.yaml":    "ab6b6dd6b49f13372b85cec64dec874ca89e4f2f3ebe3aa8fec0037933354d0e",
	"special/clash-verge.yaml":        "1d1615f77ba0f19cd15fe0efe7182a5933c00f1f5e6cdac6fa3ec029e12fce6f",
	"special/loon.conf":               "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"special/nodes/loon.html":         "62bba40017bfa64ede40993ab6cc44feba918cf435aacaa3dcd816a8cab10946",
	"special/nodes/shadowrocket.html": "6977c2fcfea3f5e5a724eab245c546ed3a0b1c512cba16952cc3205e24819e74",
	"special/shadowrocket.conf":       "3ce5f3fcc9da9b39bb387d10d7f2c783968f497d8bb395b38eaf05ee8751b8df",
	"special/sing-box.json":           "0884cd0a0dd4de02ac282754fbae24aa30c870ee44b2dffc6db582f7e27f2edd",
	"special/stash.yaml":              "f73c7c27035bd44e2ad38face415509e08fdea41f9d6488a0beb2de133e8cdea",
}
