package com.shenqianye.samsara.claim;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

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
    void patternOverrideWinsOverDyeAndFixed() {
        Map<String, Integer> patterns = Map.of("border", 9, "cross", 2);
        assertEquals(9, ClaimRangeConfig.resolveDistance(false, 4,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, patterns, "border", 16, 0));
        assertEquals(2, ClaimRangeConfig.resolveDistance(true, 6,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, patterns, "cross", 16, 0));
        assertEquals(6, ClaimRangeConfig.resolveDistance(true, 6,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, patterns, "border2", 16, 0));
    }

    @Test
    void patternOverrideStillRespectsMaxDistance() {
        Map<String, Integer> patterns = Map.of("border", 24);
        assertEquals(16, ClaimRangeConfig.resolveDistance(false, 4,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, patterns, "border", 16, 0));
    }

    @Test
    void shortTableFallsBackToColorPlusOne() {
        assertEquals(8, ClaimRangeConfig.resolveDistance(false, 4,
                List.of(1, 2, 3), 16, 7));
    }
}
