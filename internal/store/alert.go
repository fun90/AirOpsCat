package store

import (
	"context"
	"database/sql"
	"errors"
	"time"
)

const (
	AlertActive       = "ACTIVE"
	AlertAcknowledged = "ACKNOWLEDGED"
	AlertRecovered    = "RECOVERED"
)

// AlertKey 标识一条告警：同一键重复触发只会更新同一行。
type AlertKey struct {
	Type, ResourceType string
	ResourceID         int64
	ResourceKey        string
	Fingerprint        string
}

type Alert struct {
	ID int64
	AlertKey
	Status, Severity, Summary   string
	FirstTime, LastTime         string
	NotifiedTime, RecoveredTime string
	AckTime, AckBy              string
	Count                       int
	Value, Threshold            float64
}

// AlertTrigger 记录一次触发并判断是否需要通知：
// 新告警或已恢复后重新触发 → 通知；ACTIVE 且距上次通知已满 interval → 通知；ACKNOWLEDGED 持续静默。
func AlertTrigger(ctx context.Context, db *sql.DB, k AlertKey, severity string, value, threshold float64, summary string, interval time.Duration, now time.Time) (id int64, notify bool, err error) {
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return 0, false, err
	}
	defer tx.Rollback()
	ts := FormatTime(now)
	var status string
	var notified sql.NullString
	err = tx.QueryRowContext(ctx, `SELECT id,status,last_notified_time FROM alert_state WHERE alert_type=? AND resource_type=? AND resource_id=? AND fingerprint=?`,
		k.Type, k.ResourceType, k.ResourceID, k.Fingerprint).Scan(&id, &status, &notified)
	switch {
	case errors.Is(err, sql.ErrNoRows):
		res, err := tx.ExecContext(ctx, `INSERT INTO alert_state(alert_type,resource_type,resource_id,resource_key,fingerprint,status,severity,first_triggered_time,last_triggered_time,trigger_count,current_value,threshold_value,summary,create_time,update_time)
			VALUES(?,?,?,?,?,'ACTIVE',?,?,?,1,?,?,?,?,?)`, k.Type, k.ResourceType, k.ResourceID, nz(k.ResourceKey), k.Fingerprint, nz(severity), ts, ts, value, threshold, nz(summary), ts, ts)
		if err != nil {
			return 0, false, err
		}
		id, _ = res.LastInsertId()
		return id, true, tx.Commit()
	case err != nil:
		return 0, false, err
	}
	if status == AlertRecovered {
		_, err = tx.ExecContext(ctx, `UPDATE alert_state SET status='ACTIVE',first_triggered_time=?,last_triggered_time=?,last_notified_time=NULL,recovered_time=NULL,acknowledged_time=NULL,acknowledged_by=NULL,trigger_count=1,current_value=?,threshold_value=?,severity=?,summary=?,update_time=? WHERE id=?`,
			ts, ts, value, threshold, nz(severity), nz(summary), ts, id)
		notify = true
	} else {
		_, err = tx.ExecContext(ctx, `UPDATE alert_state SET last_triggered_time=?,trigger_count=trigger_count+1,current_value=?,threshold_value=?,severity=?,summary=?,update_time=? WHERE id=?`,
			ts, value, threshold, nz(severity), nz(summary), ts, id)
		if status == AlertActive {
			if !notified.Valid || notified.String == "" {
				notify = true
			} else if t, e := ParseTime(notified.String); e != nil || !t.Add(interval).After(now) {
				notify = true
			}
		}
	}
	if err != nil {
		return 0, false, err
	}
	return id, notify, tx.Commit()
}

// AlertMarkNotified 只在发送成功后调用，发送失败下一轮继续尝试。
func AlertMarkNotified(ctx context.Context, db *sql.DB, id int64, now time.Time) error {
	_, err := db.ExecContext(ctx, `UPDATE alert_state SET last_notified_time=? WHERE id=?`, FormatTime(now), id)
	return err
}

// AlertResolve 把某资源该类型下所有未恢复的告警置为已恢复，返回被恢复的行；不发送通知。
func AlertResolve(ctx context.Context, db *sql.DB, alertType, resourceType string, resourceID int64, now time.Time) ([]Alert, error) {
	rows, err := db.QueryContext(ctx, alertSel+` WHERE alert_type=? AND resource_type=? AND resource_id=? AND status<>'RECOVERED'`, alertType, resourceType, resourceID)
	if err != nil {
		return nil, err
	}
	out, err := scanAlerts(rows)
	if err != nil || len(out) == 0 {
		return nil, err
	}
	ts := FormatTime(now)
	_, err = db.ExecContext(ctx, `UPDATE alert_state SET status='RECOVERED',recovered_time=?,update_time=? WHERE alert_type=? AND resource_type=? AND resource_id=? AND status<>'RECOVERED'`, ts, ts, alertType, resourceType, resourceID)
	return out, err
}

