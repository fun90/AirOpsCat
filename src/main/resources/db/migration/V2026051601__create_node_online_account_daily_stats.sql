CREATE TABLE IF NOT EXISTS node_online_account_daily_stats (
    id BIGINT NOT NULL AUTO_INCREMENT,
    node_id BIGINT NOT NULL,
    stat_date DATE NOT NULL,
    latest_online_account_count INT NOT NULL DEFAULT 0,
    peak_online_account_count INT NOT NULL DEFAULT 0,
    unique_online_account_count INT NOT NULL DEFAULT 0,
    sample_count INT NOT NULL DEFAULT 0,
    last_sample_time DATETIME NOT NULL,
    seen_account_nos_json JSON NULL,
    create_time DATETIME NULL,
    update_time DATETIME NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_node_online_daily_node_date (node_id, stat_date),
    KEY idx_node_online_daily_node_date (node_id, stat_date),
    KEY idx_node_online_daily_stat_date (stat_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
