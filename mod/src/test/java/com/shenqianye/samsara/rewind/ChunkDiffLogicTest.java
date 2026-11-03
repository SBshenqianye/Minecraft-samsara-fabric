package com.shenqianye.samsara.rewind;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** F-165 单区块回溯纯逻辑：时间筛选、按方块取目标状态、预览计数。 */
class ChunkDiffLogicTest {
    private static DiffStore.Edit edit(int x, int y, int z, int state, long time) {
        return new DiffStore.Edit(x, y, z, state, time);
    }

    @Test
    void defaultTargetIsBeforeEarliestRecord() {
        List<DiffStore.Edit> edits = List.of(
                edit(0, 64, 0, 1, 1000L),
                edit(1, 64, 0, 2, 3000L));
        assertEquals(999L, ChunkDiffLogic.defaultTarget(edits));
        assertEquals(0L, ChunkDiffLogic.defaultTarget(List.of()));
    }

    @Test
    void planTakesEarliestEditAfterTargetPerBlock() {
        List<DiffStore.Edit> edits = List.of(
                edit(0, 64, 0, 1, 1000L),
                edit(0, 64, 0, 2, 2000L),
                edit(1, 64, 0, 3, 1500L));

        List<ChunkDiffLogic.BlockChange> all = ChunkDiffLogic.plan(edits, 999L);
        assertEquals(2, all.size());
        assertEquals(1, all.stream().filter(c -> c.x() == 0).findFirst().orElseThrow().stateId());
        assertEquals(3, all.stream().filter(c -> c.x() == 1).findFirst().orElseThrow().stateId());

        List<ChunkDiffLogic.BlockChange> later = ChunkDiffLogic.plan(edits, 1500L);
        assertEquals(1, later.size());
        assertEquals(2, later.get(0).stateId());

        assertTrue(ChunkDiffLogic.plan(edits, 2000L).isEmpty());
    }

    @Test
    void statsCountCandidatesAndTimeWindow() {
        List<DiffStore.Edit> edits = List.of(
                edit(0, 64, 0, 1, 1000L),
                edit(0, 64, 0, 2, 2000L),
                edit(1, 64, 0, 3, 1500L));
        ChunkDiffLogic.Stats stats = ChunkDiffLogic.stats(edits, 999L);
        assertEquals(3, stats.editCount());
        assertEquals(2, stats.blockCount());
        assertEquals(1000L, stats.earliestMs());
        assertEquals(2000L, stats.latestMs());
        assertEquals(999L, stats.targetMs());
    }

    @Test
    void timelineStateAtUsesFirstEditAfterTarget() {
        List<DiffStore.Edit> edits = List.of(
                edit(0, 64, 0, 1, 1000L),
                edit(0, 64, 0, 2, 2000L));
        assertEquals(0, ChunkDiffLogic.indexAfter(edits, 999L));
        assertEquals(1, ChunkDiffLogic.stateAt(edits, 999L, 99));
        assertEquals(2, ChunkDiffLogic.stateAt(edits, 1000L, 99));
        assertEquals(99, ChunkDiffLogic.stateAt(edits, 2000L, 99));
        assertEquals(1, ChunkDiffLogic.stateAt(edits, -1L, 99));
        assertEquals(2, ChunkDiffLogic.indexAfter(edits, 2000L));
    }

    @Test
    void protectionAndRewindBothBlockApply() {
        assertTrue(ChunkDiffLogic.canApply(false, false));
        assertFalse(ChunkDiffLogic.canApply(true, false));
        assertFalse(ChunkDiffLogic.canApply(false, true));
        assertFalse(ChunkDiffLogic.canApply(true, true));
    }
}
