package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-166 之后的 Web 容量预估：与 6B/点、5Hz 新鲜采样保持一致。 */
class TrackRetentionEstimateTest {
    @Test
    void defaultCurveEstimatesEachTierAndFiniteTotal() {
        TrackRetention.Estimate estimate = TrackRetention.estimate(
                TrackRetention.defaultCurve(), false);
        assertEquals(6L, estimate.bytesPerSample());
        assertEquals(432_000L, estimate.freshSamplesPerDay());
        assertEquals(2_592_000L, estimate.freshBytesPerDay());
        assertEquals(4, estimate.tiers().size());

        // ≤7 天 100%：7 × 2.47 MiB = 18,144,000 B
        assertEquals(2_592_000L, estimate.tiers().get(0).bytesPerDay());
        assertEquals(18_144_000L, estimate.tiers().get(0).cumulativeBytes());
        // 8~30 天 50%：23 × 1,296,000 = 29,808,000
        assertEquals(1_296_000L, estimate.tiers().get(1).bytesPerDay());
        assertEquals(47_952_000L, estimate.tiers().get(1).cumulativeBytes());
        // 31~90 天 25%：60 × 648,000 = 38,880,000
        assertEquals(648_000L, estimate.tiers().get(2).bytesPerDay());
        assertEquals(86_832_000L, estimate.tiers().get(2).cumulativeBytes());
        // 更老 10%：无限增长，只报告每日增量
        assertTrue(estimate.tiers().get(3).older());
        assertEquals(259_200L, estimate.tiers().get(3).bytesPerDay());
        assertEquals(86_832_000L, estimate.steadyBytes());
        assertEquals(259_200L, estimate.growthPerDay());
        assertTrue(estimate.unbounded());
        assertFalse(estimate.keepAll());
        assertTrue(estimate.warning().contains("无上限"));
    }

    @Test
    void zeroLastRatioIsBounded() {
        TrackRetention.Estimate estimate = TrackRetention.estimate(List.of(
                new TrackRetention.CurvePoint(7, 1.0),
                new TrackRetention.CurvePoint(0, 0.0)), false);
        assertFalse(estimate.unbounded());
        assertEquals(0L, estimate.growthPerDay());
        assertEquals(18_144_000L, estimate.steadyBytes());
        assertTrue(estimate.warning().contains("有上限"));
    }

    @Test
    void keepAllWarnsAboutUnboundedGrowth() {
        TrackRetention.Estimate estimate = TrackRetention.estimate(
                TrackRetention.defaultCurve(), true);
        assertTrue(estimate.keepAll());
        assertTrue(estimate.unbounded());
        assertEquals(2_592_000L, estimate.growthPerDay());
        assertEquals(0L, estimate.steadyBytes());
        assertTrue(estimate.warning().contains("无限增长"));
    }
}
