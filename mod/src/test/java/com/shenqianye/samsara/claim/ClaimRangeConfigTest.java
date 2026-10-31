package com.shenqianye.samsara.claim;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 圈地保护范围：染料表、固定模式与上限截断。 */
class ClaimRangeConfigTest {

    @Test
    void defaultDyeTableKeepsLegacyColorPlusOne() {
        for (int color = 0; color < ClaimRangeConfig.COLOR_COUNT; color++) {
            assertEquals(color + 1, ClaimRangeConfig.resolveDistance(false, 4,
                    ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, 16, color));
        }
    }

    @Test
    void fixedModeIgnoresDyeTable() {
        assertEquals(6, ClaimRangeConfig.resolveDistance(true, 6,
                List.of(1, 2, 3), 16, 15));
    }

    @Test
    void maxDistanceCapsEveryMode() {
        assertEquals(5, ClaimRangeConfig.resolveDistance(false, 4,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, 5, 15));
        assertEquals(5, ClaimRangeConfig.resolveDistance(true, 9,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, 5, 0));
    }

    @Test
    void distanceIsAlwaysPositiveEvenForZeroOrNegativeConfig() {
        assertEquals(1, ClaimRangeConfig.resolveDistance(false, 0,
                List.of(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0), 0, 0));
    }

    @Test
    void outOfRangeColorWrapsLikeDyeOrdinal() {
        assertEquals(1, ClaimRangeConfig.resolveDistance(false, 4,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, 16, 16));
        assertEquals(16, ClaimRangeConfig.resolveDistance(false, 4,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, 16, -1));
    }

    @Test
    void shortTableFallsBackToColorPlusOne() {
        assertEquals(8, ClaimRangeConfig.resolveDistance(false, 4,
                List.of(1, 2, 3), 16, 7));
    }
}
