package web

import (
	"crypto/rand"
	"database/sql"
	"encoding/json"
	"errors"
	"fmt"
	"math/big"
	"net/http"
	"strconv"

	"github.com/fun90/airopscat/internal/singbox"
	"github.com/fun90/airopscat/internal/store"
)

var errEmptyRealitySNI = errors.New("没有启用的 Reality SNI，请先添加或启用域名，也可手动填写入站配置")

func (s *Server) realityInbound(r *http.Request) (json.RawMessage, error) {
	list, err := store.RealitySNIs(r.Context(), s.DB, true)
	if err != nil {
		return nil, err
	}
	if len(list) == 0 {
		return nil, errEmptyRealitySNI
	}
	index, err := rand.Int(rand.Reader, big.NewInt(int64(len(list))))
	if err != nil {
		return nil, err
	}
	return singbox.DefaultRealityInbound(list[index.Int64()].Domain)
}

func (s *Server) realitySNIRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/reality-sni", s.realitySNIList)
	m.HandleFunc("POST /admin/reality-sni", s.realitySNISave)
	m.HandleFunc("POST /admin/reality-sni/{id}", s.realitySNISave)
	m.HandleFunc("POST /admin/reality-sni/{id}/toggle", func(w http.ResponseWriter, r *http.Request) {
		id, ok := pathID(w, r, "id")
		if !ok {
			return
		}
		if s.requestError(w, r, store.ToggleRealitySNI(r.Context(), s.DB, id, r.PostFormValue("enabled") == "1")) {
			return
		}
		redirectMsg(w, r, "/admin/reality-sni", "msg", "候选状态已更新")
	})
	m.HandleFunc("POST /admin/reality-sni/{id}/delete", func(w http.ResponseWriter, r *http.Request) {
		id, ok := pathID(w, r, "id")
		if !ok {
			return
		}
		if s.requestError(w, r, store.DeleteRealitySNI(r.Context(), s.DB, id)) {
			return
		}
		redirectMsg(w, r, "/admin/reality-sni", "msg", "候选已删除，已有节点配置保持不变")
	})
}

func (s *Server) realitySNIList(w http.ResponseWriter, r *http.Request) {
	var edit *store.RealitySNI
	if raw := r.URL.Query().Get("edit"); raw != "" {
		id, err := strconv.ParseInt(raw, 10, 64)
		if err != nil || id <= 0 {
			http.Error(w, "候选 ID 不正确", 400)
			return
		}
		list, err := store.RealitySNIs(r.Context(), s.DB, false)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		for _, v := range list {
			if v.ID == id {
				edit = &v
				break
			}
		}
		if edit == nil {
			http.NotFound(w, r)
			return
		}
	}
	if edit == nil {
		edit = &store.RealitySNI{Enabled: true}
	}
	s.realitySNIPage(w, r, http.StatusOK, edit, "")
}

func (s *Server) realitySNIPage(w http.ResponseWriter, r *http.Request, status int, form *store.RealitySNI, formErr string) {
	list, err := store.RealitySNIs(r.Context(), s.DB, false)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "Reality SNI", "reality-sni")
	d["List"], d["Form"], d["FormErr"] = list, form, formErr
	s.render(w, "reality_sni", status, d)
}

func (s *Server) realitySNISave(w http.ResponseWriter, r *http.Request) {
	id, ok := optionalPathID(w, r)
	if !ok {
		return
	}
	v := &store.RealitySNI{ID: id, Domain: formValue(r, "domain"), Note: formValue(r, "note"), Enabled: r.PostFormValue("enabled") == "1"}
	err := store.SaveRealitySNI(r.Context(), s.DB, v)
	if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
		s.realitySNIPage(w, r, http.StatusUnprocessableEntity, v, err.Error())
		return
	}
	if errors.Is(err, sql.ErrNoRows) {
		http.NotFound(w, r)
		return
	}
	if err != nil {
		s.fail(w, r, fmt.Errorf("保存 Reality SNI: %w", err))
		return
	}
	redirectMsg(w, r, "/admin/reality-sni", "msg", "候选已保存，已有节点配置保持不变")
}
