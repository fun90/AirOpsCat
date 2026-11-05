package store

import (
	"database/sql"
	"errors"
	"strings"
	"testing"
)

func TestUpdateAccountRollsBackTagReplacement(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "原标签")
	node := deployedNode(t, db, "原节点", tag)
	result := onboard(t, db, "rollback@example.com", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
	})
	before, err := AccountByID(ctx, db, result.AccountID)
	if err != nil {
		t.Fatal(err)
	}
	resetDeployed(db, node)
	edit := *before
	edit.Remark = "不应保存"
	// 先成功重建一条关联，再让第二条关联因外键失败。
	edit.TagIDs = []int64{tag, 999999}
	if err := UpdateAccount(ctx, db, before.ID, &edit); !errors.Is(err, ErrInvalid) {
		t.Fatalf("无效标签应失败: %v", err)
	}
	after, err := AccountByID(ctx, db, before.ID)
	if err != nil || after == nil {
		t.Fatal(after, err)
	}
	if after.Remark != before.Remark || !sameIDs(after.TagIDs, before.TagIDs) {
		t.Fatalf("更新失败应恢复字段和标签: %+v", after)
	}
	if nodeDeployed(t, db, node) != Deployed {
		t.Fatal("更新失败应恢复旧标签节点的部署状态")
	}
}

func TestDeleteAccountRollsBackCleanup(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "关联标签")
	node := deployedNode(t, db, "关联节点", tag)
	result := onboard(t, db, "delete-rollback@example.com", func(in *OnboardInput) {
		in.Account.TagIDs = []int64{tag}
	})
	a, err := AccountByID(ctx, db, result.AccountID)
	if err != nil || a == nil {
		t.Fatal(a, err)
	}
	exec := func(query string, args ...any) sql.Result {
		t.Helper()
		res, err := db.ExecContext(ctx, query, args...)
		if err != nil {
			t.Fatal(err)
		}
		return res
	}
	exec(`INSERT INTO alert_state (alert_type, resource_type, resource_id, fingerprint, status) VALUES ('test','account',?,'test','ACTIVE')`, a.ID)
	exec(`INSERT INTO account_online_ip (account_no, client_ip, node_ip, last_online_time) VALUES (?, '192.0.2.1', '192.0.2.2', ?)`, a.AccountNo, FormatTime(nowFunc()))
	exec(`INSERT INTO account_node_subscription_domain_binding (account_id, node_id) VALUES (?, ?)`, a.ID, node)
	// 仅在测试库中注入删除失败，覆盖先清告警和在线记录、再删账户的事务回滚。
	exec(`CREATE TRIGGER reject_account_delete AFTER DELETE ON account BEGIN SELECT RAISE(ABORT, 'test_delete_failure'); END`)
	resetDeployed(db, node)
	if err := DeleteAccount(ctx, db, a.ID); err == nil || !strings.Contains(err.Error(), "test_delete_failure") {
		t.Fatalf("应触发测试注入的删除失败: %v", err)
	}
	for _, table := range []string{"account", "account_tag", "account_node_subscription_domain_binding", "alert_state", "account_online_ip"} {
		var count int
		if err := db.QueryRowContext(ctx, `SELECT count(*) FROM `+table).Scan(&count); err != nil || count != 1 {
			t.Errorf("删除失败后 %s 应保留一行: count=%d err=%v", table, count, err)
		}
	}
	if nodeDeployed(t, db, node) != Deployed {
		t.Fatal("删除失败应恢复关联节点的部署状态")
	}
}
