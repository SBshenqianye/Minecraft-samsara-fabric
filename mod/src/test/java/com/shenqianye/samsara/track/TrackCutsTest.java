package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 捕捉截断点：只影响该玩家该周目（2026-10-28 需求 §5/§7） */
class TrackCutsTest {
    private static final UUID A = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final UUID B = UUID.fromString("00000000-0000-0000-0000-00000000000b");

    @TempDir
    Path tmp;

    @Test
    void captureIsIsolatedByPlayerAndCycle() {
        TrackCuts cuts = new TrackCuts(tmp.resolve("cutoffs.json"));
        cuts.capture("world", 2, A, 5_000L);

        assertEquals(5_000L, cuts.cutoff("world", 2, A));
        assertNull(cuts.cutoff("world", 2, B), "其他玩家不受影响");
        assertNull(cuts.cutoff("world", 3, A), "其他周目不受影响");
        assertNull(cuts.cutoff("world", 0, A), "cycle0 不受影响");
        assertNull(cuts.cutoff("world_s1", 2, A), "其他世界不受影响");
    }

    @Test
    void captureSurvivesReloadAndKeepsEarliestMoment() {
        Path file = tmp.resolve("cutoffs.json");
        TrackCuts cuts = new TrackCuts(file);
        cuts.capture("world", 1, A, 9_000L);
        cuts.capture("world", 1, A, 4_000L);
        cuts.capture("world", 1, A, 8_000L);

        assertEquals(4_000L, cuts.cutoff("world", 1, A), "同一周目取更早的捕捉时刻");

        TrackCuts reloaded = new TrackCuts(file);
        assertEquals(4_000L, reloaded.cutoff("world", 1, A));
        assertEquals(1, reloaded.all().size());
    }

    @Test
    void truncateDropsSamplesAfterCaptureMoment() {
        List<TrackStore.Sample> samples = List.of(
                new TrackStore.Sample(1_000L, 0, 64, 0, 0, 0, 0),
                new TrackStore.Sample(2_000L, 1, 64, 0, 0, 0, 0),
                new TrackStore.Sample(3_000L, 2, 64, 0, 0, 0, 0),
                new TrackStore.Sample(4_000L, 3, 64, 0, 0, 0, 0));

        List<TrackStore.Sample> kept = TrackStore.truncate(samples, 2_000L);

        assertEquals(2, kept.size());
        assertEquals(2_000L, kept.get(1).timeMs());
        assertTrue(TrackStore.truncate(samples, 500L).isEmpty());
        assertEquals(4, TrackStore.truncate(samples, 9_999L).size());
    }

    @Test
    void floorIndexFindsSampleAtOrBefore() {
        List<TrackStore.Sample> samples = List.of(
                new TrackStore.Sample(1_000L, 0, 64, 0, 0, 0, 0),
                new TrackStore.Sample(2_000L, 1, 64, 0, 0, 0, 0),
                new TrackStore.Sample(3_000L, 2, 64, 0, 0, 0, 0));
        assertEquals(0, TrackStore.floorIndex(samples, 999L));
        assertEquals(0, TrackStore.floorIndex(samples, 1_000L));
        assertEquals(1, TrackStore.floorIndex(samples, 2_500L));
        assertEquals(2, TrackStore.floorIndex(samples, 9_999L));
    }
}
