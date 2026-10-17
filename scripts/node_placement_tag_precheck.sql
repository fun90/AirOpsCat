-- 只读：执行 V2026101705 前确认影响范围；不输出账号标识或订阅令牌。
SELECT COUNT(*) AS placement_count,
       SUM(NOT EXISTS (SELECT 1 FROM node_tag nt WHERE nt.node_id = ns.node_id)) AS placements_without_tags,
       (SELECT COUNT(*) FROM node_server p JOIN node_tag nt ON nt.node_id = p.node_id) AS expected_tag_relations
FROM node_server ns;

-- 异常关联必须先处理，否则新表的唯一约束或后续外键生成可能失败。
SELECT COUNT(*) AS orphan_node_tags
FROM node_tag nt
LEFT JOIN node n ON n.id = nt.node_id
LEFT JOIN tag t ON t.id = nt.tag_id
WHERE n.id IS NULL OR t.id IS NULL;

SELECT node_id, tag_id, COUNT(*) AS duplicates
FROM node_tag
GROUP BY node_id, tag_id
HAVING duplicates > 1;

SELECT n.id AS node_id, COUNT(DISTINCT ns.id) AS placement_count,
       COUNT(DISTINCT nt.tag_id) AS shared_tag_count,
       CASE WHEN n.dns_record_id IS NOT NULL THEN 1 ELSE 0 END AS shared_dns,
       (SELECT COUNT(*) FROM account_node_subscription_domain_binding b WHERE b.node_id = n.id AND b.enabled = 1) AS account_dns_bindings
FROM node n
JOIN node_server ns ON ns.node_id = n.id
LEFT JOIN node_tag nt ON nt.node_id = n.id
GROUP BY n.id, n.dns_record_id
HAVING placement_count > 1 OR shared_dns = 1 OR account_dns_bindings > 0;

-- 一期迁移曾将同组 node_tag 合并为并集；当前库不能反推出原服务器上的差异。
-- 如需恢复原分配，先从一期迁移前备份核对原 node.id/server_id 与标签。
