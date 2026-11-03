package com.shenqianye.samsara.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 单区块回溯的最终闸门：圈地保护/倒流运行中/无改动都不得 apply。 */
class ChunkRestoreServiceTest {
    private static ChunkRestoreService.Summary summary(boolean protectedArea, boolean rewindRunning, int edits) {
        return new ChunkRestoreService.Summary(true, null, null, 0, 0,
                edits, 10, 5, 0, 0, 0,
                protectedArea, rewindRunning, true, true);
    }

    @Test
    void protectedClaimBlocksApplyEvenWhenEverythingElseIsReady() {
        assertFalse(summary(true, false, 10).canApply(),
                "真实圈地内的单区块回溯必须被拒绝");
    }

    @Test
    void rewindRunningBlocksApply() {
        assertFalse(summary(false, true, 10).canApply());
    }

    @Test
    void noEditsBlocksApply() {
        assertFalse(summary(false, false, 0).canApply());
    }

    @Test
    void cleanChunkCanApply() {
        assertTrue(summary(false, false, 10).canApply());
    }
}
