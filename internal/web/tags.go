package web

import (
	"encoding/json"
	"errors"
	"net/http"
	"regexp"
	"strconv"
	"strings"

	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) tagRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/tags", s.tagList)
	m.HandleFunc("POST /admin/tag-picker", s.tagPickerSave)
	m.HandleFunc("POST /admin/tags", s.tagSave)
	m.HandleFunc("POST /admin/tags/{id}", s.tagSave)
	m.HandleFunc("POST /admin/tags/{id}/delete", s.tagDelete)
}

func (s *Server) tagList(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	list, total, err := store.TagList(r.Context(), s.DB, q, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	tag := &store.Tag{Color: "#206bc4"}
	if id, _ := strconv.ParseInt(r.URL.Query().Get("edit"), 10, 64); id > 0 {
		t, err := store.TagByID(r.Context(), s.DB, id)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if t == nil {
			http.NotFound(w, r)
			return
		}
		if t.Color == "" {
			t.Color = tag.Color
		}
		tag = t
	}
	d := s.pageData(r, "标签", "tags")
	d["List"], d["Q"], d["Tag"] = list, q, tag
	d["Page"], d["Pages"] = page, (total+size-1)/size
	s.render(w, "tags", http.StatusOK, d)
}

func (s *Server) tagSave(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	t := &store.Tag{ID: id, Name: formValue(r, "name"), Description: formValue(r, "description"), Color: formValue(r, "color")}
	if !colorRe.MatchString(t.Color) {
		t.Color = ""
	}
	var msg string
	switch {
	case t.Name == "":
		msg = "请填写标签名称"
	case len([]rune(t.Name)) > 64:
		msg = "标签名称最长 64 个字符"
	default:
		if err := store.SaveTag(r.Context(), s.DB, t); err != nil {
			if !errors.Is(err, store.ErrConflict) {
				s.fail(w, r, err)
				return
			}
			msg = err.Error()
		}
	}
	if msg != "" {
		redirect := "/admin/tags"
		if id != 0 {
			redirect = "/admin/tags?edit=" + strconv.FormatInt(id, 10)
		}
		redirectMsg(w, r, redirect, "err", msg)
		return
	}
	redirectMsg(w, r, "/admin/tags", "msg", "标签已保存")
}

func (s *Server) tagDelete(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	if err := store.DeleteTag(r.Context(), s.DB, id); err != nil {
		if errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, "/admin/tags", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/tags", "msg", "标签已删除")
}

var colorRe = regexp.MustCompile(`^#[0-9a-fA-F]{6}$`)

// tagPickerSave 仅保存标签元数据，不提交或改动尚未保存的节点表单。
func (s *Server) tagPickerSave(w http.ResponseWriter, r *http.Request) {
	idText := formValue(r, "id")
	id, err := strconv.ParseInt(idText, 10, 64)
	if idText == "" {
		id, err = 0, nil
	}
	if err != nil || id < 0 {
		http.Error(w, "标签 ID 不正确", 400)
		return
	}
	name, color := formValue(r, "name"), formValue(r, "color")
	if name == "" || len([]rune(name)) > 64 {
		http.Error(w, "标签名称需为 1–64 个字符", 422)
		return
	}
	if !colorRe.MatchString(color) {
		http.Error(w, "颜色格式不正确", 422)
		return
	}
	tag := &store.Tag{ID: id, Name: name, Color: color}
	if id != 0 {
		old, err := store.TagByID(r.Context(), s.DB, id)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if old == nil {
			http.NotFound(w, r)
			return
		}
		tag.Description = old.Description
	}
	if err := store.SaveTag(r.Context(), s.DB, tag); err != nil {
		if errors.Is(err, store.ErrConflict) {
			http.Error(w, "标签名称已存在", 409)
			return
		}
		s.fail(w, r, err)
		return
	}
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(map[string]any{"id": tag.ID, "name": tag.Name, "color": tag.Color})
}
