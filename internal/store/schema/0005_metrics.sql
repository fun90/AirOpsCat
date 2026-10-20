-- 服务器指标：monitor 行记录 CPU/内存/网速，vnstat 行记录当月累计流量；合并替代旧的三张统计表。
CREATE TABLE server_metrics (
    id           INTEGER PRIMARY KEY,
    server_id    INTEGER NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    kind         TEXT    NOT NULL CHECK (kind IN ('monitor', 'vnstat')),
    sample_time  TEXT    NOT NULL,
    cpu_usage    REAL,
    memory_usage REAL,
    memory_used  INTEGER,
    memory_total INTEGER,
    rx_rate      INTEGER,
    tx_rate      INTEGER,
    iface        TEXT,
    period_year  INTEGER,
    period_month INTEGER,
    rx_bytes     INTEGER,
    tx_bytes     INTEGER
);
CREATE INDEX idx_server_metrics_lookup ON server_metrics (server_id, kind, sample_time);
