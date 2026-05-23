package com.fun90.airopscat.config;

import io.agroal.api.AgroalDataSource;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

@Slf4j
@ApplicationScoped
public class ServerAuthColumnMigration {

    @Inject
    AgroalDataSource dataSource;

    void onStart(@Observes StartupEvent event) {
        ensureAuthColumnCanStoreEncryptedKeys();
    }

    private void ensureAuthColumnCanStoreEncryptedKeys() {
        try (Connection connection = dataSource.getConnection()) {
            ColumnInfo column = findAuthColumn(connection);
            if (column == null) {
                return;
            }
            if (!needsMigration(column)) {
                return;
            }
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("ALTER TABLE server MODIFY COLUMN auth TEXT NULL");
            }
            log.info("已将 server.auth 字段调整为 TEXT，以支持加密后的 SSH 私钥内容");
        } catch (SQLException e) {
            throw new IllegalStateException("检查或调整 server.auth 字段失败", e);
        }
    }

    private ColumnInfo findAuthColumn(Connection connection) throws SQLException {
        String sql = """
                SELECT DATA_TYPE, CHARACTER_MAXIMUM_LENGTH
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = DATABASE()
                  AND TABLE_NAME = 'server'
                  AND COLUMN_NAME = 'auth'
                """;
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet resultSet = statement.executeQuery()) {
            if (!resultSet.next()) {
                return null;
            }
            return new ColumnInfo(resultSet.getString("DATA_TYPE"), resultSet.getLong("CHARACTER_MAXIMUM_LENGTH"));
        }
    }

    private boolean needsMigration(ColumnInfo column) {
        String dataType = column.dataType() == null ? "" : column.dataType().toLowerCase(Locale.ROOT);
        return switch (dataType) {
            case "char", "varchar", "tinytext" -> true;
            default -> column.characterMaximumLength() > 0 && column.characterMaximumLength() < 65535;
        };
    }

    private record ColumnInfo(String dataType, long characterMaximumLength) {
    }
}
