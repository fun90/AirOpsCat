-- 每日超限摘要同时累计连接数超限：kind 为 ips / connections，peak_ips / limit_ips 存对应类型的峰值与上限。
CREATE TABLE guard_ip_overlap_daily_new (
    day        TEXT    NOT NULL,
    account_no TEXT    NOT NULL COLLATE NOCASE,
    kind       TEXT    NOT NULL DEFAULT 'ips',
    seconds    INTEGER NOT NULL,
    peak_ips   INTEGER NOT NULL,
    limit_ips  INTEGER NOT NULL,
    PRIMARY KEY (day, account_no, kind)
);
INSERT INTO guard_ip_overlap_daily_new (day, account_no, seconds, peak_ips, limit_ips)
    SELECT day, account_no, seconds, peak_ips, limit_ips FROM guard_ip_overlap_daily;
DROP TABLE guard_ip_overlap_daily;
ALTER TABLE guard_ip_overlap_daily_new RENAME TO guard_ip_overlap_daily;
