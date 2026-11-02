package com.shenqianye.samsara.server;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/**
 * 服务器级备份根：{@code <server>/backups/samsara/}。
 *
 * <p>只放备份与备份任务，不放运行时数据；与服务器根下旧的 {@code samsara/}
 * 区分开，避免和 {@code config/samsara}、{@code world/samsara} 混淆。
 */
public final class ServerBackupPaths {
    public static final String ROOT = "backups/samsara";
    private static final String LEGACY_ROOT = "samsara";
    private static final String[] LEGACY_ENTRIES = {
            "server-backups", "server-backup-jobs", "server-config-backups",
            "world-stack-backup", "world-stack-archive",
            "server-backup-state.json", "pending-server-backup.json",
            "pending-server-restore.json"
    };

    private ServerBackupPaths() {
    }

    public static Path root(Path serverRoot) {
        return serverRoot.resolve(ROOT).toAbsolutePath().normalize();
    }

    /**
     * 把旧 {@code <server>/samsara/} 下的备份条目迁移到 {@code backups/samsara/}。
     * 幂等；目标已存在时保留旧文件并记 WARN，不覆盖。{@code global/} 等运行时数据不动。
     */
    public static void migrateLegacyRoot(Path serverRoot) {
        Path legacy = serverRoot.resolve(LEGACY_ROOT).toAbsolutePath().normalize();
        if (!Files.isDirectory(legacy)) {
            return;
        }
        Path target = root(serverRoot);
        for (String name : LEGACY_ENTRIES) {
            Path src = legacy.resolve(name);
            if (!Files.exists(src)) {
                continue;
            }
            Path dst = target.resolve(name);
            try {
                if (!Files.exists(dst)) {
                    Files.createDirectories(target);
                    try {
                        Files.move(src, dst, StandardCopyOption.ATOMIC_MOVE);
                    } catch (Exception atomicFailure) {
                        Files.move(src, dst);
                    }
                    com.shenqianye.samsara.Samsara.LOGGER.info(
                            "[本体备份] 旧备份路径已迁移：{} → {}", src, dst);
                } else {
                    com.shenqianye.samsara.Samsara.LOGGER.warn(
                            "[本体备份] 旧备份路径目标已存在，保留旧文件不覆盖：{}（新路径 {}）",
                            src, dst);
                }
            } catch (Exception e) {
                com.shenqianye.samsara.Samsara.LOGGER.warn(
                        "[本体备份] 旧备份路径迁移失败，保留原文件：{}: {}", src, e.getMessage());
            }
        }
    }
}
