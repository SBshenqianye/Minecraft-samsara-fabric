package com.shenqianye.samsara.geometry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 圈地几何重建基准（只打印数据，不做时间断言，避免 CI 抖动误报）。
 *
 * <p>用于回答“是否值得做只重算受影响连通域”：当前 add/remove 已按 500ms 合并，
 * 单次仍是全量 {@link Geometry#compute}。
 */
class ClaimGeometryBenchmarkTest {

    @Test
    void largeClaimFullRebuildTiming() {
        Set<ChunkCoord> seeds = new HashSet<>();
        List<int[]> segments = new ArrayList<>();
        // 模拟 8 方向各 100 区块的射线 + 8x8 面，接近“大圈地”规模。
        for (int i = 0; i < 8; i++) {
            int dx = switch (i % 4) {
                case 0 -> 1;
                case 1 -> -1;
                default -> 0;
            };
            int dz = switch (i / 4) {
                case 0 -> 1;
                default -> -1;
            };
            segments.add(new int[]{0, 0, dx * 100, dz * 100});
            seeds.add(new ChunkCoord(dx * 100, dz * 100));
        }
        for (int x = -4; x <= 4; x++) {
            segments.add(new int[]{x, -4, x, 4});
            segments.add(new int[]{-4, x, 4, x});
            seeds.add(new ChunkCoord(x, 0));
        }

        // 预热
        for (int i = 0; i < 3; i++) {
            Geometry.compute(seeds, segments);
        }
        long best = Long.MAX_VALUE;
        int cells = 0;
        for (int i = 0; i < 5; i++) {
            long start = System.nanoTime();
            Set<ChunkCoord> result = Geometry.compute(seeds, segments);
            long cost = System.nanoTime() - start;
            best = Math.min(best, cost);
            cells = result.size();
        }
        System.out.println("[bench] claim geometry: segments=" + segments.size()
                + " seeds=" + seeds.size() + " cells=" + cells
                + " best=" + (best / 1_000_000.0) + " ms");
    }
}
