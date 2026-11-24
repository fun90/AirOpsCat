package web

import (
	"net/http"
	"net/url"
	"path"
	"strings"
	"unicode"
)

// adminPageURL 接受站内管理地址，并移除上一次操作的提示。
func adminPageURL(raw string) *url.URL {
	if len(raw) > 4096 || !strings.HasPrefix(raw, "/") || strings.HasPrefix(raw, "//") {
		return nil
	}
	u, err := url.Parse(raw)
	if err != nil || u.Host != "" || (u.Path != "/admin" && !strings.HasPrefix(u.Path, "/admin/")) ||
		path.Clean(u.Path) != u.Path || strings.Contains(u.Path, "\\") || strings.ContainsFunc(u.Path, unicode.IsControl) {
		return nil
	}
	q := u.Query()
	q.Del("msg")
	q.Del("err")
	u.RawQuery, u.ForceQuery = q.Encode(), false
	return u
}

// navURL 只在明确进入编辑页或详情页的链接上使用，可保留上一级页面的返回目标。
func navURL(destination, source string) string {
	u, from := adminPageURL(destination), adminPageURL(source)
	if u == nil || from == nil || u.Path == from.Path {
		return destination
	}
	q := u.Query()
	q.Set("back", from.String())
	u.RawQuery = q.Encode()
	if len(u.String()) > 4096 {
		return destination
	}
	return u.String()
}

func editPagePath(r *http.Request, fallback string) string {
	if r.Method != http.MethodPost {
		return r.URL.Path
	}
	if r.URL.Path == fallback {
		return fallback + "/new"
	}
	return r.URL.Path + "/edit"
}

func explicitReturnURL(r *http.Request, currentPath string) string {
	raw := r.URL.Query().Get("back")
	if r.Method == http.MethodPost {
		raw = r.PostFormValue("back")
	}
	if target := adminPageURL(raw); target != nil && target.Path != currentPath {
		return target.String()
	}
	return ""
}

func editReturnURL(r *http.Request, fallback string) string {
	if back := explicitReturnURL(r, editPagePath(r, fallback)); back != "" {
		return back
	}
	return fallback
}

func editPageNavigation(d map[string]any, r *http.Request, fallback string) {
	back := editReturnURL(r, fallback)
	d["EditReturn"] = back
	if r.Method == http.MethodPost {
		d["PageURL"] = navURL(editPagePath(r, fallback), back)
	}
}

func redirectEdit(w http.ResponseWriter, r *http.Request, fallback, key, msg string) {
	redirectNotice(w, r, editReturnURL(r, fallback), key, msg)
}

// redirectMsg 保留同一目标页的列表条件或详情上下文，由调用方决定跳转页面。
func redirectMsg(w http.ResponseWriter, r *http.Request, destination, key, msg string) {
	target := adminPageURL(destination)
	if target != nil && target.RawQuery == "" {
		if posted := adminPageURL(r.PostFormValue("back")); posted != nil && posted.Path == target.Path {
			destination = posted.String()
		} else if ref, err := url.Parse(r.Referer()); err == nil && ref.Host == r.Host {
			if source := adminPageURL(ref.RequestURI()); source != nil && source.Path == target.Path {
				destination = source.String()
			}
		}
	}
	redirectNotice(w, r, destination, key, msg)
}

func redirectNotice(w http.ResponseWriter, r *http.Request, destination, key, msg string) {
	u, err := url.Parse(destination)
	if err != nil {
		http.Error(w, "跳转地址无效", http.StatusInternalServerError)
		return
	}
	q := u.Query()
	q.Del("msg")
	q.Del("err")
	q.Set(key, msg)
	u.RawQuery = q.Encode()
	http.Redirect(w, r, u.String(), http.StatusSeeOther)
}
