package notify

import (
	"context"
	"crypto/aes"
	"crypto/cipher"
	"encoding/base64"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func client(cfg Config) *Client {
	return &Client{Config: func(context.Context) (Config, error) { return cfg, nil }}
}

func TestSendPlain(t *testing.T) {
	var got map[string]any
	var path, ct string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		path, ct = r.URL.Path, r.Header.Get("Content-Type")
		json.NewDecoder(r.Body).Decode(&got)
	}))
	defer srv.Close()
	err := client(Config{URL: srv.URL + "/", DeviceKey: "dk", Group: "G", Sound: "s"}).Send(context.Background(), Message{Title: "t", Body: "b", Level: "timeSensitive"})
	if err != nil || path != "/dk" || ct != "application/json" || got["title"] != "t" || got["level"] != "timeSensitive" || got["group"] != "G" {
		t.Fatalf("%v %s %s %v", err, path, ct, got)
	}
	if _, ok := got["icon"]; ok {
		t.Fatal("空图标不应输出")
	}
}

func TestSendEncrypted(t *testing.T) {
	key, iv := strings.Repeat("k", 32), strings.Repeat("i", 16)
	var plain map[string]any
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(r.Body)
		r.Body = io.NopCloser(strings.NewReader(string(b)))
		r.ParseForm()
		if r.PostForm.Get("iv") != iv {
			t.Error("iv")
		}
		raw, _ := base64.StdEncoding.DecodeString(r.PostForm.Get("ciphertext"))
		blk, _ := aes.NewCipher([]byte(key))
		out := make([]byte, len(raw))
		cipher.NewCBCDecrypter(blk, []byte(iv)).CryptBlocks(out, raw)
		json.Unmarshal(out[:len(out)-int(out[len(out)-1])], &plain)
	}))
	defer srv.Close()
	if err := client(Config{URL: srv.URL, DeviceKey: "dk", Encrypt: true, Key: key, IV: iv}).Send(context.Background(), Message{Title: "加密", Body: "b"}); err != nil || plain["title"] != "加密" {
		t.Fatalf("%v %v", err, plain)
	}
	if err := client(Config{URL: srv.URL, DeviceKey: "dk", Encrypt: true, Key: "short", IV: iv}).Send(context.Background(), Message{Title: "x"}); err == nil {
		t.Fatal("密钥长度不对应拒绝")
	}
}

func TestSendErrors(t *testing.T) {
	if err := client(Config{}).Send(context.Background(), Message{Title: "x"}); !errors.Is(err, ErrNotConfigured) {
		t.Fatal(err)
	}
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(500) }))
	url := srv.URL
	err := client(Config{URL: url, DeviceKey: "secret-device-key"}).Send(context.Background(), Message{Title: "x"})
	if err == nil || strings.Contains(err.Error(), "secret-device-key") {
		t.Fatalf("HTTP 错误不应含设备 Key: %v", err)
	}
	srv.Close()
	if err := client(Config{URL: url, DeviceKey: "secret-device-key"}).Send(context.Background(), Message{Title: "x"}); err == nil || strings.Contains(err.Error(), "secret-device-key") {
		t.Fatalf("连接错误不应含设备 Key: %v", err)
	}
}
