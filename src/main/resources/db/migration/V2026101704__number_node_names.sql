-- 旧名称没有末尾编号时补上默认编号；部署位显示名为空表示沿用节点名称。
-- 原列最长 255 个字符，预留两个字符给默认后缀。
ALTER TABLE node MODIFY COLUMN name VARCHAR(257) NULL;
ALTER TABLE node_server MODIFY COLUMN name VARCHAR(257) NULL;

UPDATE node
SET name = CONCAT(TRIM(name), '-1')
WHERE name IS NOT NULL AND TRIM(name) <> '' AND TRIM(name) NOT REGEXP '-[0-9]+$';

UPDATE node_server
SET name = CONCAT(TRIM(name), '-1')
WHERE name IS NOT NULL AND TRIM(name) <> '' AND TRIM(name) NOT REGEXP '-[0-9]+$';
