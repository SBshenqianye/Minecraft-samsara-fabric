package com.shenqianye.samsara.rewind;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** DiffStore 的存储/维度/迁移/合并行为（纯文件系统，不依赖 MC 运行时） */
class DiffStoreTest {
    private static final String NETHER = "minecraft/the_nether";

    @TempDir
    Path tmp;

    @Test
    void recordFlushReadAndPendingCount() throws Exception {
        Path dimRoot = tmp.resolve("current").resolve(DiffStore.OVERWORLD_DIM);
        DiffStore store = DiffStore.open(dimRoot, false);
        store.record(1, 64, 2, 42, 1000L);

        // 未落盘时 count() 仍应包含内存缓冲
        assertEquals(1, store.count());
        assertEquals(1, store.pendingCount());
        store.flush();
        assertEquals(0, store.pendingCount());
        assertEquals(1, store.count());

        List<DiffStore.Edit> edits = store.readChunk(0, 0);
        assertEquals(1, edits.size());
        DiffStore.Edit e = edits.get(0);
        assertEquals(1, e.x);
        assertEquals(64, e.y);
        assertEquals(2, e.z);
        assertEquals(42, e.stateId);
        assertEquals(1000L, e.timeMs);
        assertTrue(Files.exists(dimRoot.resolve("r.0.0").resolve("0_0.dat")));
    }

    @Test
    void dimensionsAreIsolated() {
        Path current = tmp.resolve("current");
        DiffStore overworld = DiffStore.open(current.resolve(DiffStore.OVERWORLD_DIM), false);
        DiffStore nether = DiffStore.open(current.resolve(NETHER), false);

        overworld.record(0, 64, 0, 1, 1L);
        nether.record(0, 64, 0, 2, 2L);
        overworld.flush();
        nether.flush();

        assertEquals(1, overworld.readChunk(0, 0).size());
        assertEquals(1, nether.readChunk(0, 0).size());
        assertEquals(2, DiffStore.countAll(current));
    }

    @Test
    void migrateLegacyLayoutIntoOverworld() {
        Path window = tmp.resolve("current");
        DiffStore legacy = DiffStore.open(window, false); // 旧布局：window/r.0.0/0_0.dat
        legacy.record(0, 64, 0, 7, 1L);
        legacy.flush();

        DiffStore.migrateLegacy(window);

        assertFalse(Files.exists(window.resolve("r.0.0")));
        DiffStore migrated = DiffStore.open(window.resolve(DiffStore.OVERWORLD_DIM), false);
        List<DiffStore.Edit> edits = migrated.readChunk(0, 0);
        assertEquals(1, edits.size());
        assertEquals(7, edits.get(0).stateId);
    }

    @Test
    void migrateWorldLayerFromV011Layout() throws Exception {
        Path window = tmp.resolve("current");
        DiffStore old = DiffStore.open(
                window.resolve("world_s1").resolve(DiffStore.OVERWORLD_DIM), false);
        old.record(1, 70, 2, 11, 5L);
        old.flush();

        DiffStore.migrateWorldLayer(window, "world_s1");

        assertFalse(Files.exists(window.resolve("world_s1")), "旧世界层应被剥离");
        Path target = window.resolve(DiffStore.OVERWORLD_DIM);
        List<DiffStore.Edit> edits = DiffStore.open(target, false).readChunk(0, 0);
        assertEquals(1, edits.size());
        assertEquals(11, edits.get(0).stateId);
    }

    @Test
    void migrateWorldLayerChainsV010Layout() {
        Path window = tmp.resolve("current");
        DiffStore legacy = DiffStore.open(window, false);
        legacy.record(0, 64, 0, 9, 1L);
        legacy.flush();

        DiffStore.migrateWorldLayer(window, "world_s2");

        assertFalse(Files.exists(window.resolve("r.0.0")));
        Path target = window
                .resolve(DiffStore.OVERWORLD_DIM).resolve("r.0.0").resolve("0_0.dat");
        assertTrue(Files.exists(target));
    }

    @Test
    void mergeWindowsAppendsToExistingChunkFile() throws Exception {
        Path current = tmp.resolve("current");
        Path frozen = tmp.resolve("frozen");
        DiffStore a = DiffStore.open(current.resolve(DiffStore.OVERWORLD_DIM), false);
        DiffStore b = DiffStore.open(frozen.resolve(DiffStore.OVERWORLD_DIM), false);
        a.record(0, 64, 0, 1, 2L);
        b.record(0, 64, 0, 2, 1L);
        a.flush();
        b.flush();

        DiffStore.mergeWindows(frozen, current);

        List<DiffStore.Edit> merged = DiffStore.open(
                current.resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0);
        assertEquals(2, merged.size());
        assertFalse(Files.exists(frozen.resolve(DiffStore.OVERWORLD_DIM)
                .resolve("r.0.0").resolve("0_0.dat")));
    }

