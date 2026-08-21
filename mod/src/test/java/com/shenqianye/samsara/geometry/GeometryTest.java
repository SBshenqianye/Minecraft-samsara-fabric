package com.shenqianye.samsara.geometry;

import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 化线为面算法单元测试（设计文档 §6.3.4，9 个用例）。
 * 纯 Java，无 MC 依赖，可脱离服务端运行。
 */
class GeometryTest {

    private static ChunkCoord c(int x, int z) {
        return new ChunkCoord(x, z);
    }

    /** 辅助：快捷构造线段 [x1,z1,x2,z2] */
    private static int[] seg(int x1, int z1, int x2, int z2) {
        return new int[]{x1, z1, x2, z2};
    }

    // ========================================================================
    // §6.3.4 用例 1：单个节点（孤立点）→ 只有该区块
    // ========================================================================
    @Test
    void test01_singleIsolatedNode() {
        Set<ChunkCoord> nodes = Set.of(c(5, 10));
        Set<ChunkCoord> result = Geometry.compute(nodes, null);
        assertEquals(Set.of(c(5, 10)), result);
    }

    // ========================================================================
    // §6.3.4 用例 2：单条线段（两节点）→ 线上所有格点
    // ========================================================================
    @Test
    void test02_singleSegment() {
        // (0,0) → (3,3) 对角线：gcd(3,3)=3 → 4 个格点
        List<int[]> segs = List.of(seg(0, 0, 3, 3));
        Set<ChunkCoord> result = Geometry.compute(null, segs);
        assertEquals(Set.of(c(0,0), c(1,1), c(2,2), c(3,3)), result);
    }

    @Test
    void test02b_singleHorizontalSegment() {
        // (0,0) → (4,0) 水平线：5 个格点
        List<int[]> segs = List.of(seg(0, 0, 4, 0));
        Set<ChunkCoord> result = Geometry.compute(null, segs);
        assertEquals(Set.of(c(0,0), c(1,0), c(2,0), c(3,0), c(4,0)), result);
    }

    // ========================================================================
    // §6.3.4 用例 3：正交正方形四角 → 边界 + 内部
    // ========================================================================
    @Test
    void test03_orthogonalSquare() {
        // 正方形 (0,0)-(3,0)-(3,3)-(0,3)
        List<int[]> segs = List.of(
                seg(0,0, 3,0),  // 上边
                seg(3,0, 3,3),  // 右边
                seg(3,3, 0,3),  // 下边
                seg(0,3, 0,0)   // 左边
        );
        Set<ChunkCoord> result = Geometry.compute(null, segs);

        // 边界：12 个点（4边×3段 - 4角重复）
        // 内部：4 个点 (1,1)(1,2)(2,1)(2,2)
        // 总计：16 个点
        assertEquals(16, result.size());
        assertTrue(result.contains(c(1,1)));
        assertTrue(result.contains(c(1,2)));
        assertTrue(result.contains(c(2,1)));
        assertTrue(result.contains(c(2,2)));
        // 边界点都在
        for (int i = 0; i <= 3; i++) {
            assertTrue(result.contains(c(i, 0)));
            assertTrue(result.contains(c(i, 3)));
            assertTrue(result.contains(c(0, i)));
            assertTrue(result.contains(c(3, i)));
        }
    }

    // ========================================================================
    // §6.3.4 用例 4：斜边菱形 → 边界 + 内部（含格点）
    // ========================================================================
    @Test
    void test04_diagonalDiamond() {
        // 菱形 (0,2)-(2,0)-(4,2)-(2,4)
        List<int[]> segs = List.of(
                seg(0,2, 2,0),
                seg(2,0, 4,2),
                seg(4,2, 2,4),
                seg(2,4, 0,2)
        );
        Set<ChunkCoord> result = Geometry.compute(null, segs);

        // 边界：(0,2)(1,1)(2,0)(3,1)(4,2)(3,3)(2,4)(1,3) = 8 点
        // 内部：(1,2)(2,1)(2,2)(2,3)(3,2) = 5 点
        // 总计：13 点
        assertTrue(result.size() >= 12, "Diamond should have >=12 points, got " + result.size());
        assertTrue(result.contains(c(2, 2)), "Center should be interior");
        assertTrue(result.contains(c(1, 2)), "Interior point");
        assertTrue(result.contains(c(2, 1)), "Interior point");
    }

    // ========================================================================
    // §6.3.4 用例 5：两条斜线十字交叉 → 正确连通、无重复、无悬挂
    // ========================================================================
    @Test
    void test05_crossingDiagonals() {
        // (0,0)→(4,4) 和 (0,4)→(4,0) 交叉于 (2,2)
        List<int[]> segs = List.of(
                seg(0,0, 4,4),
                seg(0,4, 4,0)
        );
        Set<ChunkCoord> result = Geometry.compute(null, segs);

        // 交叉点 (2,2) 应在结果中
        assertTrue(result.contains(c(2,2)));
        // 两条线上所有格点都应存在
        // 第一条：(0,0)(1,1)(2,2)(3,3)(4,4)
        // 第二条：(0,4)(1,3)(2,2)(3,1)(4,0)
        // 合计去重：9 个点
        assertEquals(9, result.size());
    }

