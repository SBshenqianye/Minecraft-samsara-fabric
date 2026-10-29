package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.track.TrackLayout;
import com.shenqianye.samsara.track.TrackStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 只读加载：跨日期拼接 + 捕捉截断生效。 */
class ViewTrackReaderTest {

    @Test
    void loadsAcrossDaysAndAppliesCutoff(@TempDir Path dir) throws Exception {
        UUID uuid = UUID.randomUUID();
        Path world = dir.resolve("world");
        long d0 = LocalDate.of(2026, 10, 28).atStartOfDay(ZoneId.systemDefault())
                .toInstant().toEpochMilli();
        long d1 = d0 + 86_400_000L;

        TrackStore.Segment a = new TrackStore.Segment(uuid, "minecraft:overworld", d0 + 1_000, 5);
        a.samples.add(new TrackStore.Sample(d0 + 1_000, 0, 64, 0, 0, 0, 0));
        a.samples.add(new TrackStore.Sample(d0 + 2_000, 1, 64, 0, 0, 0, 0));
        a.samples.add(new TrackStore.Sample(d0 + 3_000, 2, 64, 0, 0, 0, 0));
        TrackStore.write(TrackLayout.file(world, 7, LocalDate.of(2026, 10, 28), uuid), List.of(a));

        TrackStore.Segment b = new TrackStore.Segment(uuid, "minecraft:the_nether", d1 + 1_000, 5);
        b.samples.add(new TrackStore.Sample(d1 + 1_000, 10, 32, 0, 0, 0, 0));
        b.samples.add(new TrackStore.Sample(d1 + 2_000, 11, 32, 0, 0, 0, 0));
        TrackStore.write(TrackLayout.file(world, 7, LocalDate.of(2026, 10, 29), uuid), List.of(b));

        ViewTrackReader.LoadResult result = ViewTrackReader.load(world, 7, uuid,
                d0, d1 + 10_000, d1 + 1_500);
        assertTrue(result.cutoffApplied());
        assertEquals(2, result.files().size());
        assertEquals(2, result.segments().size());

        ViewCursor cursor = ViewCursor.build(result.segments(), d0, d1 + 10_000,
                result.truncated(), result.cutoffApplied());
        assertTrue(cursor.cutoffApplied());
        // 第一段完整；第二段被截断到 d1+1_000
        assertEquals(3, cursor.segments().get(0).samples().size());
        assertEquals(1, cursor.segments().get(1).samples().size());
        assertEquals(d1 + 1_000, cursor.segments().get(1).lastMs());
        assertEquals("minecraft:the_nether", cursor.segments().get(1).dimension());
    }
}
