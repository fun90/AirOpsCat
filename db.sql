-- User 表
CREATE TABLE IF NOT EXISTS user
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         VARCHAR(100) NOT NULL,
    nick_name     VARCHAR(100),
    password      VARCHAR(100) NOT NULL,
    remark        VARCHAR(300),
    role          VARCHAR(100), -- ADMIN,PARTNER,VIP
    referrer      INTEGER,
    disabled      INTEGER DEFAULT 0,
    failed_attempts      INTEGER NOT NULL DEFAULT 0,
    lock_time      DATETIME,
    create_time   DATETIME,
    update_time   DATETIME
);

-- Website 表
CREATE TABLE IF NOT EXISTS website
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    expire_date       DATE, -- 到期日
    domain        VARCHAR(300),
    price         DECIMAL(10, 2),
    cost          DECIMAL(10, 2),
    remark        VARCHAR(300),
    create_time   DATETIME,
    update_time   DATETIME
);

-- Server 表
CREATE TABLE IF NOT EXISTS server
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    ip            VARCHAR(300) NOT NULL,
    ssh_port      INTEGER,
    auth_type     VARCHAR(50), -- 认证方式
    auth          VARCHAR(500), -- 认证密码/密钥
    host          VARCHAR(100), -- 域名
    name          VARCHAR(100), -- 名称
    expire_date       DATE, -- 到期日
    supplier      VARCHAR(100), -- 供应商
    price         DECIMAL(10, 2), -- 价格
    cost          DECIMAL(10, 2), -- 费用
    multiple      DECIMAL(10, 2),
    disabled      INTEGER DEFAULT 0,
    remark        VARCHAR(300),
    transit_config JSON,
    core_config    JSON,
    create_time    DATETIME,
    update_time    DATETIME
);

-- Node 表
CREATE TABLE IF NOT EXISTS node
(
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    server_id       INTEGER,
    port            INTEGER,
    --protocol        VARCHAR(50), -- VLESS、Hysteria2、Socks、Shadowsocks、ShadowTLS
    type            INTEGER, -- 0:代理，1:落地
    inbound         JSON,
    outbound        JSON,
    rule            JSON,
    level           INTEGER,
    disabled        INTEGER DEFAULT 0,
    name            VARCHAR(100),
    remark          VARCHAR(300),
    create_time     DATETIME,
    update_time     DATETIME
);

-- Account 表
CREATE TABLE IF NOT EXISTS account
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    level            INTEGER,
    from_date        DATETIME,
    to_date          DATETIME,
    period_type       VARCHAR(20) NOT NULL, -- 统计周期类型: DAILY, WEEKLY, MONTHLY, CUSTOM
    account_no       VARCHAR(100) NOT NULL,
    uuid             VARCHAR(100) NOT NULL,
    subscription_code VARCHAR(100),
    speed            INTEGER,
    bandwidth        INTEGER,
    disabled         INTEGER DEFAULT 0,
    user_id          INTEGER,
    create_time   DATETIME,
    update_time   DATETIME
);

-- AccountNode 表
CREATE TABLE IF NOT EXISTS account_node
(
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    account_id  INTEGER,
    node_id     INTEGER,
    create_time   DATETIME,
    update_time   DATETIME
);


-- Account 流量统计表
CREATE TABLE IF NOT EXISTS account_traffic_stats
(
    id                INTEGER PRIMARY KEY AUTOINCREMENT,
    user_id           INTEGER NOT NULL,     -- 关联到用户表的ID
    account_id        INTEGER NOT NULL,     -- 关联到账户表的ID
    period_start      DATETIME NOT NULL,    -- 统计周期开始时间
    period_end        DATETIME NOT NULL,    -- 统计周期结束时间
    upload_bytes      BIGINT DEFAULT 0,     -- 上传流量字节数
    download_bytes    BIGINT DEFAULT 0,     -- 下载流量字节数
    create_time       DATETIME,
    update_time       DATETIME,
    FOREIGN KEY (user_id) REFERENCES user(id)
);
-- 创建索引，优化查询效率
CREATE INDEX IF NOT EXISTS idx_account_traffic_user_id ON account_traffic_stats(user_id);
CREATE INDEX IF NOT EXISTS idx_account_traffic_account_id ON account_traffic_stats(account_id);