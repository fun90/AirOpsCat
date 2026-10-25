-- 服务器价格的计价周期（月数）；已有价格沿用续费页的年价口径。
ALTER TABLE server ADD COLUMN price_months INTEGER NOT NULL DEFAULT 12 CHECK (price_months IN (1, 12));
