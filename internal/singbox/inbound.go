package singbox

import (
	"crypto/ecdh"
	"crypto/rand"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"strings"
)

//go:embed templates/*.json
var templates embed.FS

// DefaultInbound 复用现有协议模板，随机凭据使用系统密码学随机源。
func DefaultInbound(protocol string) (json.RawMessage, error) {
	switch protocol {
	case "vless", "vless-reality", "hysteria2", "shadowtls", "shadowsocks", "socks":
	default:
		return nil, fmt.Errorf("不支持的协议")
	}
	body, err := templates.ReadFile("templates/sing-box-inbound-" + protocol + ".json")
	if err != nil {
		return nil, err
	}
	secret := make([]byte, 24)
	if _, err := rand.Read(secret); err != nil {
		return nil, err
	}
	password := base64.RawURLEncoding.EncodeToString(secret)
	values := map[string]string{"serverName": "www.apple.com", "password": password, "name": "default", "username": "user_" + hex.EncodeToString(secret[:6])}
	if protocol == "vless-reality" {
		key, err := ecdh.X25519().GenerateKey(rand.Reader)
		if err != nil {
			return nil, err
		}
		values["privateKey"] = base64.RawURLEncoding.EncodeToString(key.Bytes())
		values["publicKey"] = base64.RawURLEncoding.EncodeToString(key.PublicKey().Bytes())
		values["shortId"] = hex.EncodeToString(secret[:8])
	}
	text := string(body)
	for k, v := range values {
		text = strings.ReplaceAll(text, "{"+k+"}", v)
	}
	if !json.Valid([]byte(text)) {
		return nil, fmt.Errorf("默认入站模板不是合法 JSON")
	}
	return json.RawMessage(text), nil
}
