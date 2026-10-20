package store

import (
	"context"
	"database/sql"
	"fmt"
	"strconv"
	"strings"
	"time"
)

func RuntimeTrafficServers(ctx context.Context, db *sql.DB, selection string, at time.Time) ([]int64, error) {
	if strings.TrimSpace(selection) == "" {
		return nil, nil
	}
	allowed := map[int64]bool{}
	if selection != "all" {
		for _, part := range strings.Split(selection, ",") {
			id, err := strconv.ParseInt(strings.TrimSpace(part), 10, 64)
			if err != nil || id <= 0 {
				return nil, fmt.Errorf("采集服务器配置无效")
			}
			allowed[id] = true
		}
	}
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT s.id FROM server s JOIN node_server ns ON ns.server_id=s.id JOIN node n ON n.id=ns.node_id
 WHERE s.disabled=0 AND s.external_server=0 AND (s.expire_date IS NULL OR s.expire_date>=?) AND ns.deployed=1 AND n.disabled=0 ORDER BY s.id`, at.In(Shanghai).Format("2006-01-02"))
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var ids []int64
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			return nil, err
		}
		if selection == "all" || allowed[id] {
			ids = append(ids, id)
		}
	}
	return ids, rows.Err()
}
func AccountNumbers(ctx context.Context, db *sql.DB) (map[string]bool, error) {
	rows, err := db.QueryContext(ctx, `SELECT account_no FROM account`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]bool{}
	for rows.Next() {
		var no string
		if err := rows.Scan(&no); err != nil {
			return nil, err
		}
		out[no] = true
	}
	return out, rows.Err()
}
