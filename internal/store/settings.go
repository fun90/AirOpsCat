package store

import (
	"context"
	"database/sql"
	"fmt"
	"math"
	"net/url"
	"regexp"
	"strconv"
	"strings"
	"time"

	"github.com/fun90/airopscat/internal/crypto"
)

type Setting struct {
	Key, Value, Group, Type string
	Sensitive, HasValue     bool
}

// SensitiveSetting 与导入后的字段加密规则一致，Apple ID 也不回显。
func SensitiveSetting(key string) bool {
	switch strings.ToLower(key) {
	case "airopscat.api.token", "airopscat.apple.id", "airopscat.apple.pwd", "airopscat.bark.device-key", "airopscat.bark.encrypt-key", "airopscat.bark.encrypt-iv", "airopscat.sing-box.clash-api.secret":
		return true
	}
	return false
}

func settingType(key string) string {
	if SensitiveSetting(key) {
		return "password"
	}
	switch key {
	case "airopscat.bark.url", "airopscat.bark.default-icon", "airopscat.subscription.url", "airopscat.docs.url":
		return "url"
	case "airopscat.bark.encrypt-enabled", "airopscat.ratelimit.enabled", "airopscat.account.connection-limit.alert.enabled", "airopscat.account.connection-limit.alert.recovery-notify-enabled", "airopscat.server.monitor.enabled":
		return "boolean"
	}
	for _, suffix := range []string{"-mbps", "-minutes", "-seconds", "-hours", "-days", ".batch-size", "-times", "-servers"} {
		if strings.HasSuffix(key, suffix) {
			return "number"
		}
	}
	if key == ProbeServersKey {
		return "ids"
	}
	if strings.HasPrefix(key, "airopscat.server.monitor.alert.") && strings.HasSuffix(key, "-threshold") {
		return "threshold"
	}
	return "text"
}

