package com.shenqianye.samsara.geometry;

import com.shenqianye.samsara.claim.ClaimRangeConfig;
import com.shenqianye.samsara.claim.ClaimShape;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 「旗帜图案 → 射线 → 全局化线为面」规则单元测试（2026-11-02 层级修正）。
 *
 * <h3>被验证的层级</h3>
 * <ol>
 *   <li>单面旗帜只产生射线：图案方向 + 图案颜色距离；</li>
 *   <li>同一面旗帜叠加多个图案 = 射线并集，仍然只是线；</li>
 *   <li>单旗永远不生成末端环，也不单独调用化线为面；</li>
 *   <li>所有旗帜的射线汇总后交给 {@link Geometry#compute}，只有全局闭合面才填充。</li>
 * </ol>
 *
 * <p>纯 Java，无 MC 依赖；{@link ClaimShape} 与 Web 端 {@code claimRaySegments}
 * 使用同一套默认方向表。</p>
 */
class ClaimShapeTest {

    private static Set<ChunkCoord> cells(String pattern, int distance, String facing) {
        return ClaimShape.cells(pattern, distance, facing);
    }

    private static boolean has(Set<ChunkCoord> cells, int x, int z) {
        return cells.contains(new ChunkCoord(x, z));
    }

    /** 把若干面旗帜的射线汇总后做一次全局化线为面。 */
    private static Set<ChunkCoord> globalCells(List<Banner> banners) {
        Set<ChunkCoord> nodes = new HashSet<>();
        List<int[]> segments = new ArrayList<>();
        for (Banner banner : banners) {
            nodes.add(new ChunkCoord(banner.x, banner.z));
            for (int[] segment : ClaimShape.raySegments(banner.pattern, banner.distance, banner.facing,
                    ClaimShape.defaultDirections(banner.pattern))) {
                segments.add(new int[]{
                        banner.x + segment[0], banner.z + segment[1],
                        banner.x + segment[2], banner.z + segment[3]});
            }
        }
        return Geometry.compute(nodes, segments);
    }

    private record Banner(int x, int z, String pattern, int distance, String facing) {
        Banner(int x, int z, String pattern, int distance) {
            this(x, z, pattern, distance, "north");
        }
    }

    // ========================================================================
    // 单面旗帜：只有线
    // ========================================================================

    @Test
    void border_singleBanner_isRingOnly() {
        int d = 3;
        Set<ChunkCoord> r = cells("border", d, "north");

        // 边框 = 7×7 的周长 24 格 + 旗帜所在区块 = 25 格；内部不填充。
        assertEquals(25, r.size(), "单面 border = 周长 + 旗帜所在区块");
        assertTrue(has(r, 0, -d), "北边缺少格");
        assertTrue(has(r, d, -d), "东北角缺少格");
        assertTrue(has(r, d, 0), "东边缺少格");
        assertTrue(has(r, 0, d), "南边缺少格");
        assertTrue(has(r, -d, 0), "西边缺少格");
        assertTrue(has(r, 0, 0), "旗帜所在区块必须被保护");
        assertFalse(has(r, 2, 1), "边框不填充内部");
        assertFalse(has(r, 4, 0), "边框不超出距离");
    }

    @Test
    void border_distanceTwo_isSixteenCellRing() {
        Set<ChunkCoord> r = cells("border", 2, "north");
        assertEquals(17, r.size(), "全边框距离 2 = 5×5 周长 16 格 + 旗帜区块");
        assertTrue(has(r, -2, -2));
        assertTrue(has(r, 2, 2));
        assertTrue(has(r, 0, 0), "旗帜所在区块必须被保护");
        assertFalse(has(r, 1, 0), "内部留空");
    }

    @Test
    void border_withoutBorderDirection_stillRaysOnly() {
        int d = 3;
        Set<ChunkCoord> r = ClaimShape.cells("border", d, "north", List.of(
                "north", "northeast", "east", "southeast",
                "south", "southwest", "west", "northwest"));
        assertEquals(25, r.size(), "不选边框时退回八向射线 + 中心，共 25 格");
        assertFalse(has(r, 2, 1), "没有边框时不能自己闭合");
    }

    @Test
    void cross_singleBanner_isFourDiagonalRays() {
        int d = 3;
        Set<ChunkCoord> r = cells("cross", d, "north");
        assertEquals(13, r.size(), "cross = 中心 + 4 条斜向射线 × 3 = 13 格");
        for (int i = 1; i <= d; i++) {
            assertTrue(has(r, i, -i));
            assertTrue(has(r, i, i));
            assertTrue(has(r, -i, i));
            assertTrue(has(r, -i, -i));
        }
        assertFalse(has(r, 0, 1), "cross 不应包含正南格");
        assertFalse(has(r, 1, 0), "cross 不应包含正东格");
        assertFalse(has(r, 2, 1), "cross 不应填充内部");
    }

    @Test
    void straightCross_singleBanner_isFourCardinalRays() {
        int d = 3;
        Set<ChunkCoord> r = cells("straight_cross", d, "north");
        assertEquals(13, r.size(), "straight_cross = 中心 + 4 条正向射线 × 3 = 13 格");
        for (int i = 1; i <= d; i++) {
            assertTrue(has(r, 0, -i));
            assertTrue(has(r, i, 0));
            assertTrue(has(r, 0, i));
            assertTrue(has(r, -i, 0));
        }
        assertFalse(has(r, 1, 1), "straight_cross 不应包含斜向格");
        assertFalse(has(r, 2, 1), "straight_cross 不应填充内部");
    }

    @Test
    void stripeTop_singleBanner_includesIntermediateCells() {
        int d = 3;
        Set<ChunkCoord> r = cells("stripe_top", d, "north");
        assertEquals(4, r.size(), "stripe_top = 中心 + 北向 3 格");
        assertTrue(has(r, 0, 0));
        assertTrue(has(r, 0, -1));
        assertTrue(has(r, 0, -2));
        assertTrue(has(r, 0, -3));
        assertFalse(has(r, 1, 0), "端点以外不应出现横向格");
        assertFalse(has(r, 0, -4), "不应超出距离");
    }

    @Test
    void stripeCenter_singleBanner_isVerticalLine() {
        int d = 3;
        Set<ChunkCoord> r = cells("stripe_center", d, "north");
        assertEquals(7, r.size(), "stripe_center = 中心 + 南北各 3 格 = 7 格");
        for (int z = -d; z <= d; z++) {
            assertTrue(has(r, 0, z), "缺少中间竖格 (0," + z + ")");
        }
        assertFalse(has(r, 1, 0), "竖线旁边不应填充");
    }

    @Test
    void squareTopLeft_singleBanner_isDiagonalRayNotQuadrant() {
        int d = 3;
        Set<ChunkCoord> r = cells("square_top_left", d, "north");
        assertEquals(4, r.size(), "square_top_left 单旗只应有西北向 4 格，不是 16 格象限面");
        assertTrue(has(r, 0, 0));
        assertTrue(has(r, -1, -1));
        assertTrue(has(r, -2, -2));
        assertTrue(has(r, -3, -3));
        assertFalse(has(r, -1, 0), "不应填充整个左上象限");
        assertFalse(has(r, 0, -1), "不应填充整个左上象限");
    }

    @Test
    void stripeDownLeft_singleBanner_isTwoDiagonalRays() {
        int d = 3;
        Set<ChunkCoord> r = cells("stripe_downleft", d, "north");
        assertEquals(7, r.size(), "stripe_downleft = 中心 + 东北/西南两条射线 × 3 = 7 格");
        for (int i = 1; i <= d; i++) {
            assertTrue(has(r, i, -i), "缺少东北向格");
            assertTrue(has(r, -i, i), "缺少西南向格");
        }
        assertFalse(has(r, 2, 0), "斜纹不应变成宽度带");
        assertFalse(has(r, 1, -2), "斜纹不应变成宽度带");
    }

    // ========================================================================
    // 朝向：只旋转射线，单旗仍不填充
    // ========================================================================

    @Test
    void facingRotatesRays_only() {
        int d = 3;
        Set<ChunkCoord> north = cells("stripe_top", d, "north");
        Set<ChunkCoord> east = cells("stripe_top", d, "east");
        Set<ChunkCoord> south = cells("stripe_top", d, "south");
        Set<ChunkCoord> west = cells("stripe_top", d, "west");

        assertTrue(has(north, 0, -3));
        assertTrue(has(east, 3, 0), "朝东时北向射线应旋转到东");
        assertTrue(has(south, 0, 3), "朝南时应旋转到南");
        assertTrue(has(west, -3, 0), "朝西时应旋转到西");
        assertFalse(has(east, 0, -3), "旋转后不应保留原方向");
        assertEquals(4, east.size(), "旋转不改变射线数量，也不产生填充");
        assertFalse(has(east, 1, 1), "单旗旋转后仍不填充");
    }

    // ========================================================================
    // 多图案层：射线并集，不触发填充
    // ========================================================================

    @Test
    void multiplePatternLayers_unionRaysWithoutFill() {
        Set<ChunkCoord> cells = new HashSet<>();
        cells.add(new ChunkCoord(0, 0));
        ClaimShape.rasterize(new int[]{0, 0, 2, 0}, cells);   // 东 2 格
        ClaimShape.rasterize(new int[]{0, 0, 0, -3}, cells);  // 北 3 格
        assertEquals(6, cells.size(), "中心 + 东 2 + 北 3 = 6 格（只有线）");
        assertFalse(cells.contains(new ChunkCoord(1, -1)), "多图案叠加不应填充");
        assertFalse(cells.contains(new ChunkCoord(2, -3)), "多图案叠加不应填充");
    }

    // ========================================================================
    // 多面旗帜：只有全局闭合面才填充
    // ========================================================================

    @Test
    void multipleBanners_globalClosedRegionIsFilled() {
        // 三面 straight_cross 旗（d=4）围出一个 4×4 的矩形：
        // (0,0) 提供西/南边，(0,4) 提供北/东边，(4,0) 提供东/南边。
        List<Banner> banners = List.of(
                new Banner(0, 0, "straight_cross", 4),
                new Banner(0, 4, "straight_cross", 4),
                new Banner(4, 0, "straight_cross", 4));
        Set<ChunkCoord> r = globalCells(banners);

        // 边界存在
        assertTrue(r.contains(new ChunkCoord(0, 0)));
        assertTrue(r.contains(new ChunkCoord(4, 0)));
        assertTrue(r.contains(new ChunkCoord(0, 4)));
        assertTrue(r.contains(new ChunkCoord(4, 4)));
        // 25 格矩形边界/内部 + 6 条 4 格外延 = 49；内部 9 格由全局填充得到
        assertEquals(49, r.size(), "三面 straight_cross d=4 应形成 49 格（含闭合矩形内部）");
        assertTrue(r.contains(new ChunkCoord(2, 2)), "全局闭合矩形内部应被填充");
        assertTrue(r.contains(new ChunkCoord(1, 2)), "全局闭合矩形内部应被填充");
        assertTrue(r.contains(new ChunkCoord(3, 2)), "全局闭合矩形内部应被填充");
    }

    @Test
    void singleBanner_borderDoesNotFillInterior() {
        List<Banner> one = List.of(new Banner(0, 0, "border", 4));
        Set<ChunkCoord> r = globalCells(one);
        // 边框本身不在全局填充图里：单旗只贡献旗帜所在区块。
        assertFalse(r.contains(new ChunkCoord(0, -4)), "边框不参与全局化线为面");
        assertFalse(r.contains(new ChunkCoord(2, 1)), "边框不填充内部");
    }

    @Test
    void multipleBanners_doNotFillWhenRaysDoNotClose() {
        // 两面 straight_cross 相距很远：射线不闭合，中间不应被连起来。
        List<Banner> banners = List.of(
                new Banner(0, 0, "straight_cross", 3),
                new Banner(20, 0, "straight_cross", 3));
        Set<ChunkCoord> r = globalCells(banners);
        assertTrue(r.contains(new ChunkCoord(3, 0)));
        assertTrue(r.contains(new ChunkCoord(23, 0)));
        assertFalse(r.contains(new ChunkCoord(10, 0)), "两面旗帜之间不应被填充");
    }

    @Test
    void defaultRayTableMatchesRuntimeConfig() {
        // Web 的 claimRaySegments 与 ClaimShape 共用这套默认；服务端 ClaimRangeConfig
        // 的默认方向也必须同源，避免游戏内保护与 Web 预览出现方向差异。
        for (String pattern : ClaimShape.DEFAULT_RAYS.keySet()) {
            assertEquals(ClaimShape.DEFAULT_RAYS.get(pattern),
                    ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get(pattern),
                    "图案 " + pattern + " 的默认射线方向不一致");
        }
    }

    @Test
    void differentColorDistances_unionOfRays() {
        // 同一面旗帜的射线距离随颜色变化；border 走单独的边框白名单，不在此图里。
        List<Banner> shortRays = List.of(new Banner(0, 0, "straight_cross", 2));
        Set<ChunkCoord> r = globalCells(shortRays);
        assertTrue(r.contains(new ChunkCoord(2, 0)));
        assertFalse(r.contains(new ChunkCoord(3, 0)));

        Set<ChunkCoord> longer = globalCells(List.of(new Banner(0, 0, "straight_cross", 4)));
        assertTrue(longer.contains(new ChunkCoord(4, 0)));
        assertFalse(longer.contains(new ChunkCoord(5, 0)));
        assertFalse(longer.contains(new ChunkCoord(1, 1)), "只有射线时内部不填充");
    }
}
