CREATE TABLE reality_sni (
    id INTEGER PRIMARY KEY AUTOINCREMENT,
    domain TEXT NOT NULL COLLATE NOCASE UNIQUE,
    note TEXT NOT NULL DEFAULT '',
    enabled INTEGER NOT NULL DEFAULT 1 CHECK (enabled IN (0, 1))
);

-- 公共候选域名；仅作生成配置的候选，不代表部署服务器已验证可达。
INSERT INTO reality_sni (domain, note) VALUES
    ('www.apple.com', 'Apple'),
    ('www.icloud.com', 'iCloud'),
    ('www.amazon.com', 'Amazon'),
    ('www.microsoft.com', 'Microsoft'),
    ('www.bing.com', 'Bing'),
    ('www.cloudflare.com', 'Cloudflare'),
    ('www.wikipedia.org', 'Wikipedia'),
    ('www.mozilla.org', 'Mozilla'),
    ('www.kernel.org', 'Linux Kernel'),
    ('www.nvidia.com', 'NVIDIA'),
    ('www.samsung.com', 'Samsung'),
    ('www.sony.com', 'Sony'),
    ('www.oracle.com', 'Oracle'),
    ('www.adobe.com', 'Adobe'),
    ('www.python.org', 'Python');
