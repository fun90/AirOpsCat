-- 第一阶段表结构。依据 docs/rewrite/spec-data.md，按 docs/rewrite/scope.md 决策删减。
-- 约定：时间为上海本地时间 TEXT（YYYY-MM-DD HH:MM:SS），金额为整数「分」，布尔为 0/1。

CREATE TABLE user (
    id              INTEGER PRIMARY KEY,
    email           TEXT    NOT NULL COLLATE NOCASE UNIQUE,
    nick_name       TEXT,
    remark_name     TEXT,
    password        TEXT    NOT NULL,
    remark          TEXT,
    role            TEXT    NOT NULL DEFAULT 'VIP' CHECK (role IN ('ADMIN', 'PARTNER', 'VIP')),
    disabled        INTEGER NOT NULL DEFAULT 0,
    failed_attempts INTEGER NOT NULL DEFAULT 0,
    lock_time       TEXT,
    create_time     TEXT,
    update_time     TEXT
);

CREATE TABLE account (
    id              INTEGER PRIMARY KEY,
    node_multiple   INTEGER,
    node_prefix     TEXT,
    from_date       TEXT,
    to_date         TEXT,
    period_type     TEXT    NOT NULL CHECK (period_type IN ('MONTHLY', 'YEARLY')),
    uuid            TEXT    NOT NULL UNIQUE,
    account_no      TEXT    NOT NULL COLLATE NOCASE UNIQUE,
    auth_code       TEXT    NOT NULL UNIQUE,
    max_connections INTEGER,
    max_ips         INTEGER,
    bandwidth       INTEGER,
    download_mbps   INTEGER,
    upload_mbps     INTEGER,
    disabled        INTEGER NOT NULL DEFAULT 0,
    remark          TEXT,
    user_id         INTEGER REFERENCES user (id) ON DELETE RESTRICT,
    create_time     TEXT,
    update_time     TEXT
);
CREATE INDEX idx_account_user_id ON account (user_id);

CREATE TABLE tag (
    id          INTEGER PRIMARY KEY,
    name        TEXT NOT NULL COLLATE NOCASE UNIQUE,
    description TEXT,
    color       TEXT,
    create_time TEXT,
    update_time TEXT
);

CREATE TABLE server (
    id              INTEGER PRIMARY KEY,
    ip              TEXT    NOT NULL,
    ssh_port        INTEGER,
    auth_type       TEXT CHECK (auth_type IN ('PASSWORD', 'KEY')),
    username        TEXT,
    auth            TEXT,
    region          TEXT,
    server_type     INTEGER,
    name            TEXT,
    expire_date     TEXT,
    bandwidth_date  TEXT,
    supplier        TEXT,
    price           INTEGER,
    multiple        REAL,
    bandwidth       INTEGER,
    cpu_cores       INTEGER,
    disabled        INTEGER NOT NULL DEFAULT 0,
    external_server INTEGER NOT NULL DEFAULT 0,
    remark          TEXT,
    create_time     TEXT,
    update_time     TEXT
);
CREATE INDEX idx_server_expire_date ON server (expire_date);
CREATE INDEX idx_server_state_expire ON server (disabled, external_server, expire_date);

CREATE TABLE node (
    id          INTEGER PRIMARY KEY,
    port        INTEGER,
    protocol    TEXT,
    core_type   TEXT NOT NULL DEFAULT 'sing-box',
    type        INTEGER,
    inbound     TEXT CHECK (inbound IS NULL OR json_valid(inbound)),
    out_id      INTEGER REFERENCES node (id) ON DELETE RESTRICT,
    disabled    INTEGER NOT NULL DEFAULT 0,
    name        TEXT,
    remark      TEXT,
    region      TEXT,
    create_time TEXT,
    update_time TEXT
);
CREATE INDEX idx_node_out_id ON node (out_id);
CREATE INDEX idx_node_type_core_type ON node (type, core_type);

CREATE TABLE account_tag (
    account_id INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    tag_id     INTEGER NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (account_id, tag_id)
);
CREATE INDEX idx_account_tag_tag_id ON account_tag (tag_id);

CREATE TABLE node_tag (
    node_id INTEGER NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    tag_id  INTEGER NOT NULL REFERENCES tag (id) ON DELETE CASCADE,
    PRIMARY KEY (node_id, tag_id)
);
CREATE INDEX idx_node_tag_tag_id ON node_tag (tag_id);

CREATE TABLE dns_provider_config (
    id                 INTEGER PRIMARY KEY,
    provider_type      TEXT NOT NULL,
    status             TEXT NOT NULL,
    display_name       TEXT NOT NULL,
    credential_json    TEXT,
    extension_json     TEXT CHECK (extension_json IS NULL OR json_valid(extension_json)),
    last_check_time    TEXT,
    last_check_status  TEXT,
    last_check_message TEXT,
    remark             TEXT,
    create_time        TEXT,
    update_time        TEXT
);

