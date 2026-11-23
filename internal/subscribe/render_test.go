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
			"protocol": protocol, "name": name, "serverHost": "example.com", "uuid": "account-uuid", "sni": "tls.example.com", "port": 443, "coreType": "sing-box",
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
	st := map[string]any{"name": "日本 ShadowTLS", "protocol": "shadowtls", "coreType": "sing-box", "serverHost": "shadow.example.com", "uuid": "account-uuid", "port": 8443, "inbound": map[string]any{"version": 3, "handshake": map[string]any{"server": "www.apple.com"}, "users": []any{map[string]any{"password": "outer-secret"}}}}
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
	at := map[string]any{"name": "日本 AnyTLS", "protocol": "anytls", "coreType": "sing-box", "serverHost": "any.example.com", "uuid": "account-uuid", "sni": "any.example.com", "port": 8443, "inbound": map[string]any{"tls": map[string]any{"server_name": "any.example.com"}}}
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
	if s := string(out); err != nil || !strings.Contains(s, "日本 AnyTLS = anytls, any.example.com, 8443, password=account-uuid, sni=any.example.com") || !strings.Contains(s, "代理 = select, 自动, 日本-自动, 日本-1, 新加坡-2, 美国-3, 日本 AnyTLS, DIRECT") || !strings.Contains(s, "日本-自动 = url-test, 日本-1, 日本 AnyTLS, url=http://www.gstatic.com/generate_204, interval=120, tolerance=50") {
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
	"dns/clash-meta.yaml":             "970710b26338951721e40bb6ee5e379a0aa0780cad2e0f7adf8079481922ccda",
	"dns/clash-meta2.yaml":            "7b7e07b2a017bf6acd81b43f6470f8d6b033da36c09f5c1ca5b38b32a9709391",
	"dns/clash-mi.yaml":               "91a260ed302d39ce4c925b12329a00b3bfedbe8ecd688c3d0176715d2baf5581",
	"dns/clash-verge-mrs.yaml":        "72f68546394fc89a7d05b00e7043a3899f6c55b36576f9a8395f55376640ddb9",
	"dns/clash-verge.yaml":            "e53a454879e5cc546ca04eea8be22342aa4b5b2dba942844f9d27c2d70c44cad",
	"dns/loon.conf":                   "fc977ac4494741e847bbfe3dd97c82abd4dd283447acc27820ab90bf3e8f5f10",
	"dns/nodes/loon.html":             "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"dns/nodes/shadowrocket.html":     "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"dns/shadowrocket.conf":           "43f5cf43773879df83c5b77dc7976dbb260dc8ae2139be422b25c1ef4d63846c",
	"dns/sing-box.json":               "d2eb23a6e29df69fdbc0f29f490c0adcf26e7922a6bb12794e44268a574c14c9",
	"dns/stash.yaml":                  "a5097d3f854189b1725f38663d432e9bd49fef5733bdac1bb9fdd2eed91f1d87",
	"multi/clash-meta.yaml":           "970710b26338951721e40bb6ee5e379a0aa0780cad2e0f7adf8079481922ccda",
	"multi/clash-meta2.yaml":          "7b7e07b2a017bf6acd81b43f6470f8d6b033da36c09f5c1ca5b38b32a9709391",
	"multi/clash-mi.yaml":             "91a260ed302d39ce4c925b12329a00b3bfedbe8ecd688c3d0176715d2baf5581",
	"multi/clash-verge-mrs.yaml":      "8789f8a20a025bf0267f286ee56dfd7534dbddaa731cdb80b9e3437402397381",
	"multi/clash-verge.yaml":          "fa77c9a4a28b056e013f3a6d5a9b4a529431cf188399ae0586c00ee796a22d10",
	"multi/loon.conf":                 "fc977ac4494741e847bbfe3dd97c82abd4dd283447acc27820ab90bf3e8f5f10",
	"multi/nodes/loon.html":           "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"multi/nodes/shadowrocket.html":   "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"multi/shadowrocket.conf":         "43f5cf43773879df83c5b77dc7976dbb260dc8ae2139be422b25c1ef4d63846c",
	"multi/sing-box.json":             "a7f95da2d37ad45bc8b64e1832317456fb4765fa94b8cc42398a0b1a1e2afb49",
	"multi/stash.yaml":                "a5097d3f854189b1725f38663d432e9bd49fef5733bdac1bb9fdd2eed91f1d87",
	"null/clash-meta.yaml":            "9e150853002244398d115aa3299ff807f1d0ab1e3b25395e36d9c655d9612ae2",
	"null/clash-meta2.yaml":           "fd6ed9b8c213f1ceadca837c885825641360750cb9a4b807e865d564b7d7db86",
	"null/clash-mi.yaml":              "c2205aec15109f09ac1c05f2d546d89b03dca3983d5b42d8cedb06888032df9a",
	"null/clash-verge-mrs.yaml":       "540c90568d0bad301443d11a599147f2cbdf840696a02113b9b197b9cec698ac",
	"null/clash-verge.yaml":           "b33b9065aa543a6879c633a3f417ee44bf043ebeb748f146e4cb82fd07365a4c",
	"null/loon.conf":                  "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"null/nodes/loon.html":            "77a6b45409e70241eb7aacaf48b1613c6998b7673d36b7a3465c3134799f4682",
	"null/nodes/shadowrocket.html":    "81a458c3c00736c3194bf1664054c7b6bf4bee2554942f0e5e8995b498d38359",
	"null/shadowrocket.conf":          "f0dd8c29ea469ec43d2999ae8948703314940421b8def62502dd5d350738af89",
	"null/sing-box.json":              "8855db91ebb6bca1ee114fd73df2dc06c062f67f3867acf445452388a840bc8e",
	"null/stash.yaml":                 "11aadae33c697083d50ad91b3f1826c0ed480f3512b7c7f0ae2bffd87c9e7046",
	"single/clash-meta.yaml":          "288493e6d3600129082839005c1ad1175cb21fdf783d864a315182e7395e2328",
	"single/clash-meta2.yaml":         "783ecc8bf64418dacd14cf628d00fd2403aa597330c73e5846d3d85e6929b510",
	"single/clash-mi.yaml":            "24856a5fda33f49c116fdd096ae1cbbc545b94facc3691c6a7ee5edece5722d6",
	"single/clash-verge-mrs.yaml":     "e8431c53b6fac379ca765c0009ee7ed6f76fc3b7c018702acd6211027cb12714",
	"single/clash-verge.yaml":         "acd9635a8f4748e694356dd16d97611b953e880030b256e31f617edb6d25d7be",
	"single/loon.conf":                "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"single/nodes/loon.html":          "a0da00b70c298766d4d3d36f491ef5be694899924c4087160a18940c3c03dc7b",
	"single/nodes/shadowrocket.html":  "0ec9ee429d61c40df34f1a4565b1f4dbc3a9ebed9109299e68797954048685f7",
	"single/shadowrocket.conf":        "6946e7315a6e38e3edb21a69a44ed9af92ea6eb4358cd489e45864e76c4e0d58",
	"single/sing-box.json":            "314e7cb8d39925716fa9b49e8a6ae613d79031c8d65fb551a708d52f6ce8083d",
	"single/stash.yaml":               "173ef6359c2ae2c6a8c0a4aa7866ed5010031ca7badd676e40a55021410d5517",
	"special/clash-meta.yaml":         "8be51374011ec1b4236ef3cd5b8ded4872d92d9775d89c72cf483dc5f02c73eb",
	"special/clash-meta2.yaml":        "2ef8ec189c20f69aefe06b8ba662d74c08661ffad9170662cd4ad3609b3ec4eb",
	"special/clash-mi.yaml":           "97d28e2e04ea8c7bdca4308d6f0ec5a033db74fed1c2d3b1a770b1d2dffbdf58",
	"special/clash-verge-mrs.yaml":    "759f838b7c990f6282499734e19df7d3f6421d62a0c39bb41011e4ad27435f30",
	"special/clash-verge.yaml":        "7ab6668a97fbfed93b495463ee514c5b6be5037679f8386a0e670b9a13a4b438",
	"special/loon.conf":               "76a143829dd52bac1a16d9b22e6b672bc192378ed7043a02ae05d4908a9662cc",
	"special/nodes/loon.html":         "62bba40017bfa64ede40993ab6cc44feba918cf435aacaa3dcd816a8cab10946",
	"special/nodes/shadowrocket.html": "6977c2fcfea3f5e5a724eab245c546ed3a0b1c512cba16952cc3205e24819e74",
	"special/shadowrocket.conf":       "3ce5f3fcc9da9b39bb387d10d7f2c783968f497d8bb395b38eaf05ee8751b8df",
	"special/sing-box.json":           "162ccb2a6cd9d4cfb8c1f4b2e97ae64633e583ce7a95f7f324710fd09696d0e5",
	"special/stash.yaml":              "0ac6d19455d3c58cfb4ae03babe93e4119e38925bdb8510d47e73efc06d069cc",
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

func TestRegionGroups(t *testing.T) {
	data := fixture("multi")
	nodes := data["nodes"].([]map[string]any)
	if len(regionGroups(nodes)) != 0 {
		t.Fatal("每个地区仅 1 个节点时不应生成地区组")
	}
	hy2 := map[string]any{"name": "日本-1 UDP", "protocol": "hysteria2", "coreType": "sing-box", "serverHost": "example.com", "uuid": "account-uuid", "sni": "tls.example.com", "port": 8688, "inbound": map[string]any{"obfs": map[string]any{"password": "p"}}}
	xrayHy2 := map[string]any{"name": "日本-X", "protocol": "hysteria2", "coreType": "xray"}
	data["nodes"] = append(nodes, hy2, xrayHy2)
	groups := regionGroups(data["nodes"].([]map[string]any))
	if len(groups) != 1 || groups[0].Name != "日本-自动" || strings.Join(groups[0].Proxies, ",") != "日本-1,日本-1 UDP" {
		t.Fatalf("地区组不符: %+v", groups)
	}
	// 组名与真实节点重名时追加下划线。
	nodes[1]["name"] = "日本-自动"
	data["nodes"].([]map[string]any)[1]["protocol"] = "vless"
	if g := regionGroups(data["nodes"].([]map[string]any)); len(g) != 1 || g[0].Name != "日本-自动_" {
		t.Fatalf("组名未避让: %+v", g)
	}
	nodes[1]["name"] = "新加坡-2"
	out, err := Render(Templates, "clash-verge-mrs.yaml", data)
	if err != nil {
		t.Fatal(err)
	}
	s := string(out)
	for _, want := range []string{"  - name: 日本-自动\n    icon: https://flagcdn.com/w80/jp.png\n    type: url-test\n", "    interval: 120\n    tolerance: 50\n    proxies:\n      - 日本-1\n      - 日本-1 UDP\n", "      - 自动\n      - 日本-自动\n"} {
		if !strings.Contains(s, want) {
			t.Errorf("缺少 %q\n%s", want, s)
		}
	}
	if strings.Contains(s, "新加坡-自动") {
		t.Error("单节点地区不应成组")
	}
}

// 所有客户端模板都输出地区自动组：成员混合 TCP 与 UDP，且不引用未定义的节点；单节点地区不成组。
func TestRegionGroupsAllTemplates(t *testing.T) {
	data := fixture("multi")
	nodes := data["nodes"].([]map[string]any)
	hy2 := map[string]any{"name": "日本H-1", "protocol": "hysteria2", "coreType": "sing-box", "serverHost": "example.com", "uuid": "account-uuid", "sni": "tls.example.com", "port": 8688, "inbound": map[string]any{"obfs": map[string]any{"password": "p"}}}
	data["nodes"] = append(nodes, hy2)
	yamls := []string{"clash-meta.yaml", "clash-meta2.yaml", "clash-mi.yaml", "clash-verge.yaml", "clash-verge-mrs.yaml", "stash.yaml"}
	for _, path := range yamls {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatal(path, err)
		}
		s := string(out)
		for _, want := range []string{"  - name: 日本-自动\n    icon: https://flagcdn.com/w80/jp.png\n    type: url-test\n", "    interval: 120\n    tolerance: 50\n    proxies:\n      - 日本-1\n      - 日本H-1\n", "      - 自动\n      - 日本-自动\n"} {
			if !strings.Contains(s, want) {
				t.Errorf("%s 缺少 %q", path, want)
			}
		}
		if strings.Contains(s, "新加坡-自动") || strings.Contains(s, "美国-自动") {
			t.Errorf("%s 单节点地区不应成组", path)
		}
	}
	out, err := Render(Templates, "sing-box.json", data)
	if err != nil {
		t.Fatal(err)
	}
	var config struct {
		Outbounds []map[string]any `json:"outbounds"`
	}
	if err = json.Unmarshal(out, &config); err != nil {
		t.Fatalf("sing-box JSON 无效: %v\n%s", err, out)
	}
	tags := map[string]map[string]any{}
	for _, o := range config.Outbounds {
		tags[o["tag"].(string)] = o
	}
	g := tags["日本-自动"]
	if g == nil || g["type"] != "urltest" || fmt.Sprint(g["outbounds"]) != "[日本-1 日本H-1]" {
		t.Fatalf("sing-box 缺少日本-自动: %v", g)
	}
	if sel := fmt.Sprint(tags["🚀 节点选择"]["outbounds"]); !strings.Contains(sel, "日本-自动") {
		t.Fatalf("节点选择未包含地区组: %s", sel)
	}
	for _, o := range config.Outbounds {
		for _, n := range asList(o["outbounds"]) {
			if tags[n.(string)] == nil {
				t.Fatalf("悬空引用 %s", n)
			}
		}
	}
	out, err = Render(Templates, "shadowrocket.conf", data)
	if s := string(out); err != nil || !strings.Contains(s, "日本-自动 = url-test, 日本-1, 日本H-1, url=http://www.gstatic.com/generate_204, interval=120, tolerance=50") || !strings.Contains(s, "代理 = select, 自动, 日本-自动, ") {
		t.Fatalf("shadowrocket 缺少地区组: %v\n%s", err, out)
	}
	out, err = Render(Templates, "loon.conf", data)
	if s := string(out); err != nil || !strings.Contains(s, "日本节点 = NameRegex, FilterKey = (日本)\n") || !strings.Contains(s, "日本-自动 = url-test, 日本节点, interval = 120, tolerance = 50, img-url = https://flagcdn.com/w80/jp.png\n") || !strings.Contains(s, "全球手动策略 = select, 自动, 日本-自动, 全球节点") {
		t.Fatalf("loon 缺少地区组: %v\n%s", err, out)
	}
	// 不支持 shadowtls 的客户端：该协议节点不进地区组。
	st := map[string]any{"name": "日本 ShadowTLS", "protocol": "shadowtls", "coreType": "sing-box"}
	if g := regionGroups(append(data["nodes"].([]map[string]any), st), "shadowtls"); len(g) != 1 || strings.Contains(strings.Join(g[0].Proxies, ","), "ShadowTLS") {
		t.Fatalf("skip 未生效: %+v", g)
	}
}

