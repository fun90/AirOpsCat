package web

import (
	"errors"
	"net/http"
	"strconv"
	"strings"

	"github.com/fun90/airopscat/internal/store"
)

var userRoles = []string{"ADMIN", "PARTNER", "VIP"}

// UserForm 保留用户输入的原始文本，校验失败时原样回显；密码不回传。
type UserForm struct {
	ID                                        int64
	Email, NickName, RemarkName, Remark, Role string
	Disabled                                  bool
}

func (s *Server) userRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/users", s.userList)
	m.HandleFunc("GET /admin/users/new", func(w http.ResponseWriter, r *http.Request) {
		s.userForm(w, r, "新增用户", http.StatusOK, &UserForm{Role: "VIP"}, "")
	})
	m.HandleFunc("GET /admin/users/{id}/edit", s.userEdit)
	m.HandleFunc("POST /admin/users", s.userSave)
	m.HandleFunc("POST /admin/users/{id}", s.userSave)
	m.HandleFunc("POST /admin/users/{id}/delete", s.userDelete)
}

func (s *Server) userForm(w http.ResponseWriter, r *http.Request, title string, status int, f *UserForm, msg string) {
	d := s.pageData(r, title, "users")
	d["Form"], d["Roles"], d["FormErr"] = f, userRoles, msg
	s.render(w, "user_form", status, d)
}

func (s *Server) userList(w http.ResponseWriter, r *http.Request) {
	q := strings.TrimSpace(r.URL.Query().Get("q"))
	page, size := pageParams(r)
	list, total, err := store.ManagedUserList(r.Context(), s.DB, q, page, size)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "用户", "users")
	d["List"], d["Q"], d["Page"], d["Pages"] = list, q, page, (total+size-1)/size
	s.render(w, "users", http.StatusOK, d)
}

func (s *Server) userEdit(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	u, err := store.ManagedUserByID(r.Context(), s.DB, id)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	if u == nil {
		http.NotFound(w, r)
		return
	}
	s.userForm(w, r, "编辑用户", http.StatusOK, &UserForm{ID: u.ID, Email: u.Email, NickName: u.NickName,
		RemarkName: u.RemarkName, Remark: u.Remark, Role: u.Role, Disabled: u.Disabled}, "")
}

func (s *Server) userSave(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	f := &UserForm{ID: id, Email: formValue(r, "email"), NickName: formValue(r, "nick_name"), RemarkName: formValue(r, "remark_name"),
		Remark: formValue(r, "remark"), Role: formValue(r, "role"), Disabled: r.PostFormValue("disabled") == "1"}
	if id != 0 {
		old, err := store.ManagedUserByID(r.Context(), s.DB, id)
		if err != nil {
			s.fail(w, r, err)
			return
		}
		if old == nil {
			http.NotFound(w, r)
			return
		}
	}
	u := &store.ManagedUser{ID: id, Email: f.Email, NickName: f.NickName, RemarkName: f.RemarkName, Remark: f.Remark, Role: f.Role, Disabled: f.Disabled}
	// 密码不 trim：空白也是合法密码字符；为空表示保留旧密码
	err := store.SaveManagedUser(r.Context(), s.DB, u, r.PostFormValue("password"))
	if err == nil {
		redirectMsg(w, r, "/admin/users", "msg", "用户已保存")
		return
	}
	if !errors.Is(err, store.ErrInvalid) && !errors.Is(err, store.ErrConflict) {
		s.fail(w, r, err)
		return
	}
	title := "新增用户"
	if id != 0 {
		title = "编辑用户"
	}
	s.userForm(w, r, title, http.StatusUnprocessableEntity, f, err.Error())
}

func (s *Server) userDelete(w http.ResponseWriter, r *http.Request) {
	id, _ := strconv.ParseInt(r.PathValue("id"), 10, 64)
	cur := r.Context().Value(userKey).(*store.User).ID
	if err := store.DeleteManagedUser(r.Context(), s.DB, id, cur); err != nil {
		if errors.Is(err, store.ErrInvalid) || errors.Is(err, store.ErrConflict) {
			redirectMsg(w, r, "/admin/users", "err", err.Error())
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, "/admin/users", "msg", "用户已删除")
}
