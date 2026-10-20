package store

import (
	"context"
	"database/sql"
	"errors"
	"fmt"
	"strconv"
	"strings"
	"time"
)

const GuardTokenKey = "airopscat.api.token"

type GuardLimit struct {
	AccountNo              string
	MaxConnections, MaxIPs int64
}

type GuardSettings struct {
	TTL          time.Duration
	Consecutive  int
	OnlineWindow time.Duration
}

// LoadGuardState 不过滤禁用或到期账户：guard 只依据账户是否存在及其限制判定。
func LoadGuardState(ctx context.Context, db *sql.DB) (map[string]GuardLimit, GuardSettings, error) {
	settings := GuardSettings{TTL: 30 * time.Second, Consecutive: 2, OnlineWindow: 10 * time.Minute}
	limits := make(map[string]GuardLimit)
	rows, err := db.QueryContext(ctx, `SELECT account_no, coalesce(max_connections,0), coalesce(max_ips,0) FROM account`)
	if err != nil {
		return nil, settings, err
	}
	for rows.Next() {
		var l GuardLimit
		if err := rows.Scan(&l.AccountNo, &l.MaxConnections, &l.MaxIPs); err != nil {
			rows.Close()
			return nil, settings, err
		}
		limits[strings.ToLower(l.AccountNo)] = l
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, settings, err
	}
	rows, err = db.QueryContext(ctx, `SELECT config_key, config_value FROM system_config WHERE config_key IN ('airopscat.account.guard.ttl-seconds','airopscat.account.connection-limit.alert.consecutive-times','airopscat.online.check-minutes')`)
	if err != nil {
		return nil, settings, err
	}
	defer rows.Close()
	for rows.Next() {
		var key string
		var raw sql.NullString
		if err := rows.Scan(&key, &raw); err != nil {
			return nil, settings, err
		}
		n, err := strconv.Atoi(raw.String)
		if err != nil {
			continue
		}
		switch strings.ToLower(key) {
		case "airopscat.account.guard.ttl-seconds":
			settings.TTL = time.Duration(max(5, min(n, 86400))) * time.Second
		case "airopscat.account.connection-limit.alert.consecutive-times":
			settings.Consecutive = max(1, n)
		case "airopscat.online.check-minutes":
			settings.OnlineWindow = time.Duration(max(1, min(n, 525600))) * time.Minute
		}
	}
	return limits, settings, rows.Err()
}

func GuardToken(ctx context.Context, db *sql.DB) (string, error) {
	var value sql.NullString
	err := db.QueryRowContext(ctx, `SELECT config_value FROM system_config WHERE config_key=?`, GuardTokenKey).Scan(&value)
	if errors.Is(err, sql.ErrNoRows) {
		return "", nil
	}
	return value.String, err
}

func GuardNodeRegistered(ctx context.Context, db *sql.DB, ip string) (bool, error) {
	var exists bool
	err := db.QueryRowContext(ctx, `SELECT EXISTS(SELECT 1 FROM server WHERE ip=?)`, ip).Scan(&exists)
	return exists, err
}

type GuardOnlineAccount struct {
	AccountNo   string            `json:"accountNo"`
	NodeTag     string            `json:"nodeTag"`
	ClientIPs   []string          `json:"clientIps"`
	Connections []GuardConnection `json:"connections"`
}
type GuardConnection struct {
	ClientIP     string `json:"clientIp"`
	ConnectionID string `json:"connectionId"`
	Start        string `json:"start"`
}

// RefreshGuardOnline 刷新报告中出现的连接，未出现的历史记录自然过期；同一在线会话保留最初开始时间。
func RefreshGuardOnline(ctx context.Context, db *sql.DB, nodeIP string, accounts []GuardOnlineAccount, now time.Time) error {
	_, settings, err := LoadGuardState(ctx, db)
	if err != nil {
		return err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	nodes := map[string]int64{}
	rows, err := tx.QueryContext(ctx, `SELECT n.id FROM node n JOIN node_server p ON p.node_id=n.id JOIN server s ON s.id=p.server_id WHERE s.ip=? AND p.deployed=1 AND n.disabled=0 AND n.type=0`, nodeIP)
	if err != nil {
		return err
	}
	for rows.Next() {
		var id int64
		if err := rows.Scan(&id); err != nil {
			rows.Close()
			return err
		}
		nodes[fmt.Sprintf("node_%d", id)] = id
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return err
	}
	for _, a := range accounts {
		var accountNo string
		err := tx.QueryRowContext(ctx, `SELECT account_no FROM account WHERE account_no=?`, a.AccountNo).Scan(&accountNo)
		if errors.Is(err, sql.ErrNoRows) {
			continue
		}
		if err != nil {
			return err
		}
		var nodeID any
		if id, ok := nodes[a.NodeTag]; ok {
			nodeID = id
		}
		connections := a.Connections
		if len(connections) == 0 {
			for _, ip := range a.ClientIPs {
				connections = append(connections, GuardConnection{ClientIP: ip})
			}
		}
		for _, c := range connections {
			if strings.TrimSpace(c.ClientIP) == "" {
				continue
			}
			id := c.ConnectionID
			if strings.TrimSpace(id) == "" {
				id = fmt.Sprintf("guard-ip|%s|%s|%s|%s", accountNo, c.ClientIP, nodeIP, a.NodeTag)
			}
			start, err := time.Parse(time.RFC3339Nano, c.Start)
			if err != nil {
				start = now
			}
			_, err = tx.ExecContext(ctx, `INSERT INTO account_online_ip (account_no,client_ip,connection_id,node_ip,node_id,node_tag,last_online_time,session_start_time,create_time,update_time)
    VALUES (?,?,?,?,?,?,?,?,?,?) ON CONFLICT(account_no,node_ip,connection_id) DO UPDATE SET
    client_ip=excluded.client_ip,node_id=excluded.node_id,node_tag=excluded.node_tag,
    session_start_time=CASE WHEN account_online_ip.last_online_time<=? THEN excluded.session_start_time ELSE account_online_ip.session_start_time END,
    last_online_time=excluded.last_online_time,update_time=excluded.update_time`, accountNo, c.ClientIP, id, nodeIP, nodeID, a.NodeTag, FormatTime(now), FormatTime(start), FormatTime(now), FormatTime(now), FormatTime(now.Add(-settings.OnlineWindow)))
			if err != nil {
				return err
			}
		}
	}
	return tx.Commit()
}

// CleanupGuardOnline 独立提供给保留期任务，固定保留最近 30 天。
func CleanupGuardOnline(ctx context.Context, db *sql.DB, now time.Time) (int64, error) {
	result, err := db.ExecContext(ctx, `DELETE FROM account_online_ip WHERE last_online_time < ?`, FormatTime(now.AddDate(0, 0, -30)))
	if err != nil {
		return 0, err
	}
	return result.RowsAffected()
}
