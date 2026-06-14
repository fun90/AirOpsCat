package com.fun90.airopscat.config;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

@Slf4j
@ApplicationScoped
public class ObsoleteConfigTableMigration {

    @Inject
    AgroalDataSource dataSource;

    void onStart(@Observes StartupEvent event) {
        dropObsoleteConfigTable();
    }

    void dropObsoleteConfigTable() {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("DROP TABLE IF EXISTS server_config");
            log.info("已删除不再使用的 server_config 表");
        } catch (SQLException e) {
            throw new IllegalStateException("删除不再使用的 server_config 表失败", e);
        }
    }
}
