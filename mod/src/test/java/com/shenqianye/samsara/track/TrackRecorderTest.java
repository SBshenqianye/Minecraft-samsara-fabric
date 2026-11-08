package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 录制跳过规则：旁观者与回看观看者都不写轨迹。 */
class TrackRecorderTest {
    @Test
    void normalPlayerIsRecorded() {
        assertTrue(TrackRecorder.shouldRecord(false, false));
    }

    @Test
    void spectatorIsNotRecorded() {
        assertFalse(TrackRecorder.shouldRecord(true, false));
    }

    @Test
    void viewViewerIsNotRecordedEvenBeforeGameModeSwitch() {
        assertFalse(TrackRecorder.shouldRecord(false, true));
        assertFalse(TrackRecorder.shouldRecord(true, true));
    }

    @Test
    void idleSampleRequiresSamePoseWithinTolerance() {
        TrackStore.Sample base = new TrackStore.Sample(1000L, 10, 64, 10, 90f, 0f, 0);
        assertTrue(TrackRecorder.isIdleSample(base,
                new TrackStore.Sample(1200L, 10.0004, 64.0004, 10.0004, 90.004f, 0.004f, 0)));
        assertFalse(TrackRecorder.isIdleSample(base,
                new TrackStore.Sample(1200L, 10.2, 64, 10, 90f, 0f, 0)));
        assertFalse(TrackRecorder.isIdleSample(base,
                new TrackStore.Sample(1200L, 10, 64, 10, 90f, 0f, TrackStore.FLAG_SNEAK)));
        assertFalse(TrackRecorder.isIdleSample(null, base));
    }

    @Test
    void idleSamplesAreSkippedUntilHeartbeat() {
        // 静止 10s 内保持 5Hz；10s 后跳过；60s 骨架点必写。
        assertFalse(TrackRecorder.shouldSkipIdleSample(true, true,
                1_000L, 6_000L, 10_000, 1_000L, 60_000));
        assertTrue(TrackRecorder.shouldSkipIdleSample(true, true,
                1_000L, 15_000L, 10_000, 6_000L, 60_000));
        assertFalse(TrackRecorder.shouldSkipIdleSample(true, true,
                1_000L, 70_000L, 10_000, 6_000L, 60_000));
        assertFalse(TrackRecorder.shouldSkipIdleSample(true, false,
                1_000L, 15_000L, 10_000, 6_000L, 60_000));
        assertFalse(TrackRecorder.shouldSkipIdleSample(false, true,
                1_000L, 15_000L, 10_000, 6_000L, 60_000));
    }
}
