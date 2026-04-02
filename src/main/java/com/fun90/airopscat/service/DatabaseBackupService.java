package com.fun90.airopscat.service;

import com.fun90.airopscat.config.BlockingTaskExecutorConfig;
import com.fun90.airopscat.model.dto.BackupFileDto;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.GZIPOutputStream;
import java.util.zip.GZIPInputStream;

@Slf4j
@ApplicationScoped
public class DatabaseBackupService {

    private static final DateTimeFormatter FILE_DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final String FILE_PREFIX = "airopscat-";
    private static final String FILE_SUFFIX = ".sql.gz";
    private static final ZoneId SHANGHAI_ZONE = ZoneId.of("Asia/Shanghai");

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String jdbcUrl;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    @Inject
    @Named("backupTaskExecutor")
    ExecutorService backupTaskExecutor;

    @Inject
    SystemConfigService systemConfigService;

    public void scheduledBackup() {
        log.info("开始执行数据库备份任务，线程池状态: {}", BlockingTaskExecutorConfig.describeExecutor(backupTaskExecutor));
        try {
            BackupFileDto backupFile = createBackup();
            log.info("数据库备份完成: {}, 线程池状态: {}",
                    backupFile.getFileName(), BlockingTaskExecutorConfig.describeExecutor(backupTaskExecutor));
        } catch (Exception e) {
            log.error("执行定时数据库备份失败，线程池状态: {}",
                    BlockingTaskExecutorConfig.describeExecutor(backupTaskExecutor), e);
        }
    }

    public void cleanupExpiredBackups() {
        int retentionDays = getRetentionDays();
        if (retentionDays < 1) {
            log.warn("Skip cleanup because backup retention days is less than 1: {}", retentionDays);
            return;
        }

        log.info("开始执行备份清理任务，保留天数: {}, 线程池状态: {}",
                retentionDays, BlockingTaskExecutorConfig.describeExecutor(backupTaskExecutor));

        LocalDateTime cutoff = LocalDateTime.now(SHANGHAI_ZONE).minusDays(retentionDays);
        int deletedCount = 0;

        try {
            Path backupDirectory = ensureBackupDirectory();
            try (var paths = Files.list(backupDirectory)) {
                for (Path path : paths.toList()) {
                    if (!Files.isRegularFile(path)) {
                        continue;
                    }
                    String fileName = path.getFileName().toString();
                    if (!fileName.startsWith(FILE_PREFIX) || !fileName.endsWith(FILE_SUFFIX)) {
                        continue;
                    }
                    if (getLastModifiedTime(path).isBefore(cutoff)) {
                        Files.deleteIfExists(path);
                        deletedCount++;
                    }
                }
            }

            if (deletedCount > 0) {
                log.info("备份清理完成，已删除 {} 个过期备份文件，保留天数: {}, 线程池状态: {}",
                        deletedCount, retentionDays, BlockingTaskExecutorConfig.describeExecutor(backupTaskExecutor));
            } else {
                log.info("备份清理完成，没有需要删除的过期备份文件，保留天数: {}, 线程池状态: {}",
                        retentionDays, BlockingTaskExecutorConfig.describeExecutor(backupTaskExecutor));
            }
        } catch (Exception e) {
            log.error("执行备份清理失败，线程池状态: {}",
                    BlockingTaskExecutorConfig.describeExecutor(backupTaskExecutor), e);
        }
    }

    public BackupFileDto createBackup() {
        try {
            Path backupDirectory = ensureBackupDirectory();
            String fileName = generateBackupFileName(backupDirectory);
            Path targetFile = backupDirectory.resolve(fileName).normalize();
            Path tempFile = backupDirectory.resolve(fileName + ".tmp").normalize();

            DatabaseConnectionInfo connectionInfo = parseJdbcUrl(jdbcUrl);
            List<String> command = buildDumpCommand(connectionInfo);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().put("MYSQL_PWD", password);

            Process process = processBuilder.start();

            StringBuilder errorBuffer = new StringBuilder();
            CompletableFuture<Void> errorReader = startErrorReader(process.getErrorStream(), errorBuffer);

            try (InputStream inputStream = process.getInputStream();
                 GZIPOutputStream gzipOutputStream = new GZIPOutputStream(Files.newOutputStream(tempFile))) {
                inputStream.transferTo(gzipOutputStream);
            }

            int exitCode = process.waitFor();
            errorReader.join();

            if (exitCode != 0) {
                Files.deleteIfExists(tempFile);
                throw new IllegalStateException("mysqldump exited with code " + exitCode + ": " + errorBuffer);
            }

            Files.move(tempFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            return toDto(targetFile);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Database backup interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create database backup", e);
        }
    }

    public Map<String, Object> getBackupPage(int page, int size) {
        List<BackupFileDto> backups = listBackups();
        int safePage = Math.max(page, 1);
        int safeSize = Math.max(size, 1);
        int total = backups.size();
        int totalPages = total == 0 ? 0 : (int) Math.ceil((double) total / safeSize);
        int fromIndex = Math.min((safePage - 1) * safeSize, total);
        int toIndex = Math.min(fromIndex + safeSize, total);

        Map<String, Object> response = new HashMap<>();
        response.put("records", backups.subList(fromIndex, toIndex));
        response.put("total", total);
        response.put("pages", totalPages);
        response.put("current", safePage);
        response.put("size", safeSize);
        response.put("stats", getBackupStats(backups));
        return response;
    }

    public void deleteBackup(String fileName) {
        Path filePath = resolveBackupFile(fileName);
        try {
            Files.deleteIfExists(filePath);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to delete backup file: " + fileName, e);
        }
    }

    public File getBackupFile(String fileName) {
        return resolveBackupFile(fileName).toFile();
    }

    public BackupFileDto uploadBackup(String fileName, Path uploadedFile) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Backup file name is required");
        }
        if (!fileName.endsWith(FILE_SUFFIX)) {
            throw new IllegalArgumentException("Only .sql.gz backup files are supported");
        }

