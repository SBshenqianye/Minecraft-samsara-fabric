package com.shenqianye.samsara.track;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 轨迹目录布局/迁移/周目隔离（纯文件系统，不依赖 MC 运行时） */
class TrackLayoutTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-0000000000aa");
    private static final LocalDate DAY = LocalDate.of(2026, 10, 28);

    @TempDir
    Path tmp;

    private Path sampleFile(Path file) throws Exception {
        TrackStore.Segment seg = new TrackStore.Segment(PLAYER, "minecraft:overworld",
                1_000_000L, 5.0);
        seg.samples.add(new TrackStore.Sample(1_000_000L, 1, 64, 2, 0, 0, 0));
        TrackStore.write(file, List.of(seg));
        return file;
    }

    @Test
    void worldDateDirsMigrateIntoCurrentCycle() throws Exception {
        Path world = tmp.resolve("track").resolve("world");
        Path legacy = sampleFile(world.resolve(DAY.toString()).resolve(PLAYER + ".bin"));

        int moved = TrackLayout.migrateWorldLayout(world, 3);

        assertEquals(1, moved);
        assertFalse(Files.exists(legacy.getParent()), "旧日期目录应被移走");
        Path migrated = TrackLayout.file(world, 3, DAY, PLAYER);
        assertTrue(Files.isRegularFile(migrated));
        assertEquals(1, TrackStore.read(migrated).get(0).samples.size());
        // 幂等
        assertEquals(0, TrackLayout.migrateWorldLayout(world, 3));
        assertTrue(Files.isRegularFile(migrated));
    }

    @Test
    void legacyRootDateDirsMigrateIntoWorldCycle() throws Exception {
        Path root = tmp.resolve("track");
        sampleFile(root.resolve(DAY.toString()).resolve(PLAYER + ".bin"));
        Path world = TrackLayout.worldRoot(root, "world_s1");

        int moved = TrackLayout.migrateLegacyRoot(root, world, 0);

        assertEquals(1, moved);
        assertFalse(Files.exists(root.resolve(DAY.toString())));
        Path migrated = TrackLayout.file(world, 0, DAY, PLAYER);
        assertTrue(Files.isRegularFile(migrated));
        assertEquals(1, TrackStore.read(migrated).get(0).samples.size());
    }

    @Test
    void mergeDoesNotLoseExistingSegments() throws Exception {
        Path world = tmp.resolve("track").resolve("world");
        Path legacy = sampleFile(world.resolve(DAY.toString()).resolve(PLAYER + ".bin"));
        Path target = sampleFile(world.resolve("cycle2").resolve(DAY.toString()).resolve(PLAYER + ".bin"));
        assertEquals(1, TrackStore.read(target).size());

        assertEquals(1, TrackLayout.migrateWorldLayout(world, 2));

        assertFalse(Files.exists(legacy));
        assertEquals(2, TrackStore.read(target).size(), "同名轨迹文件应合并两段，而不是覆盖");
    }

    @Test
    void cycleSwitchSelectsDifferentDirectory() {
        Path world = TrackLayout.worldRoot(tmp.resolve("track"), "world");
        Path cycle1 = TrackLayout.file(world, 1, DAY, PLAYER);
        Path cycle2 = TrackLayout.file(world, 2, DAY, PLAYER);
        Path cycle0 = TrackLayout.file(world, 0, DAY, PLAYER);

        assertNotEquals(cycle1, cycle2, "跨周目必须落到不同目录");
        assertEquals("cycle1", cycle1.getParent().getParent().getFileName().toString());
        assertEquals("cycle2", cycle2.getParent().getParent().getFileName().toString());
        assertEquals("cycle0", cycle0.getParent().getParent().getFileName().toString());
        assertTrue(cycle1.toString().replace('\\', '/')
                .endsWith("track/world/cycle1/2026-10-28/" + PLAYER + ".bin"));
    }

    @Test
    void cycleDirNameRoundTrip() {
        assertEquals(0, TrackLayout.cycleIdOf(TrackLayout.cycleDirName(0)));
        assertEquals(42, TrackLayout.cycleIdOf(TrackLayout.cycleDirName(42)));
        assertEquals(-1, TrackLayout.cycleIdOf("world"));
        assertEquals(-1, TrackLayout.cycleIdOf("cycleX"));
        assertTrue(TrackLayout.isDateDir("2026-10-28"));
        assertFalse(TrackLayout.isDateDir("cycle3"));
    }
}
