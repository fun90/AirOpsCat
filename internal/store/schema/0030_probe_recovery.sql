-- 记录故障后连续成功的轮数，达到两轮后允许 DNS 重新切入。
ALTER TABLE placement_probe ADD COLUMN dns_successes INTEGER NOT NULL DEFAULT 2;
UPDATE placement_probe SET dns_successes=0 WHERE ok=0;
