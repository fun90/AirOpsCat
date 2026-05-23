ALTER TABLE account
    ADD COLUMN download_mbps INT NULL COMMENT '下行限速（Mbps），空表示不限速',
    ADD COLUMN upload_mbps   INT NULL COMMENT '上行限速（Mbps），空表示不限速';
