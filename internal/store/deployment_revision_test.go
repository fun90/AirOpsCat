package store

import (
	"context"
	"database/sql"
	"io/fs"
	"path/filepath"
	"testing"
	"testing/fstest"
)

func TestDeploymentRevisionIgnoresUnrelatedSettings(t *testing.T) {
	db, ctx := testDB(t)
	read := func() int64 {
		t.Helper()
		v, err := DeploymentRevision(ctx, db)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	before := read()
	mustExec(t, db, `INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.docs.url','https://example.test','open')`)
	if got := read(); got != before {
		t.Fatalf("文档配置改变部署版本: %d -> %d", before, got)
	}
	mustExec(t, db, `INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.ratelimit.enabled','true','account')`)
	relevant := read()
	if relevant <= before {
		t.Fatal("限速开关未改变部署版本")
	}
	mustExec(t, db, `UPDATE system_config SET config_value='true' WHERE config_key='airopscat.ratelimit.enabled'`)
	if got := read(); got != relevant {
		t.Fatal("相同配置值改变部署版本")
	}
	mustExec(t, db, `UPDATE system_config SET config_value='false' WHERE config_key='airopscat.ratelimit.enabled'`)
	if got := read(); got <= relevant {
		t.Fatal("限速配置变化未保护部署")
	}
}

func TestDeploymentRevisionTrafficThreshold(t *testing.T) {
	db, ctx := testDB(t)
	account := onboard(t, db, "revision@example.test", nil)
	mustExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota,upload_bytes,download_bytes) VALUES(?,'2026-01-01 00:00:00','2027-01-01 00:00:00',1,0,0)`, account.AccountID)
	read := func() int64 {
		t.Helper()
		v, err := DeploymentRevision(ctx, db)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	before := read()
	mustExec(t, db, `UPDATE account_traffic_stats SET upload_bytes=100,download_bytes=200`)
	if got := read(); got != before {
		t.Fatalf("普通采集误判为配置变化: %d -> %d", before, got)
	}
	// 两个方向均未达到 1GiB，合并后跨界仍必须保护部署。
	mustExec(t, db, `UPDATE account_traffic_stats SET upload_bytes=?,download_bytes=?`, int64(1<<29), int64(1<<29))
	crossed := read()
	if crossed <= before {
		t.Fatal("超额跨界未改变部署版本")
	}
	mustExec(t, db, `UPDATE account_traffic_stats SET download_bytes=download_bytes+100`)
	if got := read(); got != crossed {
		t.Fatal("持续超额计数变化误判")
	}
	mustExec(t, db, `UPDATE account_traffic_stats SET bandwidth_quota=2`)
	recovered := read()
	if recovered <= crossed {
		t.Fatal("配额调整未保护部署")
	}
	mustExec(t, db, `UPDATE account_traffic_stats SET period_end='2026-12-01 00:00:00'`)
	if got := read(); got <= recovered {
		t.Fatal("周期变化未保护部署")
	}
}

func TestDeploymentRevisionUpgradeFromSix(t *testing.T) {
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
		if file >= "schema/0007" {
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
	before, err := DeploymentRevision(ctx, db)
	if err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.docs.url','https://example.test','open')`)
	after, err := DeploymentRevision(ctx, db)
	if err != nil || after != before {
		t.Fatal("升级后旧触发器仍生效", after, err)
	}
}

func TestDeploymentConfirmationSurvivesOrdinaryTraffic(t *testing.T) {
	db, ctx := testDB(t)
	tag := mustTag(t, db, "revision")
	nid := deployedNode(t, db, "revision", tag)
	account := onboard(t, db, "capture@example.test", func(in *OnboardInput) { in.Account.TagIDs = []int64{tag} })
	mustExec(t, db, `UPDATE node SET inbound='{"type":"vless"}' WHERE id=?`, nid)
	var sid, pid int64
	if err := db.QueryRow(`SELECT server_id,id FROM node_server WHERE node_id=?`, nid).Scan(&sid, &pid); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota) VALUES(?,'2000-01-01 00:00:00','2099-01-01 00:00:00',1)`, account.AccountID)
	for _, tc := range []struct {
		bytes int64
		want  int
	}{{100, Deployed}, {1 << 30, PendingDeploy}} {
		mustExec(t, db, `UPDATE node_server SET deployed=0 WHERE id=?`, pid)
		captured, err := CaptureDeployment(ctx, db, sid, LoadOptions{})
		if err != nil {
			t.Fatal(err)
		}
		mustExec(t, db, `UPDATE account_traffic_stats SET download_bytes=?`, tc.bytes)
		if err := RecordDeploymentSnapshots(ctx, db, sid, "hash", true, "ok", captured.Revision, captured.Snapshots); err != nil {
			t.Fatal(err)
		}
		if got := nodeDeployed(t, db, nid); got != tc.want {
			t.Fatalf("bytes=%d: deployed=%d want=%d", tc.bytes, got, tc.want)
		}
	}
}

func TestDeploymentRevisionTrafficQuotaEdges(t *testing.T) {
	db, ctx := testDB(t)
	account := onboard(t, db, "edges@example.test", nil)
	mustExec(t, db, `UPDATE account SET bandwidth=1 WHERE id=?`, account.AccountID)
	mustExec(t, db, `INSERT INTO account_traffic_stats(account_id,period_start,period_end,bandwidth_quota) VALUES(?,'2000-01-01 00:00:00','2099-01-01 00:00:00',NULL)`, account.AccountID)
	read := func() int64 {
		t.Helper()
		v, err := DeploymentRevision(ctx, db)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	before := read()
	mustExec(t, db, `UPDATE account_traffic_stats SET download_bytes=?`, int64(1<<30))
	if read() <= before {
		t.Fatal("NULL配额未回落账户配额")
	}
	before = read()
	mustExec(t, db, `UPDATE account_traffic_stats SET download_bytes=0`)
	if read() <= before {
		t.Fatal("恢复未保护部署")
	}
	mustExec(t, db, `UPDATE account_traffic_stats SET bandwidth_quota=0`)
	before = read()
	mustExec(t, db, `UPDATE account_traffic_stats SET download_bytes=?`, int64(1<<62))
	if read() != before {
		t.Fatal("无限配额计数变化不应触发")
	}
	// 总计溢出会使 Go 装载失败，必须阻止确认旧输入。
	before = read()
	mustExec(t, db, `UPDATE account_traffic_stats SET upload_bytes=?`, int64(1<<62))
	if read() <= before {
		t.Fatal("非法溢出数据未保护部署")
	}
}

func TestDeploymentRevisionSettingRenameAndDelete(t *testing.T) {
	db, ctx := testDB(t)
	read := func() int64 {
		t.Helper()
		v, err := DeploymentRevision(ctx, db)
		if err != nil {
			t.Fatal(err)
		}
		return v
	}
	mustExec(t, db, `INSERT INTO system_config(config_key,config_value,group_key) VALUES('airopscat.ratelimit.enabled','true','account')`)
	before := read()
	mustExec(t, db, `UPDATE system_config SET config_key='unrelated'`)
	if read() <= before {
		t.Fatal("移出相关键未保护部署")
	}
	before = read()
	mustExec(t, db, `UPDATE system_config SET config_key='airopscat.ratelimit.enabled'`)
	if read() <= before {
		t.Fatal("移入相关键未保护部署")
	}
	before = read()
	mustExec(t, db, `DELETE FROM system_config`)
	if read() <= before {
		t.Fatal("删除相关键未保护部署")
	}
}