CREATE TABLE domain (
    id                         INTEGER PRIMARY KEY,
    expire_date                TEXT,
    domain                     TEXT,
    price                      INTEGER,
    supplier                   TEXT,
    dns_provider_config_id     INTEGER REFERENCES dns_provider_config (id) ON DELETE SET NULL,
    dns_provider_type          TEXT,
    dns_sync_status            TEXT,
    dns_last_sync_time         TEXT,
    dns_binding_extension_json TEXT CHECK (dns_binding_extension_json IS NULL OR json_valid(dns_binding_extension_json)),
    remark                     TEXT,
    create_time                TEXT,
    update_time                TEXT
);

CREATE TABLE domain_dns_record (
    id                     INTEGER PRIMARY KEY,
    domain_id              INTEGER NOT NULL REFERENCES domain (id) ON DELETE CASCADE,
    server_id              INTEGER REFERENCES server (id) ON DELETE SET NULL,
    dns_provider_config_id INTEGER REFERENCES dns_provider_config (id) ON DELETE SET NULL,
    external_record_id     TEXT,
    name                   TEXT    NOT NULL,
    full_name              TEXT COLLATE NOCASE,
    type                   TEXT    NOT NULL COLLATE NOCASE,
    content                TEXT    NOT NULL,
    ttl                    INTEGER,
    proxied                INTEGER,
    priority               INTEGER,
    status                 TEXT    NOT NULL,
    remark                 TEXT,
    biz_tags_json          TEXT CHECK (biz_tags_json IS NULL OR json_valid(biz_tags_json)),
    extension_json         TEXT CHECK (extension_json IS NULL OR json_valid(extension_json)),
    raw_data               TEXT CHECK (raw_data IS NULL OR json_valid(raw_data)),
    last_sync_time         TEXT,
    schedule_enabled       INTEGER,
    schedule_start         TEXT,
    schedule_end           TEXT,
    schedule_content_in    TEXT,
    schedule_content_out   TEXT,
    failover_home_content  TEXT,
    create_time            TEXT,
    update_time            TEXT
);
CREATE INDEX idx_dns_record_domain_id ON domain_dns_record (domain_id);
CREATE INDEX idx_dns_record_server_id ON domain_dns_record (server_id);
CREATE INDEX idx_dns_record_full_name ON domain_dns_record (full_name);

CREATE TABLE node_server (
    id             INTEGER PRIMARY KEY,
    node_id        INTEGER NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    server_id      INTEGER NOT NULL REFERENCES server (id) ON DELETE RESTRICT,
    access_host_id INTEGER REFERENCES domain_dns_record (id) ON DELETE SET NULL,
    exposed        INTEGER,
    priority       INTEGER,
    deployed       INTEGER NOT NULL DEFAULT 0 CHECK (deployed IN (0, 1, 2)),
    cheap_start    TEXT,
    cheap_end      TEXT,
    create_time    TEXT,
    update_time    TEXT,
    UNIQUE (node_id, server_id)
);
CREATE INDEX idx_node_server_server_id ON node_server (server_id);
CREATE INDEX idx_node_server_access_host_id ON node_server (access_host_id);

CREATE TABLE route_rule (
    id               INTEGER PRIMARY KEY,
    name             TEXT    NOT NULL,
    core_type        TEXT    NOT NULL DEFAULT 'sing-box',
    rule_type        TEXT    NOT NULL,
    rule_value       TEXT    NOT NULL CHECK (json_valid(rule_value)),
    outbound_node_id INTEGER NOT NULL REFERENCES node (id) ON DELETE RESTRICT,
    enabled          INTEGER NOT NULL DEFAULT 1,
    remark           TEXT,
    create_time      TEXT,
    update_time      TEXT
);

CREATE TABLE route_rule_server (
    route_rule_id INTEGER NOT NULL REFERENCES route_rule (id) ON DELETE CASCADE,
    server_id     INTEGER NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    PRIMARY KEY (route_rule_id, server_id)
);

CREATE TABLE account_node_subscription_domain_binding (
    id                   INTEGER PRIMARY KEY,
    account_id           INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    node_id              INTEGER NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    domain_dns_record_id INTEGER REFERENCES domain_dns_record (id) ON DELETE SET NULL,
    enabled              INTEGER NOT NULL DEFAULT 1,
    remark               TEXT,
    create_time          TEXT,
    update_time          TEXT,
    UNIQUE (account_id, node_id)
);
CREATE INDEX idx_ansd_node_id ON account_node_subscription_domain_binding (node_id);
CREATE INDEX idx_ansd_domain_dns_record_id ON account_node_subscription_domain_binding (domain_dns_record_id);

CREATE TABLE account_node_subscription_placement (
    binding_id     INTEGER NOT NULL REFERENCES account_node_subscription_domain_binding (id) ON DELETE CASCADE,
    node_server_id INTEGER NOT NULL REFERENCES node_server (id) ON DELETE CASCADE,
    PRIMARY KEY (binding_id, node_server_id)
);
CREATE INDEX idx_ansp_node_server_id ON account_node_subscription_placement (node_server_id);

