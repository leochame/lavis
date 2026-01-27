package com.lavis.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.stream.Stream;

@Slf4j
@Service
public class DatabaseBackupService {

    private static final String DB_FILE = System.getProperty("user.home") + "/.lavis/data/lavis.db";
    private static final String BACKUP_DIR = System.getProperty("user.home") + "/.lavis/backups";

    @Autowired
    private DataSource dataSource;

    /**
     * Scheduled backup task - runs daily at 3 AM
     */
    @Scheduled(cron = "0 0 3 * * *")
    public void backupDatabase() {
        try {
            log.info("Starting database backup...");

            // Create backup directory if it doesn't exist
            Files.createDirectories(Paths.get(BACKUP_DIR));

            // Generate backup filename with timestamp
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String backupFile = BACKUP_DIR + "/lavis_" + timestamp + ".db";

            // Perform SQLite backup using VACUUM INTO
            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM INTO '" + backupFile + "'");
            }

            log.info("Database backup completed: {}", backupFile);

            // Clean up old backups (keep last 30 days)
            cleanOldBackups(30);

        } catch (Exception e) {
            log.error("Database backup failed", e);
        }
    }

    /**
     * Clean up backups older than specified days
     * @param daysToKeep Number of days to keep backups
     */
    private void cleanOldBackups(int daysToKeep) {
        try {
            Path backupPath = Paths.get(BACKUP_DIR);
            if (!Files.exists(backupPath)) {
                return;
            }

            LocalDateTime cutoffDate = LocalDateTime.now().minusDays(daysToKeep);

            try (Stream<Path> files = Files.list(backupPath)) {
                files.filter(path -> path.toString().endsWith(".db"))
                     .filter(path -> {
                         try {
                             LocalDateTime fileTime = LocalDateTime.ofInstant(
                                 Files.getLastModifiedTime(path).toInstant(),
                                 java.time.ZoneId.systemDefault()
                             );
                             return fileTime.isBefore(cutoffDate);
                         } catch (IOException e) {
                             return false;
                         }
                     })
                     .forEach(path -> {
                         try {
                             Files.delete(path);
                             log.info("Deleted old backup: {}", path.getFileName());
                         } catch (IOException e) {
                             log.error("Failed to delete backup: {}", path, e);
                         }
                     });
            }

        } catch (IOException e) {
            log.error("Failed to clean old backups", e);
        }
    }

    /**
     * Manual backup trigger
     * @return Path to the backup file
     */
    public String manualBackup() {
        try {
            Files.createDirectories(Paths.get(BACKUP_DIR));

            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String backupFile = BACKUP_DIR + "/lavis_manual_" + timestamp + ".db";

            try (Connection conn = dataSource.getConnection();
                 Statement stmt = conn.createStatement()) {
                stmt.execute("VACUUM INTO '" + backupFile + "'");
            }

            log.info("Manual backup completed: {}", backupFile);
            return backupFile;

        } catch (Exception e) {
            log.error("Manual backup failed", e);
            throw new RuntimeException("Backup failed: " + e.getMessage(), e);
        }
    }
}
