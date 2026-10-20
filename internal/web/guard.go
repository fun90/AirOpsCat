package web

import (
	"bytes"
	"compress/gzip"
	"crypto/sha256"
	"crypto/subtle"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"net/netip"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/guard"
	"github.com/fun90/airopscat/internal/store"
)

const guardCompressedLimit = 1 << 20
const guardDecodedLimit = 8 << 20

// guardSync 由主路由注册 POST /api/open/guard-sync，独立于控制台会话和 CSRF。
func (s *Server) guardSync(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		guardError(w, http.StatusMethodNotAllowed, "仅支持 POST")
		return
	}
	encrypted, err := store.GuardToken(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	token := ""
	if s.Field != nil {
		token, err = s.Field.Decrypt(encrypted)
	}
	// 缺密钥、解密失败和空配置都拒绝，不允许明文配置回退。
	expected := sha256.Sum256([]byte(token))
	actual := sha256.Sum256([]byte(r.Header.Get("Token")))
	match := subtle.ConstantTimeCompare(expected[:], actual[:])
	if err != nil || strings.TrimSpace(token) == "" || r.Header.Get("Token") == "" || match != 1 {
		guardError(w, http.StatusUnauthorized, "无效的 Token")
		return
	}
	raw, err := io.ReadAll(http.MaxBytesReader(w, r.Body, guardCompressedLimit))
	if err != nil {
		var sizeErr *http.MaxBytesError
		if errors.As(err, &sizeErr) {
			guardError(w, http.StatusRequestEntityTooLarge, "请求过大")
		} else {
			guardError(w, http.StatusBadRequest, "读取请求失败")
		}
		return
	}
	switch strings.ToLower(strings.TrimSpace(r.Header.Get("Content-Encoding"))) {
	case "", "identity":
	case "gzip":
		reader, e := gzip.NewReader(bytes.NewReader(raw))
		if e != nil {
			guardError(w, http.StatusBadRequest, "无效的 gzip")
			return
		}
		raw, e = io.ReadAll(io.LimitReader(reader, guardDecodedLimit+1))
		reader.Close()
		if len(raw) > guardDecodedLimit {
			guardError(w, http.StatusRequestEntityTooLarge, "解压请求过大")
			return
		}
		if e != nil {
			guardError(w, http.StatusBadRequest, "无效的 gzip")
			return
		}
	default:
		guardError(w, http.StatusUnsupportedMediaType, "不支持的 Content-Encoding")
		return
	}
	var request *guard.Request
	decoder := json.NewDecoder(bytes.NewReader(raw))
	if err := decoder.Decode(&request); err != nil || request == nil {
		guardError(w, http.StatusBadRequest, "无效的 JSON")
		return
	}
	if err := decoder.Decode(new(any)); err != io.EOF {
		guardError(w, http.StatusBadRequest, "无效的 JSON")
		return
	}
	if strings.TrimSpace(request.NodeIP) == "" {
		guardError(w, http.StatusBadRequest, "缺少 nodeIp")
		return
	}
	if _, err := netip.ParseAddr(request.NodeIP); err != nil {
		guardError(w, http.StatusBadRequest, "无效的 nodeIp")
		return
	}
	exists, err := store.GuardNodeRegistered(r.Context(), s.DB, request.NodeIP)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if !exists {
		guardError(w, http.StatusForbidden, "未登记的节点 IP")
		return
	}
	if s.Guard == nil {
		guardError(w, http.StatusServiceUnavailable, "guard 尚未初始化")
		return
	}
	if err := s.Guard.Report(r.Context(), request.NodeIP, request.Accounts); err != nil {
		s.fail(w, r, err)
		return
	}
	if request.OnlineAccountIPs != nil {
		if err := store.RefreshGuardOnline(r.Context(), s.DB, request.NodeIP, request.OnlineAccountIPs, time.Now()); err != nil {
			s.fail(w, r, err)
			return
		}
	}
	response, err := s.Guard.Response(r.Context(), request.Accounts)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.Header().Set("Cache-Control", "no-store")
	json.NewEncoder(w).Encode(response)
}

func guardError(w http.ResponseWriter, status int, message string) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(map[string]string{"error": message})
}
