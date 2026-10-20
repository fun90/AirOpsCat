package store

import (
	"bytes"
	"context"
	"database/sql"
	"encoding/json"
	"errors"
	"net/netip"
	"strings"
	"unicode"
	"unicode/utf8"
)

type RouteRule struct {
	ID                                int64
	Name, RuleType, RuleValue, Remark string
	OutboundNodeID                    int64
	OutboundName                      string
	Enabled                           bool
	ServerIDs                         []int64
	ServerCount                       int
}

func (r *RouteRule) Summary() string {
	value := []rune(r.RuleValue)
	if len(value) > 80 {
		return string(value[:80]) + "…"
	}
	return r.RuleValue
}

const routeRuleColumns = `r.id,r.name,r.rule_type,r.rule_value,r.outbound_node_id,coalesce(n.name,''),r.enabled,coalesce(r.remark,''),(SELECT count(*) FROM route_rule_server rs WHERE rs.route_rule_id=r.id)`

func scanRouteRule(row interface{ Scan(...any) error }) (*RouteRule, error) {
	var r RouteRule
	err := row.Scan(&r.ID, &r.Name, &r.RuleType, &r.RuleValue, &r.OutboundNodeID, &r.OutboundName, &r.Enabled, &r.Remark, &r.ServerCount)
	if errors.Is(err, sql.ErrNoRows) {
		return nil, nil
	}
	return &r, err
}

func RouteRuleList(ctx context.Context, db *sql.DB, search string, page, size int) ([]*RouteRule, int, error) {
	if page < 1 {
		page = 1
	}
	if size < 1 {
		size = 20
	}
	like := "%" + strings.TrimSpace(search) + "%"
	const where = ` WHERE r.name LIKE ? OR r.rule_type LIKE ? OR r.rule_value LIKE ? OR coalesce(r.remark,'') LIKE ? OR coalesce(n.name,'') LIKE ?`
	args := []any{like, like, like, like, like}
	var total int
	if err := db.QueryRowContext(ctx, `SELECT count(*) FROM route_rule r LEFT JOIN node n ON n.id=r.outbound_node_id`+where, args...).Scan(&total); err != nil {
		return nil, 0, err
	}
	rows, err := db.QueryContext(ctx, `SELECT `+routeRuleColumns+` FROM route_rule r LEFT JOIN node n ON n.id=r.outbound_node_id`+where+` ORDER BY r.id DESC LIMIT ? OFFSET ?`, append(args, size, (page-1)*size)...)
	if err != nil {
		return nil, 0, err
	}
	defer rows.Close()
	var list []*RouteRule
	for rows.Next() {
		r, err := scanRouteRule(rows)
		if err != nil {
			return nil, 0, err
		}
		list = append(list, r)
	}
	return list, total, rows.Err()
}

func RouteRuleByID(ctx context.Context, db *sql.DB, id int64) (*RouteRule, error) {
	r, err := scanRouteRule(db.QueryRowContext(ctx, `SELECT `+routeRuleColumns+` FROM route_rule r LEFT JOIN node n ON n.id=r.outbound_node_id WHERE r.id=?`, id))
	if err != nil || r == nil {
		return r, err
	}
	rows, err := db.QueryContext(ctx, `SELECT server_id FROM route_rule_server WHERE route_rule_id=? ORDER BY server_id`, id)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	for rows.Next() {
		var sid int64
		if err := rows.Scan(&sid); err != nil {
			return nil, err
		}
		r.ServerIDs = append(r.ServerIDs, sid)
	}
	return r, rows.Err()
}

// validateRouteValue 保留 JSON 结构，只约束生成器对应字段接受的值。
func validateRouteValue(kind, value string) error {
	var v any
	if json.Unmarshal([]byte(value), &v) != nil || v == nil {
		return invalid("规则值必须是合法且非 null 的 JSON")
	}
	if kind == "custom" {
		obj, ok := v.(map[string]any)
		if !ok || len(obj) == 0 {
			return invalid("custom 规则值必须是非空 JSON 对象")
		}
		if _, ok := obj["outbound"]; ok {
			return invalid("custom 不能包含 outbound，请使用落地节点选择出站")
		}
		return nil
	}
	switch kind {
	case "domain", "ip", "source_ip", "port", "network", "inbound":
	default:
		return invalid("规则类型不正确")
	}
	values, ok := v.([]any)
	if !ok {
		values = []any{v}
	}
	if len(values) == 0 {
		return invalid("规则值数组不能为空")
	}
	for _, item := range values {
		if kind == "port" {
			n, ok := item.(float64)
			if !ok || n < 1 || n > 65535 || n != float64(int(n)) {
				return invalid("port 必须是 1–65535 的整数或整数数组")
			}
			continue
		}
		text, ok := item.(string)
		if !ok || strings.TrimSpace(text) == "" || strings.TrimSpace(text) != text || strings.IndexFunc(text, unicode.IsSpace) >= 0 {
			return invalid("%s 必须是非空字符串或字符串数组，值不能包含空白", kind)
		}
		switch kind {
		case "network":
			if text != "tcp" && text != "udp" {
				return invalid("network 仅支持 tcp、udp")
			}
		case "ip", "source_ip":
			if strings.HasPrefix(text, "geoip:") && len(text) > 6 {
				continue
			}
			if _, err := netip.ParseAddr(text); err == nil {
				continue
			}
			if _, err := netip.ParsePrefix(text); err != nil {
				return invalid("%s 需为 IP、CIDR 或 geoip:<名称>", kind)
			}
		case "domain":
			if strings.HasPrefix(text, "geosite:") && len(text) == 8 {
				return invalid("geosite 名称不能为空")
			}
		}
	}
	return nil
}

