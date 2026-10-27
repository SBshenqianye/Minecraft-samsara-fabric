package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.claim.AreaService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 倒流逐格恢复调度（懒加载 + 按进度均匀 + 预算分摊）。
 * <p>
 * 每次 tick（节流，如每 2 秒）：
 *  1. 按已过时间算进度 p = (now - startMs) / durationMs；
 *  2. 遍历玩家视距内<b>已加载</b>区块（懒加载：未加载区块不处理，加载时自然跟上）；
 *  3. <b>跳过受圈地保护的区块</b>（保护集 = 当前圈地并集，动态判定，见 {@link #isProtected}）；
 *  4. 恢复源 = 本次倒流的<b>冻结恢复库</b>（diff/s&lt;赛季id&gt;/minecraft/overworld，倒流开始时由
 *     {@link DiffRecorder#freezeCurrent} 冻结；窗口 = 上次删块完成后至本次倒流开始）；
 *     对每区块取 diff 记录（倒序=较新改动优先退场），恢复「进度点之前该恢复的格」
 *     为变化前状态（Block.stateById）；
 *  5. 每 tick 全局恢复上限（按待恢复量/剩余时间自适应，见《设计文档》〈时间倒流（逆熵引擎）〉）；
 *  6. 倒流结束后继续恢复（预算内）直到全部应用（归档由 RewindEngine 依据
 *     {@link #isFinished} 触发）。
 * <p>
 * <b>保护集约束（2026-09-10 修复）</b>：{@link #restoreRound} 与 {@link #isFinished} <b>必须成对</b>
 * 跳过受保护区块。只改前者会让受保护区块的 diff 永远达不到"已恢复"，导致 isFinished 恒为 false
 * → rewind.json 永不归档 → {@code RewindEngine.data} 永不为 null → 下次 {@code /season start}
 * 被"已有进行中的时间倒流"永久挡住。
 */
public class DiffRestore {
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("samsara");
    /** 每 tick 全局最大恢复格数上限 */
    public static final int MAX_PER_TICK = 2000;
    /** 每 tick 最低保底恢复格数（保证有进度） */
    private static final int MIN_PER_TICK = 200;

    private DiffRestore() {
    }

    private static final Map<Long, Integer> perChunkRestored = new HashMap<>();
    private static long lastTickMs = 0;
    private static DiffStore restoreStore;
    private static int restoreSeasonId = -1;

    /** 恢复源差异库（缓存同一赛季的库实例，避免每次重建内存缓存；仅主世界参与本轮倒流） */
    public static DiffStore restoreStore(Path configDir, int seasonId) {
        if (restoreStore == null || restoreSeasonId != seasonId) {
            restoreStore = DiffStore.forSeason(configDir, seasonId, DiffStore.OVERWORLD_DIM);
            restoreSeasonId = seasonId;
        }
        return restoreStore;
    }

    /**
     * 执行一次逐格恢复。返回本轮恢复格数。
     */
    public static int restoreRound(MinecraftServer server, RewindData data) {
        long now = System.currentTimeMillis();
        if (now - lastTickMs < 2000) {
            return 0; // 节流：每 2 秒
        }
        lastTickMs = now;
        if (data == null || data.startMs <= 0 || data.durationMs <= 0) {
            return 0;
        }
        double p = (double) (now - data.startMs) / data.durationMs;
        if (p < 0) {
            p = 0;
        }
        if (p > 1) {
            p = 1;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return 0;
        }

        // 收集已加载区块：collectSpawningChunks 拿全部真正加载的区块（含 forceload/玩家附近）
        Set<Long> loadedChunks = new HashSet<>();
        try {
            List<net.minecraft.world.level.chunk.LevelChunk> chunks = new ArrayList<>();
            overworld.getChunkSource().chunkMap.collectSpawningChunks(chunks);
            for (net.minecraft.world.level.chunk.LevelChunk c : chunks) {
                loadedChunks.add(((long) c.getPos().x() << 32) | (c.getPos().z() & 0xFFFFFFFFL));
            }
        } catch (Exception e) {
            int view = 5;
            List<net.minecraft.server.level.ServerPlayer> players = server.getPlayerList().getPlayers();
            for (net.minecraft.server.level.ServerPlayer pl : players) {
                int pcx = pl.chunkPosition().x();
                int pcz = pl.chunkPosition().z();
                for (int dx = -view; dx <= view; dx++) {
                    for (int dz = -view; dz <= view; dz++) {
                        int cx = pcx + dx;
                        int cz = pcz + dz;
                        if (overworld.isLoaded(new BlockPos(cx << 4, 0, cz << 4))) {
                            loadedChunks.add(((long) cx << 32) | (cz & 0xFFFFFFFFL));
                        }
                    }
                }
            }
        }
        if (loadedChunks.isEmpty()) {
            return 0;
        }

        DiffStore store = restoreStore(CONFIG_DIR, data.seasonId);

        // 待恢复总量（已加载区块，排除受保护），用于自适应预算
        long remainingMs = data.startMs + data.durationMs - now;
        long totalRemaining = 0;
        int restorableChunks = 0;
        for (long key : loadedChunks) {
            int cx = (int) (key >>> 32);
            int cz = (int) (long) key;
            if (isProtected(overworld, cx, cz)) {
                continue; // 受圈地保护：不参与恢复，其待恢复量不计入预算
            }
            restorableChunks++;
            List<DiffStore.Edit> edits = store.readChunk(cx, cz);
            int already = perChunkRestored.getOrDefault(key, 0);
            if (already < edits.size()) {
                totalRemaining += edits.size() - already;
            }
        }
        // 预算 = 待恢复量 / 剩余轮数（每 2s 一轮），钳制在 [MIN, MAX]；倒流结束后取 MAX 快速收尾
        long rounds = Math.max(1, Math.max(1, remainingMs) / 2000);
        long need = totalRemaining > 0 ? totalRemaining : restorableChunks;
        int budget = remainingMs <= 0
                ? MAX_PER_TICK
                : (int) Math.max(MIN_PER_TICK, Math.min(MAX_PER_TICK, (long) Math.ceil(need / (double) rounds)));
        if (budget <= 0) {
            budget = MIN_PER_TICK;
        }

        int applied = 0;
        int protectedSkipped = 0;
        store.flush(); // 先把恢复源缓冲落盘（冻结库通常无新写入），统一入口
        for (long key : loadedChunks) {
            if (budget <= 0) {
                break;
            }
            int cx = (int) (key >>> 32);
            int cz = (int) (long) key;
            // 受圈地保护：跳过逐格恢复（保护集 = 当前圈地并集，每轮重新判定
            // → 倒流期间新圈地的区块立即停止恢复，即设计要求的"圈地暂停"）
            if (isProtected(overworld, cx, cz)) {
                protectedSkipped++;
                continue;
            }
            List<DiffStore.Edit> edits = store.readChunk(cx, cz);
            if (edits.isEmpty()) {
                continue;
            }
            edits.sort((a, b) -> Long.compare(b.timeMs, a.timeMs)); // 新改动优先
            int target = (int) Math.ceil(edits.size() * p);
            if (target > edits.size()) {
                target = edits.size();
            }
            int already = perChunkRestored.getOrDefault(key, 0);
            int i = already;
            for (; i < target && budget > 0; i++) {
                DiffStore.Edit e = edits.get(i);
                if (tryRestore(overworld, e, key)) {
                    applied++;
                    budget--;
                }
            }
            // 记实际推进位置（修复：预算耗尽时不能把没恢复到的段标成已恢复，否则永久跳过）
            perChunkRestored.put(key, Math.max(i, 0));
        }
        if (applied > 0 || protectedSkipped > 0) {
            Samsara.LOGGER.info(String.format(
                    "[周目管理 samsara] 倒流恢复：本轮恢复 %d 格（进度 %.1f%%）· 跳过受保护 %d 区块（不倒流）",
                    applied, p * 100, protectedSkipped));
        }
        return applied;
    }

    /**
     * 该区块是否受圈地保护（保护集 = <b>当前</b>圈地并集，每轮动态判定）。
     * <p>
     * <b>为什么恢复侧也必须查保护集</b>：{@code DiffRecorder} 不区分区块是否受保护，圈地区块的
     * 方块变化同样被记入 diff；若恢复侧不过滤，玩家圈地保护的建筑会在倒流期间被<b>逐格抹回
     * 改动前的状态</b>（新建的方块变回空气）——白名单只挡住"删除"，挡不住"恢复"，
     * 等于废掉了圈地的全部意义。
     * <p>
     * <b>动态判定</b>（而非倒流开始时快照一次）使〈圈地暂停〉语义天然成立：
     * 倒流期间玩家新圈地的区块，从下一轮恢复起立即停止被恢复。
     * <p>
     * 注意：受保护区块的 diff 记录<b>保留</b>在冻结恢复源中（不删除），只是不被应用。
     * 这样保留审计数据，且若玩家之后拆掉旗帜使其重新变为未保护，其记录仍可被正常恢复。
     */
    private static boolean isProtected(ServerLevel level, int cx, int cz) {
        return AreaService.getInstance().isProtected(level.dimension(), cx, cz);
    }

    /**
     * 恢复是否已完成（仅统计已加载区块；未加载区块懒加载时自然跟上——但归档前要求
     * 加载中的全部应用完）。用于 RewindEngine 决定何时归档 rewind.json。
     * <p>
     * <b>必须与 {@link #restoreRound} 采用同一套跳过规则</b>：受圈地保护的区块不参与恢复，
     * 因此也不参与"是否恢复完成"的判定。否则受保护区块的 diff 永远达不到"已恢复"，
     * 本方法恒返回 false → 永不归档 → 下次开新周目被"已有进行中的倒流"挡住。
     */
    public static boolean isFinished(MinecraftServer server, RewindData data) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return true;
        }
        DiffStore store = restoreStore(CONFIG_DIR, data.seasonId);
        try {
            List<net.minecraft.world.level.chunk.LevelChunk> chunks = new ArrayList<>();
            overworld.getChunkSource().chunkMap.collectSpawningChunks(chunks);
            for (net.minecraft.world.level.chunk.LevelChunk c : chunks) {
                int cx = c.getPos().x();
                int cz = c.getPos().z();
                if (isProtected(overworld, cx, cz)) {
                    continue; // 受保护区块本轮不参与恢复，故不参与完成判定
                }
                List<DiffStore.Edit> edits = store.readChunk(cx, cz);
                if (edits.isEmpty()) {
                    continue;
                }
                long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (perChunkRestored.getOrDefault(key, 0) < edits.size()) {
                    return false;
                }
            }
        } catch (Exception e) {
            return false;
        }
        return true;
    }

    private static boolean tryRestore(ServerLevel level, DiffStore.Edit e, long chunkKey) {
        BlockPos pos = new BlockPos(e.x, e.y, e.z);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState target = Block.stateById(e.stateId);
        if (target == null) {
            return false; // 无效状态 id
        }
        // 被玩家/实体占用的格跳过
        if (!level.getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(pos), ex -> ex.blockPosition().equals(pos)).isEmpty()) {
            return false;
        }
        if (level.getBlockState(pos).equals(target)) {
            return false; // 已是目标状态
        }
        // 恢复自身的 setBlock 不记录 diff（避免"恢复→被记成新变化→下一轮再恢复成空气"自噬）
        // flag=2（通知客户端、不触发邻居更新）：大规模恢复时避免邻居更新连锁卡服
        boolean wasRecording = DiffRecorder.recording();
        DiffRecorder.setEnabled(false);
        try {
            level.setBlock(pos, target, 2);
        } finally {
            DiffRecorder.setEnabled(wasRecording);
        }
        return true;
    }

    /** 倒流重置时清空内存进度与恢复源缓存 */
    public static void reset() {
        perChunkRestored.clear();
        lastTickMs = 0;
        restoreStore = null;
        restoreSeasonId = -1;
    }
}
