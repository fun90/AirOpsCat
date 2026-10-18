-- 节点地区、部署位域名改指向 DNS 记录（docs/server-region-domain-binding-plan.md 第 2 期）。
-- 在停服、备份后、部署新版本之前手工执行，紧接 V2026101801；不可重复执行（会重映射 access_host_id）。
-- 执行前：node_server.access_host_id 是 server_host.id，执行后是 domain_dns_record.id，两者含义不同，
-- 旧版本与新版本不能共用同一份数据，所以必须先停服再执行，执行完立即部署新版本。
-- 本脚本不删除任何旧列（node.dns_record_id、failover_enabled、active_node_server_id，node_server.enabled），
-- 新版本稳定后另行清理。执行后依次运行末尾的核对查询。

-- 0. 加列
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                AND table_name = 'node' AND column_name = 'region') = 0,
               'ALTER TABLE node ADD COLUMN region VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 旧版本由 Hibernate 为 access_host_id 生成了指向 server_host 的外键（名称随机），先删除，否则写入 DNS 记录 id 会失败；
-- 新版本启动时会按实体映射重新生成指向 domain_dns_record 的外键。
SET @sql := (SELECT CONCAT('ALTER TABLE node_server DROP FOREIGN KEY `', constraint_name, '`')
             FROM information_schema.key_column_usage
             WHERE table_schema = DATABASE() AND table_name = 'node_server'
               AND column_name = 'access_host_id' AND referenced_table_name = 'server_host'
             LIMIT 1);
