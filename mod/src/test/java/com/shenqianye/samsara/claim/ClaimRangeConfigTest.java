package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.config.ConfigSchema;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
        assertEquals(List.of("north"), ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get("stripe_top"));
        assertEquals(List.of("west"), ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get("stripe_left"));
        assertEquals(List.of("northeast"), ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get("square_top_right"));
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
    void geometryFingerprintChangesWithEveryShapeInput() {
        String base = ClaimRangeConfig.geometryFingerprint(
                ClaimRangeConfig.Mode.DYE, 4, 16,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES,
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS);
        assertEquals(base, ClaimRangeConfig.geometryFingerprint(
                ClaimRangeConfig.Mode.DYE, 4, 16,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES,
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS), "相同配置必须得到相同指纹");

        assertNotEquals(base, ClaimRangeConfig.geometryFingerprint(
                ClaimRangeConfig.Mode.FIXED, 4, 16,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES,
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS), "模式变化应使地图缓存失效");
        assertNotEquals(base, ClaimRangeConfig.geometryFingerprint(
                ClaimRangeConfig.Mode.DYE, 5, 16,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES,
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS), "固定距离变化应使地图缓存失效");
        assertNotEquals(base, ClaimRangeConfig.geometryFingerprint(
                ClaimRangeConfig.Mode.DYE, 4, 15,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES,
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS), "上限变化应使地图缓存失效");

        List<Integer> colors = new ArrayList<>(ClaimRangeConfig.DEFAULT_COLOR_DISTANCES);
        colors.set(3, 9);
        assertNotEquals(base, ClaimRangeConfig.geometryFingerprint(
                ClaimRangeConfig.Mode.DYE, 4, 16, colors,
                ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS), "颜色距离变化应使地图缓存失效");

        Map<String, List<String>> directions =
                new LinkedHashMap<>(ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS);
        directions.put("straight_cross", List.of("north"));
        assertNotEquals(base, ClaimRangeConfig.geometryFingerprint(
                ClaimRangeConfig.Mode.DYE, 4, 16,
                ClaimRangeConfig.DEFAULT_COLOR_DISTANCES, directions),
                "图案方向变化应使地图缓存失效");
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
