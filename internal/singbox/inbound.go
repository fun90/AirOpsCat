package singbox

import (
	"crypto/ecdh"
	"crypto/rand"
	"embed"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"math/big"
	"net"
	"net/url"
	"strings"
)

//go:embed templates/*.json
var templates embed.FS

// DefaultInboundForHost 为 Hysteria2 填入部署目标访问地址，其他协议保持默认行为。
func DefaultInboundForHost(protocol, host string) (json.RawMessage, error) {
	body, err := DefaultInbound(protocol)
	if err != nil || protocol != "hysteria2" {
		return body, err
	}
	host = strings.TrimSpace(host)
	if host == "" || strings.ContainsAny(host, "/\\\\:@?#\r\n\t ") && net.ParseIP(host) == nil {
		return nil, fmt.Errorf("访问地址必须是域名或 IP")
	}
	if net.ParseIP(host) == nil {
		for _, label := range strings.Split(host, ".") {
			if label == "" || strings.HasPrefix(label, "-") || strings.HasSuffix(label, "-") {
				return nil, fmt.Errorf("访问域名不正确")
			}
			for _, c := range label {
				if !(c >= 'a' && c <= 'z' || c >= 'A' && c <= 'Z' || c >= '0' && c <= '9' || c == '-') {
					return nil, fmt.Errorf("访问域名不正确")
				}
			}
		}
	}
	var obj map[string]any
	if err := json.Unmarshal(body, &obj); err != nil {
		return nil, err
	}
	obj["tls"].(map[string]any)["server_name"] = host
	urlHost := host
	if strings.Contains(host, ":") {
		urlHost = "[" + host + "]"
	}
	obj["masquerade"].(map[string]any)["url"] = (&url.URL{Scheme: "https", Host: urlHost, Path: "/"}).String()
	return json.MarshalIndent(obj, "", "  ")
}

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
	if protocol == "vless-reality" || protocol == "shadowtls" {
		hosts := []string{"www.apple.com", "www.icloud.com", "www.amazon.com"}
		index, err := rand.Int(rand.Reader, big.NewInt(int64(len(hosts))))
		if err != nil {
			return nil, err
		}
		values["serverName"] = hosts[index.Int64()]
	}
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
