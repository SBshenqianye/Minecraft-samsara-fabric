package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.config.ConfigSchema;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

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
    void patternDirectionsDescribeUnitLengthShape() {
        assertEquals(List.of("south"), ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get("stripe_top"));
        assertEquals(List.of("north", "east", "south", "west"),
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get("straight_cross"));
        assertEquals(List.of("northeast", "southeast", "southwest", "northwest"),
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get("cross"));
        assertEquals(ClaimRangeConfig.DIRECTION_NAMES,
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get("border"));
    }

    @Test
    void patternDoesNotOverrideColorDistance() {
        assertEquals(6, ClaimRangeConfig.resolveDistance(true, 6,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, 16, 15));
        assertEquals(9, ClaimRangeConfig.resolveDistance(false, 4,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, 16, 8));
    }

    @Test
    void shortTableFallsBackToColorPlusOne() {
        assertEquals(8, ClaimRangeConfig.resolveDistance(false, 4,
                List.of(1, 2, 3), 16, 7));
    }

    @Test
    void webOnlyShowsModeSpecificDistanceEditor() {
        assertEquals("mode=fixed",
                ConfigSchema.entry(ConfigSchema.CLAIM_RANGE, "fixedDistance").visibleWhen());
        assertEquals("mode=dye",
                ConfigSchema.entry(ConfigSchema.CLAIM_RANGE, "colorDistances").visibleWhen());
        assertNull(ConfigSchema.entry(ConfigSchema.CLAIM_RANGE, "maxDistance").visibleWhen());
        assertEquals("mode=fixed",
                ConfigSchema.entry(ConfigSchema.CLAIM_RANGE, "fixedDistance").when("mode=fixed")
                        .visibleWhen());
    }

    @Test
    void patternDirectionValidationRejectsUnknownAndEmpty() throws Exception {
        Object value = ConfigSchema.validate(ConfigSchema.CLAIM_RANGE,
                "patternDirections.border", List.of("north", "east", "north"));
        assertEquals(List.of("north", "east"), value);
        assertThrows(com.shenqianye.samsara.config.ConfigValidationException.class,
                () -> ConfigSchema.validate(ConfigSchema.CLAIM_RANGE,
                        "patternDirections.border", List.of("up")));
        assertThrows(com.shenqianye.samsara.config.ConfigValidationException.class,
                () -> ConfigSchema.validate(ConfigSchema.CLAIM_RANGE,
                        "patternDirections.border", List.of()));
    }
}
