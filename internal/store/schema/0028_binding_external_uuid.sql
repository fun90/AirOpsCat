-- 外部服务器无法下发账户 UUID：按账户×节点保存外部分配的 UUID，订阅输出该外部部署位时使用。
ALTER TABLE account_node_subscription_domain_binding ADD COLUMN external_uuid TEXT;
