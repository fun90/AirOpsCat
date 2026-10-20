package web

import (
	"net/http"
	"os"
	"strconv"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) backupRoutes(m *http.ServeMux) {
	m.HandleFunc("GET /admin/backups", s.backupList)
	m.HandleFunc("POST /admin/backups", s.backupCreate)
	m.HandleFunc("GET /admin/backups/{name}/download", s.backupDownload)
	m.HandleFunc("POST /admin/backups/{name}/delete", s.backupDelete)
}

func (s *Server) backupList(w http.ResponseWriter, r *http.Request) {
	list, err := s.Backup.List()
	if err != nil {
		s.fail(w, r, err)
		return
	}
	type row struct {
		Name, Size, Time string
	}
	rows := make([]row, 0, len(list))
	for _, f := range list {
		rows = append(rows, row{f.Name, strconv.FormatInt(f.Size/1024, 10) + " KB", store.FormatTime(f.ModTime)})
	}
	d := s.pageData(r, "数据库备份", "backups")
	d["List"], d["Dir"] = rows, s.Backup.Dir
	d["KeepDays"] = s.configInt(r.Context(), "airopscat.backup.retention-days", 15)
	s.render(w, "backups", 200, d)
}

func (s *Server) backupCreate(w http.ResponseWriter, r *http.Request) {
	name, err := s.Backup.Create(r.Context())
	if err != nil {
		redirectMsg(w, r, "/admin/backups", "err", err.Error())
		return
	}
	redirectMsg(w, r, "/admin/backups", "msg", "已生成备份 "+name)
}

func (s *Server) backupDownload(w http.ResponseWriter, r *http.Request) {
	p, err := s.Backup.Path(r.PathValue("name"))
	if err != nil {
		http.NotFound(w, r)
		return
	}
	f, err := os.Open(p)
	if err != nil {
		http.NotFound(w, r)
		return
	}
	defer f.Close()
	w.Header().Set("Content-Type", "application/octet-stream")
	w.Header().Set("Content-Disposition", `attachment; filename="`+r.PathValue("name")+`"`)
	http.ServeContent(w, r, r.PathValue("name"), time.Time{}, f)
}

func (s *Server) backupDelete(w http.ResponseWriter, r *http.Request) {
	if err := s.Backup.Delete(r.PathValue("name")); err != nil {
		http.NotFound(w, r)
		return
	}
	redirectMsg(w, r, "/admin/backups", "msg", "备份已删除")
}
