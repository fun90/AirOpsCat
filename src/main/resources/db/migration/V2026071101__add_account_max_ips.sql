ALTER TABLE account
    ADD COLUMN max_ips INT NULL COMMENT '跨节点最大去重 IP（设备）数，空或 <=0 表示不限制';
