package web

import (
	"crypto/sha256"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"io"
	"net/http"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/monitor"
	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) monitorReport(w http.ResponseWriter, r *http.Request) {
	if !s.configBool(r.Context(), "airopscat.server.monitor.enabled", true) {
		http.Error(w, "监控已关闭", http.StatusServiceUnavailable)
		return
	}
	var report monitor.AgentReport
	decoder := json.NewDecoder(http.MaxBytesReader(w, r.Body, 256<<10))
	if err := decoder.Decode(&report); err != nil {
		http.Error(w, "无效的监控请求", 400)
		return
	}
	if decoder.Decode(new(any)) != io.EOF {
		http.Error(w, "无效的监控请求", 400)
		return
	}
	var expected string
	err := s.DB.QueryRowContext(r.Context(), `SELECT a.token_hash FROM monitor_agent a JOIN server s ON s.id=a.server_id WHERE s.id=? AND s.disabled=0 AND s.external_server=0 AND (s.expire_date IS NULL OR s.expire_date='' OR substr(s.expire_date,1,10)>=?)`, report.ServerID, time.Now().In(store.Shanghai).Format("2006-01-02")).Scan(&expected)
	token := strings.TrimPrefix(r.Header.Get("Authorization"), "Bearer ")
	hash := sha256.Sum256([]byte(token))
	if err != nil || !strings.HasPrefix(r.Header.Get("Authorization"), "Bearer ") || token == "" || subtle.ConstantTimeCompare([]byte(expected), []byte(hex.EncodeToString(hash[:]))) != 1 {
		http.Error(w, "无效的监控身份", 401)
		return
	}
	if err := store.SaveMonitorReport(r.Context(), s.DB, report, time.Now()); err != nil {
		http.Error(w, "无效、过期或重复的监控采样", 422)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	io.WriteString(w, "{\"ok\":true}\n")
}