    @Test
    void mergeWindowsMigratesLegacyOnBothSides() throws Exception {
        Path current = tmp.resolve("current");
        Path frozen = tmp.resolve("frozen");
        DiffStore a = DiffStore.open(current, false); // 两侧都是旧布局
        DiffStore b = DiffStore.open(frozen, false);
        a.record(0, 64, 0, 1, 2L);
        b.record(0, 64, 0, 2, 1L);
        a.flush();
        b.flush();

        DiffStore.mergeWindows(frozen, current);

        List<DiffStore.Edit> merged = DiffStore.open(
                current.resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0);
        assertEquals(2, merged.size());
        assertFalse(Files.exists(current.resolve("r.0.0")));
        assertFalse(Files.exists(frozen.resolve("r.0.0")));
    }

    @Test
    void countAllIgnoresNonDatFiles() throws Exception {
        Path current = tmp.resolve("current");
        DiffStore store = DiffStore.open(current.resolve(DiffStore.OVERWORLD_DIM), false);
        store.record(0, 64, 0, 1, 1L);
        store.flush();
        Files.writeString(current.resolve("notes.txt"), "not a diff");

        assertEquals(1, DiffStore.countAll(current));
    }

    @Test
    void migrateWindowNamesCurrentToLiveAndSuffixToCycle() throws Exception {
        Path diff = tmp.resolve("diff");
        DiffStore live = DiffStore.open(
                diff.resolve("current").resolve("world").resolve(DiffStore.OVERWORLD_DIM), false);
        live.record(0, 64, 0, 21, 10L);
        live.flush();
        DiffStore cycle = DiffStore.open(
                diff.resolve("s3").resolve("world").resolve(DiffStore.OVERWORLD_DIM), false);
        cycle.record(1, 70, 2, 22, 20L);
        cycle.flush();

        DiffStore.migrateLayout(diff, "world");

        assertFalse(Files.exists(diff.resolve("current")));
        assertFalse(Files.exists(diff.resolve("s3")));
        assertTrue(Files.exists(DiffStore.liveWorldRoot(tmp, "world")
                .resolve(DiffStore.OVERWORLD_DIM).resolve("r.0.0").resolve("0_0.dat")));
        assertTrue(Files.exists(DiffStore.cycleWorldRoot(tmp, 3, "world")
                .resolve(DiffStore.OVERWORLD_DIM).resolve("r.0.0").resolve("0_0.dat")));

        // 数据不丢：迁移后可正常读取
        assertEquals(21, DiffStore.open(DiffStore.liveWorldRoot(tmp, "world")
                .resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0).get(0).stateId);
        assertEquals(22, DiffStore.open(DiffStore.cycleWorldRoot(tmp, 3, "world")
                .resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0).get(0).stateId);

        // 幂等：再跑一次不改数据
        DiffStore.migrateLayout(diff, "world");
        assertEquals(1, DiffStore.open(DiffStore.liveWorldRoot(tmp, "world")
                .resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0).size());
        assertEquals(1, DiffStore.open(DiffStore.cycleWorldRoot(tmp, 3, "world")
                .resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0).size());
    }

    @Test
    void migrateWindowNamesMergesWhenTargetExists() throws Exception {
        Path diff = tmp.resolve("diff");
        DiffStore old = DiffStore.open(
                diff.resolve("current").resolve("world").resolve(DiffStore.OVERWORLD_DIM), false);
        DiffStore existing = DiffStore.open(
                diff.resolve("live").resolve("world").resolve(DiffStore.OVERWORLD_DIM), false);
        old.record(0, 64, 0, 1, 2L);
        existing.record(0, 64, 0, 2, 1L);
        old.flush();
        existing.flush();

        DiffStore.migrateLayout(diff, "world");

        assertFalse(Files.exists(diff.resolve("current")));
        List<DiffStore.Edit> merged = DiffStore.open(DiffStore.liveWorldRoot(tmp, "world")
                .resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0);
        assertEquals(2, merged.size(), "合并而不是覆盖：两条记录都要保留");
    }

    @Test
    void migrateLayoutChainsWindowAndWorldLayers() throws Exception {
        Path diff = tmp.resolve("diff");
        // v0.11 布局：diff/current/minecraft/overworld/…
        DiffStore old = DiffStore.open(
                diff.resolve("current").resolve(DiffStore.OVERWORLD_DIM), false);
        old.record(0, 64, 0, 31, 1L);
        old.flush();

        DiffStore.migrateLayout(diff, "world_s1");

        assertFalse(Files.exists(diff.resolve("current")));
        assertTrue(Files.exists(diff.resolve("live").resolve("minecraft")), "新布局保留维度层");
        List<DiffStore.Edit> edits = DiffStore.open(DiffStore.liveWorldRoot(tmp, "world_s1")
                .resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0);
        assertEquals(1, edits.size());
        assertEquals(31, edits.get(0).stateId);
        // 幂等
        DiffStore.migrateLayout(diff, "world_s1");
        assertEquals(1, DiffStore.open(DiffStore.liveWorldRoot(tmp, "world_s1")
                .resolve(DiffStore.OVERWORLD_DIM), false).readChunk(0, 0).size());
    }
}
