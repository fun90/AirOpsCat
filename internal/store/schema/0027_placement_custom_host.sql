-- 部署位外部自定义访问域名：不归本系统 DNS 管理，原样作为订阅连接地址；与 access_host_id 互斥。
ALTER TABLE node_server ADD COLUMN access_host_custom TEXT;
