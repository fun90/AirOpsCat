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
			"protocol": protocol, "name": name, "serverHost": "example.com", "sni": "tls.example.com", "port": 443, "coreType": "sing-box",
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
		plain := node("vless", "美国-3")
		plain["sni"] = "example.com" // 真实数据中域名访问时 SNI 等于访问地址，金样据此生成
		nodes = append(nodes, node("hysteria2", "新加坡-2"), plain)
	}
	data := map[string]any{"nodes": nodes, "account": map[string]any{"uuid": "account-uuid", "authCode": "test-auth"}, "subscriptionUrl": "https://example.com/subscribe", "timestamp": 123456789, "osName": "ios", "appName": "test"}
	if scenario == "dns" {
		data["dns"] = "8.8.8.8"
	}
	return data
}

// 金样摘要最初由生产 Qute 3.24.4 独立渲染生成（Clash 系列金样在 tailscale 直连与 fake-ip 过滤规则加入后由 Go 渲染重算，差异仅为这些行）：strict、removeStandaloneLines、ReflectionValueResolver、capture、urlEncode 和列表索引解析器，无内容类型变体。
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
	at := map[string]any{"name": "日本 AnyTLS", "protocol": "anytls", "coreType": "sing-box", "serverHost": "any.example.com", "sni": "any.example.com", "port": 8443, "inbound": map[string]any{"tls": map[string]any{"server_name": "any.example.com"}}}
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
	"dns/clash-meta.yaml":             "f7d5144865d6db6e8248f432d016ca6213099d840324c145fddd46d2c94c2c12",
	"dns/clash-meta2.yaml":            "784cbbbe7a31e7f20ba1e81144f56c2688ef6979f48487251c8e8a2e5ec25734",
	"dns/clash-mi.yaml":               "f0f3ed5e3824afe6c53a2436a28a840b77060a5aa605f49d76cd7c5728c49bea",
	"dns/clash-verge-mrs.yaml":        "83ac47d5110af0ab06438c294bafbc4c5937824922998a50f80b87a6c76193f1",
	"dns/clash-verge.yaml":            "ecae4f9df2fe2c9a6dc12c58c7a3777c8c0c07b2fd9ab8bc9783ed2ea018269a",
	"dns/loon.conf":                   "fc977ac4494741e847bbfe3dd97c82abd4dd283447acc27820ab90bf3e8f5f10",
	"dns/nodes/loon.html":             "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"dns/nodes/shadowrocket.html":     "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"dns/shadowrocket.conf":           "43f5cf43773879df83c5b77dc7976dbb260dc8ae2139be422b25c1ef4d63846c",
	"dns/sing-box.json":               "28d784c4d1fb3e699b8821d3ea63a57f85cbc21eab3570a3e6a0602105125286",
	"dns/stash.yaml":                  "fcd7ea7495db91cb0fd5249df13edffa24a4d40c6aab80305e24e45ceb2acf65",
	"multi/clash-meta.yaml":           "f7d5144865d6db6e8248f432d016ca6213099d840324c145fddd46d2c94c2c12",
	"multi/clash-meta2.yaml":          "784cbbbe7a31e7f20ba1e81144f56c2688ef6979f48487251c8e8a2e5ec25734",
	"multi/clash-mi.yaml":             "f0f3ed5e3824afe6c53a2436a28a840b77060a5aa605f49d76cd7c5728c49bea",
	"multi/clash-verge-mrs.yaml":      "7c0df4b5090528436eddc289007ad77f56fd779ed6a5b29a96b67c681c5a1c7e",
	"multi/clash-verge.yaml":          "2648423847995c0f7d2a75b05f32587c50a8907ffd70b7685646d028110b22de",
	"multi/loon.conf":                 "fc977ac4494741e847bbfe3dd97c82abd4dd283447acc27820ab90bf3e8f5f10",
	"multi/nodes/loon.html":           "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"multi/nodes/shadowrocket.html":   "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"multi/shadowrocket.conf":         "43f5cf43773879df83c5b77dc7976dbb260dc8ae2139be422b25c1ef4d63846c",
	"multi/sing-box.json":             "b12cc60b9e904613d04e6ed43d72410da22fe19f0bccfc0b630718c69b6dca99",
	"multi/stash.yaml":                "fcd7ea7495db91cb0fd5249df13edffa24a4d40c6aab80305e24e45ceb2acf65",
	"null/clash-meta.yaml":            "9e00f4274cc5f73ad42d290800cc9fe585073001aa1b874cc714f2ca60617e75",
	"null/clash-meta2.yaml":           "4331da5fb0641cae7c1bae0b07a7c54953afd52d25bbc3f415eb726e6ae38b7e",
	"null/clash-mi.yaml":              "2d71a5003ca234e3467ca8a7c65fee17d71b0f4d6f49fa1ed11c81f58d78019a",
	"null/clash-verge-mrs.yaml":       "fd7115f8adf069bff56c2ae4fff035295f1e3aa971f33d42dbfb21d56d0e2b49",
	"null/clash-verge.yaml":           "a30a73848ab25067be037853d260c6b2b1f674428f9f2678b7c4e05424b9207a",
	"null/loon.conf":                  "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"null/nodes/loon.html":            "77a6b45409e70241eb7aacaf48b1613c6998b7673d36b7a3465c3134799f4682",
	"null/nodes/shadowrocket.html":    "81a458c3c00736c3194bf1664054c7b6bf4bee2554942f0e5e8995b498d38359",
	"null/shadowrocket.conf":          "f0dd8c29ea469ec43d2999ae8948703314940421b8def62502dd5d350738af89",
	"null/sing-box.json":              "1441d6ff89fe7992c00fb8749db8ba665c0450b5e4fe418935cb37015262b24a",
	"null/stash.yaml":                 "215b46b3ae8c7715c2bba1ccd3733af2d9164e7958dce1e8c3bd1ea134d8767e",
	"single/clash-meta.yaml":          "8296b7961f00c7d4661732394eeea5f4b5caa3829125579e56a9ea490a52c735",
	"single/clash-meta2.yaml":         "3f5ea78badb8fb2aafe967b463673de94942d62c1a863c013da218570bd19a24",
	"single/clash-mi.yaml":            "c1de969073978863d5ca3ed91f896525979e130b927a5f1cb8b1b1671257a857",
	"single/clash-verge-mrs.yaml":     "8d81b1b5202d2da81ca2666692b56c38ee28fc8548a09e8c228890a75f3966e6",
	"single/clash-verge.yaml":         "fa668987cd0e25e2f11042b927333083e9aefc7ddbbec253e23bf7161f6fab23",
	"single/loon.conf":                "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"single/nodes/loon.html":          "a0da00b70c298766d4d3d36f491ef5be694899924c4087160a18940c3c03dc7b",
	"single/nodes/shadowrocket.html":  "0ec9ee429d61c40df34f1a4565b1f4dbc3a9ebed9109299e68797954048685f7",
	"single/shadowrocket.conf":        "6946e7315a6e38e3edb21a69a44ed9af92ea6eb4358cd489e45864e76c4e0d58",
	"single/sing-box.json":            "039d18867700afb2e4866e5b6256df8556baef66bb92bc1c75ca78783bdbc447",
	"single/stash.yaml":               "17a980f85429686b9a0e36fec8e65ee0e85dd9c2d95d3c5575034e3c709e15a8",
	"special/clash-meta.yaml":         "6eb9c6d2320fec99901726609242a1d963de77d185115c40333d87d75f30a168",
	"special/clash-meta2.yaml":        "405d3378c8409b406b9d9ba411141f4e776144f5b8d71d04821162e462ea5480",
	"special/clash-mi.yaml":           "96b16a1f875a8d06d8fcaf7daecaedb8a5ab60fed3f8187b420d575fecf165e2",
	"special/clash-verge-mrs.yaml":    "d1ba91a2a60c526a9328a64eee632c528e0cd60a12d01fe7809cd6aecab5b0b4",
	"special/clash-verge.yaml":        "ac45d02fbe9c0c63c16dcc083e1bc22c96bf263f0bf1fc60930741d660b2cfed",
	"special/loon.conf":               "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"special/nodes/loon.html":         "62bba40017bfa64ede40993ab6cc44feba918cf435aacaa3dcd816a8cab10946",
	"special/nodes/shadowrocket.html": "6977c2fcfea3f5e5a724eab245c546ed3a0b1c512cba16952cc3205e24819e74",
	"special/shadowrocket.conf":       "3ce5f3fcc9da9b39bb387d10d7f2c783968f497d8bb395b38eaf05ee8751b8df",
	"special/sing-box.json":           "0884cd0a0dd4de02ac282754fbae24aa30c870ee44b2dffc6db582f7e27f2edd",
	"special/stash.yaml":              "f73c7c27035bd44e2ad38face415509e08fdea41f9d6488a0beb2de133e8cdea",
}

// 普通 vless 的 SNI 取节点 sni 字段，可与访问地址不同。
func TestVlessSNIDiffersFromServerHost(t *testing.T) {
	data := fixture("multi")
	nodes := data["nodes"].([]map[string]any)
	nodes[2]["serverHost"] = "ccn.example.com"
	nodes[2]["sni"] = "cert.example.net"
	for path, want := range map[string]string{
		"sing-box.json":           `"server_name": "cert.example.net"`,
		"clash-meta.yaml":         "servername: cert.example.net",
		"clash-meta2.yaml":        "servername: cert.example.net",
		"clash-mi.yaml":           "servername: cert.example.net",
		"clash-verge.yaml":        "servername: cert.example.net",
		"clash-verge-mrs.yaml":    "servername: cert.example.net",
		"stash.yaml":              "servername: cert.example.net",
		"shadowrocket.conf":       "sni=cert.example.net",
		"nodes/shadowrocket.html": "sni=cert.example.net",
		"nodes/loon.html":         "sni=cert.example.net",
	} {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatal(path, err)
		}
		if !strings.Contains(string(out), want) {
			t.Errorf("%s 缺少 %q", path, want)
		}
	}
}
