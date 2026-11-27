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

// 金样摘要最初由生产 Qute 3.24.4 独立渲染生成：strict、removeStandaloneLines、ReflectionValueResolver、capture、urlEncode 和列表索引解析器，无内容类型变体。
// 后续订阅模板变更（tailscale、cn-apps 直连与 fake-ip 过滤、sing-box 直连优先于 QUIC 拦截、Clash 移除 QUIC 嗅探）的摘要由 Go 渲染重算。
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
	if count != 31 {
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
	"dns/clash-meta.yaml":             "fe640715aa53cb04a62db2586f3a9a99b2d921dfd4beea3daace6638842607cd",
	"dns/clash-meta2.yaml":            "c821db505dc795fa6366b3a24b526ca884e706d260f2ce3d0e5359b714e9b577",
	"dns/clash-mi.yaml":               "7658964b413c191ab6b9b036da04b6edecc1796c0e76d83d1a9e7c49e080c57f",
	"dns/clash-verge-mrs.yaml":        "a9498ad48966f0d2c54c453f36c568ea491dcd6bc5beaa5f0b033de81ee983e2",
	"dns/clash-verge.yaml":            "e62788c85eb5521981f20df975ebd07ff6c725ef880487e0db6a7d29157caa8c",
	"dns/loon.conf":                   "82f1fa13c705786dc4c528f6db392f43656fe8adec95b55214a7b29bac7ab680",
	"dns/nodes/loon.html":             "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"dns/nodes/shadowrocket.html":     "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"dns/shadowrocket.conf":           "cc4c2d66beb761e82df70338d1333d23399cea9ebb3589769c3a53c0c1a6e4a0",
	"dns/sing-box.json":               "7e1baf95285ba9c792c3e7c4d6262762a9e8a341055f506ed34e76fc96f828a1",
	"dns/stash.yaml":                  "f2631ee1c25f84ea28d6173b60e9b8fbc8b66cc1948d19e401e39b3fac6a8363",
	"multi/clash-meta.yaml":           "fe640715aa53cb04a62db2586f3a9a99b2d921dfd4beea3daace6638842607cd",
	"multi/clash-meta2.yaml":          "c821db505dc795fa6366b3a24b526ca884e706d260f2ce3d0e5359b714e9b577",
	"multi/clash-mi.yaml":             "7658964b413c191ab6b9b036da04b6edecc1796c0e76d83d1a9e7c49e080c57f",
	"multi/clash-verge-mrs.yaml":      "17facef2324741b1b64154d4fd9798702bee5ccc9f65daa824595c890da16e46",
	"multi/clash-verge.yaml":          "1cf5ed4451f0f3ce2c35e442453f7678a283aa2599b9703d19ba3b5f018aba6c",
	"multi/loon.conf":                 "82f1fa13c705786dc4c528f6db392f43656fe8adec95b55214a7b29bac7ab680",
	"multi/nodes/loon.html":           "1c6be18ad04e8f59e37f101080b0eda5148466cc80f03d207866a9e337299b2e",
	"multi/nodes/shadowrocket.html":   "3ac0287c389b889d9f1e664ef051a19ac6b08f54e35459a2d40cc2bd81644951",
	"multi/shadowrocket.conf":         "cc4c2d66beb761e82df70338d1333d23399cea9ebb3589769c3a53c0c1a6e4a0",
	"multi/sing-box.json":             "159ae844a1e6e2a9f39cbe8c9ac3f4f04330de49b45019c50ac31c041ffdba20",
	"multi/stash.yaml":                "f2631ee1c25f84ea28d6173b60e9b8fbc8b66cc1948d19e401e39b3fac6a8363",
	"null/clash-meta.yaml":            "97f74e6140cf84e7dd9391b37af0df8435d7eddf77fb65ba2f2fe11cfa50d5dd",
	"null/clash-meta2.yaml":           "6af4634cd4c3c52f9ea393647899bd4b0fda9b37e0b7ac49807b0fa8d8e10675",
	"null/clash-mi.yaml":              "855ce8225ecd854e745049df103c76c2ced4a581741fa143aa61705b37e9dc9e",
	"null/clash-verge-mrs.yaml":       "2d10cd77f5511ed0676492ad44cc4c37f98a0655b664318661dd8da92ecbc9b8",
	"null/clash-verge.yaml":           "3e86eae0230b2cb70087e6c52dced76806df9dc688fe3d4dae70d679768a5700",
	"null/loon.conf":                  "af321016ca339f3b3beb5729cca517c0cfdac30a441ff5e3de124d623dea9685",
	"null/nodes/loon.html":            "77a6b45409e70241eb7aacaf48b1613c6998b7673d36b7a3465c3134799f4682",
	"null/nodes/shadowrocket.html":    "81a458c3c00736c3194bf1664054c7b6bf4bee2554942f0e5e8995b498d38359",
	"null/shadowrocket.conf":          "efa8b1aa698acdfb4a63ebf6a3ff16b6fa50cc949f27562d44728d28683aa4d0",
	"null/sing-box.json":              "6eec86b145d5a2bd27b7ebf74b05fec93c86c75b63f3c6204452fccaa0b0acc7",
	"null/stash.yaml":                 "1d9f414c691dedf190141d702100a9ac0f9a77c3ee3a53dc9733622c0da75a50",
	"single/clash-meta.yaml":          "3ad51576d41f99c0c6e9e2ce8307c14ddeb8ed207d5fa6934230a779ab0eeac3",
	"single/clash-meta2.yaml":         "deda879bbc70a2b507d90ee49b6c24c8172f7e99a4d4011e3f8ecf99019b1d95",
	"single/clash-mi.yaml":            "ca43baa5202ba590c2ab591688306c4bd8a6458f5b235d3d4d92bc147c9da4c6",
	"single/clash-verge-mrs.yaml":     "4d00b44182e93cf14b1ba2fe1c824fa75dedd3b40f061ef09264527d07f4d700",
	"single/clash-verge.yaml":         "dccebea24829658242f4791f0f8b3c377e4b4fbdd9e7ed68b1f4d22a88ae64f2",
	"single/loon.conf":                "af321016ca339f3b3beb5729cca517c0cfdac30a441ff5e3de124d623dea9685",
	"single/nodes/loon.html":          "a0da00b70c298766d4d3d36f491ef5be694899924c4087160a18940c3c03dc7b",
	"single/nodes/shadowrocket.html":  "0ec9ee429d61c40df34f1a4565b1f4dbc3a9ebed9109299e68797954048685f7",
	"single/shadowrocket.conf":        "48b27c4d7c4dfc4201820f79f6e08cbf64c7c5732986e1bde35e33177fc484b7",
	"single/sing-box.json":            "b56779fbea31651b4054f2124451b78bee1d83f29e81ab97ed29f3644847e4ca",
	"single/stash.yaml":               "492f616b9a0d77e29100a6b475bc667629411fbcfa893c04f04c8a2492da977b",
	"special/clash-meta.yaml":         "1db0ec328970ca4c0503f1cf46ac02b1818bcd2ff0523cbf831be51a6a466110",
	"special/clash-meta2.yaml":        "e6a79205278db230e4df99d77d48ab231a39b9f00ee5fd7cbbad8d41d76b520a",
	"special/clash-mi.yaml":           "6a942a45d4d8119db398cdbe39d490a31ba97d8e8a591a16ee060261f87e0d41",
	"special/clash-verge-mrs.yaml":    "f3a4107969f15068437c8448fbc28d0ee4bfa368b376a00ad1861a6da89f1de6",
	"special/clash-verge.yaml":        "6e74199536b3f65ed9ed54002da68ca8b5ed778b2ad05a8c8f7b1f085cf1f750",
	"special/loon.conf":               "af321016ca339f3b3beb5729cca517c0cfdac30a441ff5e3de124d623dea9685",
	"special/nodes/loon.html":         "62bba40017bfa64ede40993ab6cc44feba918cf435aacaa3dcd816a8cab10946",
	"special/nodes/shadowrocket.html": "6977c2fcfea3f5e5a724eab245c546ed3a0b1c512cba16952cc3205e24819e74",
	"special/shadowrocket.conf":       "562de1de6c874e9a77fd429c9305df91d9ab75d25a674d00a26ee81f28ab70a9",
	"special/sing-box.json":           "013eb0d95787ec622db21b2c1b3a415255a52617aff13c1803611db8068f497b",
	"special/stash.yaml":              "3bf67e1177c8548e60f376a85483d072ed9a0b72535e5dd78d498ad9e0a26d1d",
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

func TestCNAppsDirectRoutingAndDNS(t *testing.T) {
	for _, rulePath := range []string{"rules/clash/cn-apps.yaml", "rules/sing-box/cn-apps.json", "rules/loon/cn-apps.list"} {
		raw, err := fs.ReadFile(Templates, rulePath)
		if err != nil {
			t.Fatalf("读取规则文件 %s 失败: %v", rulePath, err)
		}
		text := string(raw)
		for _, domain := range []string{"servicewechat.com", "weixin.qq.com", "meituan.com", "dianping.com", "feishu.cn", "larksuite.com"} {
			if !strings.Contains(text, domain) {
				t.Errorf("%s 缺少关键直连域名 %s", rulePath, domain)
			}
		}
		if rulePath == "rules/sing-box/cn-apps.json" {
			var doc struct {
				Version int              `json:"version"`
				Rules   []map[string]any `json:"rules"`
			}
			if err := json.Unmarshal(raw, &doc); err != nil || doc.Version != 3 || len(doc.Rules) != 2 {
				t.Fatalf("sing-box cn-apps.json 格式异常: err=%v doc=%+v", err, doc)
			}
		}
	}

	data := fixture("multi")
	for _, path := range []string{"clash-meta.yaml", "clash-meta2.yaml", "clash-mi.yaml", "clash-verge.yaml", "clash-verge-mrs.yaml", "stash.yaml"} {
		out, err := Render(Templates, path, data)
		if err != nil {
			t.Fatalf("%s: %v", path, err)
		}
		s := string(out)
		if !strings.Contains(s, "https://example.com/subscribe/rules/clash/cn-apps.yaml") {
			t.Errorf("%s 未引用 cn-apps.yaml", path)
		}
		directIdx := strings.Index(s, "- RULE-SET,mydirect,DIRECT")
		cnAppsIdx := strings.Index(s, "- RULE-SET,cn-apps,DIRECT")
		proxyIdx := strings.Index(s, "- RULE-SET,myproxy,代理")
		if directIdx == -1 || cnAppsIdx == -1 || proxyIdx == -1 || !(directIdx < cnAppsIdx && cnAppsIdx < proxyIdx) {
			t.Errorf("%s 中 cn-apps 规则顺序异常: direct=%d cnApps=%d proxy=%d", path, directIdx, cnAppsIdx, proxyIdx)
		}
		if path != "stash.yaml" {
			for _, want := range []string{`"rule-set:cn-apps":`, `"+.servicewechat.com"`, `"+.meituan.com"`, `"+.feishu.cn"`} {
				if !strings.Contains(s, want) {
					t.Errorf("%s 缺少 %s", path, want)
				}
			}
		}
	}

	for _, tc := range []struct {
		path string
		want string
	}{
		{path: "shadowrocket.conf", want: "RULE-SET,https://example.com/subscribe/rules/clash/cn-apps.yaml,DIRECT"},
		{path: "loon.conf", want: "https://example.com/subscribe/rules/loon/cn-apps.list, policy = DIRECT, tag = CN-Apps, enabled = true"},
	} {
		out, err := Render(Templates, tc.path, data)
		if err != nil || !strings.Contains(string(out), tc.want) {
			t.Fatalf("%s 缺少 %q: err=%v", tc.path, tc.want, err)
		}
	}

	out, err := Render(Templates, "sing-box.json", data)
	if err != nil {
		t.Fatal(err)
	}
	var cfg struct {
		Route struct {
			Rules   []map[string]any `json:"rules"`
			RuleSet []map[string]any `json:"rule_set"`
		} `json:"route"`
		DNS struct {
			Rules []map[string]any `json:"rules"`
		} `json:"dns"`
	}
	if err = json.Unmarshal(out, &cfg); err != nil {
		t.Fatalf("sing-box JSON 无效: %v\n%s", err, out)
	}
	hasRuleSet := false
	for _, rs := range cfg.Route.RuleSet {
		if rs["tag"] == "cn-apps" && rs["url"] == "https://example.com/subscribe/rules/sing-box/cn-apps.json" {
			hasRuleSet = true
		}
	}
	if !hasRuleSet {
		t.Fatalf("sing-box route.rule_set 缺少 cn-apps: %+v", cfg.Route.RuleSet)
	}
	cnAppsRouteIdx, quicRejectIdx := -1, -1
	for i, r := range cfg.Route.Rules {
		if r["rule_set"] == "cn-apps" && r["outbound"] == "🎯 全球直连" {
			cnAppsRouteIdx = i
		}
		if r["action"] == "reject" && r["protocol"] == "quic" {
			quicRejectIdx = i
		}
	}
	if cnAppsRouteIdx == -1 || quicRejectIdx == -1 || cnAppsRouteIdx > quicRejectIdx {
		t.Fatalf("sing-box cn-apps 直连规则应位于 reject quic 之前: cnApps=%d quicReject=%d", cnAppsRouteIdx, quicRejectIdx)
	}
	cnAppsDNSIdx, notCNDNSIdx := -1, -1
	for i, r := range cfg.DNS.Rules {
		if r["rule_set"] == "cn-apps" && r["server"] == "localDns" {
			cnAppsDNSIdx = i
		}
		if r["rule_set"] == "GeoLocation-!CN" {
			notCNDNSIdx = i
		}
	}
	if cnAppsDNSIdx == -1 || notCNDNSIdx == -1 || cnAppsDNSIdx > notCNDNSIdx {
		t.Fatalf("sing-box DNS 规则缺少位于 GeoLocation-!CN 之前的 cn-apps -> localDns: %+v", cfg.DNS.Rules)
	}
}
