package singbox

import (
	"encoding/json"
	"fmt"
	"strings"
)

// ValidateShadowTLS 限定托管节点为 v3 + Shadowsocks AEAD；账户认证和 detour 由系统生成；users 仅存节点外层握手凭据。
func ValidateShadowTLS(raw string) error {
	ib, err := parseObject(raw)
	if err != nil {
		return err
	}
	if ib["type"] != "shadowtls" {
		return fmt.Errorf("ShadowTLS 入站 type 必须为 shadowtls")
	}
	if version, ok := ib["version"].(json.Number); !ok || version.String() != "3" {
		return fmt.Errorf("ShadowTLS 托管节点只支持 version=3")
	}
	if _, ok := ib["detour"]; ok {
		return fmt.Errorf("ShadowTLS detour 由系统生成，请移除自定义 detour")
	}
	if h := asObj(ib["handshake"]); h == nil || strings.TrimSpace(stringValue(h["server"])) == "" {
		return fmt.Errorf("ShadowTLS 必须配置 handshake.server")
	}
	users := asObjList(ib["users"])
	if len(users) == 0 {
		return fmt.Errorf("ShadowTLS 必须配置节点握手 users 密码")
	}
	for _, u := range users {
		p, ok := u["password"].(string)
		if !ok || strings.TrimSpace(p) == "" {
			return fmt.Errorf("ShadowTLS 握手用户 password 不能为空")
		}
	}
	if ss, ok := ib["shadowsocks"]; ok {
		o := asObj(ss)
		if o == nil {
			return fmt.Errorf("shadowsocks 必须是 JSON 对象")
		}
		for k, v := range o {
			if k != "method" || v != "chacha20-ietf-poly1305" {
				return fmt.Errorf("内层 shadowsocks 仅支持 method=chacha20-ietf-poly1305，其他字段由系统生成")
			}
		}
	}
	return nil
}

// shadowTLSInbounds 保留 node_N 作为最终代理入站标签，现有落地路由无需了解双入站结构。
func shadowTLSInbounds(n NodeInput, outer obj) (obj, error) {
	if err := ValidateShadowTLS(n.Inbound); err != nil {
		return nil, err
	}
	tag := fmt.Sprintf("node_%d", n.ID)
	outer["tag"] = tag + "_shadowtls"
	outer["detour"] = tag
	delete(outer, "shadowsocks")
	delete(outer, "password")
	// 外层使用独立节点身份，避免 detour 前后两次占用账户连接配额。
	outerUsers := make([]any, 0)
	for i, u := range asObjList(outer["users"]) {
		outerUsers = append(outerUsers, obj{"name": fmt.Sprintf("shadowtls_%d_%d", n.ID, i), "password": u["password"]})
	}
	outer["users"] = outerUsers
	innerUsers := make([]any, 0, len(n.Clients))
	for _, c := range n.Clients {
		innerUsers = append(innerUsers, c.limits(obj{"name": c.Name, "password": c.UUID}))
	}
	// managed 保证零账户时仍使用多用户入站并拒绝认证；内层仅监听回环随机端口，不额外开放公网端口。
	return obj{"type": "shadowsocks", "tag": tag, "listen": "127.0.0.1", "listen_port": 0, "network": "tcp", "managed": len(innerUsers) == 0, "method": "chacha20-ietf-poly1305", "users": innerUsers}, nil
}

func stringValue(v any) string { s, _ := v.(string); return s }
