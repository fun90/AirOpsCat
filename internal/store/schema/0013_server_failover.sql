-- 手动切走：服务器被墙等故障时，自动 DNS 把其部署位域名切到同节点其他部署位，取消后回切。
ALTER TABLE server ADD COLUMN failover INTEGER NOT NULL DEFAULT 0;
