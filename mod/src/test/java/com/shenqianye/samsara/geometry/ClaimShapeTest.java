package com.shenqianye.samsara.geometry;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「旗形 → 保护形状」规则单元测试（2026-09-10 新增）。
 * <p>
 * 验证的是 {@link Geometry#ringSegments} 与 {@link Geometry#compute} 组合出的形状规则，
 * 即 {@code ClaimNode.getShapeSegments()} 交给「化线为面」的那套线段：
 * <b>8 条延伸射线 + 末端环</b>。
 * <p>
 * 纯 Java 无 MC 依赖（ClaimNode 依赖 MC 类型无法直接单测，故在此复现其线段构造）。
 * <p>
 * 形状结论（延伸距离同为 d）：
 * <ul>
 *   <li>`border`（8 方向）→ 末端 = 四角 + 四边中点 → **实心正方形**（(2d+1)²）</li>
 *   <li>`cross`（4 斜向）→ 末端 = 四角 → **实心正方形**（(2d+1)²）</li>
 *   <li>`straight_cross`（4 正方向）→ 末端 = 四边中点 → **实心菱形**（|dx|+|dz| ≤ d）</li>
 *   <li>单条条纹（1 方向）→ 不成环 → **只有线**</li>
 *   <li>`stripe_center`（前+后，2 方向）→ 不成环 → **只有线**</li>
 * </ul>
 */
class ClaimShapeTest {

    /** 8 方向单位向量，按角度顺序（北→东北→东→东南→南→西南→西→西北） */
    private static final int[][] CCW_ANGULAR = {
            {0, -1},   // 北
            {1, -1},   // 东北
            {1, 0},    // 东
            {1, 1},    // 东南
            {0, 1},    // 南
            {-1, 1},   // 西南
            {-1, 0},   // 西
            {-1, -1},  // 西北
    };

    /**
     * 复现 ClaimNode 的线段构造：给定方向集合与统一距离，产出"射线 + 末端环"。
     *
     * @param dirs 角序方向索引（{@link #CCW_ANGULAR} 的下标），必须按角度顺序给出
     */
    private static List<int[]> shapeSegments(int cx, int cz, int d, int... dirs) {
        List<int[]> segs = new ArrayList<>();
        List<ChunkCoord> ends = new ArrayList<>();
        for (int i : dirs) {
            int dx = CCW_ANGULAR[i][0] * d;
            int dz = CCW_ANGULAR[i][1] * d;
            segs.add(new int[]{cx, cz, cx + dx, cz + dz});
            ends.add(new ChunkCoord(cx + dx, cz + dz));
        }
        segs.addAll(Geometry.ringSegments(ends));
        return segs;
    }

    private static Set<ChunkCoord> compute(int cx, int cz, List<int[]> segs) {
        return Geometry.compute(Set.of(new ChunkCoord(cx, cz)), segs);
    }

    // ========================================================================
    // ringSegments 自身
    // ========================================================================

    @Test
    void ring_needsAtLeastThreeEndpoints() {
        assertTrue(Geometry.ringSegments(null).isEmpty());
        assertTrue(Geometry.ringSegments(List.of()).isEmpty());
        assertTrue(Geometry.ringSegments(List.of(new ChunkCoord(0, -3))).isEmpty(),
                "单末端是孤立方向，不成环");
        assertTrue(Geometry.ringSegments(List.of(new ChunkCoord(0, -3), new ChunkCoord(0, 3))).isEmpty(),
                "两个（即使相对的）末端只能连成一条线，围不出面积");
    }

    @Test
    void ring_closesWithFourEndpoints() {
        List<int[]> ring = Geometry.ringSegments(List.of(
                new ChunkCoord(0, -3), new ChunkCoord(3, 0),
                new ChunkCoord(0, 3), new ChunkCoord(-3, 0)));
        assertEquals(4, ring.size(), "4 个末端应连成 4 条边并闭合");
    }

    // ========================================================================
    // border（全部 8 方向）→ 实心正方形
    // ========================================================================

    @Test
    void border_threeBlockDistance_yieldsSolidSquare() {
        int d = 3;
        Set<ChunkCoord> r = compute(0, 0, shapeSegments(0, 0, d, 0, 1, 2, 3, 4, 5, 6, 7));

        // 期望：x∈[-3,3] × z∈[-3,3] 的实心正方形，共 49 格
        assertEquals(49, r.size(), "border 应为 (2d+1)² = 49 格的实心正方形");
        for (int x = -d; x <= d; x++) {
            for (int z = -d; z <= d; z++) {
                assertTrue(r.contains(new ChunkCoord(x, z)),
                        "缺少格点 (" + x + "," + z + ")——应被填充为实心");
            }
        }
        // 正方形之外不应多出格点（斜向末端正是四角，不越界）
        assertFalse(r.contains(new ChunkCoord(4, 0)));
        assertFalse(r.contains(new ChunkCoord(0, 4)));
    }

    @Test
    void border_oneBlockDistance_yieldsThreeByThree() {
        Set<ChunkCoord> r = compute(0, 0, shapeSegments(0, 0, 1, 0, 1, 2, 3, 4, 5, 6, 7));
        assertEquals(9, r.size(), "d=1 时 border 应为 3×3 实心正方形");
    }

    // ========================================================================
    // cross（4 斜向）→ 实心正方形；straight_cross（4 正方向）→ 实心菱形
    // ========================================================================

    @Test
    void cross_fourDiagonals_yieldsSquare() {
        int d = 3;
        Set<ChunkCoord> r = compute(0, 0, shapeSegments(0, 0, d, 1, 3, 5, 7)); // 东北/东南/西南/西北
        assertEquals(49, r.size(), "cross 的末端是正方形四角 → 实心正方形");
        assertTrue(r.contains(new ChunkCoord(3, 3)));
        assertTrue(r.contains(new ChunkCoord(-3, -3)));
    }

    @Test
    void straightCross_fourCardinals_yieldsDiamond() {
        int d = 3;
        Set<ChunkCoord> r = compute(0, 0, shapeSegments(0, 0, d, 0, 2, 4, 6)); // 北/东/南/西
        // 菱形 |x| + |z| ≤ 3：1 + 4*(1+2+3) = 25 格
        assertEquals(25, r.size(), "straight_cross 的末端是四边中点 → 实心菱形");
        assertTrue(r.contains(new ChunkCoord(0, 3)));
        assertTrue(r.contains(new ChunkCoord(3, 0)));
        assertFalse(r.contains(new ChunkCoord(3, 3)), "菱形不含四角");
        assertFalse(r.contains(new ChunkCoord(2, 2)), "2+2=4 > 3，在菱形之外");
    }

    // ========================================================================
    // 不成环的情形：只有线
    // ========================================================================

    @Test
    void singleStripe_isLineOnly() {
        int d = 3;
        Set<ChunkCoord> r = compute(0, 0, shapeSegments(0, 0, d, 2)); // 仅东
        // 中心 + 3 格 = 4 格，且不含任何偏离该行的格点
        assertEquals(4, r.size(), "单方向延伸只有线");
        assertTrue(r.contains(new ChunkCoord(3, 0)));
        for (int x = 0; x <= d; x++) {
            assertTrue(r.contains(new ChunkCoord(x, 0)));
        }
        assertFalse(r.contains(new ChunkCoord(1, 1)), "不应凭空出现（未圈住任何面积）");
    }

    @Test
    void centerStripe_twoOppositeDirections_isLineOnly() {
        int d = 3;
        Set<ChunkCoord> r = compute(0, 0, shapeSegments(0, 0, d, 0, 4)); // 北 + 南
        // 一条竖线：中心 + 北 3 + 南 3 = 7 格
        assertEquals(7, r.size(), "竖条（前后两方向）仍是不闭合的线");
        assertFalse(r.contains(new ChunkCoord(1, 0)), "线旁边不应出现填充");
        assertFalse(r.contains(new ChunkCoord(0, 4)));
    }

    // ========================================================================
    // 多旗帜：线段汇总后应取并集
    // ========================================================================

    @Test
    void multipleBanners_unionOfShapes() {
        int d = 2;
        List<int[]> all = new ArrayList<>();
        all.addAll(shapeSegments(0, 0, d, 0, 1, 2, 3, 4, 5, 6, 7));      // 正方形 [-2,2]²
        all.addAll(shapeSegments(20, 0, d, 0, 1, 2, 3, 4, 5, 6, 7));     // 正方形 [18,22]×[-2,2]
        Set<ChunkCoord> r = Geometry.compute(
                Set.of(new ChunkCoord(0, 0), new ChunkCoord(20, 0)), all);

        assertEquals(50, r.size(), "两块互不接触的正方形各 25 格，取并集");
        assertTrue(r.contains(new ChunkCoord(2, 2)));
        assertTrue(r.contains(new ChunkCoord(22, -2)));
        assertFalse(r.contains(new ChunkCoord(10, 0)), "两块之间不应被填充");
    }
}
