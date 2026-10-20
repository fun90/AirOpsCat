package crypto

import (
	"strings"
	"testing"
)

const testSecret = "0123456789abcdef0123456789abcdef"

func TestRoundTripAndFailures(t *testing.T) {
	c, _ := New(DeriveKey(testSecret, "field"))
	enc, err := c.Encrypt("ssh-密码")
	if err != nil {
		t.Fatal(err)
	}
	enc2, _ := c.Encrypt("ssh-密码")
	if enc == enc2 {
		t.Fatal("相同明文应因随机 nonce 得到不同密文")
	}
	if got, err := c.Decrypt(enc); err != nil || got != "ssh-密码" {
		t.Fatalf("got %q, %v", got, err)
	}

	other, _ := New(DeriveKey(testSecret+"x", "field"))
	if _, err := other.Decrypt(enc); err == nil {
		t.Fatal("错误密钥应失败")
	}
	tampered := enc[:len(enc)-2] + "AA"
	if tampered == enc {
		tampered = enc[:len(enc)-2] + "BB"
	}
	for name, s := range map[string]string{
		"篡改":  tampered,
		"截断":  enc[:10],
		"明文":  "plain",
		"无前缀": strings.TrimPrefix(enc, "v1:"),
	} {
		if _, err := c.Decrypt(s); err == nil {
			t.Fatalf("%s 应失败", name)
		}
	}
}

func TestDeriveKeySeparatesPurposes(t *testing.T) {
	if string(DeriveKey(testSecret, "session")) == string(DeriveKey(testSecret, "field")) {
		t.Fatal("不同用途应派生不同密钥")
	}
}

func TestLegacyDecrypt(t *testing.T) {
	// 样本由 openssl enc -aes-256-ecb 用 Java 版默认密钥（补 0 到 32 字节）生成，与 javax AES/ECB/PKCS5Padding 等价
	got, err := LegacyDecrypt("AirOpsCatDefaultSecretKey2024", "JLlpLubEEHcCC5GPL5foHKULhhBocxuii6AGc5OWYUc=")
	if err != nil || got != "ssh-密码 test!" {
		t.Fatalf("got %q, %v", got, err)
	}
	if _, err := LegacyDecrypt("wrong-key", "JLlpLubEEHcCC5GPL5foHKULhhBocxuii6AGc5OWYUc="); err == nil {
		t.Fatal("错误密钥应失败")
	}
	if _, err := LegacyDecrypt("AirOpsCatDefaultSecretKey2024", "root-password"); err == nil {
		t.Fatal("历史明文应返回错误")
	}
}
