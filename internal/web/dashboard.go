package web

import (
	"net/http"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func (s *Server) dashboard(w http.ResponseWriter, r *http.Request) {
	stats, err := store.DashboardLoad(r.Context(), s.DB, time.Now())
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
	d["Stats"], d["RecentAlerts"], d["Online"] = stats, alerts, s.onlineSummaryFor(snap)
	s.render(w, "dashboard", http.StatusOK, d)
}
