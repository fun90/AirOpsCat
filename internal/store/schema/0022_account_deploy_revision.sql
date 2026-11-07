-- nodeClients 读取 uuid、account_no、disabled、to_date、download_mbps、upload_mbps、max_ips；
-- EffectiveLimitOverrides 的超额判定读取 bandwidth；traffic.go 的周期计算读取 traffic_anchor、period_type、from_date。
-- 这些字段变化须阻止确认旧配置；remark、auth_code、expire_handled_to、node_prefix 等仅影响展示、订阅或任务状态。
-- 节点 name/remark 进入部署快照与订阅，node_server 的显示或状态字段可能影响快照/确认，保留原触发器。
DROP TRIGGER deploy_revision_account_update;
CREATE TRIGGER deploy_revision_account_update AFTER UPDATE OF
 uuid,account_no,disabled,to_date,download_mbps,upload_mbps,max_ips,bandwidth,period_type,from_date,traffic_anchor
ON account
WHEN OLD.uuid IS NOT NEW.uuid OR OLD.account_no IS NOT NEW.account_no
 OR OLD.disabled IS NOT NEW.disabled OR OLD.to_date IS NOT NEW.to_date
 OR OLD.download_mbps IS NOT NEW.download_mbps OR OLD.upload_mbps IS NOT NEW.upload_mbps
 OR OLD.max_ips IS NOT NEW.max_ips OR OLD.bandwidth IS NOT NEW.bandwidth
 OR OLD.period_type IS NOT NEW.period_type OR OLD.from_date IS NOT NEW.from_date
 OR OLD.traffic_anchor IS NOT NEW.traffic_anchor
BEGIN UPDATE deploy_revision SET revision=revision+1 WHERE id=1; END;
