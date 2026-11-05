package web

import (
	"database/sql"
	"errors"
	"net/http"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func formValue(r *http.Request, key string) string {
	return strings.TrimSpace(r.PostFormValue(key))
}

// optionalPathID 仅供原本就要求正整数 ID 的新建/编辑共用入口使用。
func optionalPathID(w http.ResponseWriter, r *http.Request) (int64, bool) {
	if r.PathValue("id") == "" {
		return 0, true
	}
	return pathID(w, r, "id")
}

// recordFound 区分查询失败与空结果；查询错误（含 ErrNoRows）沿用 500。
func (s *Server) recordFound(w http.ResponseWriter, r *http.Request, found bool, err error) bool {
	if err != nil {
		s.fail(w, r, err)
		return false
	}
	if !found {
		http.NotFound(w, r)
	}
	return found
}

// requestError 处理要求“记录不存在返回 404”的入口；内部错误仍返回通用提示。
func (s *Server) requestError(w http.ResponseWriter, r *http.Request, err error) bool {
	if err == nil {
		return false
	}
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
	} else {
		s.fail(w, r, err)
	}
	return true
}

// redirectError 只向用户展示调用方明确允许的错误，避免泄露内部错误。
func (s *Server) redirectError(w http.ResponseWriter, r *http.Request, back string, err error, visible ...error) bool {
	if err == nil {
		return false
	}
	for _, target := range visible {
		if errors.Is(err, target) {
			redirectMsg(w, r, back, "err", err.Error())
			return true
		}
	}
	s.fail(w, r, err)
	return true
}

func parseFormTime(raw string) (time.Time, error) {
	var t time.Time
	var err error
	for _, layout := range []string{"2006-01-02T15:04:05", formDateLayout} {
		if t, err = time.ParseInLocation(layout, raw, store.Shanghai); err == nil {
			break
		}
	}
	return t, err
}
