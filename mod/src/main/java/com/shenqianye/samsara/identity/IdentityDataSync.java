package com.shenqianye.samsara.identity;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

/**
 * 玩家数据文件交换（纯文件层，便于单测）。
 *
 * <p>身份数据根：{@code identity/playerdata/<dataUuid>.dat}、
 * {@code .advancements.json}、{@code .stats.json}；当前账号数据仍在原版
 * {@code world/playerdata/<uuid>.dat} 等目录。同步只在账号切换时发生。
 */
public final class IdentityDataSync {
    private final Path identityRoot;
    private final Path worldPlayerData;
    private final Path worldAdvancements;
    private final Path worldStats;
    private final IdentityJournal journal;

    public IdentityDataSync(Path identityRoot, Path worldPlayerData,
                            Path worldAdvancements, Path worldStats) {
        this.identityRoot = identityRoot.toAbsolutePath().normalize();
        this.worldPlayerData = worldPlayerData.toAbsolutePath().normalize();
        this.worldAdvancements = worldAdvancements.toAbsolutePath().normalize();
        this.worldStats = worldStats.toAbsolutePath().normalize();
        this.journal = new IdentityJournal(this.identityRoot.resolve("journal"));
    }

    public IdentityJournal journal() {
        return journal;
    }

    public Path identityData(UUID data) {
        return identityRoot.resolve("playerdata").resolve(data + ".dat");
    }

    public Path identityAdvancements(UUID data) {
        return identityRoot.resolve("playerdata").resolve(data + ".advancements.json");
    }

    public Path identityStats(UUID data) {
        return identityRoot.resolve("playerdata").resolve(data + ".stats.json");
    }

    public Path worldData(UUID uuid) {
        return worldPlayerData.resolve(uuid + ".dat");
    }

    public Path worldAdvancements(UUID uuid) {
        return worldAdvancements.resolve(uuid + ".json");
    }

    public Path worldStats(UUID uuid) {
        return worldStats.resolve(uuid + ".json");
    }

    public boolean dataExists(UUID data) {
        return data != null && Files.isRegularFile(identityData(data));
    }

    /** 当前账号文件 → identity/playerdata（账号退出/初始化）。 */
    public Result push(UUID current, UUID data, String group) {
        if (current == null || data == null) {
            return Result.business("当前账号或 data UUID 为空");
        }
        Path source = worldData(current);
        if (!Files.isRegularFile(source)) {
            return Result.business("当前账号玩家数据不存在：" + source.getFileName());
        }
        try {
            copy(source, identityData(data), current, data, group);
            copyIfExists(worldAdvancements(current), identityAdvancements(data), current, data, group);
            copyIfExists(worldStats(current), identityStats(data), current, data, group);
            return Result.success();
        } catch (RuntimeException e) {
            return Result.technical(e.getMessage());
        }
    }

    /** 只同步进度/统计（玩家本体由调用方序列化为 data 文件）。 */
    public Result pushMetadata(UUID current, UUID data, String group) {
        if (current == null || data == null) {
            return Result.business("当前账号或 data UUID 为空");
        }
        try {
            copyIfExists(worldAdvancements(current), identityAdvancements(data), current, data, group);
            copyIfExists(worldStats(current), identityStats(data), current, data, group);
            return Result.success();
        } catch (RuntimeException e) {
            return Result.technical(e.getMessage());
        }
    }

    /** identity/playerdata → 当前账号文件（账号切换）。 */
    public Result pull(UUID data, UUID current, String group) {
        if (current == null || data == null) {
            return Result.business("当前账号或 data UUID 为空");
        }
        Path source = identityData(data);
        if (!Files.isRegularFile(source)) {
            return Result.business("身份数据不存在：" + source.getFileName()
                    + "（拒绝覆盖当前账号）");
        }
        try {
            copy(source, worldData(current), data, current, group);
            copyIfExists(identityAdvancements(data), worldAdvancements(current),
                    data, current, group);
            copyIfExists(identityStats(data), worldStats(current), data, current, group);
            return Result.success();
        } catch (RuntimeException e) {
            return Result.technical(e.getMessage());
        }
    }

    /** 显式备份某一侧玩家数据（重绑/迁移用）。 */
    public static void backup(Path source, Path directory, String label) {
        if (source == null || !Files.isRegularFile(source) || directory == null) {
            return;
        }
        try {
            Files.createDirectories(directory);
            Files.copy(source, directory.resolve(label + "-" + source.getFileName()),
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        } catch (java.io.IOException e) {
            throw new RuntimeException("备份失败：" + e.getMessage(), e);
        }
    }

    private void copyIfExists(Path source, Path target, UUID from, UUID to, String group) {
        if (Files.isRegularFile(source)) {
            copy(source, target, from, to, group);
        }
    }

    private void copy(Path source, Path target, UUID from, UUID to, String group) {
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        String checksum = IdentityJournal.sha256(source);
        Path journalFile = journal.prepare(group, from, to, target, backup, checksum);
        try {
            IdentityJournal.copyAtomic(source, target, backup);
            journal.commit(journalFile);
        } catch (RuntimeException e) {
            // 回滚由启动恢复处理；当前调用直接尝试恢复备份，尽量保持可用。
            if (Files.isRegularFile(backup)) {
                try {
                    IdentityJournal.copyAtomic(backup, target, null);
                } catch (RuntimeException ignored) {
                }
            }
            throw e;
        }
    }

    /** 同步结果：业务失败不覆盖数据，技术失败保留 journal/备份。 */
    public record Result(boolean ok, boolean businessFailure, String message, boolean blocked) {
        public static Result success() {
            return new Result(true, false, "", false);
        }

        public static Result business(String message) {
            return new Result(false, true, message, false);
        }

        public static Result technical(String message) {
            return new Result(false, false, message, true);
        }
    }
}
