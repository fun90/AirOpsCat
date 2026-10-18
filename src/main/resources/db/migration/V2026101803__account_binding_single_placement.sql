-- 账户订阅定制必须选一个部署位，域名绑定到该部署位的服务器（docs/server-region-domain-binding-plan.md 第 3 期）。
-- 紧接 V2026101802 在停服、备份后手工执行，可重复执行。
-- 存量只选了域名、没选部署位的定制：按“记录已绑定的服务器 > 记录解析 IP 与服务器 IP 一致 > 已部署 > priority 最小”补上一个部署位，
-- （解析 IP 可能是 v2.3.7 自动切换留下的，所以已绑定的服务器优先）
-- 与旧版订阅一致（旧版此类定制输出的就是优先级最靠前的部署位）。执行后运行末尾的核对查询。

CREATE TABLE IF NOT EXISTS migration_bak_1803_account_placement AS SELECT * FROM account_node_subscription_placement;

-- 1. 给只有域名、没有部署位的定制补部署位
INSERT INTO account_node_subscription_placement (binding_id, node_server_id)
SELECT picked.binding_id, picked.node_server_id
FROM (
    SELECT b.id AS binding_id, ns.id AS node_server_id,
           ROW_NUMBER() OVER (
               PARTITION BY b.id
               ORDER BY (ns.server_id = r.server_id) DESC, (s.ip = r.content) DESC, (ns.deployed = 1) DESC, ns.priority ASC, ns.id ASC
           ) AS rn
    FROM account_node_subscription_domain_binding b
    JOIN domain_dns_record r ON r.id = b.domain_dns_record_id
    JOIN node_server ns ON ns.node_id = b.node_id AND ns.deployed <> 2
    JOIN server s ON s.id = ns.server_id
    WHERE b.domain_dns_record_id IS NOT NULL
      AND NOT EXISTS (SELECT 1 FROM account_node_subscription_placement p WHERE p.binding_id = b.id)
) picked
WHERE picked.rn = 1;

-- 2. 域名记录绑定到所选部署位的服务器（只处理 A 记录；已绑定其他服务器的不动，见核对 b）
UPDATE domain_dns_record r
    JOIN account_node_subscription_domain_binding b ON b.domain_dns_record_id = r.id
    JOIN account_node_subscription_placement p ON p.binding_id = b.id
    JOIN node_server ns ON ns.id = p.node_server_id
SET r.server_id = ns.server_id
WHERE r.server_id IS NULL AND UPPER(r.type) = 'A';

-- 3. 系统日志分类 NODE_FAILOVER 改名 SERVER_FAILOVER（system_log 尚未创建时跳过）
SET @sql := IF((SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = DATABASE()
                AND table_name = 'system_log') > 0,
               "UPDATE system_log SET category = 'SERVER_FAILOVER' WHERE category = 'NODE_FAILOVER'", 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 核对（执行后逐条人工确认，均为只读）
-- a. 仍没有部署位的定制（节点没有可用部署位）：订阅中该节点不再按定制输出，需在账户订阅定制页重新选择
-- SELECT b.id, b.account_id, b.node_id, b.domain_dns_record_id FROM account_node_subscription_domain_binding b
-- WHERE NOT EXISTS (SELECT 1 FROM account_node_subscription_placement p WHERE p.binding_id = b.id);
-- b. 域名记录绑定的服务器与所选部署位的服务器不一致
-- SELECT b.id AS binding_id, r.id AS record_id, r.full_name, r.server_id AS record_server, ns.server_id AS placement_server
-- FROM account_node_subscription_domain_binding b
--     JOIN domain_dns_record r ON r.id = b.domain_dns_record_id
--     JOIN account_node_subscription_placement p ON p.binding_id = b.id
--     JOIN node_server ns ON ns.id = p.node_server_id
-- WHERE r.server_id IS NULL OR r.server_id <> ns.server_id;
-- c. 域名记录不是 A 记录（不能绑定服务器，也不参与自动切换）
-- SELECT b.id AS binding_id, r.id AS record_id, r.full_name, r.type FROM account_node_subscription_domain_binding b
--     JOIN domain_dns_record r ON r.id = b.domain_dns_record_id WHERE UPPER(r.type) <> 'A';
-- d. 解析 IP 与所选部署位服务器 IP 不一致的记录（订阅按域名输出，但解析到别处）
-- SELECT b.id AS binding_id, r.full_name, r.content, s.ip AS server_ip
-- FROM account_node_subscription_domain_binding b
--     JOIN domain_dns_record r ON r.id = b.domain_dns_record_id
--     JOIN account_node_subscription_placement p ON p.binding_id = b.id
--     JOIN node_server ns ON ns.id = p.node_server_id JOIN server s ON s.id = ns.server_id
-- WHERE UPPER(r.type) = 'A' AND r.content <> s.ip;
-- e. 被多个引用方共用的 DNS 记录（新建 / 更换域名时一条记录只能一个引用方，存量共用保留；自动切换按同一节点处理）
-- SELECT r.id, r.full_name,
--        (SELECT COUNT(*) FROM node_server ns WHERE ns.access_host_id = r.id) AS placements,
--        (SELECT COUNT(*) FROM account_node_subscription_domain_binding b WHERE b.domain_dns_record_id = r.id) AS bindings
-- FROM domain_dns_record r
-- HAVING placements + bindings > 1;
-- f. 部署位域名的解析 IP 与部署位服务器 IP 不一致（订阅按域名输出，实际连到别的服务器；第 4 期自动切换会跳过这类记录）
-- SELECT ns.id AS node_server_id, ns.node_id, r.full_name, r.content, s.ip AS server_ip
-- FROM node_server ns JOIN domain_dns_record r ON r.id = ns.access_host_id JOIN server s ON s.id = ns.server_id
-- WHERE UPPER(r.type) = 'A' AND r.content <> s.ip;