// Settings 只返回已有行，敏感配置从不返回密文或明文。
func Settings(ctx context.Context, db *sql.DB) ([]Setting, error) {
	rows, err := db.QueryContext(ctx, `SELECT config_key,coalesce(config_value,''),group_key FROM system_config ORDER BY group_key,config_key`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var out []Setting
	for rows.Next() {
		var v Setting
		if err := rows.Scan(&v.Key, &v.Value, &v.Group); err != nil {
			return nil, err
		}
		if !ActiveSetting(v.Key) {
			continue
		}
		v.Group = SettingDefaults[strings.ToLower(v.Key)].Group
		v.Type = settingType(v.Key)
		v.Sensitive = SensitiveSetting(v.Key)
		v.HasValue = strings.TrimSpace(v.Value) != ""
		if v.Sensitive {
			v.Value = ""
		}
		out = append(out, v)
	}
	return out, rows.Err()
}

// SaveSettings 在同一事务中保存多项；任何一项不合法则全部不生效。只更新已有键；敏感值留空保留，API token 不提供清除操作。
func SaveSettings(ctx context.Context, db *sql.DB, cipher *crypto.Cipher, kv map[string]string) error {
	return inTx(ctx, db, func(tx *sql.Tx) error {
		for k, v := range kv {
			if err := saveSetting(ctx, tx, cipher, k, v); err != nil {
				return fmt.Errorf("%w（%s）", err, k)
			}
		}
		return nil
	})
}

func saveSetting(ctx context.Context, db dbtx, cipher *crypto.Cipher, key, value string) error {
	if !ActiveSetting(key) {
		return invalid("配置项不再使用或由环境变量控制")
	}
	var stored string
	err := db.QueryRowContext(ctx, `SELECT coalesce(config_value,'') FROM system_config WHERE config_key=?`, key).Scan(&stored)
	if err == sql.ErrNoRows {
		return fmt.Errorf("%w：配置键不存在", ErrInvalid)
	}
	if err != nil {
		return err
	}
	key = strings.ToLower(key)
	value = strings.TrimSpace(value)
	if SensitiveSetting(key) {
		if value == "" {
			if key == "airopscat.api.token" && strings.TrimSpace(stored) == "" {
				return fmt.Errorf("%w：API token 必填", ErrInvalid)
			}
			return nil
		}
		if key == "airopscat.bark.encrypt-key" && len([]byte(value)) != 32 {
			return fmt.Errorf("%w：Bark AES Key 必须为 32 字节", ErrInvalid)
		}
		if key == "airopscat.bark.encrypt-iv" && len([]byte(value)) != 16 {
			return fmt.Errorf("%w：Bark AES IV 必须为 16 字节", ErrInvalid)
		}
		if cipher == nil {
			return fmt.Errorf("敏感配置加密未初始化")
		}
		value, err = cipher.Encrypt(value)
		if err != nil {
			return err
		}
	} else {
		invalid := false
		switch settingType(key) {
		case "url":
			if value != "" {
				u, e := url.Parse(value)
				invalid = e != nil || u == nil || u.Hostname() == "" || (u.Scheme != "http" && u.Scheme != "https") || u.User != nil
			}
			if key == "airopscat.subscription.url" && value == "" {
				invalid = true
			}
		case "boolean":
			invalid = value != "true" && value != "false"
		case "number":
			n, e := strconv.ParseInt(value, 10, 32)
			invalid = e != nil || n < 1
			if key == "airopscat.account.guard.ttl-seconds" && n < 5 {
				invalid = true
			}
		case "ids":
			_, e := ParseIDs(value)
			invalid = e != nil
		case "threshold":
			n, e := strconv.ParseFloat(value, 64)
			invalid = e != nil || math.IsNaN(n) || math.IsInf(n, 0) || n < 0 || n > 100
		}
		if invalid {
			return fmt.Errorf("%w：配置值格式或范围不正确", ErrInvalid)
		}
	}
	_, err = db.ExecContext(ctx, `UPDATE system_config SET config_value=?,update_time=? WHERE config_key=?`, value, FormatTime(time.Now()), key)
	return err
}

// From、To 为 YYYY-MM-DD，含当天；空表示不限。
type LogFilter struct{ Category, Level, Q, From, To string }
type SystemLog struct {
	ID                                     int64
	Category, Level                        string
	ResourceID                             *int64
	ResourceName, Message, Detail, Created string
}

var logSecrets = regexp.MustCompile(`(?i)(["']?(?:[a-z0-9_.-]*(?:password|passwd|pwd|secret|token|auth_code|authcode|device[-_]?key|encrypt[-_]?(?:key|iv)|apple[._-]?id|api[-_]?key)[a-z0-9_.-]*)["']?\s*[:=]\s*)("(?:\\.|[^"\\])*"|'[^']*'|[^\s,;&}]+)`)
var logBearer = regexp.MustCompile(`(?i)\b(Bearer|Basic)\s+[^\s"',;]+`)
var logPrivateKey = regexp.MustCompile(`(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----`)

func safeLog(v string) string {
	v = logPrivateKey.ReplaceAllString(v, "[已隐藏私钥]")
	v = logSecrets.ReplaceAllString(v, `${1}"[已隐藏]"`)
	return logBearer.ReplaceAllString(v, `${1} [已隐藏]`)
}
func logLimit(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}

// Log 供部署和任务写安全摘要；调用方不得传凭据、完整配置或含秘密的命令输出。
// 常见凭据格式脱敏仅为补充，无法识别无标记的孤立秘密。
func Log(ctx context.Context, db *sql.DB, category, level string, id *int64, name, msg, detail string) error {
	category = strings.ToUpper(strings.TrimSpace(category))
	level = strings.ToUpper(strings.TrimSpace(level))
	if category == "" || len(category) > 32 || (level != "INFO" && level != "WARN" && level != "ERROR") || strings.TrimSpace(msg) == "" {
		return fmt.Errorf("%w：日志分类、级别或消息不正确", ErrInvalid)
	}
	_, err := db.ExecContext(ctx, `INSERT INTO system_log(category,level,resource_id,resource_name,message,detail,create_time) VALUES(?,?,?,?,?,?,?)`, category, level, id, logLimit(safeLog(name), 257), logLimit(safeLog(msg), 512), safeLog(detail), FormatTime(time.Now()))
	return err
}

// Logs 固定每页 20 条，时间相同按 id 倒序。
func Logs(ctx context.Context, db *sql.DB, f LogFilter, page int) ([]SystemLog, int, error) {
	if page < 1 {
		page = 1
	}
	if page > 100000000 {
		page = 100000000
	}
	where := " WHERE 1=1"
	args := []any{}
	if f.Category != "" {
		where += " AND category=?"
		args = append(args, f.Category)
	}
	if f.Level != "" {
		where += " AND level=?"
		args = append(args, strings.ToUpper(f.Level))
	}
	if f.Q != "" {
		where += " AND (instr(lower(message),lower(?))>0 OR instr(lower(resource_name),lower(?))>0)"
		args = append(args, f.Q, f.Q)
	}
	if f.From != "" {
		where += " AND create_time>=?"
		args = append(args, f.From+" 00:00:00")
	}
	if f.To != "" {
		where += " AND create_time<=?"
		args = append(args, f.To+" 23:59:59")
	}
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM system_log`+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT id,category,level,resource_id,coalesce(resource_name,''),message,coalesce(detail,''),create_time FROM system_log`+where+` ORDER BY create_time DESC,id DESC LIMIT 20 OFFSET ?`, append(args, (page-1)*20)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []SystemLog
	for rows.Next() {
		var v SystemLog
		if err := rows.Scan(&v.ID, &v.Category, &v.Level, &v.ResourceID, &v.ResourceName, &v.Message, &v.Detail, &v.Created); err != nil {
			return nil, 0, err
		}
		v.ResourceName = safeLog(v.ResourceName)
		v.Message = safeLog(v.Message)
		v.Detail = safeLog(v.Detail)
		list = append(list, v)
	}
	return list, total, rows.Err()
}

func LogCategories(ctx context.Context, db *sql.DB) ([]string, error) {
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT category FROM system_log ORDER BY category`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []string
	for rows.Next() {
		var v string
		if err := rows.Scan(&v); err != nil {
			return nil, err
		}
		list = append(list, v)
	}
	return list, rows.Err()
}

// CleanupSystemLogs 分批清理超过保留期的系统日志。
func CleanupSystemLogs(ctx context.Context, db *sql.DB, days int, at time.Time) error {
	if days < 1 {
		return invalid("日志保留天数至少为1")
	}
	for {
		res, err := db.ExecContext(ctx, `DELETE FROM system_log WHERE id IN(SELECT id FROM system_log WHERE create_time<? LIMIT 1000)`, FormatTime(at.AddDate(0, 0, -days)))
		if err != nil {
			return err
		}
		n, err := res.RowsAffected()
		if err != nil {
			return err
		}
		if n < 1000 {
			return nil
		}
	}
}

// ConfigValue 读取 system_config 中的明文配置项；不存在返回空串。
func ConfigValue(ctx context.Context, db *sql.DB, key string) string {
	var v sql.NullString
	db.QueryRowContext(ctx, `SELECT config_value FROM system_config WHERE config_key = ?`, key).Scan(&v)
	return strings.TrimRight(strings.TrimSpace(v.String), "/")
}

// ParseIDs 解析逗号分隔的正整数 ID 列表，空串返回空列表。
func ParseIDs(v string) ([]int64, error) {
	var ids []int64
	for _, part := range strings.Split(v, ",") {
		if part = strings.TrimSpace(part); part == "" {
			continue
		}
		id, err := strconv.ParseInt(part, 10, 64)
		if err != nil || id < 1 {
			return nil, fmt.Errorf("%w：ID 必须为正整数", ErrInvalid)
		}
		ids = append(ids, id)
	}
	return ids, nil
}
