package store

import (
	"context"
	"database/sql"
	"encoding/json"
	"fmt"
	"math/big"
	"net"
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
	nodeID, placementID, serverID int64
	name, host                    string
	external                      bool
	uuid                          string
	priority                      sql.NullInt64
	exposed                       sql.NullBool
	data                          map[string]any
}

// SubscriptionNodes 连接参数来自成功部署快照；待部署（deployed=0）但已有快照的部署位仍在服务器上运行，照常输出，
// 否则任一账户变更会让共享标签的所有账户订阅丢节点；待删除（deployed=2）不输出。
// 名称、外部自定义域名及已同步域名按既有订阅契约实时读取。
func SubscriptionNodes(ctx context.Context, db *sql.DB, a *Account, multiple *int) ([]map[string]any, error) {
	rows, err := db.QueryContext(ctx, `SELECT DISTINCT n.id,n.name,ns.id,ns.priority,ns.exposed,d.snapshot_json,coalesce(r.full_name,''),coalesce(r.status,''),s.ip,s.id,trim(coalesce(ns.access_host_custom,'')),s.external_server
 FROM account_tag at JOIN node_tag nt ON nt.tag_id=at.tag_id JOIN node n ON n.id=nt.node_id
 JOIN node_server ns ON ns.node_id=n.id JOIN node_deployment d ON d.node_server_id=ns.id
 JOIN server s ON s.id=d.server_id LEFT JOIN domain_dns_record r ON r.id=ns.access_host_id
 WHERE at.account_id=? AND n.disabled=0 AND n.type=0 AND ns.deployed<>2 AND coalesce(d.snapshot_json,'')<>'' ORDER BY n.id,ns.priority,ns.id`, a.ID)
	if err != nil {
		return nil, err
	}
	var entries []*subscriptionEntry
	for rows.Next() {
		var e subscriptionEntry
		var raw, domain, status, ip, custom string
		if err := rows.Scan(&e.nodeID, &e.name, &e.placementID, &e.priority, &e.exposed, &raw, &domain, &status, &ip, &e.serverID, &custom, &e.external); err != nil {
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
		if clients, known := e.data["clients"]; known {
			list, ok := clients.([]any)
			if !ok {
				rows.Close()
				return nil, fmt.Errorf("部署位 %d 客户端快照损坏", e.placementID)
			}
			digest := deploymentClientDigest(a.AccountNo, a.UUID)
			found := false
			for _, v := range list {
				if v == digest {
					found = true
					break
				}
			}
			if !found {
				continue
			}
			// 客户端集合只用于过滤，不进入外部订阅字段。
			delete(e.data, "clients")
		}
		// 连接地址：外部自定义域名（DNS 不归本系统管理）> 已同步访问域名 > 服务器 IP；账户订阅定制域名在后面覆盖
		e.host = ip
		if custom != "" {
			e.host = custom
		} else if status == "SYNCED" && strings.TrimSpace(domain) != "" {
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
	certHosts, err := serverCertHosts(ctx, db)
	if err != nil {
		return nil, err
	}
	bindings, err := AccountBindings(ctx, db, a.ID)
	if err != nil {
		return nil, err
	}
	selected := map[int64]map[int64]bool{}
	hosts := map[int64]string{}
	extUUID := map[int64]string{}
	for _, b := range bindings {
		if !b.Enabled {
			continue
		}
		if b.ExternalUUID != "" {
			extUUID[b.NodeID] = b.ExternalUUID
		}
		if b.PlacementID != 0 {
			if selected[b.NodeID] == nil {
				selected[b.NodeID] = map[int64]bool{}
			}
			selected[b.NodeID][b.PlacementID] = true
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
		if custom && !p[e.placementID] {
			continue
		}
		// 外部服务器无法下发账户 UUID，只输出已填写外部 UUID 的节点
		if e.external {
			if extUUID[e.nodeID] == "" {
				continue
			}
			e.uuid = extUUID[e.nodeID]
		} else {
			e.uuid = a.UUID
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
	names := make([]string, len(byPlacement))
	for i, e := range byPlacement {
		names[i] = e.name
	}
	for i, name := range uniqueSubscriptionNames(names) {
		byPlacement[i].name = name
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
			data["uuid"] = e.uuid
			data["sni"] = subscriptionSNI(e.host, certHosts[e.serverID], e.data)
			out = append(out, data)
		}
	}
	return out, nil
}

// serverCertHosts 返回每台服务器证书对应的域名：与证书脚本 05 的 server_host 同口径，
// 取指向该服务器、已同步的首条非通配 A 记录。
func serverCertHosts(ctx context.Context, db *sql.DB) (map[int64]string, error) {
	rows, err := db.QueryContext(ctx, `SELECT server_id,trim(coalesce(full_name,name)) FROM domain_dns_record
 WHERE server_id IS NOT NULL AND upper(type)='A' AND status='SYNCED' ORDER BY id`)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	out := map[int64]string{}
	for rows.Next() {
		var id int64
		var host string
		if err := rows.Scan(&id, &host); err != nil {
			return nil, err
		}
		if _, ok := out[id]; !ok && isDomainName(host) && !strings.Contains(host, "*") {
			out[id] = host
		}
	}
	return out, rows.Err()
}

func isDomainName(host string) bool { return host != "" && net.ParseIP(host) == nil }

// subscriptionSNI 是 TLS 类协议的客户端 SNI。普通 vless 若入站 tls.server_name 是域名则优先用它；其余协议不依赖该字段（服务端不使用）：
// 访问地址是域名时用它（DNS 指向本服务器）；否则用服务器证书域名；都没有时兼容旧快照的入站值，最后退回访问地址。
func subscriptionSNI(host, certHost string, snapshot map[string]any) string {
	if p, _ := snapshot["protocol"].(string); p == "vless" {
		// 普通 vless 允许在入站 tls.server_name 显式指定 SNI（与访问地址、证书域名不同时使用）。
		inbound, _ := snapshot["inbound"].(map[string]any)
		if tls, ok := inbound["tls"].(map[string]any); ok {
			if name, _ := tls["server_name"].(string); isDomainName(strings.TrimSpace(name)) {
				return strings.TrimSpace(name)
			}
		}
	}
	if isDomainName(host) {
		return host
	}
	if certHost != "" {
		return certHost
	}
	inbound, _ := snapshot["inbound"].(map[string]any)
	if tls, ok := inbound["tls"].(map[string]any); ok {
		if name, _ := tls["server_name"].(string); isDomainName(strings.TrimSpace(name)) {
			return strings.TrimSpace(name)
		}
	}
	return host
}

// uniqueSubscriptionNames 按传入顺序（部署位 ID 升序）保留首个重名，其后的重名递增编号。
func uniqueSubscriptionNames(names []string) []string {
	out := append([]string(nil), names...)
	used := map[string]bool{}
	var duplicates []int
	for i, name := range out {
		if used[name] {
			duplicates = append(duplicates, i)
		} else {
			used[name] = true
		}
	}
	for _, i := range duplicates {
		base, num := subscriptionNameParts(out[i])
		for {
			num.Add(num, big.NewInt(1))
			name := base + "-" + num.String()
			if !used[name] {
				out[i] = name
				used[name] = true
				break
			}
		}
	}
	return out
}

// PlacementSubscriptionNames 返回节点各部署位在订阅中的默认名称（未定制、未设倍数的账户）。
// shortcut: 只在本节点内去重，不同节点同名时实际订阅还会再递增编号；需要时按账户标签全量计算。
func PlacementSubscriptionNames(nodeName string, pls []*Placement) map[int64]string {
	var shown []*Placement
	for _, p := range pls {
		if p.Exposed && p.Deployed != 2 {
			shown = append(shown, p)
		}
	}
	sort.Slice(shown, func(i, j int) bool { return shown[i].ID < shown[j].ID })
	names := make([]string, len(shown))
	for i := range shown {
		names[i] = NodeNameWithNumber(nodeName)
	}
	out := map[int64]string{}
	for i, name := range uniqueSubscriptionNames(names) {
		out[shown[i].ID] = name
	}
	return out
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
