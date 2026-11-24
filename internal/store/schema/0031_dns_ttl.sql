ALTER TABLE domain_dns_record ADD COLUMN ttl_pending INTEGER NOT NULL DEFAULT 0;

-- 自动对账保存的是记录内容。本次迁移只修改 TTL，完成后恢复运行时保护。
DROP TRIGGER dns_auto_record_update;
UPDATE domain_dns_record
SET ttl = CASE WHEN upper(type) = 'A' AND coalesce(proxied, 0) = 0 THEN 120 ELSE 1 END,
    ttl_pending = CASE WHEN coalesce(external_record_id, '') <> '' THEN 1 ELSE 0 END
WHERE status <> 'PENDING_DELETE'
  AND coalesce(ttl, 1) <> CASE WHEN upper(type) = 'A' AND coalesce(proxied, 0) = 0 THEN 120 ELSE 1 END;
CREATE TRIGGER dns_auto_record_update BEFORE UPDATE ON domain_dns_record
WHEN EXISTS(SELECT 1 FROM dns_auto_operation WHERE record_id=OLD.id)
BEGIN SELECT RAISE(ABORT, 'DNS 自动操作尚待对账'); END;
