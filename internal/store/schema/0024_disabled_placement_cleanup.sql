-- 禁用服务器不执行部署；已请求删除的部署位直接清理管理记录，远端配置留待重新部署覆盖。
CREATE TEMP TABLE disabled_placement_cleanup_nodes (id INTEGER PRIMARY KEY);
INSERT OR IGNORE INTO disabled_placement_cleanup_nodes
    SELECT n.id FROM node n JOIN node_server ns ON ns.node_id=n.id
    JOIN server s ON s.id=ns.server_id WHERE s.disabled=1 AND ns.deployed=2;
INSERT OR IGNORE INTO disabled_placement_cleanup_nodes
    SELECT n.out_id FROM node n JOIN disabled_placement_cleanup_nodes c ON c.id=n.id WHERE n.out_id IS NOT NULL;
DELETE FROM node_server WHERE deployed=2 AND server_id IN (SELECT id FROM server WHERE disabled=1);
DELETE FROM node WHERE type=0 AND id IN (SELECT id FROM disabled_placement_cleanup_nodes)
    AND NOT EXISTS (SELECT 1 FROM node_server WHERE node_id=node.id)
    AND NOT EXISTS (SELECT 1 FROM node o WHERE o.out_id=node.id)
    AND NOT EXISTS (SELECT 1 FROM route_rule r WHERE r.outbound_node_id=node.id);
DELETE FROM node WHERE type=1 AND id IN (SELECT id FROM disabled_placement_cleanup_nodes)
    AND NOT EXISTS (SELECT 1 FROM node_server WHERE node_id=node.id)
    AND NOT EXISTS (SELECT 1 FROM node o WHERE o.out_id=node.id)
    AND NOT EXISTS (SELECT 1 FROM route_rule r WHERE r.outbound_node_id=node.id);
DROP TABLE disabled_placement_cleanup_nodes;
