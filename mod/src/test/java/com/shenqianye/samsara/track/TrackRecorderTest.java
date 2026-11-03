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
}
