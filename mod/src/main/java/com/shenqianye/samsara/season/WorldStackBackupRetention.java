package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** 世界栈临时备份的保留策略：只清理自动备份，保留归档与撤销安全网。 */
public final class WorldStackBackupRetention {

    private WorldStackBackupRetention() {
    }

    /** 保留份数；读不到配置时回退默认值。 */
    public static int backupKeep() {
        try {
            return Math.max(0, ConfigManager.getInstance()
                    .integer(ConfigSchema.WEB, "backupKeep"));
        } catch (RuntimeException e) {
            return 5;
        }
    }

    /**
     * 只清理服务器级 {@code world-stack-backup}（导入/提升栈顶的临时回滚备份）。
     * {@code world-stack-archive} 是用户数据，永不自动删除。
     *
     * @return 实际删除的目录数
     */
    public static int prune(Path worldDir, Path backupRoot, int keep) {
        if (keep <= 0 || backupRoot == null || !Files.isDirectory(backupRoot)) {
            return 0;
        }
        List<Path> dirs = new ArrayList<>();
        try (Stream<Path> stream = Files.list(backupRoot)) {
            stream.filter(Files::isDirectory)
                    // pre-restore 是撤销前保留的当前状态，属于用户安全网，不参与自动清理。
                    .filter(path -> {
                        String name = path.getFileName().toString();
                        return !name.startsWith("pre-restore-") && !name.startsWith("restore-");
                    })
                    .forEach(dirs::add);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[世界栈] 读取备份目录失败 {}: {}", backupRoot, e.getMessage());
            return 0;
        }
        if (dirs.size() <= keep) {
            return 0;
        }
        dirs.sort(Comparator.comparingLong(WorldStackBackupRetention::lastModified).reversed());
        int removed = 0;
        for (int i = keep; i < dirs.size(); i++) {
            Path old = dirs.get(i);
            if (protectedBackup(worldDir, old)) {
                continue;
            }
            deleteRecursive(old);
            if (!Files.exists(old)) {
                removed++;
            }
        }
        if (removed > 0) {
            Samsara.LOGGER.info("[世界栈] 备份清理（保留最新 {} 份）: 删除 {} 个旧目录",
                    keep, removed);
        }
        return removed;
    }

    /**
     * pre-restore 是撤销/恢复的安全网，永不自动删除；时间线末端操作快照也保留，
     * 否则用户刚排队撤销、重启前恰好触发保留策略时会丢失撤销依据。
     */
    private static boolean protectedBackup(Path worldDir, Path dir) {
        String name = dir.getFileName().toString();
        if (name.startsWith(WorldStackTimeline.PRE_RESTORE_PREFIX)
                || name.startsWith(WorldStackTimeline.RESTORE_STAGING_PREFIX)) {
            return true;
        }
        Path operation = dir.resolve(WorldStackTimeline.FILE_NAME);
        if (!Files.isRegularFile(operation)) {
            return false;
        }
        WorldStackTimeline.Operation op = WorldStackTimeline.readOperation(worldDir, operation);
        return op != null && op.restorable && op.restoredAt == 0
                && WorldStackTimeline.headId(worldDir).equals(op.id);
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException e) {
            return 0L;
        }
    }

    private static void deleteRecursive(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (Stream<Path> stream = Files.walk(path)) {
            stream.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                }
            });
        } catch (IOException ignored) {
        }
    }
}