        try {
            Path backupDirectory = ensureBackupDirectory();
            Path targetFile = backupDirectory.resolve(fileName).normalize();
            if (!targetFile.startsWith(backupDirectory)) {
                throw new IllegalArgumentException("Invalid backup file name");
            }

            Files.copy(uploadedFile, targetFile, StandardCopyOption.REPLACE_EXISTING);
            return toDto(targetFile);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to upload backup file: " + fileName, e);
        }
    }

    public void restoreBackup(String fileName) {
        Path backupFile = resolveBackupFile(fileName);

        try {
            DatabaseConnectionInfo connectionInfo = parseJdbcUrl(jdbcUrl);
            List<String> command = buildRestoreCommand(connectionInfo);

            ProcessBuilder processBuilder = new ProcessBuilder(command);
            processBuilder.environment().put("MYSQL_PWD", password);

            Process process = processBuilder.start();

            StringBuilder errorBuffer = new StringBuilder();
            CompletableFuture<Void> errorReader = startErrorReader(process.getErrorStream(), errorBuffer);

            try (InputStream fileInputStream = Files.newInputStream(backupFile);
                 GZIPInputStream gzipInputStream = new GZIPInputStream(fileInputStream);
                 var processOutputStream = process.getOutputStream()) {
                gzipInputStream.transferTo(processOutputStream);
            }

            int exitCode = process.waitFor();
            errorReader.join();

            if (exitCode != 0) {
                throw new IllegalStateException("mysql exited with code " + exitCode + ": " + errorBuffer);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Database restore interrupted", e);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to restore database backup: " + fileName, e);
        }
    }

    public List<BackupFileDto> listBackups() {
        try {
            Path backupDirectory = ensureBackupDirectory();
            try (var paths = Files.list(backupDirectory)) {
                return paths
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().startsWith(FILE_PREFIX))
                        .filter(path -> path.getFileName().toString().endsWith(FILE_SUFFIX))
                        .sorted(Comparator.comparing(this::getLastModifiedTime).reversed())
                        .map(this::toDto)
                        .toList();
            }
        } catch (IOException e) {
            throw new IllegalStateException("Failed to list backup files", e);
        }
    }

    private Map<String, Object> getBackupStats(List<BackupFileDto> backups) {
        long totalSize = backups.stream().mapToLong(BackupFileDto::getSize).sum();

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", backups.size());
        stats.put("totalSize", totalSize);
        stats.put("backupDir", ensureBackupDirectory().toAbsolutePath().toString());
        stats.put("lastBackupTime", backups.isEmpty() ? null : backups.get(0).getLastModifiedTime());
        return stats;
    }

    private String generateBackupFileName(Path backupDirectory) {
        String datePart = LocalDate.now(SHANGHAI_ZONE).format(FILE_DATE_FORMATTER);
        int sequence = 1;

        while (true) {
            String candidate = FILE_PREFIX + datePart + "-" + sequence + FILE_SUFFIX;
            if (!Files.exists(backupDirectory.resolve(candidate))) {
                return candidate;
            }
            sequence++;
        }
    }

    private List<String> buildDumpCommand(DatabaseConnectionInfo connectionInfo) {
        List<String> command = new ArrayList<>();
        command.add(getMysqldumpPath());
        command.add("--host=" + connectionInfo.host());
        command.add("--port=" + connectionInfo.port());
        command.add("--user=" + username);
        command.add("--single-transaction");
        command.add("--quick");
        command.add("--routines");
        command.add("--events");
        command.add("--triggers");
        command.add("--set-gtid-purged=OFF");
        command.add("--default-character-set=utf8mb4");
        command.add(connectionInfo.database());
        return command;
    }

    private List<String> buildRestoreCommand(DatabaseConnectionInfo connectionInfo) {
        List<String> command = new ArrayList<>();
        command.add(resolveMysqlExecutable());
        command.add("--host=" + connectionInfo.host());
        command.add("--port=" + connectionInfo.port());
        command.add("--user=" + username);
        command.add("--default-character-set=utf8mb4");
        command.add(connectionInfo.database());
        return command;
    }

    private String resolveMysqlExecutable() {
        String mysqldumpPath = getMysqldumpPath();
        String trimmed = mysqldumpPath == null ? "" : mysqldumpPath.trim();
        if (trimmed.isEmpty()) {
            return "mysql";
        }

        String lower = trimmed.toLowerCase();
        if (lower.endsWith("mysqldump.exe")) {
            return trimmed.substring(0, trimmed.length() - "mysqldump.exe".length()) + "mysql.exe";
        }
        if (lower.endsWith("mysqldump")) {
            return trimmed.substring(0, trimmed.length() - "mysqldump".length()) + "mysql";
        }
        return "mysql";
    }

    private DatabaseConnectionInfo parseJdbcUrl(String url) {
        String normalizedUrl = url;
        int queryIndex = normalizedUrl.indexOf('?');
        if (queryIndex >= 0) {
            normalizedUrl = normalizedUrl.substring(0, queryIndex);
        }
        String prefix = "jdbc:mysql://";
        if (!normalizedUrl.startsWith(prefix)) {
            throw new IllegalArgumentException("Unsupported JDBC URL: " + url);
        }

        String connectionPart = normalizedUrl.substring(prefix.length());
        int slashIndex = connectionPart.indexOf('/');
        if (slashIndex < 0) {
            throw new IllegalArgumentException("Database name not found in JDBC URL: " + url);
        }

        String hostPort = connectionPart.substring(0, slashIndex);
        String database = connectionPart.substring(slashIndex + 1);
        String host = hostPort;
        int port = 3306;

        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex > -1) {
            host = hostPort.substring(0, colonIndex);
            port = Integer.parseInt(hostPort.substring(colonIndex + 1));
        }

        return new DatabaseConnectionInfo(host, port, database);
    }

    private BackupFileDto toDto(Path file) {
        try {
            BackupFileDto dto = new BackupFileDto();
            String fileName = file.getFileName().toString();
            dto.setId(fileName);
            dto.setFileName(fileName);
            dto.setPath(file.toAbsolutePath().toString());
            dto.setSize(Files.size(file));
            dto.setLastModifiedTime(getLastModifiedTime(file));
            return dto;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read backup file metadata: " + file, e);
        }
    }

    private Path ensureBackupDirectory() {
        try {
            Path path = Paths.get(getBackupDir());
            if (!path.isAbsolute()) {
                path = Paths.get("").toAbsolutePath().resolve(path).normalize();
            }
            Files.createDirectories(path);
            return path;
        } catch (IOException e) {
            throw new IllegalStateException("Failed to create backup directory", e);
        }
    }

    private Path resolveBackupFile(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            throw new IllegalArgumentException("Backup file name is required");
        }

        Path backupDirectory = ensureBackupDirectory();
        Path resolved = backupDirectory.resolve(fileName).normalize();
        if (!resolved.startsWith(backupDirectory)) {
            throw new IllegalArgumentException("Invalid backup file name");
        }
        if (!Files.exists(resolved) || !Files.isRegularFile(resolved)) {
            throw new IllegalArgumentException("Backup file does not exist: " + fileName);
        }
        return resolved;
    }

    private LocalDateTime getLastModifiedTime(Path file) {
        try {
            return LocalDateTime.ofInstant(Files.getLastModifiedTime(file).toInstant(), SHANGHAI_ZONE);
        } catch (IOException e) {
            throw new IllegalStateException("Failed to read last modified time: " + file, e);
        }
    }

    private CompletableFuture<Void> startErrorReader(InputStream errorStream, StringBuilder errorBuffer) {
        return CompletableFuture.runAsync(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(errorStream))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (errorBuffer.length() > 0) {
                        errorBuffer.append(System.lineSeparator());
                    }
                    errorBuffer.append(line);
                }
            } catch (IOException e) {
                log.warn("Failed to read mysqldump error stream", e);
            }
        }, backupTaskExecutor);
    }

    private String getBackupDir() {
        return systemConfigService.getResolvedValue("airopscat.backup.dir");
    }

    private String getMysqldumpPath() {
        return systemConfigService.getResolvedValue("airopscat.backup.mysqldump-path");
    }

    private int getRetentionDays() {
        return systemConfigService.getIntValue("airopscat.backup.retention-days", 30);
    }

    private record DatabaseConnectionInfo(String host, int port, String database) {
    }
}
