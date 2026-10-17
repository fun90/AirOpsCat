-- 在部署使用 node_server_tag 的版本前，于维护窗口备份数据库后手工执行一次。
-- 此时旧版本仍按 node_tag 授权；迁移后立即部署新版本，并重新部署相关服务器。
-- 上线后不要重跑本脚本；node_tag 不再同步，重跑会恢复已删除的旧授权。
CREATE TABLE IF NOT EXISTS node_server_tag (
    node_server_id BIGINT NOT NULL,
    tag_id BIGINT NOT NULL,
    PRIMARY KEY (node_server_id, tag_id),
    KEY idx_node_server_tag_tag_id (tag_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT IGNORE INTO node_server_tag (node_server_id, tag_id)
SELECT ns.id, nt.tag_id
FROM node_server ns
JOIN node_tag nt ON nt.node_id = ns.node_id;

-- 每个部署位应继承原逻辑节点的全部标签；两列计数必须一致。
SELECT (SELECT COUNT(*) FROM node_server_tag) AS migrated_relations,
       (SELECT COUNT(*) FROM node_server ns JOIN node_tag nt ON nt.node_id = ns.node_id) AS expected_relations;