    // ========================================================================
    // §6.3.4 用例 6：封闭图形 + 外伸折线 → 并集
    // ========================================================================
    @Test
    void test06_closedShapeWithPolyline() {
        // 小正方形 (0,0)-(2,0)-(2,2)-(0,2) + 外伸折线 (2,2)→(4,4)
        List<int[]> segs = List.of(
                seg(0,0, 2,0),
                seg(2,0, 2,2),
                seg(2,2, 0,2),
                seg(0,2, 0,0),
                seg(2,2, 4,4)   // 外伸折线
        );
        Set<ChunkCoord> result = Geometry.compute(null, segs);

        // 正方形内部：(1,1)
        assertTrue(result.contains(c(1,1)));
        // 正方形边界：所有边界点
        assertTrue(result.contains(c(0,0)));
        assertTrue(result.contains(c(2,2)));
        // 外伸折线点
        assertTrue(result.contains(c(3,3)));
        assertTrue(result.contains(c(4,4)));
    }

    // ========================================================================
    // §6.3.4 用例 7：相邻封闭图形共享边 → 各自内部均正确
    // ========================================================================
    @Test
    void test07_adjacentShapesSharingEdge() {
        // 左正方形 (0,0)-(2,0)-(2,2)-(0,2)
        // 右正方形 (2,0)-(4,0)-(4,2)-(2,2)  共享边 x=2, z=0..2
        List<int[]> segs = List.of(
                seg(0,0, 2,0), seg(2,0, 4,0),
                seg(4,0, 4,2), seg(4,2, 2,2),
                seg(2,2, 0,2), seg(0,2, 0,0),
                seg(2,0, 2,2)   // 共享边
        );
        Set<ChunkCoord> result = Geometry.compute(null, segs);

        // 左正方形内部：(1,1)
        assertTrue(result.contains(c(1,1)));
        // 右正方形内部：(3,1)
        assertTrue(result.contains(c(3,1)));
        // 共享边上的点
        assertTrue(result.contains(c(2,1)));
    }

    // ========================================================================
    // §6.3.4 用例 8：凹多边形 → 内部填充正确
    // ========================================================================
    @Test
    void test08_concavePolygon() {
        // L 形：(0,0)-(3,0)-(3,1)-(1,1)-(1,3)-(0,3)
        List<int[]> segs = List.of(
                seg(0,0, 3,0),
                seg(3,0, 3,1),
                seg(3,1, 1,1),
                seg(1,1, 1,3),
                seg(1,3, 0,3),
                seg(0,3, 0,0)
        );
        Set<ChunkCoord> result = Geometry.compute(null, segs);

        // 应包含 L 形内部
        assertTrue(result.contains(c(1,0)));  // 底部
        assertTrue(result.contains(c(2,0)));  // 底部
        assertTrue(result.contains(c(1,2)));  // 左侧竖

        // 不应包含凹角内部 (2,2) — 它在 L 的缺口处
        // 实际上 (2,2) 在 L 的外面，不应被填充
        assertFalse(result.contains(c(2,2)), "(2,2) is outside the L shape");
    }

    // ========================================================================
    // §6.3.4 用例 9：空输入 / 单线段退化 → 不崩溃
    // ========================================================================
    @Test
    void test09_emptyInput() {
        Set<ChunkCoord> result = Geometry.compute(null, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void test09b_emptyNodesWithSegment() {
        Set<ChunkCoord> result = Geometry.compute(Set.of(), List.of(seg(0,0,1,1)));
        assertEquals(2, result.size());
    }

    @Test
    void test09c_degenerateSinglePoint() {
        Set<ChunkCoord> result = Geometry.compute(Set.of(c(5,5)), List.of(seg(5,5,5,5)));
        assertEquals(Set.of(c(5,5)), result);
    }

    // ========================================================================
    // 辅助方法测试
    // ========================================================================
    @Test
    void testRasterizeDiagonal() {
        List<ChunkCoord> path = Geometry.rasterize(c(0,0), c(3,3));
        assertEquals(4, path.size());
        assertEquals(c(0,0), path.get(0));
        assertEquals(c(3,3), path.get(3));
    }

    @Test
    void testRasterizeHorizontal() {
        List<ChunkCoord> path = Geometry.rasterize(c(0,0), c(5,0));
        assertEquals(6, path.size());
    }

    @Test
    void testRasterizeSamePoint() {
        List<ChunkCoord> path = Geometry.rasterize(c(2,3), c(2,3));
        assertEquals(1, path.size());
        assertEquals(c(2,3), path.get(0));
    }

    @Test
    void testChunkCoordEquality() {
        assertEquals(c(1,2), c(1,2));
        assertNotEquals(c(1,2), c(2,1));
        assertEquals(c(1,2).hashCode(), c(1,2).hashCode());
    }
}
