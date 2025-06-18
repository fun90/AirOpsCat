-- 标签系统数据库迁移脚本
-- 创建标签表和关联表

-- 1. 创建标签表
CREATE TABLE tag (
    id BIGINT PRIMARY KEY AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL UNIQUE COMMENT '标签名称',
    description VARCHAR(500) COMMENT '标签描述',
    color VARCHAR(20) COMMENT '标签颜色',
    disabled INTEGER DEFAULT 0 COMMENT '0:启用，1:禁用',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time DATETIME DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间'
) COMMENT '标签表';

-- 2. 创建节点标签关联表
CREATE TABLE node_tag (
    node_id BIGINT NOT NULL COMMENT '节点ID',
    tag_id BIGINT NOT NULL COMMENT '标签ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '关联创建时间',
    PRIMARY KEY (node_id, tag_id),
    FOREIGN KEY (node_id) REFERENCES node(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
) COMMENT '节点标签关联表';

-- 3. 创建账户标签关联表
CREATE TABLE account_tag (
    account_id BIGINT NOT NULL COMMENT '账户ID',
    tag_id BIGINT NOT NULL COMMENT '标签ID',
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '关联创建时间',
    PRIMARY KEY (account_id, tag_id),
    FOREIGN KEY (account_id) REFERENCES account(id) ON DELETE CASCADE,
    FOREIGN KEY (tag_id) REFERENCES tag(id) ON DELETE CASCADE
) COMMENT '账户标签关联表';

-- 4. 创建索引以提高查询性能
CREATE INDEX idx_tag_name ON tag(name);
CREATE INDEX idx_tag_disabled ON tag(disabled);
CREATE INDEX idx_node_tag_node_id ON node_tag(node_id);
CREATE INDEX idx_node_tag_tag_id ON node_tag(tag_id);
CREATE INDEX idx_account_tag_account_id ON account_tag(account_id);
CREATE INDEX idx_account_tag_tag_id ON account_tag(tag_id);

-- 5. 插入一些示例标签
INSERT INTO tag (name, description, color, disabled) VALUES
('VIP', 'VIP用户专用标签', '#FFD700', 0),
('Basic', '基础用户标签', '#6c757d', 0),
('Premium', '高级用户标签', '#e83e8c', 0),
('Student', '学生用户标签', '#20c997', 0),
('Enterprise', '企业用户标签', '#fd7e14', 0),
('USA', '美国地区节点', '#0d6efd', 0),
('Asia', '亚洲地区节点', '#198754', 0),
('Europe', '欧洲地区节点', '#dc3545', 0),
('High-Speed', '高速节点', '#6f42c1', 0),
('Game', '游戏优化节点', '#d63384', 0);

-- 6. 查询语句示例
-- 查询所有标签
-- SELECT * FROM tag WHERE disabled = 0 ORDER BY name;

-- 查询节点的标签
-- SELECT t.* FROM tag t 
-- JOIN node_tag nt ON t.id = nt.tag_id 
-- WHERE nt.node_id = ? AND t.disabled = 0;

-- 查询账户的标签
-- SELECT t.* FROM tag t 
-- JOIN account_tag at ON t.id = at.tag_id 
-- WHERE at.account_id = ? AND t.disabled = 0;

-- 查询拥有特定标签的节点
-- SELECT n.* FROM node n 
-- JOIN node_tag nt ON n.id = nt.node_id 
-- WHERE nt.tag_id = ? AND n.disabled = 0;

-- 查询拥有特定标签的账户
-- SELECT a.* FROM account a 
-- JOIN account_tag at ON a.id = at.account_id 
-- WHERE at.tag_id = ? AND a.disabled = 0; 