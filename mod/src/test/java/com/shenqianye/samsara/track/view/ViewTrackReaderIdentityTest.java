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

/** 轨迹写入实际 UUID，查询按身份组 alias 聚合。 */
class ViewTrackReaderIdentityTest {
    @TempDir
    Path temp;

    @Test
    void availableAndLoadMergeAliasFiles() throws Exception {
        UUID premium = UUID.randomUUID();
        UUID offline = UUID.randomUUID();
        LocalDate day = LocalDate.now();
        long start = day.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() + 1000;
        write(premium, day, start);
        write(offline, day, start + 5000);

        List<ViewTrackReader.Available> all = ViewTrackReader.available(temp.resolve("world"),
                List.of(premium, offline), (uuid, cycle) -> null);
        assertEquals(2, all.size());
        for (ViewTrackReader.Available available : all) {
            assertTrue(available.error() == null, available.error());
        }

        ViewTrackReader.LoadResult loaded = ViewTrackReader.load(temp.resolve("world"), 0,
                List.of(premium, offline), start, start + 60_000, (uuid, cycle) -> null);
        assertEquals(2, loaded.files().size());
        assertEquals(2, loaded.totalSamples());
    }

    private void write(UUID uuid, LocalDate day, long start) throws Exception {
        Path file = TrackLayout.file(temp.resolve("world"), 0, day, uuid);
        TrackStore.Segment segment = new TrackStore.Segment(uuid, "minecraft:overworld", start, 5);
        segment.samples.add(new TrackStore.Sample(start, 1, 2, 3, 0, 0, 0));
        TrackStore.write(file, List.of(segment));
    }
}
