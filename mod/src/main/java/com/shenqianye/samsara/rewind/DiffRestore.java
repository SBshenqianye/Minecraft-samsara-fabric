package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.claim.AreaService;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
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
 * 倒流逐格恢复调度（懒加载 + 双光标分档 + 预算分摊）。
 * <p>
 * 每 2 秒一轮（自节流）：
 * <ol>
 *   <li>按已过时间算<b>权威光标 A</b>：{@code pA(t) = 1 - (1-t)^k}，t = 已过时间 / durationMs；</li>
 *   <li>遍历玩家视距内<b>已加载</b>区块（懒加载：未加载区块不处理，加载时自然跟上）；</li>
 *   <li><b>跳过受圈地保护的区块</b>（保护集 = 当前圈地并集，动态判定，见 {@link #isProtected}）；</li>
 *   <li>按区块到最近玩家的距离分档：近档（≤ nearRadius）用<b>呈现光标 B</b>，
 *       远档直接跟 A；出近档后 B 在 rampMs 内追平 A；3~4 区块保持带 + 最短驻留防横跳；</li>
 *   <li>恢复源 = 本次倒流的<b>冻结恢复库</b> {@code diff/cycle<周目id>/<世界>}；</li>
 *   <li>每轮全局恢复上限按待恢复量/剩余时间自适应（200~2000 格/轮）；</li>
 *   <li>倒流结束后继续恢复（预算内）直到全部应用（归档由 RewindEngine 依据 {@link #isFinished} 触发）。</li>
 * </ol>
 * <p>
 * <b>为什么 B 可以落后而安全</b>：物理删除只对未加载区块立刻生效，仍加载的区块进待删队列；
 * 玩家所在区块一直加载，B 落后多久都不会在玩家脚下被清零。传送后（teleportPhaseDone）
 * 所有区块统一按 A 收尾，B 的欠账直接丢弃（与《设计文档》§5.3 一致）。
 * <p>
 * <b>保护集约束</b>：{@link #restoreRound} 与 {@link #isFinished} <b>必须成对</b>
 * 跳过受保护区块，否则受保护区块的 diff 永远达不到"已恢复"，isFinished 恒为 false。
 */
public class DiffRestore {
    private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir().resolve("samsara");
    /** 每轮全局最大恢复格数上限 */
    public static final int MAX_PER_TICK = 2000;
    /** 每轮最低保底恢复格数（保证有进度） */
    private static final int MIN_PER_TICK = 200;
    /** 恢复轮间隔（设计文档 §5.3：每 2 秒重算一次档位，与恢复轮同频） */
    private static final long ROUND_MS = 2000L;
    /** 档位状态保留时间：超过该时间没被加载就不再记住 */
    private static final long PACE_TTL_MS = 60_000L;

    private DiffRestore() {
    }

    /** 单区块恢复进度（按时间倒序的 diff 列表前缀长度） */
    private static final Map<Long, Integer> perChunkRestored = new HashMap<>();
    /** 每区块的呈现光标状态 */
    private static final Map<Long, Pace> paces = new HashMap<>();
    private static long lastTickMs = 0;
    /** 上一轮的权威进度（近档 B 按 ΔA 推进，不能按 2s 一次直接跳） */
    private static double prevA = 0;
    private static DiffStore restoreStore;
    private static int restoreSeasonId = -1;
    private static String restoreWorldKey;

    private enum Tier {
        /** 远档：B = A */
        FAR,
        /** 近档：B 按 A 速率的 r 倍慢放 */
        NEAR,
        /** 出近档：B 在 rampMs 内追平 A（不瞬移） */
        RAMP
    }

    private static final class Pace {
        Tier tier = Tier.FAR;
        double b;
        long nearSinceMs;
        long rampStartMs;
        double rampFromB;
        long lastSeenMs;
    }

    private record Work(long key, int cx, int cz, List<DiffStore.Edit> edits, int target, int already) {
    }

    /** 恢复源差异库（缓存同一世界+周目的库实例，避免每次重建内存缓存；仅主世界参与本轮倒流） */
    public static DiffStore restoreStore(Path configDir, String worldKey, int seasonId) {
        if (restoreStore == null || restoreSeasonId != seasonId
                || restoreWorldKey == null || !restoreWorldKey.equals(worldKey)) {
            restoreStore = DiffStore.forSeason(configDir, worldKey, seasonId, DiffStore.OVERWORLD_DIM);
            restoreSeasonId = seasonId;
            restoreWorldKey = worldKey;
        }
        return restoreStore;
    }

    /**
     * 执行一次逐格恢复。返回本轮恢复格数。
     */
    public static int restoreRound(MinecraftServer server, RewindData data) {
        long now = System.currentTimeMillis();
        if (now - lastTickMs < ROUND_MS) {
            return 0; // 节流：每 2 秒
        }
        lastTickMs = now;
        if (data == null || data.startMs <= 0 || data.durationMs <= 0) {
            return 0;
        }
        data.normalize();
        double A = authoritativeProgress(now, data);
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            prevA = A;
            return 0;
        }

        // 收集已加载区块：collectSpawningChunks 拿全部真正加载的区块（含 forceload/玩家附近）
        Set<Long> loadedChunks = loadedChunks(overworld, server);
        if (loadedChunks.isEmpty()) {
            prevA = A;
            return 0;
        }

        DiffStore store = restoreStore(CONFIG_DIR, data.worldKey, data.seasonId);
        List<ServerPlayer> players = overworldPlayers(overworld, server);

        // ① 先算每区块的档位/呈现光标/target，再按总欠账算预算
        List<Work> works = new ArrayList<>(loadedChunks.size());
        long totalRemaining = 0;
        for (long key : loadedChunks) {
            int cx = (int) (key >>> 32);
            int cz = (int) (long) key;
            if (isProtected(overworld, cx, cz)) {
                continue; // 受圈地保护：不参与恢复，其待恢复量不计入预算
            }
            List<DiffStore.Edit> edits = store.readChunk(cx, cz);
            if (edits.isEmpty()) {
                continue;
            }
            edits.sort((a, b) -> Long.compare(b.timeMs, a.timeMs)); // 新改动优先
            Pace pace = paces.computeIfAbsent(key, k -> new Pace());
            pace.lastSeenMs = now;
            updatePace(pace, now, A, nearestChunkDistance(players, cx, cz), data);
            int target = (int) Math.ceil(edits.size() * clamp01(pace.b));
            if (target > edits.size()) {
                target = edits.size();
            }
            int already = perChunkRestored.getOrDefault(key, 0);
            if (already > target) {
                already = target; // 档位回退时保持单调，不回退已恢复的格
                perChunkRestored.put(key, already);
            }
            totalRemaining += target - already;
            works.add(new Work(key, cx, cz, edits, target, already));
        }

        // ② 预算 = 待恢复量 / 剩余轮数（每 2s 一轮），钳制在 [MIN, MAX]；倒流结束后取 MAX 快速收尾
        long remainingMs = data.startMs + data.durationMs - now;
        long rounds = Math.max(1, Math.max(1, remainingMs) / ROUND_MS);
        int budget = remainingMs <= 0
                ? MAX_PER_TICK
                : (int) Math.max(MIN_PER_TICK, Math.min(MAX_PER_TICK,
                        (long) Math.ceil(totalRemaining / (double) rounds)));
        if (budget <= 0) {
            budget = MIN_PER_TICK;
        }

        // ③ 应用
        int applied = 0;
        int protectedSkipped = 0;
        int nearChunks = 0;
        store.flush(); // 先把恢复源缓冲落盘（冻结库通常无新写入），统一入口
        for (Work w : works) {
            if (budget <= 0) {
                break;
            }
            Pace pace = paces.get(w.key);
            if (pace != null && pace.tier == Tier.NEAR) {
                nearChunks++;
            }
            int i = w.already;
            for (; i < w.target && budget > 0; i++) {
                DiffStore.Edit e = w.edits.get(i);
                if (tryRestore(overworld, e)) {
                    applied++;
                    budget--;
                }
            }
            // 记实际推进位置（预算耗尽时不能把没恢复到的段标成已恢复，否则永久跳过）
            perChunkRestored.put(w.key, Math.max(i, 0));
        }
        for (long key : loadedChunks) {
            int cx = (int) (key >>> 32);
            int cz = (int) (long) key;
            if (isProtected(overworld, cx, cz)) {
                protectedSkipped++;
            }
        }
        prunePaces(now);
        prevA = A;
        if (applied > 0) {
            Samsara.LOGGER.info(String.format(
                    "[周目管理 samsara] 倒流恢复：本轮恢复 %d 格（A %.1f%%）· 近档 %d 区块 · 跳过受保护 %d 区块",
                    applied, A * 100, nearChunks, protectedSkipped));
        }
        return applied;
    }

    /** 权威光标 A：曲线 p(t)=1-(1-t)^k；传送阶段一律视为 100% */
    public static double authoritativeProgress(long now, RewindData data) {
        if (data == null || data.durationMs <= 0) {
            return 1;
        }
        if (data.teleportPhaseDone || now >= data.startMs + data.durationMs) {
            return 1;
        }
        double t = clamp01((now - data.startMs) / (double) data.durationMs);
        double k = data.curveK > 0 ? data.curveK : 2.0;
        return clamp01(1 - Math.pow(1 - t, k));
    }

    /** 近档/出档/远档状态机（滞回 + 最短驻留 + 斜坡） */
    private static void updatePace(Pace p, long now, double A, double dist, RewindData data) {
        if (data.teleportPhaseDone || A >= 1) {
            p.tier = Tier.FAR;
            p.b = 1;
            return;
        }
        switch (p.tier) {
            case NEAR -> {
                // 保持带：3~4 区块不切档；最短驻留 10s 防边界横跳
                if (dist <= data.exitRadius || now - p.nearSinceMs < data.nearDwellMs) {
                    double deltaA = Math.max(0, A - prevA);
                    p.b = Math.min(A, p.b + deltaA * data.nearRatio);
                    return;
                }
                p.tier = Tier.RAMP;
                p.rampStartMs = now;
                p.rampFromB = p.b;
                ramp(p, now, A, data);
            }
            case RAMP -> ramp(p, now, A, data);
            case FAR -> {
                if (dist <= data.nearRadius) {
                    // 进档：从当前权威进度继续慢慢退，不回退
                    p.tier = Tier.NEAR;
                    p.nearSinceMs = now;
                    p.b = A;
                } else {
                    p.b = A;
                }
            }
        }
    }

    private static void ramp(Pace p, long now, double A, RewindData data) {
        double f = data.rampMs <= 0 ? 1 : clamp01((now - p.rampStartMs) / (double) data.rampMs);
        p.b = p.rampFromB + (A - p.rampFromB) * f;
        if (f >= 1) {
            p.tier = Tier.FAR;
            p.b = A;
        }
    }

    /** 距最近玩家的水平区块距离（欧氏）；没有同维度玩家时返回 +∞（按远档） */
    private static double nearestChunkDistance(List<ServerPlayer> players, int cx, int cz) {
        double best = Double.POSITIVE_INFINITY;
        for (ServerPlayer p : players) {
            int pcx = p.chunkPosition().x();
            int pcz = p.chunkPosition().z();
            best = Math.min(best, Math.hypot(pcx - cx, pcz - cz));
        }
        return best;
    }

    private static List<ServerPlayer> overworldPlayers(ServerLevel overworld, MinecraftServer server) {
        List<ServerPlayer> out = new ArrayList<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            if (p.level().dimension().equals(overworld.dimension())) {
                out.add(p);
            }
        }
        return out;
    }

    private static Set<Long> loadedChunks(ServerLevel overworld, MinecraftServer server) {
        Set<Long> loadedChunks = new HashSet<>();
        try {
            List<net.minecraft.world.level.chunk.LevelChunk> chunks = new ArrayList<>();
            overworld.getChunkSource().chunkMap.collectSpawningChunks(chunks);
            for (net.minecraft.world.level.chunk.LevelChunk c : chunks) {
                loadedChunks.add(((long) c.getPos().x() << 32) | (c.getPos().z() & 0xFFFFFFFFL));
            }
        } catch (Exception e) {
            int view = 5;
            for (ServerPlayer pl : server.getPlayerList().getPlayers()) {
                if (!pl.level().dimension().equals(overworld.dimension())) {
                    continue;
                }
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
        return loadedChunks;
    }

    private static void prunePaces(long now) {
        paces.entrySet().removeIf(e -> now - e.getValue().lastSeenMs > PACE_TTL_MS);
    }

    /**
     * 该区块是否受圈地保护（保护集 = <b>当前</b>圈地并集，每轮动态判定）。
     * <p>
     * <b>为什么恢复侧也必须查保护集</b>：{@code DiffRecorder} 不区分区块是否受保护，圈地区块的
     * 方块变化同样被记入 diff；若恢复侧不过滤，玩家圈地保护的建筑会在倒流期间被<b>逐格抹回
     * 改动前的状态</b>（新建的方块变回空气）——白名单只挡住"删除"，挡不住"恢复"。
     * <p>
     * <b>动态判定</b>（而非倒流开始时快照一次）使〈圈地暂停〉语义天然成立：倒流期间玩家
     * 新圈地的区块，从下一轮恢复起立即停止被恢复。
     */
    private static boolean isProtected(ServerLevel level, int cx, int cz) {
        return AreaService.getInstance().isProtected(level.dimension(), cx, cz);
    }

    /**
     * 恢复是否已完成（仅统计已加载区块；未加载区块懒加载时自然跟上）。
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
        data.normalize();
        DiffStore store = restoreStore(CONFIG_DIR, data.worldKey, data.seasonId);
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

    private static boolean tryRestore(ServerLevel level, DiffStore.Edit e) {
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

    private static double clamp01(double v) {
        return v < 0 ? 0 : Math.min(v, 1);
    }

    /** 倒流重置时清空内存进度与恢复源缓存 */
    public static void reset() {
        perChunkRestored.clear();
        paces.clear();
        prevA = 0;
        lastTickMs = 0;
        restoreStore = null;
        restoreSeasonId = -1;
        restoreWorldKey = null;
    }
}
