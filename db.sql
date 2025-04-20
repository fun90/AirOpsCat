-- User 表
CREATE TABLE IF NOT EXISTS user
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    email         VARCHAR(300) NOT NULL,
    nick_name     VARCHAR(300),
    password      VARCHAR(300) NOT NULL,
    remark        VARCHAR(300),
    role          VARCHAR(300), -- admin,partner,vip
    referrer      INTEGER,
    disabled      SHORT DEFAULT 0,
    create_time   DATETIME,
    update_time   DATETIME
);

-- Website 表
CREATE TABLE IF NOT EXISTS website
(
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    from_date     DATE,
    to_date       DATE,
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
    auth_type     VARCHAR(300),
    auth          VARCHAR(300),
    host          VARCHAR(300),
    name          VARCHAR(300),
    from_date     DATE,
    to_date       DATE,
    supplier      VARCHAR(300),
    price         DECIMAL(10, 2),
    cost          DECIMAL(10, 2),
    multiple      DECIMAL(10, 2),
    disabled      SHORT DEFAULT 0,
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
    port            INTEGER,
    server_id       INTEGER,
    domain          VARCHAR(300),
    inbound         JSON,
    outbound        JSON,
    level           SHORT,
    disabled        SHORT DEFAULT 0,
    name            VARCHAR(300),
    remark          VARCHAR(300),
    create_time     DATETIME,
    update_time     DATETIME
);

-- Account 表
CREATE TABLE IF NOT EXISTS account
(
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    level            SHORT,
    from_date        DATETIME,
    to_date          DATETIME,
    cycle            INTEGER,
    account_no       VARCHAR(300) NOT NULL,
    uuid             VARCHAR(300) NOT NULL,
    subscription_code VARCHAR(300),
    speed            INTEGER,
    bandwidth        INTEGER,
    disabled         SHORT DEFAULT 0,
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
