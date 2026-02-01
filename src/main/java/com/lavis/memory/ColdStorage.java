package com.lavis.memory;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * 冷存储服务
 *
 * 管理被压缩的图片数据，将其从内存/数据库卸载到文件系统。
 * 支持按需恢复，用于历史回溯。
 *
 * 存储结构：
 * ~/.lavis/cold-storage/
 *   ├── 2026/
 *   │   ├── 01/
 *   │   │   ├── img_abc123.jpg
 *   │   │   └── img_def456.jpg
 *   │   └── 02/
 *   └── index.json (可选，用于快速查找)
 */
@Slf4j
@Service
public class ColdStorage {

    @Value("${lavis.storage.cold-path:#{systemProperties['user.home']}/.lavis/cold-storage}")
    private String coldStoragePath;

    @Value("${lavis.storage.retention-days:30}")
    private int retentionDays;

    private Path storagePath;

    @PostConstruct
    public void init() {
        storagePath = Paths.get(coldStoragePath);
        try {
            Files.createDirectories(storagePath);
            log.info("ColdStorage initialized at: {}", storagePath);
        } catch (IOException e) {
            log.error("Failed to create cold storage directory", e);
        }
    }

    /**
     * 将图片归档到冷存储
     *
     * @param imageId 图片唯一标识
     * @param base64Data Base64 编码的图片数据
     * @return 存储路径，失败返回 empty
     */
    public Optional<Path> archive(String imageId, String base64Data) {
        if (imageId == null || base64Data == null) {
            return Optional.empty();
        }

        try {
            // 按年月组织目录
            LocalDateTime now = LocalDateTime.now();
            Path monthDir = storagePath
                    .resolve(String.valueOf(now.getYear()))
                    .resolve(String.format("%02d", now.getMonthValue()));
            Files.createDirectories(monthDir);

            // 解码并保存
            byte[] imageBytes = Base64.getDecoder().decode(base64Data);
            Path imagePath = monthDir.resolve(imageId + ".jpg");
            Files.write(imagePath, imageBytes);

            log.debug("Archived image to cold storage: {}", imagePath);
            return Optional.of(imagePath);
        } catch (Exception e) {
            log.error("Failed to archive image {}: {}", imageId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 从冷存储恢复图片
     *
     * @param imageId 图片唯一标识
     * @return Base64 编码的图片数据，未找到返回 empty
     */
    public Optional<String> retrieve(String imageId) {
        if (imageId == null) {
            return Optional.empty();
        }

        try {
            // 搜索所有年月目录
            Optional<Path> imagePath = findImagePath(imageId);
            if (imagePath.isEmpty()) {
                log.debug("Image not found in cold storage: {}", imageId);
                return Optional.empty();
            }

            byte[] imageBytes = Files.readAllBytes(imagePath.get());
            String base64Data = Base64.getEncoder().encodeToString(imageBytes);

            log.debug("Retrieved image from cold storage: {}", imageId);
            return Optional.of(base64Data);
        } catch (Exception e) {
            log.error("Failed to retrieve image {}: {}", imageId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 检查图片是否存在于冷存储
     */
    public boolean exists(String imageId) {
        return findImagePath(imageId).isPresent();
    }

    /**
     * 清理过期的冷存储数据
     *
     * @return 删除的文件数量
     */
    public int cleanup() {
        return cleanup(retentionDays);
    }

    /**
     * 清理指定天数前的冷存储数据
     *
     * @param daysToKeep 保留天数
     * @return 删除的文件数量
     */
    public int cleanup(int daysToKeep) {
        LocalDateTime cutoff = LocalDateTime.now().minus(daysToKeep, ChronoUnit.DAYS);
        int deletedCount = 0;

        try (Stream<Path> years = Files.list(storagePath)) {
            for (Path yearDir : years.filter(Files::isDirectory).toList()) {
                try (Stream<Path> months = Files.list(yearDir)) {
                    for (Path monthDir : months.filter(Files::isDirectory).toList()) {
                        deletedCount += cleanupDirectory(monthDir, cutoff);
                    }
                }

                // 删除空的年目录
                if (isDirectoryEmpty(yearDir)) {
                    Files.delete(yearDir);
                }
            }
        } catch (IOException e) {
            log.error("Error during cold storage cleanup", e);
        }

        if (deletedCount > 0) {
            log.info("Cold storage cleanup: deleted {} files older than {} days", deletedCount, daysToKeep);
        }

        return deletedCount;
    }

    /**
     * 获取冷存储统计信息
     */
    public StorageStats getStats() {
        long totalFiles = 0;
        long totalSize = 0;

        try (Stream<Path> walk = Files.walk(storagePath)) {
            for (Path path : walk.filter(Files::isRegularFile).toList()) {
                totalFiles++;
                totalSize += Files.size(path);
            }
        } catch (IOException e) {
            log.error("Error getting storage stats", e);
        }

        return new StorageStats(totalFiles, totalSize, storagePath.toString());
    }

    // ==================== 私有方法 ====================

    private Optional<Path> findImagePath(String imageId) {
        String fileName = imageId + ".jpg";

        try (Stream<Path> walk = Files.walk(storagePath)) {
            return walk
                    .filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().equals(fileName))
                    .findFirst();
        } catch (IOException e) {
            log.error("Error searching for image {}", imageId, e);
            return Optional.empty();
        }
    }

    private int cleanupDirectory(Path dir, LocalDateTime cutoff) {
        int deleted = 0;

        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.filter(Files::isRegularFile).toList()) {
                LocalDateTime fileTime = LocalDateTime.ofInstant(
                        Files.getLastModifiedTime(file).toInstant(),
                        java.time.ZoneId.systemDefault());

                if (fileTime.isBefore(cutoff)) {
                    Files.delete(file);
                    deleted++;
                }
            }

            // 删除空目录
            if (isDirectoryEmpty(dir)) {
                Files.delete(dir);
            }
        } catch (IOException e) {
            log.error("Error cleaning up directory {}", dir, e);
        }

        return deleted;
    }

    private boolean isDirectoryEmpty(Path dir) {
        try (Stream<Path> entries = Files.list(dir)) {
            return entries.findFirst().isEmpty();
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * 冷存储统计信息
     */
    public record StorageStats(
            long totalFiles,
            long totalSizeBytes,
            String storagePath
    ) {
        public long totalSizeMB() {
            return totalSizeBytes / (1024 * 1024);
        }
    }
}
