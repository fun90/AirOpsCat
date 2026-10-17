-- 第一期：节点组合并为“逻辑节点 + 部署位”（docs/node-multi-server-failover-plan.md 第 5 节）
-- 执行前：备份数据库，执行 scripts/node_group_merge_precheck.sql 并人工确认结果。
-- 在部署第一期版本之前、维护窗口内手工执行一次；不可重复执行。
-- 执行后：启动新版本，对涉及分组节点的服务器执行强制部署（原非主节点的入站 tag 由 node_<旧id> 变为 node_<主id>）。

-- 1. 新表与新列
CREATE TABLE node_server (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    node_id        BIGINT       NOT NULL,
    server_id      BIGINT       NOT NULL,
    access_host_id BIGINT       NULL,
    exposed        INT          NULL,
    priority       INT          NULL,
    enabled        INT          NULL,
    name           VARCHAR(255) NULL,
    deployed       INT          NULL,
    legacy_node_id BIGINT       NULL COMMENT '迁移用，记录原 node.id，第一期稳定后删除',
    create_time    DATETIME(6)  NULL,
    update_time    DATETIME(6)  NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_node_server_node_server (node_id, server_id),
    KEY idx_node_server_server_id (server_id),
    KEY idx_node_server_access_host_id (access_host_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE node
    ADD COLUMN dns_record_id BIGINT NULL COMMENT '订阅 DNS 记录，绑定后订阅只输出一条',
    ADD INDEX idx_node_dns_record_id (dns_record_id);

ALTER TABLE node_deployment ADD COLUMN node_server_id BIGINT NULL;
ALTER TABLE node_deployment_history ADD COLUMN node_server_id BIGINT NULL;

-- node_deployment 改为按部署位唯一，先删除 node_id 上 Hibernate 生成的唯一索引（名称不固定）
SET @node_deployment_unique_index := (
    SELECT s.index_name
    FROM information_schema.statistics s
    WHERE s.table_schema = DATABASE()
      AND s.table_name = 'node_deployment'
      AND s.column_name = 'node_id'
      AND s.non_unique = 0
      AND s.index_name <> 'PRIMARY'
    LIMIT 1
);
SET @sql := IF(@node_deployment_unique_index IS NULL, 'SELECT 1',
               CONCAT('ALTER TABLE node_deployment DROP INDEX `', @node_deployment_unique_index, '`'));
PREPARE stmt FROM @sql;
EXECUTE stmt;
DEALLOCATE PREPARE stmt;
CREATE INDEX idx_node_deployment_node_id ON node_deployment (node_id);

-- 2. 节点 id 映射：同名（去空格）节点组取最小 id 为主节点；grouped 表示组内成员数 > 1
CREATE TABLE node_id_map (
    old_id  BIGINT  NOT NULL,
    new_id  BIGINT  NOT NULL,
    grouped TINYINT NOT NULL,
    PRIMARY KEY (old_id),
    KEY idx_node_id_map_new_id (new_id)
);

INSERT INTO node_id_map (old_id, new_id, grouped)
SELECT n.id,
       COALESCE(g.primary_id, n.id),
       CASE WHEN g.member_count > 1 THEN 1 ELSE 0 END
FROM node n
LEFT JOIN (SELECT TRIM(node_group) AS node_group, MIN(id) AS primary_id, COUNT(*) AS member_count
           FROM node
           WHERE TRIM(node_group) <> ''
           GROUP BY TRIM(node_group)) g ON g.node_group = TRIM(n.node_group);

-- 3. 每个原节点生成 1 个部署位
--    未分组：始终启用，禁用状态保留在逻辑节点上
--    分组：成员禁用转为部署位停用；全部成员禁用时部署位保持启用、逻辑节点禁用
--    分组部署位写入原节点名作为显示名，保证订阅名称不变
INSERT INTO node_server (node_id, server_id, access_host_id, exposed, priority, enabled, name, deployed,
                         legacy_node_id, create_time, update_time)
SELECT m.new_id,
       n.server_id,
       n.access_host_id,
       1,
       ROW_NUMBER() OVER (PARTITION BY m.new_id ORDER BY n.no, n.id) - 1,
       CASE
           WHEN m.grouped = 0 THEN 1
           WHEN MIN(COALESCE(n.disabled, 0)) OVER (PARTITION BY m.new_id) = 1 THEN 1
           ELSE 1 - COALESCE(n.disabled, 0)
       END,
       CASE WHEN m.grouped = 1 THEN n.name END,
       CASE WHEN m.grouped = 1 THEN 0 ELSE COALESCE(n.deployed, 0) END,
       n.id,
       NOW(6),
       NOW(6)
FROM node n
JOIN node_id_map m ON m.old_id = n.id
WHERE n.server_id IS NOT NULL;

UPDATE node p
JOIN (SELECT m.new_id, MIN(COALESCE(n.disabled, 0)) AS disabled
      FROM node n
      JOIN node_id_map m ON m.old_id = n.id
      WHERE m.grouped = 1
      GROUP BY m.new_id) g ON g.new_id = p.id
SET p.disabled = g.disabled;

-- 4. 引用迁移到主节点
-- 4.1 node_tag 取组内并集
CREATE TABLE node_tag_merge AS
SELECT DISTINCT m.new_id AS node_id, nt.tag_id
FROM node_tag nt
JOIN node_id_map m ON m.old_id = nt.node_id
WHERE m.grouped = 1;

DELETE nt FROM node_tag nt
JOIN node_id_map m ON m.old_id = nt.node_id
WHERE m.grouped = 1;

INSERT INTO node_tag (node_id, tag_id)
SELECT node_id, tag_id FROM node_tag_merge;

DROP TABLE node_tag_merge;

-- 4.2 账号订阅域名绑定：同一账号同组多条时保留主节点的绑定，没有则保留 id 最小的一条
CREATE TABLE binding_merge_keep AS
SELECT b.account_id,
       m.new_id,
       COALESCE(MIN(CASE WHEN b.node_id = m.new_id THEN b.id END), MIN(b.id)) AS keep_id
FROM account_node_subscription_domain_binding b
JOIN node_id_map m ON m.old_id = b.node_id
WHERE m.grouped = 1
GROUP BY b.account_id, m.new_id;

DELETE b FROM account_node_subscription_domain_binding b
JOIN node_id_map m ON m.old_id = b.node_id
JOIN binding_merge_keep k ON k.account_id = b.account_id AND k.new_id = m.new_id
WHERE b.id <> k.keep_id;

UPDATE account_node_subscription_domain_binding b
JOIN node_id_map m ON m.old_id = b.node_id
SET b.node_id = m.new_id
WHERE m.old_id <> m.new_id;

DROP TABLE binding_merge_keep;

-- 4.3 出站与路由规则
UPDATE node n
JOIN node_id_map m ON m.old_id = n.out_id
SET n.out_id = m.new_id
WHERE m.old_id <> m.new_id;

UPDATE route_rule r
JOIN node_id_map m ON m.old_id = r.outbound_node_id
SET r.outbound_node_id = m.new_id
WHERE m.old_id <> m.new_id;

-- 4.4 在线统计
UPDATE account_online_ip a
JOIN node_id_map m ON m.old_id = a.node_id
SET a.node_id = m.new_id
WHERE m.old_id <> m.new_id;

-- 同日合并：latest、peak 求和，unique 取最大（近似值），保留 id 最小的一行
CREATE TABLE node_daily_stats_merge AS
SELECT m.new_id,
       s.stat_date,
       MIN(s.id)                          AS keep_id,
       SUM(s.latest_online_account_count) AS latest_count,
       SUM(s.peak_online_account_count)   AS peak_count,
       MAX(s.unique_online_account_count) AS unique_count,
       MAX(s.sample_count)                AS sample_count,
       MAX(s.last_sample_time)            AS last_sample_time
FROM node_online_account_daily_stats s
JOIN node_id_map m ON m.old_id = s.node_id
WHERE m.grouped = 1
GROUP BY m.new_id, s.stat_date;

DELETE s FROM node_online_account_daily_stats s
JOIN node_id_map m ON m.old_id = s.node_id
JOIN node_daily_stats_merge t ON t.new_id = m.new_id AND t.stat_date = s.stat_date
WHERE s.id <> t.keep_id;

UPDATE node_online_account_daily_stats s
JOIN node_daily_stats_merge t ON t.keep_id = s.id
SET s.node_id = t.new_id,
    s.latest_online_account_count = t.latest_count,
    s.peak_online_account_count = t.peak_count,
    s.unique_online_account_count = t.unique_count,
    s.sample_count = t.sample_count,
    s.last_sample_time = t.last_sample_time;

DROP TABLE node_daily_stats_merge;

-- 5. 部署记录按 legacy_node_id 回填部署位，再映射到主节点
UPDATE node_deployment d
JOIN node_server ns ON ns.legacy_node_id = d.node_id
SET d.node_server_id = ns.id,
    d.node_id = ns.node_id;

UPDATE node_deployment_history h
JOIN node_server ns ON ns.legacy_node_id = h.node_id
SET h.node_server_id = ns.id,
    h.node_id = ns.node_id;

ALTER TABLE node_deployment ADD UNIQUE KEY uk_node_deployment_node_server_id (node_server_id);

-- 6. 删除非主节点行（部署位已在第 3 步标为待部署）
DELETE n FROM node n
JOIN node_id_map m ON m.old_id = n.id
WHERE m.old_id <> m.new_id;

-- 7. 校验：两个数应相等（部署位总数 = 迁移前有服务器的节点数）
SELECT (SELECT COUNT(*) FROM node_server)                      AS placement_count,
       (SELECT COUNT(*) FROM node_id_map m
        JOIN node_server ns ON ns.legacy_node_id = m.old_id)   AS mapped_node_count,
       (SELECT COUNT(*) FROM node_deployment WHERE node_server_id IS NULL) AS deployment_without_placement;

DROP TABLE node_id_map;
