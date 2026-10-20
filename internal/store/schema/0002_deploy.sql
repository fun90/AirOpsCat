-- 首次信任后保存的 SSH 主机公钥指纹（SHA256:xxx），为空表示尚未信任（决策 20 第 9 条）
ALTER TABLE server ADD COLUMN host_key TEXT;

-- 每台服务器只保留最近一次整机下发结果（决策 6）
CREATE TABLE server_deployment (
    server_id     INTEGER PRIMARY KEY REFERENCES server (id) ON DELETE CASCADE,
    config_sha256 TEXT,
    success       INTEGER NOT NULL,
    message       TEXT,
    deployed_at   TEXT
);
