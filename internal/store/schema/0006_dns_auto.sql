-- 自动 DNS 操作先持久化，未知结果下一轮对账；操作期间拒绝人工修改关联配置。
CREATE TABLE dns_auto_operation (
 record_id INTEGER PRIMARY KEY REFERENCES domain_dns_record(id) ON DELETE RESTRICT,
 old_content TEXT NOT NULL,
 target_content TEXT NOT NULL,
 home_content TEXT,
 reason TEXT NOT NULL,
 created_at TEXT NOT NULL
);
CREATE TRIGGER dns_auto_record_update BEFORE UPDATE ON domain_dns_record
WHEN EXISTS(SELECT 1 FROM dns_auto_operation WHERE record_id=OLD.id)
BEGIN SELECT RAISE(ABORT, 'DNS 自动操作尚待对账'); END;
CREATE TRIGGER dns_auto_record_delete BEFORE DELETE ON domain_dns_record
WHEN EXISTS(SELECT 1 FROM dns_auto_operation WHERE record_id=OLD.id)
BEGIN SELECT RAISE(ABORT, 'DNS 自动操作尚待对账'); END;
CREATE TRIGGER dns_auto_domain_update BEFORE UPDATE ON domain
WHEN EXISTS(SELECT 1 FROM dns_auto_operation o JOIN domain_dns_record r ON r.id=o.record_id WHERE r.domain_id=OLD.id)
BEGIN SELECT RAISE(ABORT, 'DNS 自动操作尚待对账'); END;
CREATE TRIGGER dns_auto_provider_update BEFORE UPDATE ON dns_provider_config
WHEN EXISTS(SELECT 1 FROM dns_auto_operation o JOIN domain_dns_record r ON r.id=o.record_id JOIN domain d ON d.id=r.domain_id WHERE d.dns_provider_config_id=OLD.id)
BEGIN SELECT RAISE(ABORT, 'DNS 自动操作尚待对账'); END;
