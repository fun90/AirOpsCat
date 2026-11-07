package store

import (
	"context"
	"database/sql"
	"time"
)

// TaskLastStarts 返回各持久化任务的上次开始时间；无法解析的记录跳过。
func TaskLastStarts(ctx context.Context, db *sql.DB) (map[string]time.Time, error) {
	rows, err := db.QueryContext(ctx, `SELECT task_key,last_start FROM task_run`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[string]time.Time{}
	for rows.Next() {
		var key, ts string
		if err := rows.Scan(&key, &ts); err != nil {
			return nil, err
		}
		if t, err := ParseTime(ts); err == nil {
			out[key] = t
		}
	}
	return out, rows.Err()
}

// SaveTaskStart 记录任务一次执行的开始时间，只前进不后退，避免较早开始的并发执行覆盖较新的记录。
func SaveTaskStart(ctx context.Context, db *sql.DB, key string, at time.Time) error {
	_, err := db.ExecContext(ctx, `INSERT INTO task_run(task_key,last_start) VALUES(?,?)
 ON CONFLICT(task_key) DO UPDATE SET last_start=excluded.last_start WHERE excluded.last_start>task_run.last_start`, key, FormatTime(at))
	return err
}
