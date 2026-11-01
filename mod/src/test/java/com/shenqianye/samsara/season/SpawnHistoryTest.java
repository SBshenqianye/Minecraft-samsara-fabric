package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 出生点历史：自动轮次、检测追加、旧 season.json 迁移。 */
class SpawnHistoryTest {

    @Test
    void ensureInitialCreatesFirstCycleOnlyOnce() {
        SpawnHistory h = new SpawnHistory();
        assertTrue(h.isEmpty());
        SpawnHistory.Cycle first = h.ensureInitial(100, -50, 1L);
        assertNotNull(first);
        assertEquals(0, first.index);
        assertEquals("initial", first.source);

        assertNull(h.ensureInitial(100, -50, 2L));
        assertEquals(1, h.size());
    }

    @Test
    void detectsLevelDatSpawnChangeBeyondThreshold() {
        SpawnHistory h = new SpawnHistory();
        h.ensureInitial(0, 0, 1L);

        assertNull(h.detect(16, 0, 2L), "16 格以内视为同一出生点");
        SpawnHistory.Cycle moved = h.detect(17, 0, 3L);
        assertNotNull(moved);
        assertEquals(1, moved.index);
        assertEquals("detected", moved.source);
        assertEquals(2, h.size());
    }

    @Test
    void sameWorldResetCanAppendExplicitSource() {
        SpawnHistory h = new SpawnHistory();
        h.ensureInitial(0, 0, 1L);
        h.append(15234, -8231, "same-world-reset", 2L);
        assertEquals(1, h.currentIndex());
        assertEquals("same-world-reset", h.last().source);
        assertEquals(2, h.size());
    }

    @Test
    void centersAreOrderedOldToNew() {
        SpawnHistory h = new SpawnHistory();
        h.ensureInitial(0, 0, 1L);
        h.append(100, 0, "same-world-reset", 2L);
        List<int[]> centers = h.centers();
        assertEquals(2, centers.size());
        assertEquals(0, centers.get(0)[0]);
        assertEquals(100, centers.get(1)[0]);
    }

    @Test
    void migratesOldSeasonSpawnCentersInStartTimeOrder() {
        SeasonData data = new SeasonData();
        Season a = new Season();
        a.id = 2;
        a.startTime = 2000L;
        a.spawnCenter.x = 500;
        a.spawnCenter.z = 600;
        Season b = new Season();
        b.id = 1;
        b.startTime = 1000L;
        b.spawnCenter.x = 100;
        b.spawnCenter.z = 200;
        Season pending = new Season();
        pending.id = 3;
        pending.startTime = null;
        pending.spawnCenter.x = 9999;
        data.seasons.add(a);
        data.seasons.add(pending);
        data.seasons.add(b);

        SpawnHistory h = SpawnHistory.fromSeasons(data, 500, 600, 10L);
        assertEquals(2, h.size());
        assertTrue(h.migratedFromSeasonJson);
        assertEquals(100, h.cycles.get(0).x);
        assertEquals(500, h.cycles.get(1).x);
        assertEquals("migrated", h.cycles.get(0).source);
        // 世界出生点与最后一条相同，不重复追加
        assertEquals(2, h.size());
    }

    @Test
    void migratedHistoryAppendsDetectedWhenLevelSpawnDiffers() {
        SeasonData data = new SeasonData();
        Season a = new Season();
        a.id = 1;
        a.startTime = 1000L;
        a.spawnCenter.x = 100;
        a.spawnCenter.z = 200;
        data.seasons.add(a);

        SpawnHistory h = SpawnHistory.fromSeasons(data, 5000, 6000, 10L);
        assertEquals(2, h.size());
        assertEquals("detected", h.last().source);
        assertEquals(5000, h.last().x);
    }

    @Test
    void emptySeasonDataFallsBackToWorldSpawn() {
        SpawnHistory h = SpawnHistory.fromSeasons(new SeasonData(), 64, -64, 10L);
        assertEquals(1, h.size());
        assertEquals("initial", h.cycles.get(0).source);
    }

    @Test
    void distanceUsesEuclideanBlocks() {
        assertEquals(5, SpawnHistory.distance(0, 0, 3, 4));
        assertEquals(0, SpawnHistory.distance(10, 10, 10, 10));
    }
}
