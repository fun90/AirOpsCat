CREATE TABLE guard_ip_overlap_daily (
    day        TEXT    NOT NULL,
    account_no TEXT    NOT NULL COLLATE NOCASE,
    seconds    INTEGER NOT NULL,
    peak_ips   INTEGER NOT NULL,
    limit_ips  INTEGER NOT NULL,
    PRIMARY KEY (day, account_no)
);
