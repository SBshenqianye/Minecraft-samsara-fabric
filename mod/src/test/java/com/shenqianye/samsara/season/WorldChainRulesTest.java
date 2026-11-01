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
        // 主世界：minY=-64，height=384 → maxBuild=320，y>320 触发
        assertTrue(WorldChainRules.isTop(321, -64, 384, WorldChainRules.DimensionKind.OVERWORLD));
        assertFalse(WorldChainRules.isTop(320, -64, 384, WorldChainRules.DimensionKind.OVERWORLD));
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
}
