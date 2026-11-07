-- airopscat:foreign-keys-off
-- 账户、服务器、域名主键改为 AUTOINCREMENT：删除最大 ID 后不再复用，避免 transactions.business_id 把旧流水关联到新记录。
-- 按 SQLite 12 步重建法执行（迁移器已在事务外关闭外键）：建新表、复制、删旧表、改名，再重建索引和挂在表上的触发器。
-- 列定义以 0001 及后续 ALTER（0002/0004/0012/0015/0018/0019，0013 加的 failover 已由 0014 删除）为准，顺序不变。
-- 同时分离「到期」与「手动禁用」：account 新增 expire_handled_to，记录到期任务已处理过的 to_date；disabled 只表示手动封禁。

-- 其他表上引用 account/domain 的触发器会让 RENAME 校验失败（旧表已删），先删除，末尾按原文重建。
DROP TRIGGER deploy_revision_account_traffic_stats_update;
DROP TRIGGER dns_auto_provider_update;

CREATE TABLE account_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
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
    update_time     TEXT,
    traffic_anchor  TEXT,
    expire_handled_to TEXT
);
-- 已到期且禁用的存量账户视为旧到期任务停用：恢复 disabled=0 并记为已处理；配置与订阅按 to_date 过滤，不会因此恢复可用。
-- 已到期但未禁用的账户保持未处理，交给到期任务部署移除。
INSERT INTO account_new (id, node_multiple, node_prefix, from_date, to_date, period_type, uuid, account_no, auth_code,
    max_connections, max_ips, bandwidth, download_mbps, upload_mbps, disabled, remark, user_id, create_time, update_time,
    traffic_anchor, expire_handled_to)
SELECT id, node_multiple, node_prefix, from_date, to_date, period_type, uuid, account_no, auth_code,
    max_connections, max_ips, bandwidth, download_mbps, upload_mbps,
    CASE WHEN disabled = 1 AND to_date IS NOT NULL AND to_date <> '' AND to_date <= datetime('now', '+8 hours') THEN 0 ELSE disabled END,
    remark, user_id, create_time, update_time, traffic_anchor,
    CASE WHEN disabled = 1 AND to_date IS NOT NULL AND to_date <> '' AND to_date <= datetime('now', '+8 hours') THEN to_date END
FROM account;
DROP TABLE account;
ALTER TABLE account_new RENAME TO account;
CREATE INDEX idx_account_user_id ON account (user_id);
CREATE TRIGGER deploy_revision_account_insert AFTER INSERT ON account BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
CREATE TRIGGER deploy_revision_account_update AFTER UPDATE ON account BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
CREATE TRIGGER deploy_revision_account_delete AFTER DELETE ON account BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
CREATE TRIGGER account_traffic_anchor_insert AFTER INSERT ON account
WHEN NEW.traffic_anchor IS NULL
BEGIN
    UPDATE account SET traffic_anchor = coalesce(NEW.from_date, NEW.to_date, '2000-01-01 00:00:00') WHERE id = NEW.id;
END;

CREATE TABLE server_new (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
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
    update_time     TEXT,
    host_key        TEXT,
    price_months    INTEGER NOT NULL DEFAULT 12 CHECK (price_months IN (1, 12)),
    traffic_offset  INTEGER NOT NULL DEFAULT 0,
    traffic_offset_period TEXT,
    bandwidth_unit  TEXT NOT NULL DEFAULT 'GiB' CHECK (bandwidth_unit IN ('GB','GiB')),
    supplier_url    TEXT
);
INSERT INTO server_new (id, ip, ssh_port, auth_type, username, auth, region, server_type, name, expire_date, bandwidth_date,
    supplier, price, multiple, bandwidth, cpu_cores, disabled, external_server, remark, create_time, update_time,
    host_key, price_months, traffic_offset, traffic_offset_period, bandwidth_unit, supplier_url)
SELECT id, ip, ssh_port, auth_type, username, auth, region, server_type, name, expire_date, bandwidth_date,
    supplier, price, multiple, bandwidth, cpu_cores, disabled, external_server, remark, create_time, update_time,
    host_key, price_months, traffic_offset, traffic_offset_period, bandwidth_unit, supplier_url
