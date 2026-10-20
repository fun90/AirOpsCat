package web

import (
	"errors"
	"net/http"
	"net/url"
	"sort"
	"strings"

	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) settingsRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/settings", s.settingsPage)
	m.HandleFunc("POST /admin/settings", s.settingsSave)
	m.HandleFunc("GET /admin/logs", s.logsPage)
}

type settingView struct {
	store.Setting
	Label, Hint string
}
type settingGroupView struct {
	Key, Title, Icon string
	Count            int
}

func (s *Server) settingsPage(w http.ResponseWriter, r *http.Request) {
	list, err := store.Settings(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	byGroup := map[string][]settingView{}
	for _, v := range list {
		sv := settingView{Setting: v, Label: v.Key}
		if m, ok := settingMeta[v.Key]; ok {
			sv.Label, sv.Hint = m[0], m[1]
		}
		byGroup[v.Group] = append(byGroup[v.Group], sv)
	}
	for _, items := range byGroup {
		sort.SliceStable(items, func(i, j int) bool {
			ri, oki := settingOrder[items[i].Key]
			rj, okj := settingOrder[items[j].Key]
			if oki != okj {
				return oki
			}
			return ri < rj
		})
	}
	var groups []settingGroupView
	seen := map[string]bool{}
	for _, g := range settingGroups {
		if n := len(byGroup[g.Key]); n > 0 {
			groups = append(groups, settingGroupView{g.Key, g.Title, g.Icon, n})
			seen[g.Key] = true
		}
	}
	for _, v := range list {
		if !seen[v.Group] {
			seen[v.Group] = true
			groups = append(groups, settingGroupView{v.Group, v.Group, "ti-settings", len(byGroup[v.Group])})
		}
	}
	d := s.pageData(r, "系统配置", "settings")
	d["Groups"] = groups
	if s.Backup != nil {
		d["BackupDir"] = s.Backup.Dir
	}
	if len(groups) > 0 {
		cur := groups[0]
		for _, g := range groups {
			if g.Key == r.URL.Query().Get("group") {
				cur = g
			}
		}
		d["Current"], d["Items"] = cur, byGroup[cur.Key]
	}
	s.render(w, "settings", http.StatusOK, d)
}

// settingsSave 保存一个分组的全部配置项，任一项不合法则整组不生效。
func (s *Server) settingsSave(w http.ResponseWriter, r *http.Request) {
	group := strings.TrimSpace(r.PostFormValue("group"))
	back := "/admin/settings?group=" + url.QueryEscape(group)
	kv := map[string]string{}
	for k, vs := range r.PostForm {
		if strings.HasPrefix(k, "airopscat.") && len(vs) > 0 {
			kv[k] = vs[len(vs)-1] // 复选框带隐藏的 false，取最后一个值
		}
	}
	if len(kv) == 0 {
		redirectMsg(w, r, back, "err", "没有可保存的配置")
		return
	}
	if err := store.SaveSettings(r.Context(), s.DB, s.Field, kv); err != nil {
		if errors.Is(err, store.ErrInvalid) {
			http.Redirect(w, r, back+"&err="+url.QueryEscape(err.Error()), http.StatusSeeOther)
			return
		}
		s.fail(w, r, err)
		return
	}
	redirectMsg(w, r, back, "msg", "配置已保存")
}
func (s *Server) logsPage(w http.ResponseWriter, r *http.Request) {
	page, _ := pageParams(r)
	f := store.LogFilter{Category: strings.TrimSpace(r.URL.Query().Get("category")), Level: strings.ToUpper(strings.TrimSpace(r.URL.Query().Get("level"))), Q: strings.TrimSpace(r.URL.Query().Get("q"))}
	if f.Level != "" && f.Level != "INFO" && f.Level != "WARN" && f.Level != "ERROR" {
		f.Level = ""
	}
	list, total, err := store.Logs(r.Context(), s.DB, f, page)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	categories, err := store.LogCategories(r.Context(), s.DB)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	d := s.pageData(r, "系统日志", "logs")
	d["List"], d["Total"], d["Page"], d["Pages"], d["Category"], d["Level"], d["Q"], d["Categories"] = list, total, page, (total+19)/20, f.Category, f.Level, f.Q, categories
	s.render(w, "logs", http.StatusOK, d)
}
