package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"math/big"
	"sort"
	"strings"
)

// SubscriptionAccount 保留 node_multiple 的 NULL 语义，避免导入账户被额外改名。
func SubscriptionAccount(ctx context.Context, db *sql.DB, code string) (*Account, *int, error) {
	a, err := scanAccount(db.QueryRowContext(ctx, `SELECT `+accountCols+` FROM account a WHERE a.auth_code=?`, code))
	if err == sql.ErrNoRows {
		return nil, nil, nil
	}
	if err != nil {
		return nil, nil, err
	}
	var multiple sql.NullInt64
	if err := db.QueryRowContext(ctx, `SELECT node_multiple FROM account WHERE id=?`, a.ID).Scan(&multiple); err != nil {
		return nil, nil, err
	}
	if multiple.Valid {
		n := int(multiple.Int64)
		return a, &n, nil
	}
	return a, nil, nil
}

type subscriptionEntry struct {
	nodeID, placementID int64
	name, host          string
	priority            sql.NullInt64
	exposed             sql.NullBool
	data                map[string]any
}

// SubscriptionNodes 连接参数来自成功部署快照；名称及已同步域名按既有订阅契约实时读取。
func SubscriptionNodes(ctx context.Context, db *sql.DB, a *Account, multiple *int) ([]map[string]any, error) {
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT n.id,n.name,ns.id,ns.priority,ns.exposed,d.snapshot_json,coalesce(r.full_name,''),coalesce(r.status,''),s.ip
 FROM account_tag at JOIN node_tag nt ON nt.tag_id=at.tag_id JOIN node n ON n.id=nt.node_id
 JOIN node_server ns ON ns.node_id=n.id JOIN node_deployment d ON d.node_server_id=ns.id
 JOIN server s ON s.id=d.server_id LEFT JOIN domain_dns_record r ON r.id=ns.access_host_id
 WHERE at.account_id=? AND n.disabled=0 AND n.type=0 AND ns.deployed=1 AND coalesce(d.snapshot_json,'')<>'' ORDER BY n.id,ns.priority,ns.id`, a.ID)
	if err != nil {
		return nil, err
	}
	var entries []*subscriptionEntry
	for rows.Next() {
		var e subscriptionEntry
		var raw, domain, status, ip string
		if err := rows.Scan(&e.nodeID, &e.name, &e.placementID, &e.priority, &e.exposed, &raw, &domain, &status, &ip); err != nil {
			rows.Close()
			return nil, err
		}
		dec := json.NewDecoder(strings.NewReader(raw))
		dec.UseNumber()
		if err := dec.Decode(&e.data); err != nil {
			rows.Close()
			return nil, fmt.Errorf("部署位 %d 快照损坏: %w", e.placementID, err)
		}
		disabled := fmt.Sprint(e.data["disabled"])
		if disabled == "1" || disabled == "true" {
			continue
		}
		if fmt.Sprint(e.data["type"]) != "0" {
			continue
		}
		e.host = ip
		if status == "SYNCED" && strings.TrimSpace(domain) != "" {
			e.host = strings.TrimSpace(domain)
		}
		e.data["serverIp"] = ip
		e.name = NodeNameWithNumber(e.name)
		entries = append(entries, &e)
	}
	err = rows.Err()
	rows.Close()
	if err != nil {
		return nil, err
	}
	bindings, err := AccountBindings(ctx, db, a.ID)
	if err != nil {
		return nil, err
	}
	selected := map[int64]int64{}
	hosts := map[int64]string{}
	for _, b := range bindings {
		if !b.Enabled {
			continue
		}
		if b.PlacementID != 0 {
			selected[b.NodeID] = b.PlacementID
		}
		if b.RecordID != nil {
			var name string
			if err := db.QueryRowContext(ctx, `SELECT coalesce(full_name,'') FROM domain_dns_record WHERE id=? AND status='SYNCED'`, *b.RecordID).Scan(&name); err != nil && err != sql.ErrNoRows {
				return nil, err
			}
			if strings.TrimSpace(name) != "" {
				hosts[b.NodeID] = strings.TrimSpace(name)
			}
		}
	}
	sort.SliceStable(entries, func(i, j int) bool {
		l, r := entries[i], entries[j]
		if l.nodeID != r.nodeID {
			return l.nodeID < r.nodeID
		}
		if l.priority.Valid != r.priority.Valid {
			return l.priority.Valid
		}
		if l.priority.Int64 != r.priority.Int64 {
			return l.priority.Int64 < r.priority.Int64
		}
		return l.placementID < r.placementID
	})
	var chosen []*subscriptionEntry
	done := map[int64]bool{}
	for _, e := range entries {
		p, custom := selected[e.nodeID]
		if custom && p != e.placementID {
			continue
		}
		if h := hosts[e.nodeID]; h != "" {
			if done[e.nodeID] {
				continue
			}
			e.host = h
			done[e.nodeID] = true
		} else if !custom && e.exposed.Valid && !e.exposed.Bool {
			continue
		}
		chosen = append(chosen, e)
	}
	// 先按部署位 ID 解决重名，再保持节点输出顺序进行混淆。
	byPlacement := append([]*subscriptionEntry(nil), chosen...)
	sort.SliceStable(byPlacement, func(i, j int) bool { return byPlacement[i].placementID < byPlacement[j].placementID })
	used := map[string]bool{}
	var duplicates []*subscriptionEntry
	for _, e := range byPlacement {
		if used[e.name] {
			duplicates = append(duplicates, e)
		} else {
			used[e.name] = true
		}
	}
	for _, e := range duplicates {
		base, num := subscriptionNameParts(e.name)
		for {
			num.Add(num, big.NewInt(1))
			name := base + "-" + num.String()
			if !used[name] {
				e.name = name
				used[name] = true
				break
			}
		}
	}
	var out []map[string]any
	for _, e := range chosen {
		count := 1
		if multiple != nil {
			count = max(1, min(10, *multiple))
		}
		base, num := subscriptionNameParts(e.name)
		num.Mul(num, big.NewInt(10))
		for i := 0; i < count; i++ {
			data := map[string]any{}
			for k, v := range e.data {
				data[k] = v
			}
			data["name"] = e.name
			if multiple != nil {
				data["name"] = base + "-" + a.NodePrefix + new(big.Int).Add(num, big.NewInt(int64(i))).String()
			}
			data["serverHost"] = e.host
			out = append(out, data)
		}
	}
	return out, nil
}
func subscriptionNameParts(name string) (string, *big.Int) {
	name = NodeNameWithNumber(name)
	i := strings.LastIndex(name, "-")
	if i >= 0 {
		n, ok := new(big.Int).SetString(name[i+1:], 10)
		if ok {
			return name[:i], n
		}
	}
	return name, big.NewInt(1)
}
