-- 流量校准：vnstat 与服务商统计的差值（字节），只对 traffic_offset_period（周期起始 YYYY-MM）生效，下个周期自动失效。
ALTER TABLE server ADD COLUMN traffic_offset INTEGER NOT NULL DEFAULT 0;
ALTER TABLE server ADD COLUMN traffic_offset_period TEXT;
