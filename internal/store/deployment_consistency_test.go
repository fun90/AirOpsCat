package store

import (
	"context"
	"database/sql"
	"io/fs"
	"path/filepath"
	"testing"
	"testing/fstest"
)

func TestDeploymentRevisionAccountColumns(t *testing.T) {
	db, ctx := testDB(t)
	a := onboard(t, db, "revision-columns@example.test", nil)
	read := func() int64 {
		t.Helper()
		n, err := DeploymentRevision(ctx, db)
		if err != nil {
			t.Fatal(err)
		}
		return n
	}
	for _, tc := range []struct {
		column, value string
		changes       bool
	}{
		{"remark", "'新备注'", false}, {"auth_code", "'new-auth-code'", false}, {"expire_handled_to", "'2026-01-01 00:00:00'", false}, {"node_prefix", "'新前缀'", false},
		{"uuid", "'11111111-1111-4111-8111-111111111111'", true}, {"account_no", "'NEWNO'", true}, {"disabled", "1", true}, {"to_date", "'2030-01-01 00:00:00'", true},
		{"download_mbps", "3", true}, {"upload_mbps", "4", true}, {"max_ips", "5", true}, {"bandwidth", "6", true}, {"period_type", "'YEARLY'", true}, {"from_date", "'2025-01-01 00:00:00'", true}, {"traffic_anchor", "'2025-01-01 00:00:00'", true},
	} {
		before := read()
		mustExec(t, db, `UPDATE account SET `+tc.column+`=`+tc.value+` WHERE id=?`, a.AccountID)
		if changed := read() > before; changed != tc.changes {
			t.Errorf("%s: 版本变化=%v，期望 %v", tc.column, changed, tc.changes)
		}
	}
}

func TestDeploymentRevisionUpgradeFromTwentyOne(t *testing.T) {
	ctx := context.Background()
	db, err := sql.Open("sqlite", filepath.Join(t.TempDir(), "old.db"))
	if err != nil {
		t.Fatal(err)
	}
	defer db.Close()
	old := fstest.MapFS{}
	files, err := fs.Glob(schemaFS, "schema/*.sql")
	if err != nil {
		t.Fatal(err)
	}
	for _, file := range files {
		if file >= "schema/0022" {
			continue
		}
		body, err := schemaFS.ReadFile(file)
		if err != nil {
			t.Fatal(err)
		}
		old[file] = &fstest.MapFile{Data: body}
	}
	if err := migrate(ctx, db, old); err != nil {
		t.Fatal(err)
	}
	if err := migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO account(uuid,account_no,auth_code,period_type) VALUES('uuid-old','old-no','old-code','MONTHLY')`)
	before, _ := DeploymentRevision(ctx, db)
	mustExec(t, db, `UPDATE account SET remark='升级后备注' WHERE account_no='old-no'`)
	after, _ := DeploymentRevision(ctx, db)
	if after != before {
		t.Fatalf("升级后备注仍改变部署版本: %d -> %d", before, after)
	}
	mustExec(t, db, `UPDATE account SET uuid='uuid-new' WHERE account_no='old-no'`)
	after, _ = DeploymentRevision(ctx, db)
	if after <= before {
		t.Fatal("升级后 uuid 未保护部署版本")
	}
}