CREATE TABLE account_traffic_stats (
    id              INTEGER PRIMARY KEY,
    user_id         INTEGER,
    account_id      INTEGER NOT NULL REFERENCES account (id) ON DELETE CASCADE,
    period_start    TEXT    NOT NULL,
    period_end      TEXT    NOT NULL,
    bandwidth_quota INTEGER,
    upload_bytes    INTEGER NOT NULL DEFAULT 0,
    download_bytes  INTEGER NOT NULL DEFAULT 0,
    create_time     TEXT,
    update_time     TEXT,
    UNIQUE (account_id, period_start)
);
CREATE INDEX idx_account_traffic_user_period ON account_traffic_stats (user_id, period_start, period_end);

-- account_no 不设外键：guard 上报可能先于账户落库，按账户号关联
CREATE TABLE account_online_ip (
    id                 INTEGER PRIMARY KEY,
    account_no         TEXT NOT NULL COLLATE NOCASE,
    client_ip          TEXT NOT NULL,
    connection_id      TEXT,
    node_ip            TEXT NOT NULL,
    node_id            INTEGER,
    node_tag           TEXT,
    last_online_time   TEXT NOT NULL,
    session_start_time TEXT,
    create_time        TEXT,
    update_time        TEXT,
    UNIQUE (account_no, node_ip, connection_id)
);
CREATE INDEX idx_account_online_account_time ON account_online_ip (account_no, last_online_time);
CREATE INDEX idx_account_online_logic_node_time ON account_online_ip (node_id, last_online_time);
CREATE INDEX idx_account_online_last_time ON account_online_ip (last_online_time);

-- 每个部署位只保留最近一次部署结果（决策 6）
CREATE TABLE node_deployment (
    id             INTEGER PRIMARY KEY,
    node_id        INTEGER NOT NULL REFERENCES node (id) ON DELETE CASCADE,
    node_server_id INTEGER NOT NULL UNIQUE REFERENCES node_server (id) ON DELETE CASCADE,
    version        INTEGER NOT NULL,
    snapshot_hash  TEXT    NOT NULL,
    snapshot_json  TEXT CHECK (snapshot_json IS NULL OR json_valid(snapshot_json)),
    server_id      INTEGER,
    core_type      TEXT,
    protocol       TEXT,
    type           INTEGER,
    port           INTEGER,
    disabled       INTEGER,
    deployed_at    TEXT,
    create_time    TEXT,
    update_time    TEXT
);
CREATE INDEX idx_node_deployment_node_id ON node_deployment (node_id);

CREATE TABLE system_config (
    id           INTEGER PRIMARY KEY,
    config_key   TEXT NOT NULL COLLATE NOCASE UNIQUE,
    config_value TEXT,
    group_key    TEXT NOT NULL,
    create_time  TEXT,
    update_time  TEXT
);

CREATE TABLE system_log (
    id            INTEGER PRIMARY KEY,
    category      TEXT NOT NULL,
    level         TEXT NOT NULL CHECK (level IN ('INFO', 'WARN', 'ERROR')),
    resource_id   INTEGER,
    resource_name TEXT,
    message       TEXT NOT NULL,
    detail        TEXT,
    create_time   TEXT NOT NULL
);
CREATE INDEX idx_system_log_category_time ON system_log (category, create_time);
CREATE INDEX idx_system_log_resource ON system_log (category, resource_id);

CREATE TABLE transactions (
    id               INTEGER PRIMARY KEY,
    transaction_date TEXT    NOT NULL,
    amount           INTEGER NOT NULL,
    type             INTEGER NOT NULL CHECK (type IN (0, 1)),
    business_table   TEXT CHECK (business_table IS NULL OR business_table IN ('account', 'domain', 'server')),
    business_id      INTEGER,
    description      TEXT,
    payment_method   TEXT,
    remark           TEXT,
    create_time      TEXT,
    update_time      TEXT
);
CREATE INDEX idx_transactions_date ON transactions (transaction_date);
CREATE INDEX idx_transactions_business ON transactions (business_table, business_id);

CREATE TABLE alert_state (
    id                   INTEGER PRIMARY KEY,
    alert_type           TEXT    NOT NULL,
    resource_type        TEXT    NOT NULL,
    resource_id          INTEGER NOT NULL,
    resource_key         TEXT,
    fingerprint          TEXT    NOT NULL,
    status               TEXT    NOT NULL,
    severity             TEXT,
    first_triggered_time TEXT,
    last_triggered_time  TEXT,
    last_notified_time   TEXT,
    recovered_time       TEXT,
    acknowledged_time    TEXT,
    acknowledged_by      TEXT,
    trigger_count        INTEGER,
    current_value        REAL,
    threshold_value      REAL,
    summary              TEXT,
    create_time          TEXT,
    update_time          TEXT,
    UNIQUE (alert_type, resource_type, resource_id, fingerprint)
);
CREATE INDEX idx_alert_state_type_status ON alert_state (alert_type, status);
CREATE INDEX idx_alert_state_resource ON alert_state (resource_type, resource_id);
