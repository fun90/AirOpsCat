-- 国内探测机对部署位的定时连通检测，每个部署位每台探测机只存最新一次结果；fails 为连续失败轮数。
CREATE TABLE placement_probe (
    placement_id    INTEGER NOT NULL REFERENCES node_server (id) ON DELETE CASCADE,
    probe_server_id INTEGER NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    ok              INTEGER NOT NULL,
    ms              INTEGER NOT NULL DEFAULT 0,
    msg             TEXT    NOT NULL,
    fails           INTEGER NOT NULL DEFAULT 0,
    checked_time    TEXT    NOT NULL,
    last_ok_time    TEXT,
    PRIMARY KEY (placement_id, probe_server_id)
);
CREATE INDEX idx_placement_probe_probe ON placement_probe (probe_server_id);
