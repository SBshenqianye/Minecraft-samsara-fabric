package com.shenqianye.samsara.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.season.SeasonData;
import com.shenqianye.samsara.whitelist.ChunkProtector;
import com.shenqianye.samsara.whitelist.WhitelistData;
import com.shenqianye.samsara.whitelist.WhitelistStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * M2 时间倒流引擎 —— 档2「区块级波浪」（设计文档 §12.2 档2，提前自 M4d）。
 * <p>
 * 机制（安全优先，遵守 §10「禁止热删，仅停服维护窗口执行」与 §6.5「先备份」）：
 * <ol>
 *   <li>{@code /season start}（same-world 且 settings.rewindDurationMs &gt; 0）时启动：
 *       扫描主世界 region/ 下已存在区块，除去保护集（世界出生圈 + 各周目出生区 +
 *       分组出生点 + whitelist.json 配置区域，见 {@link ChunkProtector}）；
 *       每个未圈地块分配均匀分布在时长内的"倒流时刻"，持久化 rewind.json；</li>
 *   <li>运行期 tick：到点区块打标（marked）并公告进度 —— 倒流"幕布"以时刻表推进；
 *       物理删除<b>不</b>在运行期执行（热删易坏档）；</li>
 *   <li>时长到期 → 执行传入的完成回调（SeasonManager 传送全员 + 广播）；</li>
 *   <li>停服窗口（SERVER_STOPPED，世界已保存、区域文件已关闭）：对已标记区块
 *       执行物理删除 —— 先备份 region/entities/poi 区域文件到
 *       {@code backups/purge_&lt;时间戳&gt;/}，再把对应区块槽位置/时间戳清零，
 *       下次启动时这些区块按当前版本重新生成。</li>
 * </ol>
 * 单例（设计规范 §6）。
 */
public class RewindEngine {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RewindEngine instance;
    private final Path file;
    private RewindData data;
    /** 倒流完成回调（SeasonManager 注入：传送 + 广播） */
    private Runnable onComplete;
    private long lastTickMs;
    private long lastBroadcastMs;

