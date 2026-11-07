package store

import (
	"context"
	"path/filepath"
	"testing"
)

func TestMigration23UpgradesOldDatabaseAndCascades(t *testing.T) {
	ctx := context.Background()
	db := openRawFK(t, filepath.Join(t.TempDir(), "old.db"))
	if err := migrate(ctx, db, schemaBefore(t, "schema/0023")); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO server(id,ip) VALUES(1,'192.0.2.1'),(2,'192.0.2.2')`)
	mustExec(t, db, `INSERT INTO node(id,name,type,protocol,port) VALUES(1,'n',0,'vless',8443)`)
	mustExec(t, db, `INSERT INTO node_server(id,node_id,server_id) VALUES(1,1,1),(2,1,2)`)
	if err := migrate(ctx, db, schemaFS); err != nil {
		t.Fatal(err)
	}
	mustExec(t, db, `INSERT INTO server_migration(source_server_id,target_server_id,create_time) VALUES(1,2,'2026-01-01 00:00:00')`)
	mustExec(t, db, `INSERT INTO server_migration_placement(migration_id,source_placement_id,target_placement_id,created) VALUES(1,1,2,1)`)
	mustExec(t, db, `DELETE FROM node_server WHERE id=2`)
	if count(t, db, `SELECT count(*) FROM server_migration_placement`) != 0 {
		t.Fatal("部署位删除应级联映射")
	}
	mustExec(t, db, `DELETE FROM server WHERE id=2`)
	if count(t, db, `SELECT count(*) FROM server_migration`) != 0 {
		t.Fatal("服务器删除应级联迁移记录")
	}
}
