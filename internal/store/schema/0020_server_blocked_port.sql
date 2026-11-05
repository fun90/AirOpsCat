-- 防刷处置：被防火墙封禁的旧节点端口。每次部署后按此表补规则；这些端口不再分配给节点。
CREATE TABLE server_blocked_port (
    server_id   INTEGER NOT NULL REFERENCES server (id) ON DELETE CASCADE,
    port        INTEGER NOT NULL,
    create_time TEXT    NOT NULL,
    PRIMARY KEY (server_id, port)
);
