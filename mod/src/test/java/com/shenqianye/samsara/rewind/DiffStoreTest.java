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
}
