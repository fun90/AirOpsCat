-- 普通流量计数和展示配置不改变部署内容；保留限速跨界与配置契约保护。
DROP TRIGGER deploy_revision_system_config_insert;
DROP TRIGGER deploy_revision_system_config_update;
DROP TRIGGER deploy_revision_system_config_delete;
CREATE TRIGGER deploy_revision_system_config_insert AFTER INSERT ON system_config
WHEN NEW.config_key IN ('airopscat.sing-box.clash-api.secret','airopscat.ratelimit.enabled','airopscat.account.traffic-over-quota.download-mbps','airopscat.account.traffic-over-quota.upload-mbps')
BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
CREATE TRIGGER deploy_revision_system_config_delete AFTER DELETE ON system_config
WHEN OLD.config_key IN ('airopscat.sing-box.clash-api.secret','airopscat.ratelimit.enabled','airopscat.account.traffic-over-quota.download-mbps','airopscat.account.traffic-over-quota.upload-mbps')
BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
CREATE TRIGGER deploy_revision_system_config_update AFTER UPDATE ON system_config
WHEN (OLD.config_key IN ('airopscat.sing-box.clash-api.secret','airopscat.ratelimit.enabled','airopscat.account.traffic-over-quota.download-mbps','airopscat.account.traffic-over-quota.upload-mbps')
 OR NEW.config_key IN ('airopscat.sing-box.clash-api.secret','airopscat.ratelimit.enabled','airopscat.account.traffic-over-quota.download-mbps','airopscat.account.traffic-over-quota.upload-mbps'))
 AND (OLD.config_key IS NOT NEW.config_key OR OLD.config_value IS NOT NEW.config_value)
BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;

DROP TRIGGER deploy_revision_account_traffic_stats_update;
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
