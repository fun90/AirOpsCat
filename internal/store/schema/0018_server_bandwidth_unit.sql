-- 月流量额度单位：服务商有的按 GB（1000³）有的按 GiB（1024³）计，按服务器区分；既有服务器保持原有 GiB 口径。
ALTER TABLE server ADD COLUMN bandwidth_unit TEXT NOT NULL DEFAULT 'GiB' CHECK (bandwidth_unit IN ('GB','GiB'));
