-- 账户订阅定制：DNS 记录改为可选，并记录账户订阅中展示的部署位。
ALTER TABLE account_node_subscription_domain_binding MODIFY domain_dns_record_id BIGINT NULL;

CREATE TABLE IF NOT EXISTS account_node_subscription_placement (
    binding_id BIGINT NOT NULL,
    node_server_id BIGINT NOT NULL,
    PRIMARY KEY (binding_id, node_server_id),
    KEY idx_ansp_node_server_id (node_server_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
