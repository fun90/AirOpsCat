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
	report := []AccountReport{{"A", 6, []string{"1", "2"}}, {"B", 1, []string{"1", "2"}}, {"unknown", 100, nil}}
	for i := 0; i < 20; i++ {
		if err := a.Report(ctx, "node1", report); err != nil {
			t.Fatal(err)
		}
	}
	if err := a.Report(ctx, "node2", []AccountReport{{"A", 6, []string{"2", "3"}}}); err != nil {
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
	if _, err := a.Evaluate(ctx); err != nil {
		t.Fatal(err)
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
	a.Report(ctx, "one", []AccountReport{{"A", 11, nil}})
	a.Evaluate(ctx)
	a.Evaluate(ctx)
	a.Report(ctx, "one", nil)
	stats, err := a.Evaluate(ctx)
	if err != nil || stats["A"].Blocked != nil || stats["A"].Connections != 0 {
		t.Fatal(stats, err)
	}
	a.Report(ctx, "one", []AccountReport{{"A", -1, []string{"1", "1", "2", ""}}})
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
				if err := a.Report(ctx, "one", []AccountReport{{"A", 11, nil}}); err != nil {
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
