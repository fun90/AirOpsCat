package singbox

import (
	"crypto/ecdh"
	"encoding/base64"
	"encoding/json"
	"testing"
)

func TestDefaultInbound(t *testing.T) {
	for _, p := range []string{"vless", "vless-reality", "hysteria2", "shadowtls", "shadowsocks", "socks"} {
		t.Run(p, func(t *testing.T) {
			b, err := DefaultInbound(p)
			if err != nil {
				t.Fatal(err)
			}
			var obj map[string]any
			if err := json.Unmarshal(b, &obj); err != nil {
				t.Fatal(err)
			}
			typ := p
			if p == "vless-reality" {
				typ = "vless"
			}
			if obj["type"] != typ {
				t.Fatal(obj["type"])
			}
			if p == "shadowtls" || p == "vless-reality" {
				var host string
				if p == "shadowtls" {
					host = obj["handshake"].(map[string]any)["server"].(string)
				} else {
					tls := obj["tls"].(map[string]any)
					host = tls["reality"].(map[string]any)["handshake"].(map[string]any)["server"].(string)
					if host != tls["server_name"] {
						t.Fatal("Reality SNI 与握手域名不一致")
					}
				}
				if host != "www.apple.com" && host != "www.icloud.com" && host != "www.amazon.com" {
					t.Fatal("握手域名不在候选中", host)
				}
			}
			if p == "vless-reality" {
				r := obj["tls"].(map[string]any)["reality"].(map[string]any)
				raw, err := base64.RawURLEncoding.DecodeString(r["private_key"].(string))
				if err != nil {
					t.Fatal(err)
				}
				k, err := ecdh.X25519().NewPrivateKey(raw)
				if err != nil {
					t.Fatal(err)
				}
				if base64.RawURLEncoding.EncodeToString(k.PublicKey().Bytes()) != r["public_key"] {
					t.Fatal("Reality 密钥不匹配")
				}
				b2, _ := DefaultInbound(p)
				if string(b) == string(b2) {
					t.Fatal("密钥未随机生成")
				}
			}
		})
	}
	if _, err := DefaultInbound("unknown"); err == nil {
		t.Fatal("不支持的协议应被拒绝")
	}
}
