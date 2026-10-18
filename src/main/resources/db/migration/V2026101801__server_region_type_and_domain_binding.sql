-- 服务器新增地区、类型，DNS 记录新增绑定服务器（docs/server-region-domain-binding-plan.md 第 1 期）。
-- 在停服、备份后、部署新版本之前手工执行，可重复执行；与 V2026101802 一起发布。

-- 1. 加列（已存在则跳过，与 schema-management=update 生成的列一致）
SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                AND table_name = 'server' AND column_name = 'region') = 0,
               'ALTER TABLE server ADD COLUMN region VARCHAR(64) NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                AND table_name = 'server' AND column_name = 'server_type') = 0,
               'ALTER TABLE server ADD COLUMN server_type INT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

SET @sql := IF((SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE()
                AND table_name = 'domain_dns_record' AND column_name = 'server_id') = 0,
               'ALTER TABLE domain_dns_record ADD COLUMN server_id BIGINT NULL', 'SELECT 1');
PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;

-- 地区：按名称前缀回填；匹配不上的（如“测试”）留空，由人工在服务器编辑页补填。
UPDATE server SET region = CASE
    WHEN name LIKE '美国%' THEN '美国'
    WHEN name LIKE '日本%' THEN '日本'
    WHEN name LIKE '新加坡%' THEN '新加坡'
    WHEN name LIKE '德国%' THEN '德国'
    WHEN name LIKE '英国%' THEN '英国'
    WHEN name LIKE '越南%' THEN '越南'
    WHEN name LIKE '香港%' THEN '香港'
    WHEN name LIKE '马来西亚%' THEN '马来西亚'
    WHEN name LIKE '韩国%' THEN '韩国'
    WHEN name LIKE '土耳其%' THEN '土耳其'
    WHEN name LIKE '墨西哥%' THEN '墨西哥'
    END
WHERE region IS NULL;

-- 类型（0 代理，1 落地）：名称含 ISP 的为落地；其余若只部署过落地节点（type=1）也为落地；其余为代理。
UPDATE server s SET s.server_type = CASE
    WHEN s.name LIKE '%ISP%' THEN 1
    WHEN EXISTS (SELECT 1 FROM node_server ns JOIN node n ON n.id = ns.node_id WHERE ns.server_id = s.id AND n.type = 1)
         AND NOT EXISTS (SELECT 1 FROM node_server ns JOIN node n ON n.id = ns.node_id WHERE ns.server_id = s.id AND n.type = 0) THEN 1
    ELSE 0 END
WHERE s.server_type IS NULL;

-- 旧接入 host 按完整域名匹配 DNS 记录，回填绑定；同一域名被多台服务器使用时无法判断归属，跳过。
UPDATE domain_dns_record r
    JOIN server_host h ON LOWER(h.host) = LOWER(r.full_name)
SET r.server_id = h.server_id
WHERE r.server_id IS NULL
  AND r.type IN ('A', 'AAAA', 'CNAME')
  AND (SELECT COUNT(DISTINCT h2.server_id) FROM server_host h2 WHERE LOWER(h2.host) = LOWER(r.full_name)) = 1;

-- 核对：未能对应到 DNS 记录的旧接入 host，需在节点改造后用绑定域名替换。
-- SELECT h.server_id, h.host FROM server_host h
-- WHERE NOT EXISTS (SELECT 1 FROM domain_dns_record r WHERE LOWER(r.full_name) = LOWER(h.host) AND r.server_id = h.server_id);
