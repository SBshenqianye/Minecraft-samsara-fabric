package com.shenqianye.samsara.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.whitelist.ChunkProtector;
import com.shenqianye.samsara.whitelist.WhitelistData;
import com.shenqianye.samsara.whitelist.WhitelistStore;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.LevelChunk;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * M2 时间倒流引擎 —— 档2「区块级波浪」+ 档3「方块级恢复」（提前自 M4d）。
 * <p>
 * 机制（遵守设计规范 §10/§11/§12 快照语义与先备份）：
 * <ol>
 *   <li>{@code /season start}（same-world 且 settings.rewindDurationMs &gt; 0）时启动：
 *       扫描主世界 region/ 下已存在区块，除去保护集（仅圈地 whitelist.json，见
 *       {@link ChunkProtector}）；每个未圈地块分配均匀分布在时长内的「倒流时刻」，
 *       持久化 rewind.json；同时把记录窗口冻结为本次倒流的恢复源（§11 快照语义）；</li>
 *   <li>运行期 tick：到「倒流时刻」的区块打标（待退场）并公告进度；档3 对加载区块
 *       按进度逐格恢复（懒加载 + 预算分摊，恢复读冻结恢复源，倒流期间新改动不受影响）；</li>
 *   <li>时长到期 → 传送全员 + 广播；随后物理删除未圈地区块：</li>
 *   <li><b>待删队列兜底（设计规范 §12.6）</b>：删除时仍<b>加载中</b>的区块不强制卸载、
 *       不删除，记入 {@code pending-delete.json} 队列 —— <b>卸载立即删除</b>；
 *       重启（SERVER_STARTED）与停服（SERVER_STOPPED）窗口兜底清空队列；
 *       备份先于删除（region/entities/poi，去重，备份至 {@code config/samsara/backups/}，
 *       不落存档目录，设计规范 §15）；删除在后台线程执行，避免主线程
 *       复制数 GB 区域文件卡服；</li>
 *   <li>档3 恢复在倒流结束后继续（预算内推进），直到该批 diff 全部应用后再
 *       <b>归档</b> rewind.json 至 {@code archive/}（保留记录，不删除）。</li>
 * </ol>
 * 单例（设计规范 §6）。
 */
public class RewindEngine {
    /** 无可倒流区块的错误消息（SeasonManager 据此回退为直接传送） */
    public static final String NO_REWINDABLE_MSG = "没有可倒流的区块（全部受保护或世界为空）";
    /** 备份保留策略（设计规范 §15，防磁盘膨胀）：purge/compact 各保留最新 N 份，更旧的自动删除 */
    private static final int KEEP_PURGE_BACKUPS = 1;
    private static final int KEEP_COMPACT_BACKUPS = 1;

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static RewindEngine instance;
    private final Path file;
    private final Path configDir;
    private RewindData data;
    /** 倒流完成回调（SeasonManager 注入：传送 + 广播） */
    private Runnable onComplete;
    private long lastTickMs;
    private long lastBroadcastMs;
    private long lastSaveMs;
    /** 后台删除线程（备份 + 清零区域文件，不占主线程） */
    private ExecutorService worker;
    /** 待删队列：完成时仍加载的区块，卸载即删；启动/停服兜底 */
    private final PendingDeleteQueue pendingQueue;

