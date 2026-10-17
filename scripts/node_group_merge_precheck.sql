-- 节点组合并为“逻辑节点 + 部署位”前的只读检查（docs/node-multi-server-failover-plan.md 5.2）
-- 只包含 SELECT，执行结果交人工确认后，再执行 V2026101701__merge_node_group_into_node_server.sql。
-- 分组键为 TRIM(node_group)，主节点为组内最小 id。

-- 0. 节点数、组数、部署位预计数（每个有服务器的原节点生成 1 个部署位）
SELECT COUNT(*)                                                          AS node_count,
       SUM(server_id IS NOT NULL)                                        AS expected_placement_count,
       COUNT(DISTINCT CASE WHEN TRIM(node_group) <> '' THEN TRIM(node_group) END) AS group_count,
       SUM(server_id IS NULL)                                            AS node_without_server_count
FROM node;

-- 1. 组内 type、protocol、port、rule、level、disabled、remark 不一致的组；合并后以主节点配置为准
--    disabled 例外：任一成员启用则逻辑节点启用，成员禁用转为对应部署位停用
SELECT TRIM(node_group)                AS node_group,
       COUNT(*)                        AS member_count,
       COUNT(DISTINCT type)            AS type_variants,
       COUNT(DISTINCT protocol)        AS protocol_variants,
       COUNT(DISTINCT port)            AS port_variants,
       COUNT(DISTINCT CAST(rule AS CHAR)) AS rule_variants,
       COUNT(DISTINCT level)           AS level_variants,
       COUNT(DISTINCT disabled)        AS disabled_variants,
       COUNT(DISTINCT remark)          AS remark_variants,
       GROUP_CONCAT(id ORDER BY id)    AS node_ids
FROM node
WHERE TRIM(node_group) <> ''
GROUP BY TRIM(node_group)
HAVING COUNT(*) > 1
   AND (type_variants > 1 OR protocol_variants > 1 OR port_variants > 1 OR rule_variants > 1
        OR level_variants > 1 OR disabled_variants > 1 OR remark_variants > 1);

-- 1b. 组内两个节点在同一服务器上（违反部署位唯一键，必须先处理）
SELECT TRIM(node_group) AS node_group, server_id, GROUP_CONCAT(id ORDER BY id) AS node_ids
FROM node
WHERE TRIM(node_group) <> ''
GROUP BY TRIM(node_group), server_id
HAVING COUNT(*) > 1;

-- 1c. 组内名称相同的节点：合并后多条订阅条目会重名，需要给部署位设置显示名
SELECT TRIM(node_group) AS node_group, name, GROUP_CONCAT(CONCAT(id, '#', no) ORDER BY id) AS node_ids
FROM node
WHERE TRIM(node_group) <> ''
GROUP BY TRIM(node_group), name
HAVING COUNT(*) > 1;

-- 2. 组内 node_tag 并集与差集；合并后取并集，会扩大部分账号的可见范围
SELECT g.node_group,
       n.id                                   AS node_id,
       GROUP_CONCAT(DISTINCT nt.tag_id ORDER BY nt.tag_id) AS node_tag_ids,
       g.union_tag_ids
FROM node n
JOIN (SELECT TRIM(n2.node_group) AS node_group,
             GROUP_CONCAT(DISTINCT nt2.tag_id ORDER BY nt2.tag_id) AS union_tag_ids
      FROM node n2
      LEFT JOIN node_tag nt2 ON nt2.node_id = n2.id
      WHERE TRIM(n2.node_group) <> ''
      GROUP BY TRIM(n2.node_group)
      HAVING COUNT(DISTINCT n2.id) > 1) g ON g.node_group = TRIM(n.node_group)
LEFT JOIN node_tag nt ON nt.node_id = n.id
GROUP BY g.node_group, n.id, g.union_tag_ids
HAVING NOT (node_tag_ids <=> g.union_tag_ids)
ORDER BY g.node_group, n.id;

-- 3. 同一账号绑定了同组多个节点的订阅域名记录；合并后违反唯一键 (account_id, node_id)
--    迁移默认保留主节点的绑定，没有则保留 id 最小的一条；需要其他选择时请在执行迁移前手工删除
SELECT b.account_id,
       TRIM(n.node_group)                     AS node_group,
       GROUP_CONCAT(CONCAT(b.id, ':node', b.node_id, ':record', b.domain_dns_record_id) ORDER BY b.id) AS bindings
FROM account_node_subscription_domain_binding b
JOIN node n ON n.id = b.node_id
WHERE TRIM(n.node_group) <> ''
GROUP BY b.account_id, TRIM(n.node_group)
HAVING COUNT(*) > 1;
