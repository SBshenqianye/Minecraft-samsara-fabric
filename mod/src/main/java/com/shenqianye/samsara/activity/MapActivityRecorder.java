package com.shenqianye.samsara.activity;

import com.shenqianye.samsara.season.StackPaths;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 地图“最近操作”环形缓冲：记录玩家/爆炸造成的真实方块变化。
 *
 * <p>只保存最近 {@value #MAX_ENTRIES} 条，进程内内存态，不写存档；用于 Web 地图的
 * 实时操作流。它不是审计日志，重启后清空，完整历史仍以 diff/track 为准。
 */
public final class MapActivityRecorder {
    public static final int MAX_ENTRIES = 500;
    private static final Deque<Entry> ENTRIES = new ArrayDeque<>();

    private MapActivityRecorder() {
    }

    public static synchronized void record(ServerLevel level, String actorUuid, String actorName,
                                           BlockPos pos, BlockState before, BlockState after) {
        if (level == null || pos == null || before == null || after == null) {
            return;
        }
        if (before.getBlock() == after.getBlock()) {
            return;
        }
        String stackId = StackPaths.layerIdOf(level.dimension());
        String dimension = StackPaths.dimensionOf(level.dimension());
        if (stackId == null || dimension == null) {
            return;
        }
        String action = after.isAir() ? "break" : before.isAir() ? "place" : "change";
        Entry entry = new Entry(System.currentTimeMillis(),
                actorUuid == null ? "" : actorUuid,
                actorName == null || actorName.isBlank() ? "环境" : actorName,
                stackId, dimension, pos.getX(), pos.getY(), pos.getZ(), action,
                blockId(before), blockId(after));
        synchronized (ENTRIES) {
            ENTRIES.addFirst(entry);
            while (ENTRIES.size() > MAX_ENTRIES) {
                ENTRIES.removeLast();
            }
        }
    }

    public static synchronized List<Map<String, Object>> recent(int limit) {
        int max = Math.max(1, Math.min(limit, MAX_ENTRIES));
        List<Map<String, Object>> out = new ArrayList<>(Math.min(max, ENTRIES.size()));
        synchronized (ENTRIES) {
            for (Entry entry : ENTRIES) {
                out.add(entry.toMap());
                if (out.size() >= max) break;
            }
        }
        return out;
    }

    private static String blockId(BlockState state) {
        try {
            return BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        } catch (RuntimeException e) {
            return "unknown";
        }
    }

    private record Entry(long timeMs, String actorUuid, String actorName, String stackId,
                         String dimension, int x, int y, int z, String action,
                         String beforeBlock, String afterBlock) {
        Map<String, Object> toMap() {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("timeMs", timeMs);
            out.put("actorUuid", actorUuid);
            out.put("actor", actorName);
            out.put("stackId", stackId);
            out.put("dimension", dimension);
            out.put("x", x);
            out.put("y", y);
            out.put("z", z);
            out.put("action", action);
            out.put("before", beforeBlock);
            out.put("after", afterBlock);
            return out;
        }
    }
}