    private RewindEngine() {
        configDir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(configDir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = configDir.resolve("rewind.json");
        pendingQueue = new PendingDeleteQueue(configDir.resolve("pending-delete.json"));
        load();
        lastSaveMs = System.currentTimeMillis();
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

    /** 中止倒流时清除（不归档、不删除任何区块） */
    private void clearPersistence() {
        data = null;
        onComplete = null;
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            // 忽略
        }
    }

    /**
     * 归档已完成的倒流：先把最新状态落盘，再把 rewind.json 移入 {@code archive/} 保留
     * （而非删除），供审计/排查；内存状态照常清空（倒流已结束）。
     * 归档失败则回退为删除（避免残留 rewind.json 在重启后复活旧状态）。
     */
    private void archivePersistence() {
        if (data == null) {
            return;
        }
        save(); // 先落盘，保证归档的是最新进度（含待删队列标记）
        String ts = LocalDateTime.now(ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
        Path archiveDir = file.getParent().resolve("archive");
        Path dst = archiveDir.resolve("rewind_s" + data.seasonId + "_" + ts + ".json");
        boolean moved = false;
        try {
            Files.createDirectories(archiveDir);
            if (Files.exists(file)) {
                Files.move(file, dst, StandardCopyOption.REPLACE_EXISTING);
                moved = true;
                Samsara.LOGGER.info("[周目管理 samsara] 倒流完成归档: {} → {}", file.getFileName(),
                        dst.toAbsolutePath().normalize());
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] rewind.json 归档失败，回退为删除: {}", e.getMessage());
        }
        if (!moved) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }
        data = null;
        onComplete = null;
        Tier3Restore.reset();
    }

    // ---- 后台删除线程 ----

    private ExecutorService worker() {
        if (worker == null || worker.isShutdown()) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "samsara-delete");
                t.setDaemon(true);
                return t;
            });
        }
        return worker;
    }

    private void submitDeletion(Runnable r) {
        try {
            worker().execute(r);
        } catch (RejectedExecutionException e) {
            r.run(); // 停服过程中，同步兜底执行
        }
    }

    /** 备份保留策略：只保留最新 keep 个 prefix_* 备份目录，更旧的递归删除（防磁盘膨胀，设计规范 §15） */
    private static void pruneBackupDirs(Path backupsRoot, String prefix, int keep) {
        if (backupsRoot == null || !Files.isDirectory(backupsRoot)) {
            return;
        }
        try (var stream = Files.list(backupsRoot)) {
            List<Path> dirs = new ArrayList<>();
            for (Path p : (Iterable<Path>) stream::iterator) {
                if (Files.isDirectory(p) && p.getFileName().toString().startsWith(prefix)) {
                    dirs.add(p);
                }
            }
            dirs.sort(java.util.Comparator.comparing(p -> p.getFileName().toString()));
            for (int i = 0; i < dirs.size() - keep; i++) {
                Path old = dirs.get(i);
                try (var walk = Files.walk(old)) {
                    for (Path f : (Iterable<Path>) walk.sorted(java.util.Comparator.reverseOrder())::iterator) {
                        Files.deleteIfExists(f);
                    }
                } catch (IOException e) {
                    Samsara.LOGGER.warn("[周目管理 samsara] 备份清理删除失败 {}: {}", old, e.getMessage());
                }
                Samsara.LOGGER.info("[周目管理 samsara] 备份清理（保留最新 {} 个 {}*）: 删除 {}",
                        keep, prefix, old.getFileName());
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 备份清理失败: {}", e.getMessage());
        }
    }

    // ---- 生命周期 ----

    /**
     * 启动倒流：扫描区块 + 分配时刻表 + 冻结恢复源 + 持久化 + 公告。
     *
     * @return 概要文本；无可倒流区块时返回 {@link #NO_REWINDABLE_MSG}
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
            return NO_REWINDABLE_MSG;
        }
        // 冻结记录窗口为本次倒流的恢复源（设计规范 §11：窗口 = 上次删块完成后至本次倒流开始）
        DiffRecorder.freezeCurrent(seasonId);
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
        lastSaveMs = now;
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
        sb.append("保护集说明: 仅圈地区域(whitelist.json regions，/samsara debug claim 维护)受保护；未圈地全部视为未保留可删");
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
        // ① 到「倒流时刻」的区块打标（待退场）
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
            // 落盘节流（L8）：状态推进不必要每 5 秒全量写 rewind.json
            if (now - lastSaveMs >= 30_000) {
                save();
                lastSaveMs = now;
            }
            if (now - lastBroadcastMs >= 30_000) {
                lastBroadcastMs = now;
                broadcastProgress(server);
            }
            if (newlyMarked > 0) {
                Samsara.LOGGER.info("[周目管理 samsara] 倒流进度: {}/{} 区块到倒流时刻（本次 +{}）",
                        data.markedCount(), data.chunks.size(), newlyMarked);
            }
        }
        // ② 档3：对已加载区块逐格恢复（懒加载 + 预算分摊）。倒流期间与完成后都持续，
        //    直到恢复完毕（isFinished 见归档条件）——恢复读冻结的恢复源，倒流期间新改动不会自噬。
        try {
            Tier3Restore.restoreRound(server, data);
        } catch (Exception e) {
            Samsara.LOGGER.error("[周目管理 samsara] 档3 恢复异常: {}", e.getMessage());
        }
        // ③ 时长到期 → 传送 + 物理删除（完成后新记录窗口同时开启）
        if (now >= data.startMs + data.durationMs && !data.teleportPhaseDone) {
            data.teleportPhaseDone = true;
            save();
            lastSaveMs = now;
            DiffRecorder.resetWindow(); // 丢弃倒流期间改动，重新累计下个窗口
            Samsara.LOGGER.info("[周目管理 samsara] 倒流完成（{} 区块到倒流时刻），进入传送+删除阶段",
                    data.markedCount());
            Runnable cb = onComplete;
            if (cb != null) {
                broadcast(server, "✨ 周目 #" + data.seasonId + " 倒流完成：旧世界未圈地已退场，开始全员传送至新出生区！");
                cb.run();
            } else {
                broadcast(server, "✨ 周目 #" + data.seasonId + " 倒流完成：旧世界未圈地已退场（本次为只倒流模式，未传送）");
            }
            try {
                applyPhysicalDeletion(server);
            } catch (Exception e) {
                Samsara.LOGGER.error("[周目管理 samsara] 倒流完成物理删除异常: {}", e.getMessage());
            }
        }
        // ④ 归档兜底：传送完成 && 恢复完成 → 尝试再删已卸载的排队区块 → 归档
        if (data != null && data.teleportPhaseDone && Tier3Restore.isFinished(server, data)) {
            try {
                applyPhysicalDeletion(server);
            } catch (Exception e) {
                Samsara.LOGGER.error("[周目管理 samsara] 归档前追加删除异常: {}", e.getMessage());
            }
            archivePersistence();
        }
    }

    /**
     * 物理删除：仅处理「仍存在于磁盘」的已到倒流时刻区块（幂等）。
     * <p>
     * 删除前把区块从世界卸载逻辑中区别对待：<b>加载中的区块不删</b>，记入待删队列
     * （卸载即删，重启/停服兜底），避免"删了又回来"与玩家坠落；已卸载的区块在后台线程
     * 备份 + 清零（region/entities/poi）。
     */
    public synchronized void applyPhysicalDeletion(MinecraftServer server) {
        if (data == null) {
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path dimRoot = McaRegion.overworldDimensionRoot(worldRoot);
        List<int[]> marked = new ArrayList<>();
        for (RewindData.Chunk c : data.chunks) {
            if (c.marked && McaRegion.chunkExists(dimRoot, c.x, c.z)) {
                marked.add(new int[]{c.x, c.z});
            }
        }
        if (marked.isEmpty()) {
            return;
        }
        pendingQueue.beginCycle(dimRoot, configDir.resolve("backups"), data.seasonId);
        pruneBackupDirs(configDir.resolve("backups"), "purge_", KEEP_PURGE_BACKUPS);
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        List<int[]> toQueue = new ArrayList<>();
        List<int[]> toDeleteNow = new ArrayList<>();
        for (int[] c : marked) {
            boolean loaded = overworld != null && overworld.getChunkSource().getChunkNow(c[0], c[1]) != null;
            if (loaded) {
                toQueue.add(c);
            } else {
                toDeleteNow.add(c);
            }
        }
        if (!toQueue.isEmpty()) {
            pendingQueue.enqueue(toQueue, overworld);
            Samsara.LOGGER.info("[周目管理 samsara] 待删队列 +{} 个仍加载的区块（卸载即删/停服兜底），累计 {}",
                    toQueue.size(), pendingQueue.queuedCount());
        }
        if (!toDeleteNow.isEmpty()) {
            List<int[]> batch = toDeleteNow;
            submitDeletion(() -> {
                int n = pendingQueue.deleteAndBackup(batch);
                Samsara.LOGGER.info("[周目管理 samsara] 后台物理删除完成: {} 个已到倒流时刻区块清零（备份+清零）", n);
            });
        }
    }

    /** 区块卸载事件：在待删队列中的区块 → 立即删除（后台线程），不再等待停服 */
    public synchronized void onChunkUnload(ServerLevel overworld, LevelChunk chunk) {
        if (pendingQueue.isEmpty()) {
            return;
        }
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        if (!pendingQueue.contains(cx, cz)) {
            return;
        }
        Path dimRoot = pendingQueue.dimRoot();
        submitDeletion(() -> {
            if (pendingQueue.deleteChunkIfQueued(dimRoot, cx, cz) >= 0) {
                Samsara.LOGGER.info("[周目管理 samsara] 待删队列：区块 ({},{}) 已卸载，立即删除", cx, cz);
            }
        });
    }

    /** 服务器启动：初始化待删队列的删除上下文并兜底清空（加载中的跳过，遇卸载即删） */
    public synchronized void onServerStarted(MinecraftServer server) {
        if (pendingQueue.isEmpty()) {
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path dimRoot = McaRegion.overworldDimensionRoot(worldRoot);
        pendingQueue.beginCycle(dimRoot, configDir.resolve("backups"), pendingQueue.seasonIdOr(-1));
        int removed = pendingQueue.drain(server.getLevel(Level.OVERWORLD), true);
        if (removed > 0) {
            Samsara.LOGGER.info("[周目管理 samsara] 启动兜底：待删队列清空 {} 个已卸载区块", removed);
        }
    }

    /** 服务器停止：兜底删除待删队列 + 未删完区块；按恢复完成情况归档或保留；停服窗口区域文件瘦身 */
    public synchronized void onServerStopped(MinecraftServer server) {
        try {
            if (data != null) {
                applyPhysicalDeletion(server);
                if (data.teleportPhaseDone && Tier3Restore.isFinished(server, data)) {
                    archivePersistence();
                } else {
                    save(); // 未完成（或恢复未完）→ 保留 rewind.json，重启后继续
                }
            }
            if (!pendingQueue.isEmpty()) {
                int removed = pendingQueue.drain(server.getLevel(Level.OVERWORLD), false);
                if (removed > 0) {
                    Samsara.LOGGER.info("[周目管理 samsara] 停服兜底：待删队列清空 {} 个区块", removed);
                }
            }
        } finally {
            ExecutorService w = worker;
            worker = null;
            if (w != null) {
                w.shutdown();
                try {
                    w.awaitTermination(60, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            // 停服窗口（世界已保存、区域文件已关闭、删除线程已结束）：区域文件瘦身
            // —— 全空 .mca 直接删除 + 残留孤儿数据压缩重写（真正回收磁盘空间）
            // 备份位置：config/samsara/backups/（模组专属目录，不落存档内，设计规范 §15）
            try {
                McaRegion.CompactStats st = McaRegion.compact(
                        McaRegion.overworldDimensionRoot(server.getWorldPath(LevelResource.ROOT)),
                        configDir.resolve("backups"));
                if (st.filesScanned() > 0) {
                    Samsara.LOGGER.info("[周目管理 samsara] 停服瘦身: 扫描 {} · 全空删除 {} · 压缩重写 {} · 释放约 {} MB",
                            st.filesScanned(), st.filesDeleted(), st.filesRewritten(),
                            st.bytesFreed() / 1024 / 1024);
                }
                pruneBackupDirs(configDir.resolve("backups"), "compact_", KEEP_COMPACT_BACKUPS);
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 停服瘦身异常: {}", e.getMessage());
            }
        }
    }

    /**
     * 区域文件瘦身（debug 命令）：全空 .mca 直接删除；部分有效文件备份后压缩重写。
     * 运行中调用为尽力而为（被 MC 占用的文件跳过，停服窗口会自动再执行一次）。
     * 备份位置：config/samsara/backups/（设计规范 §15，不落存档目录）。
     */
    public synchronized String compactNow(MinecraftServer server) {
        McaRegion.CompactStats st = McaRegion.compact(
                McaRegion.overworldDimensionRoot(server.getWorldPath(LevelResource.ROOT)),
                configDir.resolve("backups"));
        pruneBackupDirs(configDir.resolve("backups"), "compact_", KEEP_COMPACT_BACKUPS);
        return "区域文件瘦身：扫描 " + st.filesScanned() + " 个 · 全空删除 " + st.filesDeleted()
                + " · 压缩重写 " + st.filesRewritten() + " · 释放约 " + (st.bytesFreed() / 1024 / 1024) + " MB"
                + "（被占用文件会在停服窗口自动重试；原文件已备份至 config/samsara/backups/compact_<时间戳>/，旧备份自动清理仅留 1 份）";
    }

    // ---- 查询/控制 ----

    public synchronized String status() {
        if (data == null) {
            return "当前没有进行中的时间倒流"
                    + (pendingQueue.isEmpty() ? "" : "（待删队列 " + pendingQueue.queuedCount() + " 个区块待卸载删除）");
        }
        long now = System.currentTimeMillis();
        long remain = data.startMs + data.durationMs - now;
        return "倒流进行中 · 周目 #" + data.seasonId
                + " · 总区块 " + data.chunks.size()
                + " · 已到倒流时刻 " + data.markedCount() + " · 保护 " + data.protectedCount
                + " · 剩余 " + (remain > 0 ? fmtDuration(remain) : "0（等待传送）")
                + " · 传送阶段" + (data.teleportPhaseDone ? "已完成" : "未开始")
                + (pendingQueue.isEmpty() ? "" : " · 待删队列 " + pendingQueue.queuedCount());
    }

    public synchronized String abort() {
        if (data == null) {
            return "当前没有进行中的时间倒流";
        }
        int seasonId = data.seasonId;
        clearPersistence();
        Tier3Restore.reset();
        pendingQueue.drop(); // 中止 = 一切保留；尚未删除的区块不再删除
        return "已中止倒流（周目 #" + seasonId + "）。所有区块保持原样，不会删除任何区块"
                + "（包括已到倒流时刻、已打退场标记但尚未删除的区块）"
                + "。世界维持当前状态；如需重新倒流请再次 /season start";
    }

    public synchronized boolean running() {
        return data != null;
    }

    /** 当前进行中倒流的赛季 id（无则 -1）；供 fake 注入选择恢复源 */
    public synchronized int currentSeasonId() {
        return data != null ? data.seasonId : -1;
    }

    /**
     * 立即物理删除已到倒流时刻的未保留区块（备份 + 清零，后台线程）。
     * 等价于倒流完成时的自动删除，但可随时显式触发。仍加载的区块入待删队列。
     */
    public synchronized String purgeNow(MinecraftServer server) {
        if (data == null || data.chunks.isEmpty()) {
            return "当前没有进行中的倒流/待删区块";
        }
        int marked = data.markedCount();
        if (marked == 0) {
            return "还没有区块到倒流时刻可删（倒流尚未推进）";
        }
        applyPhysicalDeletion(server);
        return "已提交删除 " + marked + " 个已到倒流时刻的未保留区块（备份+清零，重启后按当前版本重新生成）"
                + (pendingQueue.isEmpty() ? "" : "；仍加载的 " + pendingQueue.queuedCount() + " 个已入待删队列（卸载即删）");
    }

    /**
     * 立即对指定区块执行一次档3 恢复（读恢复源并按其时间进度恢复）。
     * 用于验证/懒加载补恢复：强制恢复某个区块，不依赖玩家站位。
     */
    public synchronized String restoreChunkNow(MinecraftServer server, int cx, int cz) {
        if (data == null) {
            return "当前没有进行中的倒流";
        }
        DiffStore store = Tier3Restore.restoreStore(configDir, data.seasonId);
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return "主世界不可用";
        }
        store.flush();
        double p = (double) (System.currentTimeMillis() - data.startMs) / data.durationMs;
        p = Math.max(0, Math.min(1, p));
        java.util.List<DiffStore.Edit> edits = store.readChunk(cx, cz);
        if (edits.isEmpty()) {
            return "区块 (" + cx + "," + cz + ") 无 diff 记录（恢复源 diff/s" + data.seasonId
                    + " 无此区块记录）";
        }
        edits.sort((a, b) -> Long.compare(b.timeMs, a.timeMs)); // 新改动优先
        int target = (int) Math.ceil(edits.size() * p);
        if (target > edits.size()) {
            target = edits.size();
        }
        int applied = 0;
        overworld.getChunk(cx, cz);
        for (int i = 0; i < Math.min(target, edits.size()); i++) {
            DiffStore.Edit e = edits.get(i);
            net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(e.x, e.y, e.z);
            if (!overworld.isLoaded(pos)) {
                continue;
            }
            net.minecraft.world.level.block.state.BlockState t =
                    net.minecraft.world.level.block.Block.stateById(e.stateId);
            if (t == null) {
                continue;
            }
            if (overworld.getBlockState(pos).equals(t)) {
                continue;
            }
            boolean wasRecording = DiffRecorder.recording();
            DiffRecorder.setEnabled(false);
            boolean ok;
            try {
                ok = overworld.setBlock(pos, t, 2);
            } finally {
                DiffRecorder.setEnabled(wasRecording);
            }
            if (ok) {
                applied++;
            }
        }
        return "区块 (" + cx + "," + cz + ") diff " + edits.size() + " 条 · 进度 " + (int) (p * 100)
                + "% · 应恢复 " + Math.min(target, edits.size()) + " · 实际恢复 " + applied + " 格";
    }

    // ---- 待删队列（设计规范 §12.6 兜底：加载区块不删，卸载即删；重启/停服清空） ----

    /**
     * 待删队列持久化（pending-delete.json）：完成时仍加载、不能立即删除的「已到倒流时刻」
     * 区块。删除时先备份（去重），再清零槽位；启动/停服窗口兜底清空；区块换载即删。
     */
    private static class PendingDeleteQueue {
        private final Path file;
        private Path dimRoot;
        private Path backupsRoot;
        private String backupRootName;
        private int seasonId = -1;
        /** 本周期已备份过的区域文件（相对 dimRoot），避免重复复制 */
        private final Set<String> backedUp = new LinkedHashSet<>();
        /** 待删区块 (x,z) */
        private final Map<Long, int[]> chunks = new LinkedHashMap<>();

        PendingDeleteQueue(Path file) {
            this.file = file;
            load();
        }

        @SuppressWarnings("unchecked")
        private void load() {
            if (!Files.exists(file)) {
                return;
            }
            try {
                Map<String, Object> m = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Map.class);
                if (m == null) {
                    return;
                }
                backupRootName = (String) m.get("backupRoot");
                Object sid = m.get("seasonId");
                if (sid instanceof Number n) {
                    seasonId = n.intValue();
                }
                Object bu = m.get("backedUp");
                if (bu instanceof java.util.Collection<?> c) {
                    for (Object o : c) {
                        if (o instanceof String s) {
                            backedUp.add(s);
                        }
                    }
                }
                Object cs = m.get("chunks");
                if (cs instanceof java.util.Collection<?> c) {
                    for (Object o : c) {
                        if (o instanceof java.util.Collection<?> pair && pair.size() == 2) {
                            Object[] it = pair.toArray();
                            if (it[0] instanceof Number && it[1] instanceof Number) {
                                int x = ((Number) it[0]).intValue();
                                int z = ((Number) it[1]).intValue();
                                chunks.put(key(x, z), new int[]{x, z});
                            }
                        }
                    }
                }
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 待删队列解析失败: {}", e.getMessage());
            }
        }

        private void save() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("seasonId", seasonId);
            m.put("backupRoot", backupRootName);
            m.put("backedUp", new ArrayList<>(backedUp));
            List<List<Integer>> cs = new ArrayList<>();
            for (int[] c : chunks.values()) {
                cs.add(java.util.List.of(c[0], c[1]));
            }
            m.put("chunks", cs);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(m), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Samsara.LOGGER.error("[周目管理 samsara] 待删队列写入失败: {}", e.getMessage());
            }
        }

        private static long key(int x, int z) {
            return ((long) x << 32) | (z & 0xFFFFFFFFL);
        }

        synchronized boolean isEmpty() {
            return chunks.isEmpty();
        }

        synchronized int queuedCount() {
            return chunks.size();
        }

        synchronized boolean contains(int x, int z) {
            return chunks.containsKey(key(x, z));
        }

        synchronized Path dimRoot() {
            return dimRoot;
        }

        synchronized int seasonIdOr(int def) {
            return seasonId >= 0 ? seasonId : def;
        }

        synchronized void beginCycle(Path dimRoot, Path backupsRoot, int seasonId) {
            this.dimRoot = dimRoot;
            this.backupsRoot = backupsRoot;
            this.seasonId = seasonId;
            if (backupRootName == null || backupRootName.isBlank()) {
                backupRootName = "purge_" + LocalDateTime.now(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            }
        }

        synchronized void enqueue(List<int[]> coords, ServerLevel overworld) {
            boolean changed = false;
            WhitelistData wl = WhitelistStore.getInstance().data();
            for (int[] c : coords) {
                // 入队时复查保护：已被圈地的区块不再删除
                if (overworld != null && ChunkProtector.isProtected(overworld, wl, c[0], c[1])) {
                    changed = true;
                    continue;
                }
                long k = key(c[0], c[1]);
                if (!chunks.containsKey(k)) {
                    chunks.put(k, c);
                    changed = true;
                }
            }
            if (changed) {
                save();
            }
        }

        /** 删除并备份一组区块（幂等：已清零的自动跳过）。可在线程外调用。 */
        synchronized int deleteAndBackup(List<int[]> coords) {
            int deleted = 0;
            for (int[] c : coords) {
                deleted += deleteOne(c[0], c[1]);
            }
            if (deleted > 0) {
                save();
            }
            return deleted;
        }

        /** 若区块仍在队列则删除；返回删除格数或 -1（不在队列） */
        synchronized int deleteChunkIfQueued(Path dimRoot, int cx, int cz) {
            if (!contains(cx, cz)) {
                return -1;
            }
            int n = deleteOne(cx, cz);
            chunks.remove(key(cx, cz));
            if (chunks.isEmpty()) {
                dropLocked();
            } else {
                save();
            }
            return n;
        }

        /** 逐块删除（已在 synchronized 内）：备份（去重）→ 清零槽位。0=已不存在。 */
        private int deleteOne(int cx, int cz) {
            if (dimRoot == null || !McaRegion.chunkExists(dimRoot, cx, cz)) {
                chunks.remove(key(cx, cz)); // 已不存在，直接剔除
                return 0;
            }
            backupRegionFiles(cx, cz);
            List<int[]> one = List.of(new int[]{cx, cz});
            return McaRegion.deleteChunks(dimRoot, one);
        }

        private void backupRegionFiles(int cx, int cz) {
            if (backupsRoot == null || backupRootName == null) {
                Samsara.LOGGER.warn("[周目管理 samsara] 待删队列备份目录未初始化，跳过备份");
                return;
            }
            McaRegion.RegionPos rp = McaRegion.RegionPos.ofChunk(cx, cz);
            String name = "r." + rp.rx() + "." + rp.rz() + ".mca";
            Path base = backupsRoot.resolve(backupRootName).resolve("dimensions").resolve("minecraft")
                    .resolve("overworld");
            for (String dir : new String[]{"region", "entities", "poi"}) {
                Path src = dimRoot.resolve(dir).resolve(name);
                if (!Files.exists(src)) {
                    continue;
                }
                String rel = dimRoot.relativize(src).toString().replace('\\', '/');
                if (backedUp.contains(rel)) {
                    continue;
                }
                try {
                    Path dst = base.resolve(rel);
                    Files.createDirectories(dst.getParent());
                    Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    backedUp.add(rel);
                } catch (IOException e) {
                    Samsara.LOGGER.error("[周目管理 samsara] 待删队列备份失败 {}: {}", src, e.getMessage());
                }
            }
        }

        /** 清空队列（删除文件，不再删除任何区块） */
        synchronized void drop() {
            chunks.clear();
            dropLocked();
        }

        private void dropLocked() {
            try {
                Files.deleteIfExists(file);
            } catch (IOException ignored) {
            }
        }

        /**
         * 兜底清空：遍历队列逐块删除；skipLoaded=true 时跳过仍加载的区块（卸载即删，启动时用）。
         *
         * @return 删除的区块数
         */
        synchronized int drain(ServerLevel overworld, boolean skipLoaded) {
            int removed = 0;
            WhitelistData wl = WhitelistStore.getInstance().data();
            var it = chunks.entrySet().iterator();
            while (it.hasNext()) {
                int[] c = it.next().getValue();
                boolean loaded = overworld != null && overworld.getChunkSource().getChunkNow(c[0], c[1]) != null;
                if (skipLoaded && loaded) {
                    continue; // 仍在内存，保持队列（卸载即删）
                }
                if (overworld != null && ChunkProtector.isProtected(overworld, wl, c[0], c[1])) {
                    it.remove(); // 已被圈地 → 保留不删
                    continue;
                }
                int n = deleteOne(c[0], c[1]);
                it.remove();
                removed++;
            }
            if (chunks.isEmpty()) {
                dropLocked();
            } else {
                save();
            }
            return removed;
        }
    }

    // ---- 内部 ----

    private record ScanResult(int total, int protectedCount, List<int[]> toRewind) {
    }

    private ScanResult scan(MinecraftServer server) {
        WhitelistData wl = WhitelistStore.getInstance().data();
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
                if (overworld != null && ChunkProtector.isProtected(overworld, wl, c[0], c[1])) {
                    protectedCount++;
                } else {
                    toRewind.add(c);
                }
            }
        }
        return new ScanResult(total, protectedCount, toRewind);
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
        broadcast(server, "🌀 世界倒流进行中：" + marked + "/" + total + " 区块已到倒流时刻（" + pct + "%），剩余约 "
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
