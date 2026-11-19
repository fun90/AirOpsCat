-- 手动切走某部署位时可指定承接它的同节点部署位；为空表示按优先级自动选择。
ALTER TABLE node_server ADD COLUMN failover_target INTEGER;
-- 没有域名（无访问域名记录、未被订阅定制绑定）的部署位不能处于切走状态。
UPDATE node_server SET failover = 0
WHERE failover = 1
  AND NOT (coalesce(access_host_id IN (SELECT id FROM domain_dns_record), 0)
           OR EXISTS (SELECT 1 FROM account_node_subscription_placement bp WHERE bp.node_server_id = node_server.id));
