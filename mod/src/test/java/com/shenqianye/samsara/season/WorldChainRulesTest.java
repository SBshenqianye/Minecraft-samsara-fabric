package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 跨世界传送阈值与坐标缩放（纯逻辑）。 */
class WorldChainRulesTest {

    @Test
    void voidThresholdIs32BelowMinY() {
        assertTrue(WorldChainRules.isVoid(-97.0, -64));
        assertFalse(WorldChainRules.isVoid(-96.0, -64));
        assertTrue(WorldChainRules.isVoid(-33.0, 0));
        assertFalse(WorldChainRules.isVoid(-32.0, 0));
    }

    @Test
    void topThresholdMatchesBuildLimits() {
        // 主世界：minY=-64，height=384 → maxBuild=320，超出建筑限制 5 格（y>325）触发
        assertTrue(WorldChainRules.isTop(326, -64, 384, WorldChainRules.DimensionKind.OVERWORLD));
        assertFalse(WorldChainRules.isTop(321, -64, 384, WorldChainRules.DimensionKind.OVERWORLD));
        assertFalse(WorldChainRules.isTop(325, -64, 384, WorldChainRules.DimensionKind.OVERWORLD));
        // 下界：minY=0，height=256 → y>255 触发
        assertTrue(WorldChainRules.isTop(256, 0, 256, WorldChainRules.DimensionKind.NETHER));
        assertFalse(WorldChainRules.isTop(255, 0, 256, WorldChainRules.DimensionKind.NETHER));
        // 末地不参与
        assertFalse(WorldChainRules.isTop(1000, 0, 256, WorldChainRules.DimensionKind.END));
    }

    @Test
    void coordinateScalingUsesEightToOne() {
        assertEquals(12, WorldChainRules.toNether(100.5));
        assertEquals(-13, WorldChainRules.toNether(-100.5));
        assertEquals(100, WorldChainRules.toOverworld(12.5));
        assertEquals(-100, WorldChainRules.toOverworld(-12.5));
    }

    @Test
    void voidDropYIsHighAboveGroundButBelowTopThreshold() {
        // 26.3 主世界：minY=-64，height=384 → 320 - 16 = 304
        assertEquals(304, WorldChainRules.voidDropY(-64, 384));
        assertTrue(WorldChainRules.voidDropY(-64, 384) < -64 + 384);
        assertFalse(WorldChainRules.isTop(
                WorldChainRules.voidDropY(-64, 384), -64, 384,
                WorldChainRules.DimensionKind.OVERWORLD));
    }

    @Test
    void voidDropYClampsToDimensionBounds() {
        // 低高度维度按传入的 minY/height 计算，不写死 320。
        assertEquals(112, WorldChainRules.voidDropY(0, 128));
        assertEquals(240, WorldChainRules.voidDropY(0, 256));
        assertEquals(176, WorldChainRules.voidDropY(64, 128));
        // 目标值低于下限时钳到 minY + 2
        assertEquals(2, WorldChainRules.voidDropY(0, 16));
        assertEquals(-62, WorldChainRules.voidDropY(-64, 16));
        for (int height : new int[] { 16, 32, 64, 128, 256, 384, 512 }) {
            int y = WorldChainRules.voidDropY(-64, height);
            assertTrue(y >= -62, "落点不能低于 minY + 2");
            assertTrue(y <= -64 + height - 2, "落点不能高于 minY + height - 2");
            assertTrue(y < -64 + height, "落点必须严格低于顶部阈值");
        }
    }

    @Test
    void voidDropGuardSuppressesTopOnlyUntilExpiry() {
        assertTrue(WorldChainRules.isVoidDropGuardActive(1_000L, 6_000L));
        assertFalse(WorldChainRules.isVoidDropGuardActive(6_000L, 6_000L));
        assertFalse(WorldChainRules.isVoidDropGuardActive(7_000L, 6_000L));
        // 过期后“主世界顶部 → 上层下界”恢复
        int overworldTop = 326;
        assertFalse(WorldChainRules.isVoidDropGuardActive(6_000L, 6_000L));
        assertTrue(WorldChainRules.isTop(overworldTop, -64, 384,
                WorldChainRules.DimensionKind.OVERWORLD));
    }
}
