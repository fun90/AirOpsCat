package store

import (
	"context"
	"database/sql"
	"fmt"
	"sort"
	"strings"
	"time"
)

// 下线建议的固定口径：近 30 天活跃账户不超过 2 个，且当前周期流量低于均值的 20%。
const (
	idleWindowDays   = 30
	idleMaxAccounts  = 2
	idleTrafficShare = 0.2
)

// IdleServer 是下线建议页的一行。
type IdleServer struct {
	ID                                     int64
	Name, IP, Region, Supplier, ExpireDate string
	MonthlyCents                           *int64 // 价格折算到每月，未设置价格为 nil
	ActiveAccounts                         int    // 近 30 天 guard 上报的去重账户数
	TrafficBytes                           int64  // 当前流量周期 vnstat 上下行合计
	HasTraffic                             bool
	Blockers                               []string // 下线前须先处理的原因
	Candidate                              bool
	serverType                             int
}

func (s IdleServer) TrafficGB() float64 { return float64(s.TrafficBytes) / 1e9 }

// costPerAccount 用于排序：无活跃账户按 1 个计，未设置价格排最后。
func (s IdleServer) costPerAccount() int64 {
	if s.MonthlyCents == nil {
		return -1
	}
	return *s.MonthlyCents / int64(max(s.ActiveAccounts, 1))
}

// IdleServers 列出启用的非外部服务器的使用情况：候选在前，其余按单位账户月成本降序。
func IdleServers(ctx context.Context, db *sql.DB, at time.Time) ([]IdleServer, error) {
	at = at.In(Shanghai)
	rows, err := db.QueryContext(ctx, `SELECT s.id, coalesce(s.name,''), s.ip, coalesce(s.region,''), coalesce(s.supplier,''), coalesce(s.expire_date,''),
 coalesce(s.server_type,0), s.price, s.price_months,
 (SELECT count(DISTINCT o.account_no) FROM account_online_ip o WHERE o.node_ip=s.ip AND o.last_online_time>=?),
 coalesce(v.rx_bytes,0)+coalesce(v.tx_bytes,0), v.id IS NOT NULL
 FROM server s
 LEFT JOIN server_metrics v ON v.id=`+latestVnstatSQL+`
 WHERE s.disabled=0 AND s.external_server=0 ORDER BY s.id`,
		append([]any{FormatTime(at.AddDate(0, 0, -idleWindowDays))}, vnstatPeriodArgs(at)...)...)
	if err != nil {
		return nil, err
	}
	defer rows.Close()
	var list []IdleServer
	regions := map[string]int{}
	var total int64
	var sampled int
	for rows.Next() {
		var s IdleServer
		var price sql.NullInt64
		var months int64
		if err := rows.Scan(&s.ID, &s.Name, &s.IP, &s.Region, &s.Supplier, &s.ExpireDate, &s.serverType, &price, &months,
			&s.ActiveAccounts, &s.TrafficBytes, &s.HasTraffic); err != nil {
			return nil, err
		}
		if price.Valid {
			c := (price.Int64 + months/2) / months
			s.MonthlyCents = &c
		}
		if s.HasTraffic {
			total += s.TrafficBytes
			sampled++
		}
		if s.Region != "" {
			regions[fmt.Sprint(s.serverType, "|", s.Region)]++
		}
		list = append(list, s)
	}
	if err := rows.Err(); err != nil {
		return nil, err
	}
	rows.Close()
	// 节点除这台外没有其他已部署的部署位时，删除部署位会被拒绝，须先加部署位
	sole, err := db.QueryContext(ctx, `SELECT p.server_id, coalesce(n.name,'') FROM node_server p JOIN node n ON n.id=p.node_id
 WHERE p.deployed<>2 AND n.disabled=0 AND NOT EXISTS (SELECT 1 FROM node_server q JOIN server t ON t.id=q.server_id
   WHERE q.node_id=p.node_id AND q.server_id<>p.server_id AND q.deployed=1 AND t.disabled=0)
 ORDER BY n.id`)
	if err != nil {
		return nil, err
	}
	defer sole.Close()
	soleNodes := map[int64][]string{}
	for sole.Next() {
		var id int64
		var name string
		if err := sole.Scan(&id, &name); err != nil {
			return nil, err
		}
		soleNodes[id] = append(soleNodes[id], name)
	}
	if err := sole.Err(); err != nil {
		return nil, err
	}
	var avg float64
	if sampled > 0 {
		avg = float64(total) / float64(sampled)
	}
	for i := range list {
		s := &list[i]
		if names := soleNodes[s.ID]; len(names) > 0 {
			s.Blockers = append(s.Blockers, "节点无其他部署位："+strings.Join(names, "、"))
		}
		if s.Region != "" && regions[fmt.Sprint(s.serverType, "|", s.Region)] == 1 {
			s.Blockers = append(s.Blockers, "地区唯一："+s.Region)
		}
		s.Candidate = len(s.Blockers) == 0 && s.HasTraffic && s.ActiveAccounts <= idleMaxAccounts &&
			float64(s.TrafficBytes) < avg*idleTrafficShare
	}
	sort.SliceStable(list, func(i, j int) bool {
		if list[i].Candidate != list[j].Candidate {
			return list[i].Candidate
		}
		return list[i].costPerAccount() > list[j].costPerAccount()
	})
	return list, nil
}
