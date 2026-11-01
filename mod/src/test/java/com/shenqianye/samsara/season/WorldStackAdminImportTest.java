package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 本地世界导入：布局校验、复制导入、源目录不变、栈清单/index 更新。 */
class WorldStackAdminImportTest {
    @TempDir
    Path server;

    private Path world() throws Exception {
        Path world = server.resolve("world");
        StackManifest manifest = new StackManifest();
        StackLayer top = new StackLayer();
        top.index = 0;
        top.id = "world";
        top.top = true;
        top.seed = 42;
        top.spawnX = 1;
        top.spawnY = 70;
        top.spawnZ = 2;
        manifest.layers.add(top);
        manifest.save(world);
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(world.resolve("players/data"));
        return world;
    }

    private Path sourceWorld(String name) throws Exception {
        Path src = server.resolve("imports").resolve(name);
        Files.createDirectories(src.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(src.resolve("dimensions/minecraft/the_nether/region"));
        Files.createDirectories(src.resolve("players/data"));
        Files.createDirectories(src.resolve("data/minecraft"));
        Files.writeString(src.resolve("level.dat"), "", StandardCharsets.UTF_8);
        Files.write(src.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                new byte[]{1, 2, 3});
        Files.write(src.resolve("dimensions/minecraft/the_nether/region/r.0.0.mca"),
                new byte[]{4});
        Files.writeString(src.resolve("data/minecraft/world_gen_settings.dat"), "",
                StandardCharsets.UTF_8);
        Files.writeString(src.resolve("players/data/00000000-0000-0000-0000-000000000001.dat"),
                "player", StandardCharsets.UTF_8);
        return src;
    }

    @Test
    void previewAcceptsNewLayoutAndRejectsLegacy() throws Exception {
        Path world = world();
        Path source = sourceWorld("new-world");
        WorldStackAdmin.Preview preview = WorldStackAdmin.previewImport(world, server,
                List.of(), source.toString(), "bottom", "", false);
        assertTrue(preview.ok, String.valueOf(preview.errors));
        assertEquals("v26.3", preview.source.layout);
        assertEquals("stack_0", preview.targetId);
        assertEquals(0, preview.targetIndex);
        assertTrue(preview.requiresRestart);
        assertTrue(preview.steps.stream().anyMatch(step -> step.contains("pending-import")));

        Path legacy = server.resolve("imports/legacy");
        Files.createDirectories(legacy.resolve("region"));
        Files.writeString(legacy.resolve("level.dat"), "", StandardCharsets.UTF_8);
        WorldStackAdmin.Preview bad = WorldStackAdmin.previewImport(world, server,
                List.of(), legacy.toString(), "bottom", "", false);
        assertFalse(bad.ok);
        assertTrue(bad.errors.stream().anyMatch(error -> error.contains("旧版布局")));

        Path outside = server.resolve("..").resolve("outside-world");
        WorldStackAdmin.Preview denied = WorldStackAdmin.previewImport(world, server,
                List.of(), outside.toString(), "bottom", "", false);
        assertFalse(denied.ok);
    }

    @Test
    void pendingImportCopiesIntoLayerAndLeavesSourceUntouched() throws Exception {
        Path world = world();
        Path source = sourceWorld("copy-me");
        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "bottom", "", false);
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        assertNotNull(scheduled.jobId);
        assertTrue(Files.isRegularFile(world.resolve("samsara/pending-import.json")));

        WorldStackAdmin.consumePending(world);

        StackManifest manifest = StackManifest.load(world);
        StackLayer imported = manifest.byId("stack_0");
        assertNotNull(imported, "导入后应出现栈底 stack_0");
        assertEquals(0, imported.index);
        assertEquals("imported", imported.source);
        assertTrue(Files.isRegularFile(
                world.resolve("dimensions/samsara/stack_0/overworld/region/r.0.0.mca")));
        assertTrue(Files.isRegularFile(
                world.resolve("samsara/stack/stack_0/level.dat")));
        assertTrue(Files.isRegularFile(
                world.resolve("players/data/00000000-0000-0000-0000-000000000001.dat")));
        assertTrue(Files.isRegularFile(
                world.resolve("samsara/stack/stack_0/spawn-history.json")));
        assertFalse(Files.exists(world.resolve("samsara/pending-import.json")));

        // 源目录保持不变
        assertTrue(Files.isRegularFile(
                source.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertTrue(Files.isRegularFile(
                source.resolve("players/data/00000000-0000-0000-0000-000000000001.dat")));
    }

    @Test
    void betweenImportInsertsAtRequestedIndexAndKeepsExistingIds() throws Exception {
        Path world = world();
        StackManifest manifest = StackManifest.load(world);
        StackLayer first = new StackLayer();
        first.id = "stack_0";
        first.index = 0;
        first.seed = 1;
        StackLayer second = new StackLayer();
        second.id = "stack_1";
        second.index = 1;
        second.seed = 2;
        manifest.layers.add(0, first);
        manifest.layers.add(1, second);
        manifest.normalize();
        manifest.save(world);
        Files.createDirectories(world.resolve("dimensions/samsara/stack_0/overworld"));
        Files.createDirectories(world.resolve("dimensions/samsara/stack_1/overworld"));

        Path source = sourceWorld("between-world");
        WorldStackAdmin.Preview preview = WorldStackAdmin.previewImport(world, server,
                List.of(), source.toString(), "between", "stack_0", false);
        assertTrue(preview.ok, String.valueOf(preview.errors));
        assertEquals("stack_2", preview.targetId);
        assertEquals(1, preview.targetIndex);

        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "between", "stack_0", false);
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        WorldStackAdmin.consumePending(world);

        StackManifest after = StackManifest.load(world);
        assertEquals(List.of("stack_0", "stack_2", "stack_1", "world"),
                after.layers.stream().map(l -> l.id).toList());
        assertEquals(1, after.byId("stack_2").index);
        assertEquals(2, after.byId("stack_1").index);
        assertEquals("stack_2", after.byId("stack_1").parent);
        assertTrue(Files.isRegularFile(
                world.resolve("dimensions/samsara/stack_2/overworld/region/r.0.0.mca")));
    }

    @Test
    void topImportDemotesCurrentWorldAndKeepsBackup() throws Exception {
        Path world = world();
        Files.writeString(world.resolve("level.dat"), "old-level", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("data/minecraft"));
        Files.writeString(world.resolve("data/minecraft/world_gen_settings.dat"), "old-gen",
                StandardCharsets.UTF_8);
        Files.writeString(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "old-region", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("samsara/claims"));
        Files.writeString(world.resolve("samsara/claims/claims.json"), "{\"nodes\":[]}",
                StandardCharsets.UTF_8);
        Path source = sourceWorld("new-top");
        Files.writeString(source.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "new-region", StandardCharsets.UTF_8);
        Files.createDirectories(source.resolve("samsara/claims"));
        Files.writeString(source.resolve("samsara/claims/claims.json"), "{\"nodes\":[{\"source\":\"new\"}]}",
                StandardCharsets.UTF_8);

        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "top", "", false);
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        WorldStackAdmin.consumePending(world);

        StackManifest manifest = StackManifest.load(world);
        assertEquals(2, manifest.layers.size(),
                manifest.layers.stream().map(l -> l.id + ":" + l.index + ":" + l.top).toList().toString());
        assertEquals("world", manifest.top().id);
        assertEquals("stack_0", manifest.byId("stack_0").id);
        assertEquals("imported", manifest.top().source);
        assertEquals("old-region", Files.readString(
                world.resolve("dimensions/samsara/stack_0/overworld/region/r.0.0.mca")));
        assertEquals("new-region", Files.readString(
                world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertTrue(Files.isRegularFile(
                world.resolve("samsara/stack/stack_0/claims/claims.json")));
        assertEquals("old-level", Files.readString(
                world.resolve("samsara/stack/stack_0/level.dat")));
        assertTrue(Files.isRegularFile(
                world.resolve("samsara/stack-backup/import-" + scheduled.jobId)
                        .resolve("data-minecraft/world_gen_settings.dat")));
        assertTrue(Files.readString(world.resolve("samsara/claims/claims.json"))
                .contains("\"new\""), "栈顶导入应带走源目录的 claims");
        assertTrue(Files.isRegularFile(
                source.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
    }

    @Test
    void archiveMovesLayerToBackupAndRemovesFromManifest() throws Exception {
        Path world = world();
        StackManifest manifest = StackManifest.load(world);
        StackLayer old = new StackLayer();
        old.id = "stack_0";
        old.index = 0;
        old.seed = 1;
        manifest.layers.add(0, old);
        manifest.normalize();
        manifest.save(world);
        Path dims = world.resolve("dimensions/samsara/stack_0/overworld/region");
        Files.createDirectories(dims);
        Files.writeString(dims.resolve("r.0.0.mca"), "old", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/stack/stack_0/marker.txt"), "meta",
                StandardCharsets.UTF_8);

        WorldStackAdmin.ScheduleResult scheduled =
                WorldStackAdmin.scheduleArchive(world, "stack_0");
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        WorldStackAdmin.consumePending(world);

        assertNull(StackManifest.load(world).byId("stack_0"));
        Path archive = world.resolve("samsara/stack-archive");
        try (var stream = Files.list(archive)) {
            Path backup = stream.filter(Files::isDirectory).findFirst().orElseThrow();
            assertTrue(Files.isRegularFile(backup.resolve(
                    "dimensions/overworld/region/r.0.0.mca")));
            assertTrue(Files.isRegularFile(backup.resolve("meta/marker.txt")));
        }
        assertFalse(Files.exists(world.resolve("dimensions/samsara/stack_0")));
    }

    @Test
    void archiveQueueIsRejectedForTop() throws Exception {
        Path world = world();
        WorldStackAdmin.Preview preview = WorldStackAdmin.previewArchive(world, "world");
        assertFalse(preview.ok);
        assertTrue(preview.errors.stream().anyMatch(error -> error.contains("栈顶")));
    }

    @Test
    void reorderRejectsUnknownOrDuplicateIds() throws Exception {
        Path world = world();
        var result = WorldStackAdmin.reorder(world, List.of("stack_9"));
        assertEquals(false, result.get("ok"));
    }
}