SET @sql := COALESCE(@sql, 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 1. 备份：回滚与核对用，确认无误后手工删除
CREATE TABLE migration_bak_1802_node_server AS SELECT * FROM node_server;
CREATE TABLE migration_bak_1802_node AS SELECT id, dns_record_id, failover_enabled, active_node_server_id FROM node;
CREATE TABLE migration_bak_1802_account_placement AS SELECT * FROM account_node_subscription_placement;

-- 2. 节点地区：取生效部署位（priority 最小、未待删除）所在服务器的地区。
-- 代理节点多是中转，名称里的国家是落地国，不能按名称推断。
UPDATE node n
    JOIN (
        SELECT node_id, server_id FROM (
            SELECT ns.node_id, ns.server_id,
                   ROW_NUMBER() OVER (PARTITION BY ns.node_id
                                      ORDER BY COALESCE(ns.priority, 2147483647), ns.id) AS rn
            FROM node_server ns
            WHERE COALESCE(ns.deployed, 0) <> 2
        ) ranked WHERE rn = 1
    ) effective ON effective.node_id = n.id
    JOIN server s ON s.id = effective.server_id
SET n.region = s.region
WHERE n.region IS NULL AND s.region IS NOT NULL;

-- 3. access_host_id：server_host.id -> domain_dns_record.id。
-- 按 host = full_name 且记录已绑定到该部署位的服务器（V2026101801 回填）匹配；同一部署位匹配到多条记录时无法判断，不映射。
-- 一条记录只能被一个部署位引用：原先同一服务器上多个部署位共用一个 host 的，记录留给 id 最小的部署位，
-- 其余部署位改用服务器 IP，需要在节点页为它们各自选择或新建域名（见末尾核对查询 c）。
CREATE TABLE migration_tmp_1802_host_match AS
SELECT ns.id AS node_server_id, r.id AS record_id
FROM node_server ns
    JOIN server_host h ON h.id = ns.access_host_id
    JOIN domain_dns_record r ON LOWER(r.full_name) = LOWER(h.host)
        AND r.server_id = ns.server_id AND r.type = 'A' AND r.status <> 'PENDING_DELETE'
WHERE ns.access_host_id IS NOT NULL;

CREATE TABLE migration_tmp_1802_host_map AS
SELECT MIN(m.node_server_id) AS node_server_id, m.record_id
FROM migration_tmp_1802_host_match m
WHERE m.node_server_id IN (
    SELECT node_server_id FROM migration_tmp_1802_host_match GROUP BY node_server_id HAVING COUNT(*) = 1)
GROUP BY m.record_id;

UPDATE node_server ns
    LEFT JOIN migration_tmp_1802_host_map m ON m.node_server_id = ns.id
SET ns.access_host_id = m.record_id
WHERE ns.access_host_id IS NOT NULL;

-- 4. 节点原绑定的订阅 DNS 记录（node.dns_record_id）：订阅实际输出的域名，优先于第 3 步的映射。
-- 只处理 A 记录且未开时段计划；挂到解析内容等于其服务器 IP 的部署位，没有则挂到生效部署位。
-- 其余部署位改用服务器 IP（订阅条目由 1 条变为每个暴露部署位 1 条）。
CREATE TABLE migration_tmp_1802_node_dns AS
SELECT n.id AS node_id, r.id AS record_id,
       COALESCE(
           (SELECT ns.id FROM node_server ns JOIN server s ON s.id = ns.server_id
            WHERE ns.node_id = n.id AND COALESCE(ns.deployed, 0) <> 2 AND s.ip = r.content
            ORDER BY COALESCE(ns.priority, 2147483647), ns.id LIMIT 1),
           (SELECT ns.id FROM node_server ns
            WHERE ns.node_id = n.id AND COALESCE(ns.deployed, 0) <> 2
            ORDER BY COALESCE(ns.priority, 2147483647), ns.id LIMIT 1)
       ) AS node_server_id
FROM node n
    JOIN domain_dns_record r ON r.id = n.dns_record_id
WHERE r.type = 'A' AND r.status <> 'PENDING_DELETE' AND COALESCE(r.schedule_enabled, 0) = 0;

-- 记录被别的部署位占用时让出，保证一条记录只被一个部署位引用
UPDATE node_server ns
    JOIN migration_tmp_1802_node_dns t ON t.record_id = ns.access_host_id AND t.node_server_id <> ns.id
SET ns.access_host_id = NULL;

UPDATE node_server ns
    JOIN migration_tmp_1802_node_dns t ON t.node_server_id = ns.id
SET ns.access_host_id = t.record_id;

-- 5. 记录绑定到部署位所在服务器
UPDATE domain_dns_record r
    JOIN node_server ns ON ns.access_host_id = r.id
SET r.server_id = ns.server_id;

-- 6. 原停用（enabled=0）的部署位：保留，只部署、不进订阅（exposed=0）
UPDATE node_server SET exposed = 0 WHERE enabled = 0;

-- 7. 账户订阅定制只能选一个部署位：保留 priority 最小的，其余已备份在 migration_bak_1802_account_placement
DELETE p FROM account_node_subscription_placement p
    JOIN (
        SELECT binding_id, node_server_id FROM (
            SELECT p2.binding_id, p2.node_server_id,
                   ROW_NUMBER() OVER (PARTITION BY p2.binding_id
                                      ORDER BY COALESCE(ns.priority, 2147483647), ns.id) AS rn
            FROM account_node_subscription_placement p2
                JOIN node_server ns ON ns.id = p2.node_server_id
        ) ranked WHERE rn > 1
    ) extra ON extra.binding_id = p.binding_id AND extra.node_server_id = p.node_server_id;

DROP TABLE migration_tmp_1802_host_match;
DROP TABLE migration_tmp_1802_host_map;
DROP TABLE migration_tmp_1802_node_dns;

-- 核对（执行后逐条人工确认，均为只读）
-- a. 没有地区的节点：部署位同步对它们不生效，需在节点编辑页补地区
-- SELECT id, name, type FROM node WHERE region IS NULL;
-- b. 没有地区的服务器
-- SELECT id, name FROM server WHERE region IS NULL;
-- c. 原有域名但没能映射到 DNS 记录的部署位（已改用服务器 IP）
-- SELECT b.id AS node_server_id, b.node_id, b.server_id, b.access_host_id AS old_host_id
-- FROM migration_bak_1802_node_server b JOIN node_server ns ON ns.id = b.id
-- WHERE b.access_host_id IS NOT NULL AND ns.access_host_id IS NULL;
-- d. node.dns_record_id 没能挂到部署位的节点（CNAME、时段计划或记录已删除）
-- SELECT b.id AS node_id, b.dns_record_id FROM migration_bak_1802_node b
-- WHERE b.dns_record_id IS NOT NULL
--   AND NOT EXISTS (SELECT 1 FROM node_server ns WHERE ns.node_id = b.id AND ns.access_host_id = b.dns_record_id);
-- e. 同时被部署位和账户订阅定制引用的 DNS 记录（新版本一条记录只能有一个引用方）
-- SELECT DISTINCT r.id, r.full_name FROM domain_dns_record r
--     JOIN node_server ns ON ns.access_host_id = r.id
--     JOIN account_node_subscription_domain_binding b ON b.domain_dns_record_id = r.id;
-- f. 被丢弃的账户订阅定制部署位选择
-- SELECT b.* FROM migration_bak_1802_account_placement b
-- WHERE NOT EXISTS (SELECT 1 FROM account_node_subscription_placement p
--                   WHERE p.binding_id = b.binding_id AND p.node_server_id = b.node_server_id);
-- g. 原 enabled=0 的部署位
-- SELECT id, node_id, server_id FROM migration_bak_1802_node_server WHERE enabled = 0;
