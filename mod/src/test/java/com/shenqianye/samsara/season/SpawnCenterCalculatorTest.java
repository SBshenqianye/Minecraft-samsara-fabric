package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 排斥点：最小间距增长、方向随机、地形校验有限次。 */
class SpawnCenterCalculatorTest {

    @Test
    void separationGrowsWithHistoryAndIsCapped() {
        assertEquals(15_000, SpawnCenterCalculator.effectiveSeparation(15_000, 0));
        assertEquals(17_500, SpawnCenterCalculator.effectiveSeparation(15_000, 1));
        assertEquals(22_500, SpawnCenterCalculator.effectiveSeparation(15_000, 3));
        assertEquals(100_000, SpawnCenterCalculator.effectiveSeparation(95_000, 10));
    }

    @Test
    void candidateIsAtLeastSeparationAwayFromEveryHistoryCenter() {
        SeasonData.Settings s = new SeasonData.Settings();
        s.newCenterMaxDistance = 60_000;
        List<int[]> centers = List.of(new int[]{0, 0}, new int[]{20_000, 0});
        SpawnCenterCalculator.CenterResult r = SpawnCenterCalculator.next(
                20_000, 0, centers, 17_500, s, new Random(42L), (x, z, d) -> true, 8);
        assertTrue(r.validated());
        assertTrue(r.minDist() >= 17_500, "距最近历史中心应 >= 最小间距，实际 " + r.minDist());
        for (int[] c : centers) {
            assertTrue(Math.hypot(r.x() - c[0], r.z() - c[1]) >= 17_500 - 1);
        }
    }

    @Test
    void filterCanRejectCandidatesAndFallbackStillReturnsAPoint() {
        SeasonData.Settings s = new SeasonData.Settings();
        s.newCenterMinDistance = 100;
        s.newCenterMaxDistance = 1000;
        SpawnCenterCalculator.CenterResult r = SpawnCenterCalculator.next(
                0, 0, List.of(new int[]{0, 0}), 100, s, new Random(7L),
                (x, z, d) -> false, 3);
        assertTrue(!r.validated());
        assertTrue(r.minDist() >= 0);
    }

    @Test
    void emptyHistoryReturnsCandidateAtRequiredDistance() {
        SeasonData.Settings s = new SeasonData.Settings();
        s.newCenterMinDistance = 15_000;
        SpawnCenterCalculator.CenterResult r = SpawnCenterCalculator.next(
                0, 0, List.of(), s, new Random(1L));
        double d = Math.hypot(r.x(), r.z());
        assertEquals(15_000, Math.round(d), 2.0);
    }
}
