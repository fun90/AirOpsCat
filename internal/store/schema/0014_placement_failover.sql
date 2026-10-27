-- 手动切走下沉到部署位：可只切某服务器上的部分节点；服务器级切走即标记其全部部署位。
ALTER TABLE node_server ADD COLUMN failover INTEGER NOT NULL DEFAULT 0;
UPDATE node_server SET failover = 1 WHERE server_id IN (SELECT id FROM server WHERE failover = 1);
ALTER TABLE server DROP COLUMN failover;
