package com.shenqianye.samsara.rewind;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * M2 时间倒流引擎 —— 「区块级波浪退场」+ 「方块级逐格恢复」（提前自 M4d）。
 * <p>
 * 机制（遵守设计规范 §10/§11/§12 快照语义与先备份）：
 * <ol>
 *   <li>{@code /season start}（same-world 且 settings.rewindDurationMs &gt; 0）时启动：
 *       扫描主世界 region/ 下已存在区块，除去保护集（旗帜圈地白名单）；
 *       每个未圈地块分配均匀分布在时长内的「倒流时刻」，
 *       持久化 rewind.json；同时把记录窗口冻结为本次倒流的恢复源（§11 快照语义）；</li>
 *   <li>运行期 tick：到「倒流时刻」的区块打标（待退场）并公告进度；对加载区块
 *       按进度逐格恢复（懒加载 + 预算分摊，恢复读冻结恢复源，倒流期间新改动不受影响）；</li>
 *   <li>时长到期 → 传送全员 + 广播；随后物理删除未圈地区块：</li>
 *   <li><b>待删队列兜底（设计规范 §12.6）</b>：删除时仍<b>加载中</b>的区块不强制卸载、
 *       不删除，记入 {@code pending-delete.json} 队列 —— <b>卸载立即删除</b>；
 *       重启（SERVER_STARTED）与停服（SERVER_STOPPED）窗口兜底清空队列；
 *       备份先于删除（region/entities/poi，去重，备份至 {@code config/samsara/backups/}，
 *       不落存档目录，设计规范 §15）；删除在后台线程执行，避免主线程
 *       复制数 GB 区域文件卡服；</li>
 *   <li>逐格恢复在倒流结束后继续（预算内推进），直到该批 diff 全部应用后再
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
        DiffRestore.reset();
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

    /**
     * 从 ResourceKey.toString() 提取 namespace 和 path。
     * MC 26.2 的 toString() 返回 "ResourceKey[minecraft:overworld]" 格式，
     * 需要提取 "minecraft" 和 "overworld"。
     *
     * @return [namespace, path] 或 null（解析失败）
     */
    private static String[] parseResourceKey(String keyStr) {
        // 找最后一个冒号（namespace:path）
        int colonIdx = keyStr.lastIndexOf(':');
        if (colonIdx < 0) return null;
        // ns: 从 [ 或空格后到冒号前
        int nsStart = Math.max(keyStr.lastIndexOf('[', colonIdx), keyStr.lastIndexOf(' ', colonIdx)) + 1;
        String ns = keyStr.substring(nsStart, colonIdx);
        // dim: 从冒号后到 ] 或 > 或字符串末尾
        int dimEnd = keyStr.length();
        for (int i = colonIdx + 1; i < keyStr.length(); i++) {
            char c = keyStr.charAt(i);
            if (c == ' ' || c == ']' || c == '>') { dimEnd = i; break; }
        }
        String dim = keyStr.substring(colonIdx + 1, dimEnd);
        return new String[]{ns, dim};
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
        sb.append("保护集说明: 旗帜圈地白名单保护；未圈地全部视为未保留可删");
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
        // ② 对已加载区块逐格恢复（懒加载 + 预算分摊）。倒流期间与完成后都持续，
        //    直到恢复完毕（isFinished 见归档条件）——恢复读冻结的恢复源，倒流期间新改动不会自噬。
        try {
            DiffRestore.restoreRound(server, data);
        } catch (Exception e) {
            Samsara.LOGGER.error("[周目管理 samsara] 逐格恢复异常: {}", e.getMessage());
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
        if (data != null && data.teleportPhaseDone && DiffRestore.isFinished(server, data)) {
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

    /** 卸载区块缓冲区：攒够一批后批量删除（避免逐个读写 MCA header） */
    private final List<QueuedChunk> unloadBuffer = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** 区块卸载事件：攒到缓冲区，由 tick 定时批量处理 */
    public void onChunkUnload(ServerLevel level, LevelChunk chunk) {
        if (pendingQueue.isEmpty()) {
            return;
        }
        int cx = chunk.getPos().x();
        int cz = chunk.getPos().z();
        // 根据 ServerLevel 计算 dimRoot 路径
        Path dimRoot = null;
        String[] nsDim = parseResourceKey(level.dimension().toString());
        if (nsDim != null) {
            Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            dimRoot = worldRoot.resolve("dimensions").resolve(nsDim[0]).resolve(nsDim[1]);
        }
        if (!pendingQueue.contains(cx, cz, dimRoot)) {
            return;
        }
        // 从队列移除并加入缓冲区（避免重复处理）
        QueuedChunk removed = pendingQueue.removeChunk(cx, cz, dimRoot);
        if (removed != null) {
            unloadBuffer.add(removed);
        }
    }

    /**
     * 处理卸载缓冲区：按 dimRoot 分组批量删除。
     * 由 tick() 每 20 tick（1秒）调用一次。
     */
    public void flushUnloadBuffer(MinecraftServer server) {
        if (unloadBuffer.isEmpty()) return;
        // 取出所有缓冲项
        List<QueuedChunk> batch = new ArrayList<>(unloadBuffer);
        unloadBuffer.clear();
        // 按 dimRoot 分组批量删除
        var wlIndex = pendingQueue.buildWhitelistIndex();
        pendingQueue.batchDeleteByDimRoot(batch, wlIndex);
        com.shenqianye.samsara.claim.SafeDeletion.dbg("[flushUnloadBuffer] 批量删除 " + batch.size() + " 个卸载区块");
    }

    /**
     * 圈地删除：将非白名单区块加入待删队列（不检查圈地保护，由调用方保证）。
     * 区块在自然卸载时由 {@link #onChunkUnload} 触发 MCA 清零。
     * 每个区块可携带自己的 dimRoot（多维度混合删除时用）。
     *
     * @param items  待删区块列表（QueuedChunk 含坐标和 dimRoot）
     * @return 入队的区块数
     */
    public synchronized int enqueueClaimDelete(List<QueuedChunk> items, ServerLevel level) {
        Path worldRoot = level.getServer().getWorldPath(LevelResource.ROOT);
        Path dimRoot = McaRegion.overworldDimensionRoot(worldRoot);
        pendingQueue.beginCycle(dimRoot, configDir.resolve("backups"), pendingQueue.seasonIdOr(-1));
        return pendingQueue.enqueueNoCheck(items);
    }

    /**
     * 立即处理队列中所有当前未加载的区块（安全：只删不在内存中的区块）。
     * 维度感知：检查区块在其所属维度中是否仍加载，不会误删其他维度中正在加载的区块。
     * 由 execute 调用，避免依赖 onChunkUnload 的被动等待。
     *
     * @return 实际删除的区块数
     */
    public synchronized int drainNonLoaded(MinecraftServer server) {
        return pendingQueue.drainNonLoaded(server);
    }

    /** 返回当前待删队列中的区块数 */
    public int pendingQueueCount() {
        return pendingQueue.queuedCount();
    }

    /** 服务器启动：初始化待删队列的删除上下文并兜底清空（加载中的跳过，遇卸载即删） */
    public synchronized void onServerStarted(MinecraftServer server) {
        if (pendingQueue.isEmpty()) {
            return;
        }
        Path worldRoot = server.getWorldPath(LevelResource.ROOT);
        Path dimRoot = McaRegion.overworldDimensionRoot(worldRoot);
        pendingQueue.beginCycle(dimRoot, configDir.resolve("backups"), pendingQueue.seasonIdOr(-1));
        int removed = pendingQueue.drainNonLoaded(server);
        if (removed > 0) {
            Samsara.LOGGER.info("[周目管理 samsara] 启动兜底：待删队列清空 {} 个已卸载区块", removed);
        }
    }

    /** 服务器停止：兜底删除待删队列 + 未删完区块；按恢复完成情况归档或保留；停服窗口区域文件瘦身 */
    public synchronized void onServerStopped(MinecraftServer server) {
        try {
            if (data != null) {
                applyPhysicalDeletion(server);
                if (data.teleportPhaseDone && DiffRestore.isFinished(server, data)) {
                    archivePersistence();
                } else {
                    save();
                }
            }
            // 先处理卸载缓冲区（可能有未 flush 的区块）
            flushUnloadBuffer(server);
            // 再处理队列中剩余的区块（停服时所有区块已卸载，可以安全删除）
            if (!pendingQueue.isEmpty()) {
                int removed = pendingQueue.drainNonLoaded(server);
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
        DiffRestore.reset();
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
     * 立即对指定区块执行一次逐格恢复（读恢复源并按其时间进度恢复）。
     * 用于验证/懒加载补恢复：强制恢复某个区块，不依赖玩家站位。
     */
    public synchronized String restoreChunkNow(MinecraftServer server, int cx, int cz) {
        if (data == null) {
            return "当前没有进行中的倒流";
        }
        DiffStore store = DiffRestore.restoreStore(configDir, data.seasonId);
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return "主世界不可用";
        }
        // 保护集一致性（2026-09-10 修复）：强制恢复同样不得越过圈地保护，
        // 否则 debug 命令会把生产路径刚堵上的"保护区块被逐格抹除"重新打开。
        if (com.shenqianye.samsara.claim.AreaService.getInstance()
                .isProtected(overworld.dimension(), cx, cz)) {
            return "区块 (" + cx + "," + cz + ") 受圈地保护，拒绝强制恢复"
                    + "（保护集不参与倒流）。如需恢复请先拆除该区域旗帜。";
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
     * 队列中每个条目：坐标 + 该区块所属维度的 dimRoot 路径。
     * 多维度混合删除时每个区块使用自己的 dimRoot。
     */
    public record QueuedChunk(int x, int z, Path dimRoot) {}

    /**
     * 待删队列持久化（pending-delete.json）：完成时仍加载、不能立即删除的「已到倒流时刻」
     * 区块。删除时先备份（去重），再清零槽位；启动/停服窗口兜底清空；区块换载即删。
     */
    private static class PendingDeleteQueue {
        private static final int SAVE_BATCH = 50;
        private final Path file;
        /** 全局默认 dimRoot（兼容旧版：所有区块用同一路径） */
        private Path defaultDimRoot;
        private Path backupsRoot;
        private String backupRootName;
        private int seasonId = -1;
        /** 本周期已备份过的区域文件（相对 dimRoot），避免重复复制 */
        private final Set<String> backedUp = ConcurrentHashMap.newKeySet();
        /** 待删区块 key → (坐标 + 维度路径) — ConcurrentHashMap 保证读操作不阻塞服务器线程 */
        private final ConcurrentHashMap<String, QueuedChunk> chunks = new ConcurrentHashMap<>();
        /** 脏标记：入队/删队后置 true，攒够 SAVE_BATCH 个再落盘 */
        private volatile int dirtyCount = 0;

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
                String dr = (String) m.get("dimRoot");
                if (dr != null) {
                    defaultDimRoot = Path.of(dr);
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
                        if (o instanceof java.util.Collection<?> pair && pair.size() >= 2) {
                            Object[] it = pair.toArray();
                            if (it[0] instanceof Number && it[1] instanceof Number) {
                                int x = ((Number) it[0]).intValue();
                                int z = ((Number) it[1]).intValue();
                                // 新格式：[x, z, "dimRootPath"]；旧格式：[x, z]
                                Path chunkDimRoot = null;
                                if (pair.size() >= 3 && it[2] instanceof String s) {
                                    chunkDimRoot = Path.of(s);
                                }
                                chunks.put(key(x, z, chunkDimRoot), new QueuedChunk(x, z, chunkDimRoot));
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
            m.put("dimRoot", defaultDimRoot != null ? defaultDimRoot.toString() : null);
            m.put("backupRoot", backupRootName);
            m.put("backedUp", new ArrayList<>(backedUp));
            List<List<Object>> cs = new ArrayList<>();
            for (QueuedChunk qc : chunks.values()) {
                if (qc.dimRoot() != null) {
                    cs.add(java.util.List.of(qc.x(), qc.z(), qc.dimRoot().toString()));
                } else {
                    cs.add(java.util.List.of(qc.x(), qc.z()));
                }
            }
            m.put("chunks", cs);
            try {
                Files.createDirectories(file.getParent());
                Files.writeString(file, GSON.toJson(m), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Samsara.LOGGER.error("[周目管理 samsara] 待删队列写入失败: {}", e.getMessage());
            }
        }

        private static String key(int x, int z, Path dimRoot) {
            String dim = dimRoot != null ? dimRoot.getFileName().toString() : "default";
            return dim + ":" + x + ":" + z;
        }

        /** 兼容旧调用（无维度参数） */
        private static String key(int x, int z) {
            return "default:" + x + ":" + z;
        }

        boolean isEmpty() {
            return chunks.isEmpty();
        }

        int queuedCount() {
            return chunks.size();
        }

        boolean contains(int x, int z, Path dimRoot) {
            return chunks.containsKey(key(x, z, dimRoot));
        }

        /** 移除并返回指定区块（供 onChunkUnload 缓冲用） */
        QueuedChunk removeChunk(int x, int z, Path dimRoot) {
            return chunks.remove(key(x, z, dimRoot));
        }

        /** 兼容旧调用：检查任意维度是否有该坐标 */
        boolean containsAny(int x, int z) {
            String prefix = ":" + x + ":" + z;
            return chunks.keySet().stream().anyMatch(k -> k.endsWith(prefix));
        }

        synchronized Path defaultDimRoot() {
            return defaultDimRoot;
        }

        synchronized int seasonIdOr(int def) {
            return seasonId >= 0 ? seasonId : def;
        }

        synchronized void beginCycle(Path dimRoot, Path backupsRoot, int seasonId) {
            this.defaultDimRoot = dimRoot;
            this.backupsRoot = backupsRoot;
            this.seasonId = seasonId;
            if (backupRootName == null || backupRootName.isBlank()) {
                backupRootName = "purge_" + LocalDateTime.now(ZoneId.systemDefault())
                        .format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            }
        }

        synchronized void enqueue(List<int[]> coords, ServerLevel overworld) {
            boolean changed = false;
            // 使用 AreaService 的旗帜圈地白名单
            var whitelist = overworld != null
                    ? com.shenqianye.samsara.claim.AreaService.getInstance().getWhitelist(overworld.dimension())
                    : java.util.Collections.<com.shenqianye.samsara.geometry.ChunkCoord>emptySet();
            for (int[] c : coords) {
                // 入队时复查保护：已被圈地的区块不再删除
                if (overworld != null && whitelist.contains(new com.shenqianye.samsara.geometry.ChunkCoord(c[0], c[1]))) {
                    changed = true;
                    continue;
                }
                String k = key(c[0], c[1], defaultDimRoot);
                if (!chunks.containsKey(k)) {
                    chunks.put(k, new QueuedChunk(c[0], c[1], defaultDimRoot));
                    changed = true;
                }
            }
            if (changed) {
                dirtyCount += coords.size();
                saveIfDirty();
            }
        }

        /** 入队（不检查圈地保护，由调用方保证）；每个 QueuedChunk 可携带自己的 dimRoot */
        synchronized int enqueueNoCheck(List<QueuedChunk> items) {
            boolean changed = false;
            for (QueuedChunk qc : items) {
                Path dr = qc.dimRoot() != null ? qc.dimRoot() : defaultDimRoot;
                String k = key(qc.x(), qc.z(), dr);
                if (!chunks.containsKey(k)) {
                    chunks.put(k, qc);
                    changed = true;
                }
            }
            if (changed) {
                dirtyCount += items.size();
                saveIfDirty();
            }
            return changed ? items.size() : 0;
        }

        /** 脏标记攒够阈值时落盘，避免每个区块都写一次 JSON */
        private void saveIfDirty() {
            if (dirtyCount >= SAVE_BATCH || chunks.isEmpty()) {
                save();
                dirtyCount = 0;
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
                dirtyCount = 0;
            }
            return deleted;
        }

        /**
         * 若区块仍在队列则删除；返回删除格数或 -1（不在队列）。
         * 使用该区块自己的 dimRoot（从 QueuedChunk 取），不依赖全局 dimRoot。
         */
        int deleteChunkIfQueued(int cx, int cz, Path dimRoot) {
            String k = key(cx, cz, dimRoot);
            // ① 快速检查+移除（ConcurrentHashMap，几乎不阻塞）
            QueuedChunk qc = chunks.remove(k);
            if (qc == null) {
                com.shenqianye.samsara.claim.SafeDeletion.dbg("[deleteChunkIfQueued] (" + cx + "," + cz + ") 不在队列中");
                return -1;
            }
            Path dr = qc.dimRoot() != null ? qc.dimRoot() : defaultDimRoot;
            com.shenqianye.samsara.claim.SafeDeletion.dbg("[deleteChunkIfQueued] (" + cx + "," + cz + ") dimRoot=" + dr);
            // ② 文件 I/O 在锁外执行（不阻塞服务器线程）
            int n = doDeleteOne(dr, cx, cz);
            com.shenqianye.samsara.claim.SafeDeletion.dbg("[deleteChunkIfQueued] (" + cx + "," + cz + ") doDeleteOne 返回=" + n);
            // ③ 落盘（批量）
            synchronized (this) {
                dirtyCount++;
                if (chunks.isEmpty()) {
                    dropLocked();
                } else {
                    saveIfDirty();
                }
            }
            return n;
        }

        /** 逐块删除（需在 synchronized 内调用）：备份（去重）→ 清零槽位。0=已不存在。 */
        private int deleteOne(int cx, int cz) {
            // 尝试所有维度查找
            for (var entry : chunks.entrySet()) {
                QueuedChunk qc = entry.getValue();
                if (qc.x() == cx && qc.z() == cz) {
                    Path dr = qc.dimRoot() != null ? qc.dimRoot() : defaultDimRoot;
                    chunks.remove(entry.getKey());
                    if (dr == null || !McaRegion.chunkExists(dr, cx, cz)) {
                        return 0;
                    }
                    backupRegionFiles(dr, cx, cz);
                    List<int[]> one = List.of(new int[]{cx, cz});
                    return McaRegion.deleteChunks(dr, one);
                }
            }
            return 0;
        }

        /**
         * 纯 I/O 删除（不操作 chunks map，无需持锁）：检查是否存在 → 备份 → 清零。
         */
        private int doDeleteOne(Path dimRoot, int cx, int cz) {
            if (dimRoot == null) {
                com.shenqianye.samsara.claim.SafeDeletion.dbg("[doDeleteOne] (" + cx + "," + cz + ") dimRoot=null");
                return 0;
            }
            boolean exists = McaRegion.chunkExists(dimRoot, cx, cz);
            if (!exists) {
                com.shenqianye.samsara.claim.SafeDeletion.dbg("[doDeleteOne] (" + cx + "," + cz + ") 区块不存在于磁盘");
                return 0;
            }
            backupRegionFiles(dimRoot, cx, cz);
            List<int[]> one = List.of(new int[]{cx, cz});
            int n = McaRegion.deleteChunks(dimRoot, one);
            com.shenqianye.samsara.claim.SafeDeletion.dbg("[doDeleteOne] (" + cx + "," + cz + ") deleteChunks=" + n);
            return n;
        }

        private void backupRegionFiles(Path dimRoot, int cx, int cz) {
            if (backupsRoot == null || backupRootName == null) {
                Samsara.LOGGER.warn("[周目管理 samsara] 待删队列备份目录未初始化，跳过备份");
                return;
            }
            McaRegion.RegionPos rp = McaRegion.RegionPos.ofChunk(cx, cz);
            String name = "r." + rp.rx() + "." + rp.rz() + ".mca";
            // 备份目标：backups/purge_<ts>/<dimRoot相对路径>/region/
            Path base = backupsRoot.resolve(backupRootName).resolve(dimRoot.toString());
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
         * 批量兜底清空：按 dimRoot 分组，每维度只做一次 MCA 文件批量清零（快 100x+）。
         * <p>
         * 旧实现逐块调用 deleteOne → 每块3次文件 I/O（chunkExists + backup + deleteChunks）。
         * 新实现：收集同 dimRoot 的所有坐标 → 一次 McaRegion.deleteChunks(dimRoot, batch)
         * （内部按 region 文件分组，每文件只读写一次 header）。
         *
         * @param skipLoaded true=跳过仍加载的区块（启动兜底用）
         * @return 删除的区块数
         */
        synchronized int drain(ServerLevel overworld, boolean skipLoaded) {
            // 使用 AreaService 的旗帜圈地白名单
            var whitelist = overworld != null
                    ? com.shenqianye.samsara.claim.AreaService.getInstance().getWhitelist(overworld.dimension())
                    : java.util.Collections.<com.shenqianye.samsara.geometry.ChunkCoord>emptySet();

            // 第一轮：分类 — 可删 vs 保留（加载中/已保护）
            List<QueuedChunk> toDelete = new ArrayList<>();
            var it = chunks.entrySet().iterator();
            while (it.hasNext()) {
                QueuedChunk qc = it.next().getValue();
                boolean loaded = overworld != null && overworld.getChunkSource().getChunkNow(qc.x(), qc.z()) != null;
                if (skipLoaded && loaded) continue; // 仍在内存，保持队列
                if (overworld != null && whitelist.contains(new com.shenqianye.samsara.geometry.ChunkCoord(qc.x(), qc.z()))) {
                    it.remove(); // 已被圈地 → 保留不删
                    continue;
                }
                toDelete.add(qc);
                it.remove();
            }

            // 第二轮：按 dimRoot 分组 → 批量删除（每个 dimRoot 只做一次文件 I/O）
            var wlIndex = buildWhitelistIndex();
            int removed = batchDeleteByDimRoot(toDelete, wlIndex);

            if (chunks.isEmpty()) {
                dropLocked();
            } else {
                save();
                dirtyCount = 0;
            }
            return removed;
        }

        /**
         * 维度感知兜底清空：逐块检查其所属维度的加载状态，跳过仍加载的区块。
         * 批量按 dimRoot 分组删除，每个 MCA 文件只读写一次。
         *
         * @param server  MinecraftServer，用于获取各维度的 ServerLevel
         * @return 删除的区块数
         */
        synchronized int drainNonLoaded(MinecraftServer server) {
            com.shenqianye.samsara.claim.SafeDeletion.dbg("[drainNonLoaded] 开始, 队列大小=" + chunks.size());
            // 构建 dimRoot 绝对路径 → ServerLevel 映射
            java.util.Map<String, ServerLevel> dimMap = new java.util.HashMap<>();
            Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            for (var dimKey : server.levelKeys()) {
                ServerLevel sl = server.getLevel(dimKey);
                if (sl != null) {
                    String[] nsDim = parseResourceKey(dimKey.toString());
                    if (nsDim == null) { com.shenqianye.samsara.claim.SafeDeletion.dbg("[drainNonLoaded] parseResourceKey 失败: " + dimKey.toString()); continue; }
                    Path dimRoot = worldRoot.resolve("dimensions").resolve(nsDim[0]).resolve(nsDim[1]);
                    String key = dimRoot.toAbsolutePath().normalize().toString();
                    dimMap.put(key, sl);
                    com.shenqianye.samsara.claim.SafeDeletion.dbg("[drainNonLoaded] dimMap: " + nsDim[0] + "/" + nsDim[1] + " → " + key);
                }
            }

            // 第一轮：分类 — 可删 vs 保留（加载中）
            List<QueuedChunk> toDelete = new ArrayList<>();
            int loadedCount = 0;
            int unloadedCount = 0;
            int noDimLevel = 0;
            var it = chunks.entrySet().iterator();
            while (it.hasNext()) {
                QueuedChunk qc = it.next().getValue();
                Path qcDimRoot = qc.dimRoot() != null ? qc.dimRoot() : defaultDimRoot;
                String qcKey = qcDimRoot.toAbsolutePath().normalize().toString();
                ServerLevel dimLevel = dimMap.get(qcKey);
                if (dimLevel != null && dimLevel.getChunkSource().getChunkNow(qc.x(), qc.z()) != null) {
                    loadedCount++;
                    continue; // 仍在内存，保持队列
                }
                if (dimLevel == null) {
                    noDimLevel++;
                    com.shenqianye.samsara.claim.SafeDeletion.dbg("[drainNonLoaded] dimLevel=null for (" + qc.x() + "," + qc.z() + ") dimRoot=" + qcKey);
                }
                unloadedCount++;
                toDelete.add(qc);
                it.remove();
            }
            com.shenqianye.samsara.claim.SafeDeletion.dbg("[drainNonLoaded] 分类: 已加载=" + loadedCount + " 未加载=" + unloadedCount + " dimLevel=null=" + noDimLevel);

            // 第二轮：按 dimRoot 分组 → 批量删除
            var wlIndex = buildWhitelistIndex();
            com.shenqianye.samsara.claim.SafeDeletion.dbg("[drainNonLoaded] 白名单索引: " + wlIndex.size() + " 个 dimRoot");
            int removed = batchDeleteByDimRoot(toDelete, wlIndex);
            com.shenqianye.samsara.claim.SafeDeletion.dbg("[drainNonLoaded] 批量删除完成: " + removed + " 区块");

            if (chunks.isEmpty()) {
                dropLocked();
            } else {
                save();
                dirtyCount = 0;
            }
            return removed;
        }

        /**
         * 构建白名单索引：dimRoot 绝对路径 → (region 文件名 → 白名单区块数)。
         * 由 GeometryStore 的白名单数据按 region 文件聚合得到。
         */
        java.util.Map<Path, java.util.Map<String, Integer>> buildWhitelistIndex() {
            java.util.Map<Path, java.util.Map<String, Integer>> result = new java.util.HashMap<>();
            for (var dimKey : com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes().keySet()) {
                java.util.Map<String, Integer> perRegion =
                        com.shenqianye.samsara.claim.AreaService.getInstance().getWhitelistCountByRegionFile(dimKey);
                if (perRegion.isEmpty()) continue;
                String[] nsDim = parseResourceKey(dimKey.toString());
                if (nsDim == null) continue;
                Path worldRoot = defaultDimRoot.getParent().getParent().getParent();
                if (worldRoot == null) continue;
                Path dimRoot = worldRoot.resolve("dimensions").resolve(nsDim[0]).resolve(nsDim[1]).toAbsolutePath().normalize();
                result.put(dimRoot, perRegion);
            }
            return result;
        }

        /**
         * 按 dimRoot 分组批量删除：每个 dimRoot 只调一次 McaRegion.deleteChunks，
         * 内部按 region 文件分组，每文件只读写一次 header。
         * 这比逐块 deleteOne 快 100x+（108K 区块从 ~50 分钟降到 ~30 秒）。
         *
         * @param whitelistIndex 可选索引：dimRoot → (region 文件名 → 白名单区块数)
         */
        int batchDeleteByDimRoot(List<QueuedChunk> toDelete,
                                         java.util.Map<Path, java.util.Map<String, Integer>> whitelistIndex) {
            if (toDelete.isEmpty()) return 0;

            // 按 dimRoot 分组
            java.util.Map<Path, List<int[]>> byDimRoot = new java.util.LinkedHashMap<>();
            for (QueuedChunk qc : toDelete) {
                Path dr = qc.dimRoot() != null ? qc.dimRoot() : defaultDimRoot;
                byDimRoot.computeIfAbsent(dr, k -> new ArrayList<>()).add(new int[]{qc.x(), qc.z()});
            }

            int removed = 0;
            for (var entry : byDimRoot.entrySet()) {
                Path dimRoot = entry.getKey();
                List<int[]> coords = entry.getValue();
                com.shenqianye.samsara.claim.SafeDeletion.dbg("[batchDelete] dimRoot=" + dimRoot + " 待删=" + coords.size() + " 区块");

                // 备份：按 region 文件去重（每个文件只备份一次，不 per-chunk）
                java.util.Set<String> backedUpFiles = new java.util.HashSet<>();
                for (int[] c : coords) {
                    McaRegion.RegionPos rp = McaRegion.RegionPos.ofChunk(c[0], c[1]);
                    String regionKey = rp.rx() + "." + rp.rz();
                    if (backedUpFiles.add(regionKey)) {
                        backupRegionFiles(dimRoot, c[0], c[1]);
                    }
                }

                // 查索引：该 dimRoot 对应的 region 文件白名单统计
                java.util.Map<String, Integer> dimIndex = whitelistIndex != null
                        ? whitelistIndex.get(dimRoot.toAbsolutePath().normalize()) : null;
                // 批量删除（McaRegion.deleteChunks 内部按 region 文件分组，每文件只读写一次）
                removed += McaRegion.deleteChunks(dimRoot, coords, dimIndex);
            }
            return removed;
        }
    }

    // ---- 内部 ----

    private record ScanResult(int total, int protectedCount, List<int[]> toRewind) {
    }

    private ScanResult scan(MinecraftServer server) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        // 使用 AreaService 的旗帜圈地白名单
        var whitelist = overworld != null
                ? com.shenqianye.samsara.claim.AreaService.getInstance().getWhitelist(overworld.dimension())
                : java.util.Collections.<com.shenqianye.samsara.geometry.ChunkCoord>emptySet();
        Path dimRoot = McaRegion.overworldDimensionRoot(server.getWorldPath(LevelResource.ROOT));
        Path regionDir = dimRoot.resolve("region");
        List<int[]> toRewind = new ArrayList<>();
        int total = 0;
        int protectedCount = 0;
        for (Path f : McaRegion.listRegionFiles(regionDir)) {
            List<int[]> exist = McaRegion.scanExisting(f);
            for (int[] c : exist) {
                total++;
                if (overworld != null && whitelist.contains(new com.shenqianye.samsara.geometry.ChunkCoord(c[0], c[1]))) {
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
