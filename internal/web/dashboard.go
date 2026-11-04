package web

import (
	"net/http"
	"strconv"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) dashboard(w http.ResponseWriter, r *http.Request) {
	days, _ := strconv.Atoi(r.URL.Query().Get("days"))
	if days != 3 && days != 7 {
		days = 1
	}
	stats, err := store.DashboardLoad(r.Context(), s.DB, time.Now(), days)
	if err != nil {
		s.fail(w, r, err)
		return
	}
	snap, err := s.onlineSnapshot(r.Context())
	if err != nil {
		s.fail(w, r, err)
		return
	}
	alerts := make([]alertView, len(stats.RecentAlerts))
	for i, a := range stats.RecentAlerts {
		name := alertTypeNames[a.Type]
		if name == "" {
			name = a.Type
		}
		alerts[i] = alertView{a, name}
	}
	d := s.pageData(r, "概览", "dashboard")
	var latest time.Time
	for _, sv := range snap.Servers {
		if sv.At.After(latest) {
			latest = sv.At
		}
	}
	d["Stats"], d["RecentAlerts"], d["Online"], d["Days"] = stats, alerts, s.onlineSummaryFor(snap), days
	if !latest.IsZero() {
		d["OnlineAt"] = latest.In(store.Shanghai).Format("15:04:05")
	}
	s.render(w, "dashboard", http.StatusOK, d)
}