// SaveRouteRule 原子保存规则和服务器关联；规则变化不修改部署位状态。
func SaveRouteRule(ctx context.Context, db *sql.DB, r *RouteRule) error {
	r.Name = strings.TrimSpace(r.Name)
	r.RuleType = strings.ToLower(strings.TrimSpace(r.RuleType))
	if r.Name == "" || utf8.RuneCountInString(r.Name) > 255 {
		return invalid("规则名称需为 1–255 个字符")
	}
	if utf8.RuneCountInString(r.Remark) > 255 {
		return invalid("备注不能超过 255 个字符")
	}
	if err := validateRouteValue(r.RuleType, r.RuleValue); err != nil {
		return err
	}
	tx, err := db.BeginTx(ctx, nil)
	if err != nil {
		return err
	}
	defer tx.Rollback()
	var typ int
	err = tx.QueryRowContext(ctx, `SELECT type FROM node WHERE id=? AND lower(trim(core_type))='sing-box'`, r.OutboundNodeID).Scan(&typ)
	if errors.Is(err, sql.ErrNoRows) || err == nil && typ != NodeLanding {
		return invalid("出站必须是已存在的 sing-box 落地节点")
	}
	if err != nil {
		return err
	}
	ids := normalizeTagIDs(r.ServerIDs)
	for _, sid := range ids {
		var count int
		if err := tx.QueryRowContext(ctx, `SELECT count(*) FROM server WHERE id=?`, sid).Scan(&count); err != nil {
			return err
		}
		if count == 0 {
			return invalid("所选服务器不存在")
		}
	}
	var compact bytes.Buffer
	_ = json.Compact(&compact, []byte(r.RuleValue))
	now := FormatTime(nowFunc())
	if r.ID == 0 {
		res, err := tx.ExecContext(ctx, `INSERT INTO route_rule (name,core_type,rule_type,rule_value,outbound_node_id,enabled,remark,create_time,update_time) VALUES (?,'sing-box',?,?,?,?,?,?,?)`, r.Name, r.RuleType, compact.String(), r.OutboundNodeID, r.Enabled, nz(r.Remark), now, now)
		if err != nil {
			return err
		}
		if r.ID, err = res.LastInsertId(); err != nil {
			return err
		}
	} else {
		res, err := tx.ExecContext(ctx, `UPDATE route_rule SET name=?,core_type='sing-box',rule_type=?,rule_value=?,outbound_node_id=?,enabled=?,remark=?,update_time=? WHERE id=?`, r.Name, r.RuleType, compact.String(), r.OutboundNodeID, r.Enabled, nz(r.Remark), now, r.ID)
		if err := routeRuleAffected(res, err); err != nil {
			return err
		}
	}
	if _, err := tx.ExecContext(ctx, `DELETE FROM route_rule_server WHERE route_rule_id=?`, r.ID); err != nil {
		return err
	}
	for _, sid := range ids {
		if _, err := tx.ExecContext(ctx, `INSERT INTO route_rule_server (route_rule_id,server_id) VALUES (?,?)`, r.ID, sid); err != nil {
			return err
		}
	}
	if err := tx.Commit(); err != nil {
		return err
	}
	r.RuleValue = compact.String()
	r.ServerIDs = ids
	return nil
}

func routeRuleAffected(res sql.Result, err error) error {
	if err != nil {
		return err
	}
	n, err := res.RowsAffected()
	if err != nil {
		return err
	}
	if n == 0 {
		return sql.ErrNoRows
	}
	return nil
}

func ToggleRouteRule(ctx context.Context, db *sql.DB, id int64, enabled bool) error {
	res, err := db.ExecContext(ctx, `UPDATE route_rule SET enabled=?,update_time=? WHERE id=?`, enabled, FormatTime(nowFunc()), id)
	return routeRuleAffected(res, err)
}

func DeleteRouteRule(ctx context.Context, db *sql.DB, id int64) error {
	res, err := db.ExecContext(ctx, `DELETE FROM route_rule WHERE id=?`, id)
	return routeRuleAffected(res, err)
}

// RouteRuleServers 只加载选择器需要的公开字段。
func RouteRuleServers(ctx context.Context, db *sql.DB) ([]*Server, error) {
	rows, err := db.QueryContext(ctx, `SELECT id,coalesce(name,''),ip FROM server ORDER BY name,id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []*Server
	for rows.Next() {
		var s Server
		if err := rows.Scan(&s.ID, &s.Name, &s.IP); err != nil {
			return nil, err
		}
		list = append(list, &s)
	}
	return list, rows.Err()
}
