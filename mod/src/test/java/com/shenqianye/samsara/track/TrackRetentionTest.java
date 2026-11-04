package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-163 保留策略纯逻辑。 */
class TrackRetentionTest {
    @Test
    void adaptiveDefaultMatchesV1Tiers() {
        assertEquals(5.0, TrackRetention.adaptiveTargetHz(0), 1e-9);
        assertEquals(5.0, TrackRetention.adaptiveTargetHz(0), 1e-9);
        assertEquals(2.5, TrackRetention.adaptiveTargetHz(1), 1e-9);
        assertEquals(2.5, TrackRetention.adaptiveTargetHz(3), 1e-9);
        assertEquals(1.25, TrackRetention.adaptiveTargetHz(4), 1e-9);
        assertEquals(1.25, TrackRetention.adaptiveTargetHz(15), 1e-9);
        assertEquals(0.625, TrackRetention.adaptiveTargetHz(16), 1e-9);
        assertEquals(0.3125, TrackRetention.adaptiveTargetHz(64), 1e-9);
        assertEquals(0.3125, TrackRetention.adaptiveTargetHz(255), 1e-9);
        // v1 在 >=256 天落到骨架 0.05Hz（0.15625 档在旧实现中不可达，保持等价）
        assertEquals(0.05, TrackRetention.adaptiveTargetHz(256), 1e-9);
        assertEquals(0.05, TrackRetention.adaptiveTargetHz(999), 1e-9);
        assertEquals(5.0, TrackRetention.targetHz(TrackRetention.Settings.defaults(), 0), 1e-9);
    }

    @Test
    void keepAllNeverDecimates() {
        TrackRetention.Settings settings = new TrackRetention.Settings(
                TrackRetention.Mode.KEEP_ALL, 0.9, 2000, false, 0, TrackRetention.defaultCurve());
        assertEquals(-1, TrackRetention.targetHz(settings, 999), 1e-9);
        TrackRetention.Settings legacy = new TrackRetention.Settings(
                TrackRetention.Mode.ADAPTIVE, 0.9, 2000, true, 0, TrackRetention.defaultCurve());
        assertEquals(-1, TrackRetention.targetHz(legacy, 999), 1e-9);
    }

    @Test
    void curveMatchesExampleRatios() {
        List<TrackRetention.CurvePoint> curve = TrackRetention.defaultCurve();
        assertEquals(1.0, TrackRetention.keepRatioAt(0, curve), 1e-9);
        assertEquals(1.0, TrackRetention.keepRatioAt(7, curve), 1e-9);
        // 7→30 线性插值：8 天 = 1 - 1/23 * 0.5
        assertEquals(1.0 - (1.0 / 23.0) * 0.5, TrackRetention.keepRatioAt(8, curve), 1e-9);
        assertEquals(0.5, TrackRetention.keepRatioAt(30, curve), 1e-9);
        // 30→90 线性插值：31 天 = 0.5 - 1/60 * 0.25
        assertEquals(0.5 - (1.0 / 60.0) * 0.25, TrackRetention.keepRatioAt(31, curve), 1e-9);
        // 末档 0/0.1 的平滑尾部：90→180 天线性降到 0.1
        assertEquals(0.25 - (1.0 / 90.0) * 0.15, TrackRetention.keepRatioAt(91, curve), 1e-9);
        assertEquals(0.1, TrackRetention.keepRatioAt(180, curve), 1e-9);
        assertEquals(0.1, TrackRetention.keepRatioAt(999, curve), 1e-9);
        assertEquals(5.0 * (1.0 - (1.0 / 23.0) * 0.5),
                TrackRetention.curveTargetHz(8, curve), 1e-9);
        assertEquals(0.5, TrackRetention.curveTargetHz(999, curve), 1e-9);
    }

    @Test
    void curveValidationRejectsNonMonotonicAgeAndRatio() {
        assertFalse(TrackRetention.validateCurve(List.of(
                new TrackRetention.CurvePoint(7, 1.0),
                new TrackRetention.CurvePoint(30, 0.5),
                new TrackRetention.CurvePoint(90, 0.25),
                new TrackRetention.CurvePoint(0, 0.1))).size() > 0);

        assertTrue(TrackRetention.validateCurve(List.of(
                new TrackRetention.CurvePoint(7, 1.0),
                new TrackRetention.CurvePoint(7, 0.5))).size() > 0);
        assertTrue(TrackRetention.validateCurve(List.of(
                new TrackRetention.CurvePoint(0, 1.0),
                new TrackRetention.CurvePoint(30, 0.5))).size() > 0);
        assertTrue(TrackRetention.validateCurve(List.of(
                new TrackRetention.CurvePoint(7, 0.5),
                new TrackRetention.CurvePoint(30, 0.8))).size() > 0);
        assertTrue(TrackRetention.validateCurve(List.of(
                new TrackRetention.CurvePoint(7, 1.2))).size() > 0);
        assertTrue(TrackRetention.validateCurve(List.of()).size() > 0);
    }

    @Test
    void maxBytesDowngradesThenRefuses() {
        TrackRetention.Settings settings = new TrackRetention.Settings(
                TrackRetention.Mode.CURVE, 0.9, 2000, false, 1000, TrackRetention.defaultCurve());
        assertEquals(TrackRetention.Mode.CURVE,
                TrackRetention.effectiveModeForBytes(settings, 1000));
        assertEquals(TrackRetention.Mode.ADAPTIVE,
                TrackRetention.effectiveModeForBytes(settings, 1001));
        assertTrue(TrackRetention.shouldRefuseNewArchive(settings, 1001));
        assertFalse(TrackRetention.shouldRefuseNewArchive(settings, 999));

        TrackRetention.Settings unlimited = new TrackRetention.Settings(
                TrackRetention.Mode.KEEP_ALL, 0.9, 2000, false, 0, TrackRetention.defaultCurve());
        assertEquals(TrackRetention.Mode.KEEP_ALL,
                TrackRetention.effectiveModeForBytes(unlimited, Long.MAX_VALUE));
        assertFalse(TrackRetention.shouldRefuseNewArchive(unlimited, Long.MAX_VALUE));
    }

    @Test
    void invalidCurveValueFallsBackToDefault() {
        assertEquals(TrackRetention.DEFAULT_CURVE, TrackRetention.parseCurve("bad"));
        assertEquals(TrackRetention.DEFAULT_CURVE, TrackRetention.parseCurve(List.of(
                java.util.Map.of("ageDays", 7, "keepRatio", 2.0))));
    }

    @Test
    void dropPolicyOnlyAllowsCapturedReplayEntity() {
        assertTrue(TrackDropPolicy.shouldDropEquipment(true, true, false, true));
        assertFalse(TrackDropPolicy.shouldDropEquipment(false, true, false, true));
        assertFalse(TrackDropPolicy.shouldDropEquipment(true, false, false, true));
        assertFalse(TrackDropPolicy.shouldDropEquipment(true, true, true, true));
        assertFalse(TrackDropPolicy.shouldDropEquipment(true, true, false, false));
    }
}
