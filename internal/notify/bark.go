// Package notify 发送 Bark 推送。失败只返回错误，不重试，也不把密钥写进错误信息。
package notify

import (
	"bytes"
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"strings"
	"time"
)

type Config struct {
	URL, DeviceKey     string
	Encrypt            bool
	Key, IV            string
	Group, Sound, Icon string
}

type Client struct {
	Config func(ctx context.Context) (Config, error) // 每次发送时读取，页面改配置立即生效
	HTTP   *http.Client
}

type Message struct {
	Title, Body, Level string // Level：timeSensitive / active / 空
}

var ErrNotConfigured = errors.New("Bark 未配置")

func (c *Client) Send(ctx context.Context, m Message) error {
	cfg, err := c.Config(ctx)
	if err != nil {
		return err
	}
	if strings.TrimSpace(cfg.URL) == "" || strings.TrimSpace(cfg.DeviceKey) == "" {
		return ErrNotConfigured
	}
	payload := map[string]any{"device_key": cfg.DeviceKey, "title": m.Title, "body": m.Body}
	if cfg.Icon != "" {
		payload["icon"] = cfg.Icon
	}
	if cfg.Sound != "" {
		payload["sound"] = cfg.Sound
	}
	if cfg.Group != "" {
		payload["group"] = cfg.Group
	}
	if m.Level != "" {
		payload["level"] = m.Level
	}
	raw, _ := json.Marshal(payload)
	endpoint := strings.TrimRight(cfg.URL, "/") + "/" + url.PathEscape(cfg.DeviceKey)
	var req *http.Request
	if cfg.Encrypt || cfg.Key != "" || cfg.IV != "" {
		ct, err := encrypt(raw, cfg.Key, cfg.IV)
		if err != nil {
			return err
		}
		form := url.Values{"ciphertext": {ct}, "iv": {cfg.IV}}
		req, err = http.NewRequestWithContext(ctx, "POST", endpoint, strings.NewReader(form.Encode()))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	} else {
		req, err = http.NewRequestWithContext(ctx, "POST", endpoint, bytes.NewReader(raw))
		if err != nil {
			return err
		}
		req.Header.Set("Content-Type", "application/json")
	}
	hc := c.HTTP
	if hc == nil {
		hc = &http.Client{Timeout: 15 * time.Second}
	}
	resp, err := hc.Do(req)
	if err != nil {
		// url.Error 会带上含设备 Key 的完整地址，只保留底层原因。
		var ue *url.Error
		if errors.As(err, &ue) {
			err = ue.Err
		}
		return fmt.Errorf("发送 Bark 失败: %w", err)
	}
	defer resp.Body.Close()
	io.Copy(io.Discard, io.LimitReader(resp.Body, 1<<20))
	if resp.StatusCode/100 != 2 {
		return fmt.Errorf("Bark 返回 HTTP %d", resp.StatusCode)
	}
	return nil
}

// encrypt 为 AES-256-CBC/PKCS7，key 必须 32 字节、iv 16 字节，输出标准 Base64。
func encrypt(plain []byte, key, iv string) (string, error) {
	if len(key) != 32 || len(iv) != 16 {
		return "", errors.New("Bark 加密需要 32 字节 Key 和 16 字节 IV")
	}
	block, err := aes.NewCipher([]byte(key))
	if err != nil {
		return "", err
	}
	pad := aes.BlockSize - len(plain)%aes.BlockSize
	buf := append(append([]byte{}, plain...), bytes.Repeat([]byte{byte(pad)}, pad)...)
	out := make([]byte, len(buf))
	cipher.NewCBCEncrypter(block, []byte(iv)).CryptBlocks(out, buf)
	return base64.StdEncoding.EncodeToString(out), nil
}
