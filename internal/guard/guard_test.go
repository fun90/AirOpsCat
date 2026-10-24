package guard

import (
	"context"
	"database/sql"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/fun90/airopscat/internal/store"
)

func setup(t *testing.T) (*Aggregator, *sql.DB, *time.Time) {
	t.Helper()
	db, err := store.Open(context.Background(), filepath.Join(t.TempDir(), "guard.db"))
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { db.Close() })
	if _, err := db.Exec(`INSERT INTO account(account_no,uuid,auth_code,period_type,max_connections,max_ips) VALUES ('A','a','a','MONTHLY',10,2),('B','b','b','MONTHLY',0,1)`); err != nil {
		t.Fatal(err)
	}
	now := time.Unix(1800000000, 0)
	return New(db, func() time.Time { return now }), db, &now
}
func TestAggregationSamplingTTLAndDeletion(t *testing.T) {
	a, db, now := setup(t)
	ctx := context.Background()
	report := []AccountReport{{"A", 6, []string{"1", "2"}, nil}, {"B", 1, []string{"1", "2"}, nil}, {"unknown", 100, nil, nil}}
	for i := 0; i < 20; i++ {
		if err := a.Report(ctx, "node1", report); err != nil {
			t.Fatal(err)
		}
	}
	if err := a.Report(ctx, "node2", []AccountReport{{"A", 6, []string{"2", "3"}, nil}}); err != nil {
		t.Fatal(err)
	}
	response, err := a.Response(ctx, report)
	if err != nil || len(response.BlockedAccounts) != 0 {
		t.Fatal(response, err)
	}
	stats, err := a.Evaluate(ctx)
	if err != nil {
		t.Fatal(err)
	}
	if stats["A"].Connections != 12 || stats["A"].IPs != 3 || stats["A"].ActiveNodes != 2 || stats["A"].Consecutive != 1 || stats["A"].Blocked != nil {
		t.Fatal(stats)
	}
	for i := 0; i < 2; i++ {
		if _, err := a.Evaluate(ctx); err != nil {
			t.Fatal(err)
		}
	}
	response, err = a.Response(ctx, report[:1])
	if err != nil || len(response.BlockedAccounts) != 1 || response.BlockedAccounts["A"].Reason != "connections" || response.SchemaVersion != 1 || response.TTLSeconds != 30 {
		t.Fatal(response, err)
	}
	response, _ = a.Response(ctx, report)
	if response.BlockedAccounts["B"].Reason != "ips" {
		t.Fatal(response)
	}
	*now = now.Add(30 * time.Second)
	stats, _ = a.Evaluate(ctx)
	if stats["A"].Blocked == nil {
		t.Fatal("TTL 边界提前失效")
	}
	*now = now.Add(time.Second)
	stats, _ = a.Evaluate(ctx)
	if stats["A"].Blocked != nil || stats["A"].Consecutive != 0 {
		t.Fatal(stats)
	}
	if err := a.Evict(ctx); err != nil || len(a.table) != 0 {
		t.Fatal(err, a.table)
	}
	a.Report(ctx, "node1", report)
	a.Evaluate(ctx)
	a.Evaluate(ctx)
	if _, err := db.Exec(`DELETE FROM account WHERE account_no='A'`); err != nil {
		t.Fatal(err)
	}
	response, err = a.Response(ctx, report)
	if err != nil {
		t.Fatal(err)
	}
	if _, ok := response.BlockedAccounts["A"]; ok {
		t.Fatal(response)
	}
	if _, ok := a.table["a"]; ok {
		t.Fatal("已删除账户仍被跟踪")
	}
}
func TestSnapshotReplacementAndRecovery(t *testing.T) {
	a, _, _ := setup(t)
	ctx := context.Background()
	a.Report(ctx, "one", []AccountReport{{"A", 11, nil, nil}})
	a.Evaluate(ctx)
	a.Evaluate(ctx)
	a.Report(ctx, "one", nil)
	stats, err := a.Evaluate(ctx)
	if err != nil || stats["A"].Blocked != nil || stats["A"].Connections != 0 {
		t.Fatal(stats, err)
	}
	a.Report(ctx, "one", []AccountReport{{"A", -1, []string{"1", "1", "2", ""}, nil}})
	stats, _ = a.Evaluate(ctx)
	if stats["A"].Connections != 0 || stats["A"].IPs != 2 || stats["A"].Consecutive != 0 {
		t.Fatal(stats)
	}
}
func TestConcurrentAccess(t *testing.T) {
	a, _, _ := setup(t)
	ctx := context.Background()
	var wg sync.WaitGroup
	for i := 0; i < 4; i++ {
		wg.Go(func() {
			for j := 0; j < 10; j++ {
				if err := a.Report(ctx, "one", []AccountReport{{"A", 11, nil, nil}}); err != nil {
					t.Error(err)
				}
				if _, err := a.Evaluate(ctx); err != nil {
					t.Error(err)
				}
				a.Response(ctx, nil)
				a.Evict(ctx)
			}
		})
	}
	wg.Wait()
}

