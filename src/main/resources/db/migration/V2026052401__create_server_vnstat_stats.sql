CREATE TABLE server_vnstat_stats (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    server_id      BIGINT       NOT NULL,
    iface          VARCHAR(32)  NOT NULL DEFAULT 'eth0',
    period_year    SMALLINT     NOT NULL,
    period_month   TINYINT      NOT NULL,
    rx_bytes       BIGINT       NOT NULL DEFAULT 0,
    tx_bytes       BIGINT       NOT NULL DEFAULT 0,
    sampled_at     DATETIME     NOT NULL,
    create_time    DATETIME     NULL,
    update_time    DATETIME     NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uq_server_vnstat_period (server_id, period_year, period_month)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
