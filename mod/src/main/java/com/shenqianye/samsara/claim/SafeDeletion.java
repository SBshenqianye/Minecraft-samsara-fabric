package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import com.shenqianye.samsara.rewind.McaRegion;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.rewind.RewindEngine.QueuedChunk;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * 安全删除（设计文档 §6.5 + §6.6）。
 * <p>
 * 按白名单删除区块：不在白名单内的区块会被清除并恢复为自然地形。
 * <ul>
 *   <li>dry-run：列出将被删除的区块，不实际操作</li>
 *   <li>backup：扫描磁盘上所有 .mca 文件，备份包含非白名单区块的文件</li>
 *   <li>execute：将非白名单区块加入待删队列（卸载时自动删除 MCA → 再走回来从种子重新生成）</li>
 *   <li>report：生成删除报告</li>
 * </ul>
 */
public class SafeDeletion {
    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    /** 获取主世界维度根目录 */
    private static Path getOverworldDimRoot(ServerLevel level) {
        Path worldDir = level.getServer()
                .getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return worldDir.resolve("dimensions/minecraft/overworld");
    }

    /** 获取主世界区域文件目录 */
    private static Path getOverworldRegionDir(ServerLevel level) {
        return getOverworldDimRoot(level).resolve("region");
    }

    /** 收集不在白名单中的已加载区块（用于 dryRun/report） */
    private static List<ChunkCoord> collectNonWhitelisted(ServerLevel level) {
        Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist(level.dimension());
        List<ChunkCoord> toDelete = new ArrayList<>();
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            ChunkPos pos = chunk.getPos();
            ChunkCoord cc = new ChunkCoord(pos.x(), pos.z());
            if (!whitelist.contains(cc)) {
                toDelete.add(cc);
            }
        });
        return toDelete;
    }

    /**
     * 收集磁盘上所有不在白名单中的区块。
     * <p>
     * 优化：先构建白名单索引（region 文件名 → 白名单区块数）。
     * 对于索引显示 0 白名单的 region 文件，读 header 后直接加入删除列表
     * （跳过 per-slot 的 HashSet.contains 检查，省掉 ~50万次查找）。
     */
    private static List<QueuedChunk> collectAllNonWhitelistedFromDisk(ServerLevel level) {
        List<QueuedChunk> toDelete = new ArrayList<>();

        List<Path> regionDirs = findAllDimensionRegionDirs(level);
        for (Path regionDir : regionDirs) {
            if (!Files.exists(regionDir)) continue;

            ResourceKey<Level> dim = dimensionFromPath(regionDir);
            Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist(dim);
            // 预构建索引：region 文件名 → 该文件内白名单区块数
            Map<String, Integer> wlIndex = AreaService.getInstance().getWhitelistCountByRegionFile(dim);
            boolean allProtected = whitelist.isEmpty();
            Path dimRoot = regionDir.getParent();

            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
                for (Path mcaFile : stream) {
                    try (RandomAccessFile raf = new RandomAccessFile(mcaFile.toFile(), "r")) {
                        if (raf.length() < 8192) continue;
                        String fileName = mcaFile.getFileName().toString();
                        String[] parts = fileName.replace(".mca", "").split("\\.");
                        if (parts.length < 3) continue;
                        int rx = Integer.parseInt(parts[1]);
                        int rz = Integer.parseInt(parts[2]);

                        byte[] header = new byte[4096];
                        raf.seek(0);
                        raf.readFully(header);

                        int wlCount = wlIndex.getOrDefault(fileName, 0);

                        if (wlCount == 0) {
                            // ★ 快路径：该 region 文件 0 个白名单 → 全部加入删除列表，跳过 HashSet
                            for (int slot = 0; slot < 1024; slot++) {
                                int off = slot * 4;
                                if ((((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF)) != 0) {
                                    toDelete.add(new QueuedChunk(rx * 32 + slot % 32, rz * 32 + slot / 32, dimRoot));
                                }
                            }
                        } else {
                            // 慢路径：混合文件，逐 slot 检查白名单
                            for (int slot = 0; slot < 1024; slot++) {
                                int off = slot * 4;
                                if ((((header[off] & 0xFF) << 16) | ((header[off + 1] & 0xFF) << 8) | (header[off + 2] & 0xFF)) != 0) {
                                    int cx = rx * 32 + slot % 32;
                                    int cz = rz * 32 + slot / 32;
                                    if (!whitelist.contains(new ChunkCoord(cx, cz))) {
                                        toDelete.add(new QueuedChunk(cx, cz, dimRoot));
                                    }
                                }
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (IOException ignored) {}
        }
        return toDelete;
    }

    /**
     * 从 region 目录路径推断维度 ResourceKey。
     * 路径格式：dimensions/<namespace>/<dimension>/region/
     * 通过 namespace+dimension 组合字符串匹配 Level.OVERWORLD.toString()。
     * NETHER/END 在 MC 26.2 中没有静态字段，通过 Level.OVERWORLD 的 registry 推断。
     */
    private static ResourceKey<Level> dimensionFromPath(Path regionDir) {
        Path dimDir = regionDir.getParent(); // dimensions/<ns>/<dim>
        if (dimDir == null) return Level.OVERWORLD;
        Path nsDir = dimDir.getParent(); // dimensions/<ns>
        if (nsDir == null) return Level.OVERWORLD;
        String ns = nsDir.getFileName().toString();
        String dimName = dimDir.getFileName().toString();
        String key = ns + ":" + dimName;

        if (key.equals(Level.OVERWORLD.toString())) return Level.OVERWORLD;

        // NETHER/END：Level 类中可能有同名静态字段（不同 MC 版本命名不同）
        // 优先尝试反射获取，失败则 fallback 到 overworld
        try {
            for (var field : Level.class.getDeclaredFields()) {
                if (field.getType() == ResourceKey.class) {
                    var val = field.get(null);
                    if (val instanceof ResourceKey<?> rk && rk.toString().equals(key)) {
                        @SuppressWarnings("unchecked")
                        ResourceKey<Level> result = (ResourceKey<Level>) rk;
                        return result;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 最终 fallback：所有非 overworld 维度都用 overworld 的白名单
        // （如果 NETHER/END 有独立旗帜会在此修复后生效）
        Samsara.LOGGER.debug("[samsara] 维度 '{}' 未找到对应 ResourceKey，使用 overworld 白名单", key);
        return Level.OVERWORLD;
    }

    /**
     * 扫描磁盘上所有维度的 .mca 文件，返回目录路径列表。
     */
    private static List<Path> findAllDimensionRegionDirs(ServerLevel level) {
        Path worldDir = level.getServer()
                .getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path dimsDir = worldDir.resolve("dimensions");
        List<Path> result = new ArrayList<>();

        if (!Files.exists(dimsDir)) return result;

        try {
            Files.list(dimsDir).forEach(nsDir -> {
                if (!Files.isDirectory(nsDir)) return;
                try {
                    Files.list(nsDir).forEach(dimDir -> {
                        if (!Files.isDirectory(dimDir)) return;
                        Path regionDir = dimDir.resolve("region");
                        if (Files.exists(regionDir)) result.add(regionDir);
                        Path entitiesDir = dimDir.resolve("entities");
                        if (Files.exists(entitiesDir)) result.add(entitiesDir);
                        Path poiDir = dimDir.resolve("poi");
                        if (Files.exists(poiDir)) result.add(poiDir);
                    });
                } catch (IOException ignored) {}
            });
        } catch (IOException ignored) {}

        Path oldRegion = worldDir.resolve("region");
        if (Files.exists(oldRegion) && !result.contains(oldRegion)) {
            result.add(oldRegion);
        }
        return result;
    }

    /**
     * Dry-run：列出将被删除的区块（不实际操作）。
     */
    public static String dryRun(ServerLevel level) {
        List<ChunkCoord> toDelete = collectNonWhitelisted(level);
        if (toDelete.isEmpty()) {
            return "dry-run：已加载区块中无需要删除的区块（全部受保护或无已加载区块）";
        }
        Set<String> regions = new HashSet<>();
        for (ChunkCoord c : toDelete) {
            regions.add((c.x >> 5) + "," + (c.z >> 5));
        }
        StringBuilder sb = new StringBuilder();
        sb.append("dry-run：将恢复 ").append(toDelete.size()).append(" 个区块为自然地形\n");
        sb.append("涉及 ").append(regions.size()).append(" 个区域文件\n");
        int show = Math.min(5, toDelete.size());
        sb.append("示例：\n");
        for (int i = 0; i < show; i++) {
            sb.append("  ").append(toDelete.get(i)).append("\n");
        }
        if (toDelete.size() > show) sb.append("  ... 共 ").append(toDelete.size()).append(" 个\n");
        sb.append("白名单保护区块：").append(AreaService.getInstance().getWhitelist(level.dimension()).size()).append(" 个");
        return sb.toString();
    }

    /**
     * Backup：扫描磁盘上所有 .mca 文件，备份包含非白名单区块的文件。
     * 按世界维度结构组织备份目录。
     */
    public static String backup(ServerLevel level) {
        Path backupDir = FabricLoader.getInstance().getConfigDir().resolve("samsara")
                .resolve("backups").resolve("delete_" + LocalDateTime.now().format(TS_FMT));
        try {
            Files.createDirectories(backupDir);
        } catch (IOException e) {
            return "备份失败：无法创建目录 " + e.getMessage();
        }

        Set<ChunkCoord> whitelist = AreaService.getInstance().getWhitelist(level.dimension());
        int backedUp = 0;
        int scanned = 0;

        List<Path> regionDirs = findAllDimensionRegionDirs(level);
        for (Path regionDir : regionDirs) {
            if (!Files.exists(regionDir)) continue;
            try (DirectoryStream<Path> stream = Files.newDirectoryStream(regionDir, "*.mca")) {
                for (Path mcaFile : stream) {
                    scanned++;
                    if (mcaContainsNonWhitelisted(mcaFile, whitelist)) {
                        Path worldDir = level.getServer()
                                .getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
                        Path relPath = worldDir.relativize(mcaFile);
                        Path dst = backupDir.resolve(relPath);
                        Files.createDirectories(dst.getParent());
                        Files.copy(mcaFile, dst, StandardCopyOption.REPLACE_EXISTING);
                        backedUp++;
                    }
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 扫描 {} 失败: {}", regionDir, e.getMessage());
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("备份完成：\n");
        sb.append("  扫描: ").append(scanned).append(" 个区域文件\n");
        sb.append("  备份: ").append(backedUp).append(" 个（包含非白名单区块）\n");
        sb.append("  路径: ").append(backupDir);
        return sb.toString();
    }

    /** 检查 .mca 文件是否包含非白名单区块 */
    private static boolean mcaContainsNonWhitelisted(Path mcaFile, Set<ChunkCoord> whitelist) {
        try (RandomAccessFile raf = new RandomAccessFile(mcaFile.toFile(), "r")) {
            if (raf.length() < 8192) return false;
            String fileName = mcaFile.getFileName().toString();
            String[] parts = fileName.replace(".mca", "").split("\\.");
            if (parts.length < 3) return false;
            int rx = Integer.parseInt(parts[1]);
            int rz = Integer.parseInt(parts[2]);

            byte[] header = new byte[4096];
            raf.seek(0);
            raf.readFully(header);

            for (int slot = 0; slot < 1024; slot++) {
                int off = slot * 4;
                int sectorOffset = ((header[off] & 0xFF) << 16)
                        | ((header[off + 1] & 0xFF) << 8)
                        | (header[off + 2] & 0xFF);
                if (sectorOffset != 0) {
                    int localX = slot % 32;
                    int localZ = slot / 32;
                    int cx = rx * 32 + localX;
                    int cz = rz * 32 + localZ;
                    if (!whitelist.contains(new ChunkCoord(cx, cz))) {
                        return true;
                    }
                }
            }
        } catch (Exception ignored) {}
        return false;
    }

    /**
     * Execute：将非白名单区块加入待删队列，并立即处理所有未加载的区块。
     * <p>
     * 原理（复用 RewindEngine 的 PendingDeleteQueue）：
     * <ol>
     *   <li>将非白名单区块坐标加入待删队列（持久化到 pending-delete.json）</li>
     *   <li>立即扫描并删除队列中所有当前未加载的区块（安全：不删内存中的区块）</li>
     *   <li>剩余已加载的区块（如 spawn chunks）留在队列中，等卸载时由 onChunkUnload 处理</li>
     * </ol>
     * <p>
     * 注意：execute 不包含备份操作。如需备份请先执行 /samsara debug claim delete backup。
     */
    public static String execute(ServerLevel level) {
        List<QueuedChunk> toDelete = collectAllNonWhitelistedFromDisk(level);
        if (toDelete.isEmpty()) {
            return "执行：磁盘上无需要删除的区块（全部受保护或无区域文件）";
        }

        int enqueued = RewindEngine.getInstance().enqueueClaimDelete(toDelete, level);

        // 立即处理所有当前未加载的区块（不等 onChunkUnload 被动触发）
        int drained = RewindEngine.getInstance().drainNonLoaded(level.getServer());

        // 剩余队列 = 已加载的区块（spawn chunks 等），等卸载时由 onChunkUnload 处理
        int remaining = RewindEngine.getInstance().pendingQueueCount();

        StringBuilder sb = new StringBuilder();
        sb.append("执行完成：\n");
        sb.append("  入队: ").append(enqueued).append(" 个区块\n");
        sb.append("  立即删除: ").append(drained).append(" 个（当前未加载）\n");
        if (remaining > 0) {
            sb.append("  等待卸载: ").append(remaining).append(" 个（spawn chunks 等永久加载区块）\n");
        }
        sb.append("  白名单保护: ").append(AreaService.getInstance().getWhitelist(level.dimension()).size()).append(" 个（不受影响）\n");
        sb.append("  → 已删除的区块：玩家再走回来时，MC 从种子重新生成自然地形\n");
        if (remaining > 0) {
            sb.append("  → 等待卸载的区块：区块自然卸载时自动删除\n");
        }
        return sb.toString();
    }

    /**
     * Report：生成删除报告。
     */
    public static String report(ServerLevel level) {
        List<ChunkCoord> toDelete = collectNonWhitelisted(level);
        StringBuilder sb = new StringBuilder();
        sb.append("删除报告：\n");
        sb.append("  白名单: ").append(AreaService.getInstance().getWhitelist(level.dimension()).size()).append(" 区块\n");
        sb.append("  待恢复: ").append(toDelete.size()).append(" 区块\n");
        sb.append("  示例: ");
        for (int i = 0; i < Math.min(10, toDelete.size()); i++) {
            if (i > 0) sb.append(", ");
            sb.append("(").append(toDelete.get(i).x).append(",").append(toDelete.get(i).z).append(")");
        }
        if (toDelete.size() > 10) sb.append(",...");
        sb.append("\n  区域目录: ").append(getOverworldRegionDir(level));
        return sb.toString();
    }
}