// AlertAcknowledge 只允许确认进行中的告警；确认后条件持续也不再通知，条件恢复后重新触发会重置。
func AlertAcknowledge(ctx context.Context, db *sql.DB, id int64, by string, now time.Time) error {
	ts := FormatTime(now)
	res, err := db.ExecContext(ctx, `UPDATE alert_state SET status='ACKNOWLEDGED',acknowledged_time=?,acknowledged_by=?,update_time=? WHERE id=? AND status='ACTIVE'`, ts, by, ts, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return invalid("只能确认进行中的告警")
	}
	return nil
}

// AlertRecoverManual 人工恢复：下次条件仍满足会重新触发。
func AlertRecoverManual(ctx context.Context, db *sql.DB, id int64, now time.Time) error {
	ts := FormatTime(now)
	res, err := db.ExecContext(ctx, `UPDATE alert_state SET status='RECOVERED',recovered_time=?,update_time=? WHERE id=? AND status<>'RECOVERED'`, ts, ts, id)
	if err != nil {
		return err
	}
	if n, _ := res.RowsAffected(); n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

const alertSel = `SELECT id,alert_type,resource_type,resource_id,coalesce(resource_key,''),fingerprint,status,coalesce(severity,''),coalesce(summary,''),
	coalesce(first_triggered_time,''),coalesce(last_triggered_time,''),coalesce(last_notified_time,''),coalesce(recovered_time,''),coalesce(acknowledged_time,''),coalesce(acknowledged_by,''),
	coalesce(trigger_count,0),coalesce(current_value,0),coalesce(threshold_value,0) FROM alert_state`

func scanAlerts(rows *sql.Rows) ([]Alert, error) {
	defer rows.Close()
	var out []Alert
	for rows.Next() {
		var a Alert
		if err := rows.Scan(&a.ID, &a.Type, &a.ResourceType, &a.ResourceID, &a.ResourceKey, &a.Fingerprint, &a.Status, &a.Severity, &a.Summary,
			&a.FirstTime, &a.LastTime, &a.NotifiedTime, &a.RecoveredTime, &a.AckTime, &a.AckBy, &a.Count, &a.Value, &a.Threshold); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, rows.Err()
}

// AlertList status 为空表示全部；进行中的排在前面。
func AlertList(ctx context.Context, db *sql.DB, status, q string, page, size int) ([]Alert, int, error) {
	where, args := ` WHERE 1=1`, []any{}
	if status != "" {
		where += ` AND status=?`
		args = append(args, status)
	}
	if q != "" {
		where += ` AND (alert_type LIKE ? OR coalesce(summary,'') LIKE ? OR coalesce(resource_key,'') LIKE ?)`
		like := "%" + q + "%"
		args = append(args, like, like, like)
	}
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM alert_state`+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, alertSel+where+` ORDER BY (status='RECOVERED'), last_triggered_time DESC, id DESC LIMIT ? OFFSET ?`, append(args, size, (page-1)*size)...)
	if err != nil {
		return nil, 0, err
	}
	list, err := scanAlerts(rows)
	return list, total, err
}

func AlertActiveCount(ctx context.Context, db *sql.DB) (n int) {
	db.QueryRowContext(ctx, `SELECT count(*) FROM alert_state WHERE status='ACTIVE'`).Scan(&n)
	return
}

// AlertResolveMissing 恢复某类型下资源不在 keep 中的所有未恢复告警（资源被删除、禁用或不再满足条件）。
func AlertResolveMissing(ctx context.Context, db *sql.DB, alertType, resourceType string, keep map[int64]bool, now time.Time) ([]Alert, error) {
	rows, err := db.QueryContext(ctx, alertSel+` WHERE alert_type=? AND resource_type=? AND status<>'RECOVERED'`, alertType, resourceType)
	if err != nil {
		return nil, err
	}
	all, err := scanAlerts(rows)
	if err != nil {
		return nil, err
	}
	var out []Alert
	for _, a := range all {
		if keep[a.ResourceID] {
			continue
		}
		if _, err := db.ExecContext(ctx, `UPDATE alert_state SET status='RECOVERED',recovered_time=?,update_time=? WHERE id=?`, FormatTime(now), FormatTime(now), a.ID); err != nil {
			return nil, err
		}
		out = append(out, a)
	}
	return out, nil
}