    private RewindEngine() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("rewind.json");
        load();
    }

    public static synchronized RewindEngine getInstance() {
        if (instance == null) {
            instance = new RewindEngine();
        }
        return instance;
    }

    private void load() {
        if (Files.exists(file)) {
            try {
                data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), RewindData.class);
                if (data == null) {
                    data = null;
                    return;
                }
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] rewind.json 解析失败，倒流状态重置: {}", e.getMessage());
                data = null;
            }
        } else {
            data = null;
        }
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.error("[周目管理 samsara] rewind.json 写入失败: {}", e.getMessage());
        }
    }

    private void clearPersistence() {
        data = null;
        onComplete = null;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 忽略
        }
    }

    // ---- 生命周期 ----

    /**
     * 启动倒流：扫描区块 + 分配时刻表 + 持久化 + 公告。
     *
     * @return 概要文本（dry-run 与真实启动共用扫描逻辑）
     */
    public synchronized String start(MinecraftServer server, int seasonId, long durationMs, Runnable onComplete) {
        if (data != null) {
            return "已有进行中的倒流（周目 #" + data.seasonId + "），请先 /samsara debug rewind abort";
        }
        if (durationMs <= 0) {
            return "倒流时长必须 &gt; 0";
        }
        ScanResult scan = scan(server);
        if (scan.toRewind.isEmpty()) {
            return "没有可倒流的区块（全部受保护或世界为空）";
        }
        Random rnd = new Random();
        long now = System.currentTimeMillis();
        RewindData d = new RewindData();
        d.seasonId = seasonId;
        d.startMs = now;
        d.durationMs = durationMs;
        d.totalCount = scan.total;
        d.protectedCount = scan.protectedCount;
        for (int[] c : scan.toRewind) {
            long at = now + (long) (rnd.nextDouble() * durationMs);
            d.chunks.add(new RewindData.Chunk(c[0], c[1], at));
        }
        data = d;
        this.onComplete = onComplete;
        save();
        broadcast(server, "⏳ 周目 #" + seasonId + " 开幕仪式：世界倒流即将开始（时长 "
                + fmtDuration(durationMs) + "）。未圈地区块将在期间依次退场，倒流完成后全员传送至新出生区。");
        return summaryText(d);
    }

    /** dry-run 预览：只扫描与统计，不落盘、不启动 */
    public synchronized String preview(MinecraftServer server) {
        ScanResult scan = scan(server);
        StringBuilder sb = new StringBuilder();
        sb.append("倒流 dry-run 预览（未启动，仅统计）：\n");
        if (data != null) {
            sb.append("⚠ 当前已有进行中的倒流（周目 #").append(data.seasonId).append("），预览将按当前周目+保护集估算\n");
        }
        sb.append("主世界已存在区块: ").append(scan.total)
                .append(" · 受保护（不倒流）: ").append(scan.protectedCount)
                .append(" · 将被倒流: ").append(scan.toRewind.size()).append('\n');
        if (!scan.toRewind.isEmpty()) {
            sb.append("最早倒流的 5 个区块示例: ");
            List<int[]> head = scan.toRewind.subList(0, Math.min(5, scan.toRewind.size()));
            List<String> parts = new ArrayList<>();
            for (int[] c : head) {
                parts.add("(" + c[0] + ", " + c[1] + ")");
            }
            sb.append(String.join(" ", parts)).append('\n');
        }
        sb.append("保护集说明: 世界出生点保护圈(whitelist.json) + 各周目出生中心/分组出生点 + 配置区域");
        return sb.toString();
    }

    /** 服务端 tick（由 Samsara 注册的 END_SERVER_TICK 驱动；内部 5 秒节流） */
    public synchronized void tick(MinecraftServer server) {
        if (data == null) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastTickMs < 5000) {
            return;
        }
        lastTickMs = now;
        if (now < data.startMs) {
            return;
        }
        boolean changed = false;
        int newlyMarked = 0;
        for (RewindData.Chunk c : data.chunks) {
            if (!c.marked && now >= c.rewindAtMs) {
                c.marked = true;
                newlyMarked++;
                changed = true;
            }
        }
        if (changed) {
            save();
            if (now - lastBroadcastMs >= 30_000) {
                lastBroadcastMs = now;
                broadcastProgress(server);
            }
            if (newlyMarked > 0) {
                Samsara.LOGGER.info("[周目管理 samsara] 倒流进度: {}/{} 区块到点（本次 +{}）",
                        data.markedCount(), data.chunks.size(), newlyMarked);
            }
        }
        // 时长到期 → 完成回调（传送阶段）
        if (now >= data.startMs + data.durationMs && !data.teleportPhaseDone) {
            data.teleportPhaseDone = true;
            save();
            Samsara.LOGGER.info("[周目管理 samsara] 倒流完成（{} 区块到点），进入传送阶段",
                    data.markedCount());
            broadcast(server, "✨ 周目 #" + data.seasonId + " 倒流完成：旧世界未圈地已退场，开始全员传送至新出生区！");
            Runnable cb = onComplete;
            if (cb != null) {
                cb.run();
            }
        }
    }

    /** 停服窗口物理删除：备份 + 清零已标记区块（region/entities/poi 三个区域文件） */
    public synchronized void applyPhysicalDeletion(MinecraftServer server) {
        if (data == null || data.chunks.isEmpty()) {
            return;
        }
        List<int[]> marked = new ArrayList<>();
        for (RewindData.Chunk c : data.chunks) {
            if (c.marked) {
                marked.add(new int[]{c.x, c.z});
            }
        }
        if (marked.isEmpty()) {
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path dimRoot = McaRegion.overworldDimensionRoot(worldRoot);
        String ts = LocalDateTime.now(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path backupDir = worldRoot.getParent().resolve("backups").resolve("purge_" + ts);
        // 备份所有涉及的区域文件（region/entities/poi），仅存在的文件
        Map<Path, Boolean> touched = new HashMap<>();
        for (int[] c : marked) {
            McaRegion.RegionPos rp = McaRegion.RegionPos.ofChunk(c[0], c[1]);
            String name = "r." + rp.rx() + "." + rp.rz() + ".mca";
            for (String dir : new String[]{"region", "entities", "poi"}) {
                Path src = dimRoot.resolve(dir).resolve(name);
                if (Files.exists(src)) {
                    touched.put(src, true);
                }
            }
        }
        int backedUp = 0;
        int deletedChunks = 0;
        try {
            for (Path src : touched.keySet()) {
                // 备份到 backupDir/dimensions/minecraft/overworld/<region|entities|poi>/<file>
                Path dst = backupDir.resolve("dimensions").resolve("minecraft").resolve("overworld")
                        .resolve(dimRoot.relativize(src));
                Files.createDirectories(dst.getParent());
                Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                backedUp++;
            }
            deletedChunks = McaRegion.deleteChunks(dimRoot, marked);
        } catch (IOException e) {
            Samsara.LOGGER.error("[周目管理 samsara] 倒流物理删除失败（已备份部分文件）: {}", e.getMessage());
            return;
        }
        Samsara.LOGGER.info("[周目管理 samsara] 倒流物理删除完成: {} 个已标记区块清零（涉及 {} 个区域文件，备份至 {}）",
                deletedChunks, backedUp, backupDir.toAbsolutePath().normalize());
        // 保留倒流进度：未到点区块继续（重启后按真实时间推进；停服时间也算倒流时长）
        if (data.markedCount() >= data.chunks.size()) {
            clearPersistence();
        } else {
            save();
        }
    }

    // ---- 查询/控制 ----

    public synchronized String status() {
        if (data == null) {
            return "当前没有进行中的时间倒流";
        }
        long now = System.currentTimeMillis();
        long remain = data.startMs + data.durationMs - now;
        return "倒流进行中 · 周目 #" + data.seasonId
                + " · 总区块 " + data.chunks.size()
                + " · 已到点 " + data.markedCount() + " · 保护 " + data.protectedCount
                + " · 剩余 " + (remain > 0 ? fmtDuration(remain) : "0（等待传送）")
                + " · 传送阶段" + (data.teleportPhaseDone ? "已完成" : "未开始");
    }

    public synchronized String abort() {
        if (data == null) {
            return "当前没有进行中的时间倒流";
        }
        int seasonId = data.seasonId;
        int pending = data.chunks.size() - data.markedCount();
        clearPersistence();
        return "已中止倒流（周目 #" + seasonId + "），未到点区块 " + pending + " 个保留；已到点区块将在停服时删除（如需保留请在停服前重建）";
    }

    public synchronized boolean running() {
        return data != null;
    }

    // ---- 内部 ----

    private record ScanResult(int total, int protectedCount, List<int[]> toRewind) {
    }

    private ScanResult scan(MinecraftServer server) {
        WhitelistData wl = WhitelistStore.getInstance().data();
        SeasonData sd = getSeasonData();
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        Path dimRoot = McaRegion.overworldDimensionRoot(server.getWorldPath(LevelResource.ROOT));
        Path regionDir = dimRoot.resolve("region");
        List<int[]> toRewind = new ArrayList<>();
        int total = 0;
        int protectedCount = 0;
        for (Path f : McaRegion.listRegionFiles(regionDir)) {
            List<int[]> exist = McaRegion.scanExisting(f);
            for (int[] c : exist) {
                total++;
                if (overworld != null && ChunkProtector.isProtected(overworld, wl, sd, c[0], c[1])) {
                    protectedCount++;
                } else {
                    toRewind.add(c);
                }
            }
        }
        return new ScanResult(total, protectedCount, toRewind);
    }

    private SeasonData getSeasonData() {
        return com.shenqianye.samsara.season.SeasonManager.getInstance().data();
    }

    private String summaryText(RewindData d) {
        return "倒流已启动 · 周目 #" + d.seasonId
                + " · 时长 " + fmtDuration(d.durationMs)
                + " · 主世界已存在区块 " + d.totalCount
                + " · 受保护 " + d.protectedCount
                + " · 将倒流 " + d.chunks.size() + " 个区块"
                + "\n倒流完成后将自动传送全员至新出生区";
    }

    private void broadcastProgress(MinecraftServer server) {
        int marked = data.markedCount();
        int total = data.chunks.size();
        int pct = total == 0 ? 100 : Math.min(100, marked * 100 / total);
        long remain = data.startMs + data.durationMs - System.currentTimeMillis();
        broadcast(server, "🌀 世界倒流进行中：" + marked + "/" + total + " 区块已退场（" + pct + "%），剩余约 "
                + (remain > 0 ? fmtDuration(remain) : "0"));
    }

    private void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("[周目管理] " + msg), false);
        Samsara.LOGGER.info("[周目管理 samsara] {}", msg);
    }

    private static String fmtDuration(long ms) {
        long s = ms / 1000;
        if (s < 60) {
            return s + " 秒";
        }
        long m = s / 60;
        if (m < 60) {
            return m + " 分钟";
        }
        long h = m / 60;
        if (h < 24) {
            return h + " 小时";
        }
        return (h / 24) + " 天 " + (h % 24) + " 小时";
    }
}