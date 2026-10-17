-- 第一期稳定后执行（docs/node-multi-server-failover-plan.md 5.3 第 5 步），不要与 V2026101701 同时执行。
-- 前提：所有分组节点涉及的服务器已强制部署，订阅核对无误。执行后只能通过备份回滚。
-- 删除 node.server_id、access_host_id、node_group、deployed，node_deployment(_history).node_group，node_server.legacy_node_id。

DROP PROCEDURE IF EXISTS airopscat_drop_column_with_keys;

DELIMITER //
-- 先删除引用该列的外键与索引（Hibernate 生成的名称不固定），再删除列
CREATE PROCEDURE airopscat_drop_column_with_keys(IN p_table VARCHAR(64), IN p_column VARCHAR(64))
BEGIN
    DECLARE v_name VARCHAR(64);
    DECLARE v_done INT DEFAULT 0;
    DECLARE fk_cursor CURSOR FOR
        SELECT DISTINCT k.constraint_name
        FROM information_schema.key_column_usage k
        WHERE k.table_schema = DATABASE() AND k.table_name = p_table AND k.column_name = p_column
          AND k.referenced_table_name IS NOT NULL;
    DECLARE index_cursor CURSOR FOR
        SELECT DISTINCT s.index_name
        FROM information_schema.statistics s
        WHERE s.table_schema = DATABASE() AND s.table_name = p_table AND s.column_name = p_column
          AND s.index_name <> 'PRIMARY';
    DECLARE CONTINUE HANDLER FOR NOT FOUND SET v_done = 1;

    IF EXISTS (SELECT 1 FROM information_schema.columns
               WHERE table_schema = DATABASE() AND table_name = p_table AND column_name = p_column) THEN
        OPEN fk_cursor;
        fk_loop: LOOP
            FETCH fk_cursor INTO v_name;
            IF v_done = 1 THEN LEAVE fk_loop; END IF;
            SET @sql = CONCAT('ALTER TABLE `', p_table, '` DROP FOREIGN KEY `', v_name, '`');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END LOOP;
        CLOSE fk_cursor;

        SET v_done = 0;
        OPEN index_cursor;
        index_loop: LOOP
            FETCH index_cursor INTO v_name;
            IF v_done = 1 THEN LEAVE index_loop; END IF;
            SET @sql = CONCAT('ALTER TABLE `', p_table, '` DROP INDEX `', v_name, '`');
            PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
        END LOOP;
        CLOSE index_cursor;

        SET @sql = CONCAT('ALTER TABLE `', p_table, '` DROP COLUMN `', p_column, '`');
        PREPARE stmt FROM @sql; EXECUTE stmt; DEALLOCATE PREPARE stmt;
    END IF;
END //
DELIMITER ;

CALL airopscat_drop_column_with_keys('node', 'server_id');
CALL airopscat_drop_column_with_keys('node', 'access_host_id');
CALL airopscat_drop_column_with_keys('node', 'node_group');
CALL airopscat_drop_column_with_keys('node', 'deployed');
CALL airopscat_drop_column_with_keys('node_deployment', 'node_group');
CALL airopscat_drop_column_with_keys('node_deployment_history', 'node_group');
CALL airopscat_drop_column_with_keys('node_server', 'legacy_node_id');

DROP PROCEDURE airopscat_drop_column_with_keys;
