// Package crypto 负责敏感字段加解密和由主密钥派生各用途子密钥。
package crypto

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"errors"
	"strings"
)

// MinSecretLen 是主密钥的最小长度，主密钥应是随机生成的字符串而不是普通口令。
const MinSecretLen = 32

const prefix = "v1:"

var ErrFormat = errors.New("密文格式错误")

// DeriveKey 由主密钥派生指定用途的 32 字节子密钥，使会话签名与数据加密互不复用密钥。
func DeriveKey(secret, purpose string) []byte {
	m := hmac.New(sha256.New, []byte(secret))
	m.Write([]byte("airopscat/" + purpose))
	return m.Sum(nil)
}

// Cipher 用 AES-256-GCM 加解密字段，密文格式为 "v1:" + base64(nonce ‖ 密文)。
type Cipher struct{ aead cipher.AEAD }

func New(key []byte) (*Cipher, error) {
	block, err := aes.NewCipher(key)
	if err != nil {
		return nil, err
	}
	aead, err := cipher.NewGCM(block)
	if err != nil {
		return nil, err
	}
	return &Cipher{aead: aead}, nil
}

// Encrypt 加密明文；空串原样返回，与「未填写」区分不开的值不加密。
func (c *Cipher) Encrypt(plain string) (string, error) {
	if plain == "" {
		return "", nil
	}
	nonce := make([]byte, c.aead.NonceSize())
	if _, err := rand.Read(nonce); err != nil {
		return "", err
	}
	sealed := c.aead.Seal(nonce, nonce, []byte(plain), nil)
	return prefix + base64.StdEncoding.EncodeToString(sealed), nil
}

// Decrypt 解密；不认识的格式、被篡改或密钥不对都返回错误，不回退为原文。
func (c *Cipher) Decrypt(s string) (string, error) {
	if s == "" {
		return "", nil
	}
	if !strings.HasPrefix(s, prefix) {
		return "", ErrFormat
	}
	raw, err := base64.StdEncoding.DecodeString(s[len(prefix):])
	if err != nil || len(raw) < c.aead.NonceSize() {
		return "", ErrFormat
	}
	n := c.aead.NonceSize()
	plain, err := c.aead.Open(nil, raw[:n], raw[n:], nil)
	if err != nil {
		return "", err
	}
	return string(plain), nil
}

// LegacyDecrypt 解开 Java 版 CryptoUtil 的密文（AES/ECB/PKCS5，密钥 UTF-8 字节截断或补 0 到 32 字节，标准 Base64），
// 只供导入工具使用。解不开时返回错误，由调用方决定是否当作历史明文。
func LegacyDecrypt(secret, s string) (string, error) {
	key := make([]byte, 32)
	copy(key, secret)
	raw, err := base64.StdEncoding.DecodeString(s)
	if err != nil || len(raw) == 0 || len(raw)%aes.BlockSize != 0 {
		return "", ErrFormat
	}
	block, err := aes.NewCipher(key)
	if err != nil {
		return "", err
	}
	out := make([]byte, len(raw))
	for i := 0; i < len(raw); i += aes.BlockSize {
		block.Decrypt(out[i:i+aes.BlockSize], raw[i:i+aes.BlockSize])
	}
	pad := int(out[len(out)-1])
	if pad == 0 || pad > aes.BlockSize {
		return "", ErrFormat
	}
	for _, b := range out[len(out)-pad:] {
		if int(b) != pad {
			return "", ErrFormat
		}
	}
	return string(out[:len(out)-pad]), nil
}