FROM server;
DROP TABLE server;
ALTER TABLE server_new RENAME TO server;
CREATE INDEX idx_server_expire_date ON server (expire_date);
CREATE INDEX idx_server_state_expire ON server (disabled, external_server, expire_date);
CREATE TRIGGER deploy_revision_server_insert AFTER INSERT ON server BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
CREATE TRIGGER deploy_revision_server_update AFTER UPDATE OF ip,ssh_port,auth_type,username,auth,disabled,external_server ON server BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
CREATE TRIGGER deploy_revision_server_delete AFTER DELETE ON server BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;

CREATE TABLE domain_new (
    id                         INTEGER PRIMARY KEY AUTOINCREMENT,
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
INSERT INTO domain_new (id, expire_date, domain, price, supplier, dns_provider_config_id, dns_provider_type, dns_sync_status,
    dns_last_sync_time, dns_binding_extension_json, remark, create_time, update_time)
SELECT id, expire_date, domain, price, supplier, dns_provider_config_id, dns_provider_type, dns_sync_status,
    dns_last_sync_time, dns_binding_extension_json, remark, create_time, update_time
FROM domain;
DROP TABLE domain;
ALTER TABLE domain_new RENAME TO domain;
CREATE TRIGGER dns_auto_domain_update BEFORE UPDATE ON domain
WHEN EXISTS(SELECT 1 FROM dns_auto_operation o JOIN domain_dns_record r ON r.id=o.record_id WHERE r.domain_id=OLD.id)
BEGIN SELECT RAISE(ABORT, 'DNS 自动操作尚待对账'); END;

-- 按 0006、0007 原文重建跨表触发器。
CREATE TRIGGER dns_auto_provider_update BEFORE UPDATE ON dns_provider_config
WHEN EXISTS(SELECT 1 FROM dns_auto_operation o JOIN domain_dns_record r ON r.id=o.record_id JOIN domain d ON d.id=r.domain_id WHERE d.dns_provider_config_id=OLD.id)
BEGIN SELECT RAISE(ABORT, 'DNS 自动操作尚待对账'); END;
CREATE TRIGGER deploy_revision_account_traffic_stats_update AFTER UPDATE ON account_traffic_stats
WHEN OLD.account_id IS NOT NEW.account_id OR OLD.period_start IS NOT NEW.period_start OR OLD.period_end IS NOT NEW.period_end
 OR OLD.bandwidth_quota IS NOT NEW.bandwidth_quota
 OR NEW.upload_bytes<0 OR NEW.download_bytes<0 OR OLD.upload_bytes<0 OR OLD.download_bytes<0
 OR NEW.upload_bytes>9223372036854775807-NEW.download_bytes OR OLD.upload_bytes>9223372036854775807-OLD.download_bytes
 OR (coalesce(OLD.bandwidth_quota,(SELECT bandwidth FROM account WHERE id=OLD.account_id),0)>0
 AND (OLD.upload_bytes/1073741824 + OLD.download_bytes/1073741824 + (OLD.upload_bytes%1073741824 + OLD.download_bytes%1073741824)/1073741824)>=coalesce(OLD.bandwidth_quota,(SELECT bandwidth FROM account WHERE id=OLD.account_id),0))
 IS NOT (coalesce(NEW.bandwidth_quota,(SELECT bandwidth FROM account WHERE id=NEW.account_id),0)>0
 AND (NEW.upload_bytes/1073741824 + NEW.download_bytes/1073741824 + (NEW.upload_bytes%1073741824 + NEW.download_bytes%1073741824)/1073741824)>=coalesce(NEW.bandwidth_quota,(SELECT bandwidth FROM account WHERE id=NEW.account_id),0))
BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;

-- 自增起点取现存最大 ID 与流水中出现过的最大 business_id 的较大值，已删除记录的 ID 也不再分配。
DELETE FROM sqlite_sequence WHERE name IN ('account', 'server', 'domain', 'account_new', 'server_new', 'domain_new');
INSERT INTO sqlite_sequence (name, seq)
SELECT t.name, max(coalesce(t.max_id, 0), coalesce((SELECT max(business_id) FROM transactions WHERE business_table = t.name), 0))
FROM (SELECT 'account' AS name, (SELECT max(id) FROM account) AS max_id
      UNION ALL SELECT 'server', (SELECT max(id) FROM server)
      UNION ALL SELECT 'domain', (SELECT max(id) FROM domain)) t;
