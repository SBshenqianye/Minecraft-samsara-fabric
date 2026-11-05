package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 片段收藏：磁盘容错、查询、保护区间、删除。 */
class TrackClipsTest {
    @TempDir
    Path tmp;

    private static final UUID PLAYER =
            UUID.fromString("9d9472a9-01b9-3468-957b-73a21a18429c");

    @Test
    void addFindListAndRemove() {
        Path file = tmp.resolve("clips.json");
        TrackClips store = new TrackClips(file);
        String id = store.newId();
        TrackClips.Entry entry = new TrackClips.Entry(id, "world", 3, PLAYER,
                1_000L, 2_000L, "团队战", "Admin", 2_100L);
        assertNotNull(store.add(entry));

        TrackClips reloaded = new TrackClips(file);
        assertEquals(1, reloaded.all().size());
        assertEquals("团队战", reloaded.find(id).label);
        assertEquals(1, reloaded.forTarget("world", 3, PLAYER).size());
        assertEquals(1, reloaded.countByCreator("Admin"));

        List<TrackClips.Range> ranges = reloaded.protectedRanges("world", 3, PLAYER);
        assertEquals(1, ranges.size());
        assertTrue(ranges.get(0).intersects(1_500L, 1_500L));
        assertFalse(ranges.get(0).intersects(2_001L, 3_000L));

        assertTrue(reloaded.remove(id));
        assertEquals(0, new TrackClips(file).all().size());
    }

    @Test
    void malformedFileFallsBackToEmptyAndSkipsBadEntries() throws Exception {
        Path file = tmp.resolve("clips.json");
        Files.writeString(file, "not json");
        assertTrue(new TrackClips(file).all().isEmpty());

        Files.writeString(file, """
                {"version":1,"clips":[
                  {"id":"ok","world":"world","cycle":1,"uuid":"%s","fromMs":10,"toMs":20,
                   "label":"x","createdBy":"A","createdAt":30},
                  {"id":"bad","world":"world","cycle":1,"uuid":"%s","fromMs":20,"toMs":10}
                ]}
                """.formatted(PLAYER, PLAYER));
        TrackClips store = new TrackClips(file);
        assertEquals(1, store.all().size());
        assertEquals("ok", store.all().get(0).id);
    }
}