func TestBrokerRoutingAndDNS(t *testing.T) {
	data := fixture("multi")
	for _, path := range []string{"clash-meta.yaml", "clash-meta2.yaml", "clash-mi.yaml", "clash-verge.yaml", "clash-verge-mrs.yaml", "stash.yaml"} {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		s := string(out)
		if !strings.Contains(s, "https://example.com/subscribe/rules/clash/hk-broker.yaml") {
			t.Errorf("%s 未引用 hk-broker.yaml", path)
		}
		if path != "stash.yaml" {
			if !strings.Contains(s, `"rule-set:Broker":`) || !strings.Contains(s, "parse-pure-ip: true") {
				t.Errorf("%s 缺少 Broker DNS 策略或 sniffer", path)
			}
		}
	}
	out, err := Render(Templates, "sing-box.json", data)
	if err != nil {
		t.Fatal(err)
	}
	var cfg struct {
		DNS struct {
			Rules []map[string]any `json:"rules"`
		} `json:"dns"`
	}
	if err = json.Unmarshal(out, &cfg); err != nil {
		t.Fatal(err)
	}
	brokerIdx, cnIdx := -1, -1
	for i, r := range cfg.DNS.Rules {
		if r["rule_set"] == "Broker" && r["server"] == "proxyDns" {
			brokerIdx = i
		}
		if r["rule_set"] == "GeoSite-CN" {
			cnIdx = i
		}
	}
	if brokerIdx == -1 || cnIdx == -1 || brokerIdx > cnIdx {
		t.Fatalf("sing-box DNS 规则缺少位于 GeoSite-CN 之前的 Broker -> proxyDns: %+v", cfg.DNS.Rules)
	}
}
