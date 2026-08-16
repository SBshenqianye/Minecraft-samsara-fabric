package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 档3 倒流逐格恢复调度（懒加载 + 按进度均匀 + 全局每 tick 上限）。
 * <p>
 * 每次 tick（节流，如每 2 秒）：
 *  1. 按已过时间算进度 p = (now - startMs) / durationMs；
 *  2. 遍历玩家视距内<b>已加载</b>区块（懒加载：未加载区块不处理，加载时自然跟上）；
 *  3. 对每区块取其 diff 记录（倒序=较新改动优先退场），恢复「进度点之前该恢复的格」
 *     为变化前状态（Block.stateById）；
 *  4. 每 tick 全局恢复上限（防刷屏/负载）；被实体占用格跳过；
 *  5. 从未改过的区块/无 diff 的格保持不变。
 * <p>
 * 恢复进度按"samsara-per-block"：对每个格子，其"该退场的时刻"以其 diff 记录写入时间按
 * 比例映射到倒流时长；这里简化为按进度 p 每区块恢复 p 比例的记录（较新的先退场）。
 */
public class Tier3Restore {
    /** 每 tick 全局最大恢复格数 */
    public static int MAX_PER_TICK = 2000;

    private Tier3Restore() {
    }

    private static final Map<Long, Integer> perChunkRestored = new HashMap<>();
    private static long lastTickMs = 0;

    /**
     * 执行一次档3恢复。返回本轮恢复格数。
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

        // 收集已加载区块（玩家视距内）
        Set<Long> loadedChunks = new HashSet<>();
        int view = 5; // 视距半径（区块），配合懒加载：玩家附近才处理
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        for (ServerPlayer pl : players) {
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
        if (loadedChunks.isEmpty()) {
            return 0;
        }

        DiffStore store = DiffStore.current();
        if (store == null) {
            return 0;
        }

        int applied = 0;
        int budget = MAX_PER_TICK;
        for (long key : loadedChunks) {
            if (budget <= 0) {
                break;
            }
            int cx = (int) (key >>> 32);
            int cz = (int) (key);
            int already = perChunkRestored.getOrDefault(key, 0);
            List<DiffStore.Edit> edits = store.readChunk(cx, cz);
            if (edits.isEmpty()) {
                continue;
            }
            // 倒序（新改动优先退场）
            edits.sort((a, b) -> Long.compare(b.timeMs, a.timeMs));
            int target = (int) Math.floor(edits.size() * p);
            for (int i = already; i < target && budget > 0; i++) {
                if (i >= edits.size()) {
                    break;
                }
                DiffStore.Edit e = edits.get(i);
                if (tryRestore(overworld, e, key)) {
                    applied++;
                    budget--;
                }
            }
            perChunkRestored.put(key, Math.max(already, target));
        }
        if (applied > 0) {
            Samsara.LOGGER.info(String.format("[周目管理 samsara] 档3倒流：本轮恢复 %d 格（进度 %.1f%%）", applied, p * 100));
        }
        return applied;
    }

    private static boolean tryRestore(ServerLevel level, DiffStore.Edit e, long chunkKey) {
        BlockPos pos = new BlockPos(e.x, e.y, e.z);
        if (!level.isLoaded(pos)) {
            return false;
        }
        BlockState target = Block.stateById(e.stateId);
        if (target == null || target.isAir()) {
            return false; // 避免还原成空气造成异常
        }
        // 被玩家/实体占用的格跳过
        if (!level.getEntities((net.minecraft.world.entity.Entity) null,
                new net.minecraft.world.phys.AABB(pos), ex -> ex.blockPosition().equals(pos)).isEmpty()) {
            perChunkRestored.put(chunkKey, perChunkRestored.getOrDefault(chunkKey, 0) + 1); // 视为已处理
            return false;
        }
        if (level.getBlockState(pos).equals(target)) {
            return false; // 已是目标状态
        }
        level.setBlock(pos, target, 3);
        return true;
    }

    /** 倒流重置时清空内存进度 */
    public static void reset() {
        perChunkRestored.clear();
        lastTickMs = 0;
    }
}
