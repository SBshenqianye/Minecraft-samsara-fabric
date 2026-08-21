package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;

import java.io.IOException;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 安全删除（设计文档 §6.5 + §6.6）。
 * <p>
 * 按白名单删除区块：不在白名单内的区块会被删除。
 * <ul>
 *   <li>dry-run：列出将被删除的区块，不实际操作</li>
 *   <li>backup：备份 .mca 文件</li>
 *   <li>execute：执行删除（标记待卸载）</li>
 *   <li>report：生成报告</li>
 * </ul>
 */
public class SafeDeletion {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /**
     * Dry-run：列出将被删除的区块（不实际操作）。
     */
    public static String dryRun(ServerLevel level) {
        Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist();
        List<ChunkCoord> toDelete = new ArrayList<>();
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            ChunkPos pos = chunk.getPos();
            ChunkCoord cc = new ChunkCoord(pos.x(), pos.z());
            if (!whitelist.contains(cc)) {
                toDelete.add(cc);
            }
        });
        if (toDelete.isEmpty()) {
            return "dry-run：已加载区块中无需要删除的区块（全部受保护或无已加载区块）";
        }
        Set<String> regions = new HashSet<>();
        for (ChunkCoord c : toDelete) {
            regions.add((c.x >> 5) + "," + (c.z >> 5));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("dry-run：将删除 ").append(toDelete.size()).append(" 个区块\n");
        sb.append("涉及 ").append(regions.size()).append(" 个区域文件\n");
        int show = Math.min(5, toDelete.size());
        sb.append("示例：\n");
        for (int i = 0; i < show; i++) {
            sb.append("  ").append(toDelete.get(i)).append("\n");
        }
        if (toDelete.size() > show) {
            sb.append("  ... 共 ").append(toDelete.size()).append(" 个\n");
        }
        sb.append("白名单保护区块：").append(whitelist.size()).append(" 个");
        return sb.toString();
    }

    /**
     * Backup：备份将被删除的区块对应的区域文件。
     */
    public static String backup(ServerLevel level) {
        Path backupDir = FabricLoader.getInstance().getConfigDir().resolve("samsara")
                .resolve("backups").resolve("delete_" + LocalDateTime.now().format(TS_FMT));
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            return "备份失败：无法创建目录 " + e.getMessage();
        }

        // 区域文件目录 = world/region/（主世界）
        Path worldDir = level.getServer().getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT).toAbsolutePath().normalize();
        Path regionDir = worldDir.resolve("region");
        if (!Files.exists(regionDir)) return "备份失败：区域目录不存在 " + regionDir;

        Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist();
        Set<String> regionFilesToBackup = new LinkedHashSet<>();
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            ChunkPos pos = chunk.getPos();
            ChunkCoord cc = new ChunkCoord(pos.x(), pos.z());
            if (!whitelist.contains(cc)) {
                int rx = pos.x() >> 5;
                int rz = pos.z() >> 5;
                regionFilesToBackup.add("r." + rx + "." + rz + ".mca");
            }
        });

        int backedUp = 0;
        for (String rf : regionFilesToBackup) {
            Path src = regionDir.resolve(rf);
            if (Files.exists(src)) {
                try {
                    Path dst = backupDir.resolve(rf);
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    backedUp++;
                } catch (IOException e) {
                    Samsara.LOGGER.warn("[周目管理 samsara] 备份 {} 失败: {}", rf, e.getMessage());
                }
            }
        }
        return "备份完成：" + backedUp + " 个区域文件 → " + backupDir;
    }

    /**
     * Execute：报告将被删除的区块（实际删除需停服后手动操作区域文件）。
     * 安全起见，服务端运行时不直接删除。
     */
    public static String execute(ServerLevel level) {
        Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist();
        List<ChunkCoord> toDelete = new ArrayList<>();
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            ChunkPos pos = chunk.getPos();
            ChunkCoord cc = new ChunkCoord(pos.x(), pos.z());
            if (!whitelist.contains(cc)) {
                toDelete.add(cc);
            }
        });
        if (toDelete.isEmpty()) {
            return "执行：已加载区块中无需要删除的区块";
        }
        return "待删除 " + toDelete.size() + " 个区块。"
                + "服务端运行时不直接删除（安全约束 §6.6）。"
                + "请先 /samsara debug delete backup 备份，然后停服后手动删除区域文件。";
    }

    /**
     * Report：生成删除报告。
     */
    public static String report(ServerLevel level) {
        Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist();
        List<int[]> toDelete = new ArrayList<>();
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            ChunkPos pos = chunk.getPos();
            ChunkCoord cc = new ChunkCoord(pos.x(), pos.z());
            if (!whitelist.contains(cc)) {
                toDelete.add(new int[]{pos.x(), pos.z()});
            }
        });
        StringBuilder sb = new StringBuilder();
        sb.append("删除报告：\n");
        sb.append("  白名单: ").append(whitelist.size()).append(" 区块\n");
        sb.append("  待删除: ").append(toDelete.size()).append(" 区块\n");
        sb.append("  示例: ");
        for (int i = 0; i < Math.min(10, toDelete.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(").append(toDelete.get(i)[0]).append(",").append(toDelete.get(i)[1]).append(")");
        }
        if (toDelete.size() > 10) sb.append(",...");
        sb.append("\n  约束（§6.6）：删除前必须 dry-run；双保险（.mca 备份）");
        return sb.toString();
    }
}