func TestSnapshotOnlineView(t *testing.T) {
	a, _, now := setup(t)
	ctx := context.Background()
	n3 := int64(3)
	if err := a.Report(ctx, "s1", []AccountReport{{"A", 3, []string{"1", "2"}, nil}, {"unknown", 9, nil, nil}}); err != nil {
		t.Fatal(err)
	}
	if err := a.ReportTags(ctx, "s1", []store.GuardOnlineAccount{{AccountNo: "a", NodeTag: "node_1", ClientIPs: []string{"1", "2"}, ConnectionCount: &n3}}); err != nil {
		t.Fatal(err)
	}
	// s2 为旧版 agent：有账户上报，但没有按标签分组。
	if err := a.Report(ctx, "s2", []AccountReport{{"A", 2, []string{"2", "3"}, nil}}); err != nil {
		t.Fatal(err)
	}
	if err := a.ReportTags(ctx, "s2", nil); err != nil {
		t.Fatal(err)
	}
	snap, err := a.Snapshot(ctx)
	if err != nil {
		t.Fatal(err)
	}
	acc := snap.Accounts["a"]
	if acc.Connections != 5 || acc.IPs != 3 || len(acc.Nodes) != 2 || snap.TotalConnections != 5 {
		t.Fatal(snap)
	}
	if _, ok := snap.Accounts["unknown"]; ok {
		t.Fatal("未知账户不应出现在在线视图")
	}
	if sv := snap.Servers["s1"]; sv.Accounts != 1 || sv.Connections != 3 || !sv.TagsKnown {
		t.Fatal(snap.Servers)
	}
	if snap.Servers["s2"].TagsKnown {
		t.Fatal("旧版 agent 不应标记为已知标签")
	}
	if tg := snap.Tags["node_1"]; tg.Accounts != 1 || tg.Connections != 3 || !tg.ConnKnown {
		t.Fatal(snap.Tags)
	}
	// 空报告属于新鲜上报：服务器仍在，但账户消失，而不是“无上报”。
	if err := a.Report(ctx, "s2", nil); err != nil {
		t.Fatal(err)
	}
	snap, _ = a.Snapshot(ctx)
	if _, ok := snap.Servers["s2"]; !ok || snap.Accounts["a"].Connections != 3 {
		t.Fatal(snap)
	}
	// 缺少连接数的来源让标签连接数变为未知。
	if err := a.ReportTags(ctx, "s1", []store.GuardOnlineAccount{{AccountNo: "A", NodeTag: "node_1", ClientIPs: []string{"1"}}}); err != nil {
		t.Fatal(err)
	}
	snap, _ = a.Snapshot(ctx)
	if snap.Tags["node_1"].ConnKnown {
		t.Fatal(snap.Tags)
	}
	// TTL 边界：30 秒仍有效，超过后整体消失。
	*now = now.Add(30 * time.Second)
	if snap, _ = a.Snapshot(ctx); len(snap.Servers) != 2 {
		t.Fatal("TTL 边界提前失效", snap.Servers)
	}
	*now = now.Add(time.Second)
	snap, _ = a.Snapshot(ctx)
	if len(snap.Servers) != 0 || len(snap.Accounts) != 0 || len(snap.Tags) != 0 || snap.TotalConnections != 0 {
		t.Fatal(snap)
	}
}

// 账户 A 限 2 个 IP：换网（新 IP 短暂重叠）不超限，三个 IP 持续并行才超限，极端超限立即判定。
func TestIPOverlapFiltersHandoverKeepsSharing(t *testing.T) {
	a, _, now := setup(t)
	ctx := context.Background()
	step := func(ips, active []string) Stats {
		*now = now.Add(10 * time.Second)
		if err := a.Report(ctx, "n", []AccountReport{{"A", 1, ips, active}}); err != nil {
			t.Fatal(err)
		}
		stats, err := a.Evaluate(ctx)
		if err != nil {
			t.Fatal(err)
		}
		return stats["A"]
	}
	// 换网：旧 IP 3 无流量残留，新 IP 2 只活跃 1 分钟后旧连接消失
	for i := 0; i < 6; i++ {
		if st := step([]string{"1", "2", "3"}, []string{"1", "2"}); st.Blocked != nil {
			t.Fatalf("换网被判超限: %+v", st)
		}
	}
	// 真共享：三个 IP 都有流量，满 5 分钟才计入
	var st Stats
	for i := 0; i < 30; i++ {
		if st = step([]string{"1", "2", "3"}, []string{"1", "2", "3"}); st.Blocked != nil {
			t.Fatalf("未满重叠窗口就超限: %+v", st)
		}
	}
	for i := 0; i < 3; i++ { // 满窗口后再连续 3 次评估
		st = step([]string{"1", "2", "3"}, []string{"1", "2", "3"})
	}
	if st.SustainedIPs != 3 || st.Blocked == nil || st.Blocked.Reason != "ips" || st.Blocked.Total != 3 {
		t.Fatalf("持续并行应超限: %+v", st)
	}
	// 极端超限（≥2 倍）不等窗口
	a2, _, _ := setup(t)
	a2.Report(ctx, "n", []AccountReport{{"A", 1, []string{"1", "2", "3", "4"}, nil}})
	for i := 0; i < 3; i++ {
		st = Stats{}
		s, _ := a2.Evaluate(ctx)
		st = s["A"]
	}
	if st.Blocked == nil || st.Blocked.Total != 4 {
		t.Fatalf("极端超限应立即判定: %+v", st)
	}
}
