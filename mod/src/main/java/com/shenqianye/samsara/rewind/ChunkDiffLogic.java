package com.shenqianye.samsara.rewind;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单区块 diff 的纯逻辑：时间筛选、按方块取目标状态、预览计数。
 * <p>
 * 不依赖 Minecraft / 文件系统，便于单测；服务层只负责读记录、保护判定与 setBlock。
 */
public final class ChunkDiffLogic {
    private ChunkDiffLogic() {
    }

    public record Pos(int x, int y, int z) {
    }

    public record BlockChange(int x, int y, int z, int stateId, long timeMs) {
    }

    public record Stats(int editCount, int blockCount, long earliestMs, long latestMs,
                        long targetMs) {
    }

    /** 默认目标时间：最早记录之前 1ms（即完整回溯到该区块历史起点之前）。 */
    public static long defaultTarget(List<DiffStore.Edit> edits) {
        long earliest = Long.MAX_VALUE;
        for (DiffStore.Edit e : edits) {
            earliest = Math.min(earliest, e.timeMs);
        }
        if (earliest == Long.MAX_VALUE) {
            return 0;
        }
        return earliest - 1;
    }

    /**
     * 目标时刻的状态：对每个方块取“时间 &gt; target 的最早一条 edit 的 pre-state”；
     * 没有则说明该方块在 target 之后没有变化，不需要写。
     */
    public static List<BlockChange> plan(List<DiffStore.Edit> edits, long targetMs) {
        Map<Pos, DiffStore.Edit> chosen = new LinkedHashMap<>();
        for (DiffStore.Edit e : edits) {
            if (e.timeMs <= targetMs) {
                continue;
            }
            Pos pos = new Pos(e.x, e.y, e.z);
            DiffStore.Edit old = chosen.get(pos);
            if (old == null || e.timeMs < old.timeMs) {
                chosen.put(pos, e);
            }
        }
        List<BlockChange> out = new ArrayList<>(chosen.size());
        for (Map.Entry<Pos, DiffStore.Edit> entry : chosen.entrySet()) {
            Pos p = entry.getKey();
            DiffStore.Edit e = entry.getValue();
            out.add(new BlockChange(p.x(), p.y(), p.z(), e.stateId, e.timeMs));
        }
        out.sort(Comparator.comparingLong(BlockChange::timeMs)
                .thenComparingInt(BlockChange::y)
                .thenComparingInt(BlockChange::x)
                .thenComparingInt(BlockChange::z));
        return out;
    }

    public static Stats stats(List<DiffStore.Edit> edits, long targetMs) {
        long earliest = 0;
        long latest = 0;
        boolean first = true;
        for (DiffStore.Edit e : edits) {
            if (first) {
                earliest = e.timeMs;
                latest = e.timeMs;
                first = false;
            } else {
                earliest = Math.min(earliest, e.timeMs);
                latest = Math.max(latest, e.timeMs);
            }
        }
        return new Stats(edits.size(), plan(edits, targetMs).size(), earliest, latest, targetMs);
    }

    /** 保存型回溯前置条件：圈地保护与倒流进行中都必须拒绝。 */
    public static boolean canApply(boolean protectedArea, boolean rewindRunning) {
        return !protectedArea && !rewindRunning;
    }

    /** 第一个 timeMs &gt; targetMs 的下标（edits 必须按时间升序）；没有则返回 size。 */
    public static int indexAfter(List<DiffStore.Edit> edits, long targetMs) {
        int low = 0;
        int high = edits.size();
        while (low < high) {
            int mid = (low + high) >>> 1;
            if (edits.get(mid).timeMs <= targetMs) {
                low = mid + 1;
            } else {
                high = mid;
            }
        }
        return low;
    }

    /** 只读回看：目标时刻的方块状态 id（edits 按时间升序）。 */
    public static int stateAt(List<DiffStore.Edit> edits, long targetMs, int currentStateId) {
        int index = indexAfter(edits, targetMs);
        return index < edits.size() ? edits.get(index).stateId : currentStateId;
    }
}
