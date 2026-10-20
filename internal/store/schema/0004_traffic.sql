-- 固定账户流量锚点，续期或编辑 from_date/to_date 不再移动周期。
ALTER TABLE account ADD COLUMN traffic_anchor TEXT;
-- 旧周期优先；取首个周期两端中较大的日，尽量恢复月末钳制前的日期。
-- 历史信息不足时无法还原原始 29/30/31 日，保留可证明的边界。
UPDATE account SET traffic_anchor = coalesce(
    (SELECT CASE WHEN CAST(strftime('%d', s.period_end) AS INTEGER) > CAST(strftime('%d', s.period_start) AS INTEGER)
                 THEN s.period_end ELSE s.period_start END
     FROM account_traffic_stats s WHERE s.account_id = account.id
     ORDER BY s.period_start, s.id LIMIT 1),
    from_date, to_date, '2000-01-01 00:00:00');
CREATE TRIGGER account_traffic_anchor_insert AFTER INSERT ON account
WHEN NEW.traffic_anchor IS NULL
BEGIN
    UPDATE account SET traffic_anchor = coalesce(NEW.from_date, NEW.to_date, '2000-01-01 00:00:00') WHERE id = NEW.id;
END;

-- reset=false 的原始累计读数；不做历史清理，防止长期停采后重新建立免计基线。
CREATE TABLE account_traffic_counter (
    server_id INTEGER NOT NULL REFERENCES server(id) ON DELETE CASCADE,
    account_no TEXT NOT NULL COLLATE NOCASE REFERENCES account(account_no) ON DELETE CASCADE,
    direction TEXT NOT NULL CHECK(direction IN ('uplink', 'downlink')),
    value INTEGER NOT NULL CHECK(typeof(value) = 'integer' AND value >= 0),
    sampled_at TEXT NOT NULL,
    PRIMARY KEY(server_id, account_no, direction)
);
CREATE INDEX idx_account_traffic_period_end ON account_traffic_stats(period_end);
