-- 迁移记录随任一服务器删除而删除；部署位被单独删除时移除对应映射，完成时仍须检查源服务器全部有效部署位。
CREATE TABLE server_migration (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    source_server_id INTEGER NOT NULL REFERENCES server(id) ON DELETE CASCADE,
    target_server_id INTEGER NOT NULL REFERENCES server(id) ON DELETE CASCADE,
    create_time TEXT NOT NULL,
    UNIQUE(source_server_id, target_server_id),
    CHECK(source_server_id <> target_server_id)
);
CREATE TABLE server_migration_placement (
    migration_id INTEGER NOT NULL REFERENCES server_migration(id) ON DELETE CASCADE,
    source_placement_id INTEGER NOT NULL REFERENCES node_server(id) ON DELETE CASCADE,
    target_placement_id INTEGER NOT NULL REFERENCES node_server(id) ON DELETE CASCADE,
    created INTEGER NOT NULL CHECK(created IN (0,1)),
    PRIMARY KEY(migration_id, source_placement_id)
);
CREATE INDEX idx_server_migration_placement_target ON server_migration_placement(target_placement_id);

-- 删除封禁后保留远端待解封动作；部署成功执行后清理，服务器删除时一并清理。
CREATE TABLE server_unblocked_port (
    server_id INTEGER NOT NULL REFERENCES server(id) ON DELETE CASCADE,
    port INTEGER NOT NULL,
    create_time TEXT NOT NULL,
    PRIMARY KEY(server_id, port)
);
