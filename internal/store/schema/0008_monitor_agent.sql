CREATE TABLE monitor_agent (
    server_id INTEGER PRIMARY KEY REFERENCES server(id) ON DELETE CASCADE,
    token_hash TEXT NOT NULL,
    last_seen TEXT,
    sampled_at INTEGER NOT NULL DEFAULT 0,
    boot_id TEXT NOT NULL DEFAULT '',
    uptime_ms INTEGER NOT NULL DEFAULT 0,
    cpu_idle INTEGER NOT NULL DEFAULT 0,
    cpu_total INTEGER NOT NULL DEFAULT 0,
    rx_bytes INTEGER NOT NULL DEFAULT 0,
    tx_bytes INTEGER NOT NULL DEFAULT 0
);
