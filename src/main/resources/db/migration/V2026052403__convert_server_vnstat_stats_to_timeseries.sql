-- 将 server_vnstat_stats 从"每月一条 UPSERT"改为时序存储（每次采集插一条快照）
-- 原因：支持每5分钟采集一次并在图表中展示累计流量趋势

ALTER TABLE server_vnstat_stats DROP INDEX uq_server_vnstat_period;

CREATE INDEX idx_server_vnstat_sampled_at
    ON server_vnstat_stats (server_id, sampled_at);

CREATE INDEX idx_server_vnstat_period_latest
    ON server_vnstat_stats (server_id, period_year, period_month, sampled_at);
