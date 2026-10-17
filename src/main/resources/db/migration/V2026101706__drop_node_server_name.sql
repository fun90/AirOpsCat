-- 部署位不维护独立显示名，订阅始终使用节点名称。
ALTER TABLE node_server DROP COLUMN name;
