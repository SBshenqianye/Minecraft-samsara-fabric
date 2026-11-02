package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

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
        assertTrue(Files.isRegularFile(world.resolve("samsara/pending-imports.json")));

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
        assertFalse(Files.exists(world.resolve("samsara/pending-imports.json")));

        WorldStackAdmin.Model model = WorldStackAdmin.model(world, server, List.of());
        assertTrue(model.files.stream().anyMatch(f -> f.label.contains("world-stack-archive")),
                "文件树应包含服务器级归档备份目录");
        assertTrue(model.files.stream().anyMatch(f -> f.label.contains("world-stack-backup")),
                "文件树应包含服务器级导入/提升备份目录");

        // 源目录保持不变
        assertTrue(Files.isRegularFile(
                source.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertTrue(Files.isRegularFile(
                source.resolve("players/data/00000000-0000-0000-0000-000000000001.dat")));
    }

    @Test
    void multipleBottomImportsQueueAndExecuteInOneRestart() throws Exception {
        Path world = world();
        Path first = sourceWorld("queue-first");
        Path second = sourceWorld("queue-second");

        WorldStackAdmin.ScheduleResult scheduledFirst = WorldStackAdmin.scheduleImport(world, server,
                List.of(), first.toString(), "bottom", "", false);
        assertTrue(scheduledFirst.ok, String.valueOf(scheduledFirst.preview.errors));

        WorldStackAdmin.Preview previewSecond = WorldStackAdmin.previewImport(world, server,
                List.of(), second.toString(), "bottom", "", false);
        assertTrue(previewSecond.ok, String.valueOf(previewSecond.errors));
        assertEquals("stack_1", previewSecond.targetId, "第二次栈底导入应排在队列之后");
        assertEquals(0, previewSecond.targetIndex);
        assertTrue(previewSecond.warnings.stream().anyMatch(w -> w.contains("队列已有 1 项")),
                String.valueOf(previewSecond.warnings));

        WorldStackAdmin.ScheduleResult scheduledSecond = WorldStackAdmin.scheduleImport(world, server,
                List.of(), second.toString(), "bottom", "", false);
        assertTrue(scheduledSecond.ok, String.valueOf(scheduledSecond.preview.errors));

        WorldStackAdmin.Model model = WorldStackAdmin.model(world, server, List.of());
        assertEquals(2, model.pendingImports.size());
        assertTrue(model.restartRequired);

        WorldStackAdmin.consumePending(world);

        StackManifest manifest = StackManifest.load(world);
        assertEquals(List.of("stack_1", "stack_0", "world"),
                manifest.layers.stream().map(l -> l.id).toList(),
                "后确认的栈底导入应变成最下层");
        assertEquals(first.toRealPath().toString(), manifest.byId("stack_0").importedFrom);
        assertEquals(second.toRealPath().toString(), manifest.byId("stack_1").importedFrom);
        assertFalse(Files.exists(world.resolve("samsara/pending-imports.json")));
    }

    @Test
    void cancelQueuedImportRemovesOnlyThatItem() throws Exception {
        Path world = world();
        Path first = sourceWorld("cancel-first");
        Path second = sourceWorld("cancel-second");
        WorldStackAdmin.ScheduleResult scheduledFirst = WorldStackAdmin.scheduleImport(world, server,
                List.of(), first.toString(), "bottom", "", false);
        WorldStackAdmin.ScheduleResult scheduledSecond = WorldStackAdmin.scheduleImport(world, server,
                List.of(), second.toString(), "bottom", "", false);
        assertTrue(scheduledFirst.ok && scheduledSecond.ok);

        var cancel = WorldStackAdmin.cancelImport(world, scheduledFirst.jobId);
        assertEquals(true, cancel.get("ok"), String.valueOf(cancel));
        assertEquals(1, cancel.get("remaining"));

        WorldStackAdmin.Model model = WorldStackAdmin.model(world, server, List.of());
        assertEquals(1, model.pendingImports.size());
        assertEquals(scheduledSecond.jobId, model.pendingImports.get(0).jobId);
        assertEquals("canceled", WorldStackAdmin.jobs(world, 10).stream()
                .filter(job -> scheduledFirst.jobId.equals(job.id))
                .findFirst().orElseThrow().status);

        WorldStackAdmin.consumePending(world);
        StackManifest manifest = StackManifest.load(world);
        assertEquals(List.of("stack_1", "world"),
                manifest.layers.stream().map(l -> l.id).toList());
        assertFalse(Files.exists(world.resolve("samsara/pending-imports.json")));
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
                server.resolve("backups/samsara/world-stack-backup/import-" + scheduled.jobId)
                        .resolve("data-minecraft/world_gen_settings.dat")));
        assertTrue(Files.readString(world.resolve("samsara/claims/claims.json"))
                .contains("\"new\""), "栈顶导入应带走源目录的 claims");
        assertTrue(Files.isRegularFile(
                source.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
    }

    @Test
    void promoteTopExchangesWorldDataAtRestart() throws Exception {
        Path world = world();
        Files.writeString(world.resolve("level.dat"), "old-top-level", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("data/minecraft"));
        Files.writeString(world.resolve("data/minecraft/world_gen_settings.dat"), "old-top-gen",
                StandardCharsets.UTF_8);
        Files.writeString(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "old-top-region", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("samsara/claims"));
        Files.writeString(world.resolve("samsara/claims/claims.json"), "{\"nodes\":[{\"source\":\"top\"}]}",
                StandardCharsets.UTF_8);

        StackManifest manifest = StackManifest.load(world);
        StackLayer first = new StackLayer();
        first.id = "stack_0";
        first.seed = 11;
        StackLayer second = new StackLayer();
        second.id = "stack_1";
        second.seed = 22;
        manifest.layers.add(0, first);
        manifest.layers.add(1, second);
        manifest.normalize();
        manifest.save(world);
        Files.createDirectories(world.resolve("dimensions/samsara/stack_0/overworld"));
        Files.createDirectories(world.resolve("dimensions/samsara/stack_1/overworld/region"));
        Files.writeString(world.resolve("dimensions/samsara/stack_1/overworld/region/r.0.0.mca"),
                "promoted-region", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/stack/stack_1/level.dat"), "promoted-level",
                StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("samsara/stack/stack_1/data/minecraft"));
        Files.writeString(world.resolve("samsara/stack/stack_1/data/minecraft/world_gen_settings.dat"),
                "promoted-gen", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("samsara/stack/stack_1/claims"));
        Files.writeString(world.resolve("samsara/stack/stack_1/claims/claims.json"),
                "{\"nodes\":[{\"source\":\"promoted\"}]}", StandardCharsets.UTF_8);

        var result = WorldStackAdmin.reorder(world, List.of("stack_0", "world", "stack_1"));
        assertEquals(true, result.get("ok"), String.valueOf(result.get("error")));
        assertEquals(true, result.get("requiresRestart"));
        WorldStackAdmin.consumePending(world);

        StackManifest after = StackManifest.load(world);
        var job = WorldStackAdmin.jobs(world, 1);
        String jobText = job.isEmpty() ? "no job" : job.get(0).message + " " + job.get(0).log;
        assertEquals(List.of("stack_0", "stack_1", "world"),
                after.layers.stream().map(l -> l.id).toList(), jobText);
        assertEquals("promoted-level", Files.readString(world.resolve("level.dat")));
        assertEquals("promoted-region", Files.readString(
                world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertEquals("old-top-region", Files.readString(
                world.resolve("dimensions/samsara/stack_1/overworld/region/r.0.0.mca")));
        assertTrue(Files.readString(world.resolve("samsara/claims/claims.json"))
                .contains("promoted"));
        assertTrue(Files.readString(
                world.resolve("samsara/stack/stack_1/claims/claims.json")).contains("top"));
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
        assertTrue(scheduled.requiresRestart);
        assertNotNull(scheduled.job);
        assertTrue(scheduled.job.requiresRestart, "归档任务同样需要重启，任务卡应保持一致");
        assertTrue(scheduled.preview.backupPath.contains("stack_0-<job>"),
                "预览备份路径应显示占位符而不是真实文件名：" + scheduled.preview.backupPath);
        WorldStackAdmin.consumePending(world);

        assertNull(StackManifest.load(world).byId("stack_0"));
        Path archive = server.resolve("backups/samsara/world-stack-archive");
        try (var stream = Files.list(archive)) {
            Path backup = stream.filter(Files::isDirectory).findFirst().orElseThrow();
            assertTrue(Files.isRegularFile(backup.resolve(
                    "dimensions/overworld/region/r.0.0.mca")));
            assertTrue(Files.isRegularFile(backup.resolve("meta/marker.txt")));
        }
        assertFalse(Files.exists(world.resolve("dimensions/samsara/stack_0")));
    }

    @Test
    void pruneBackupsKeepsNewestAndNeverTouchesArchive() throws Exception {
        Path world = world();
        Path backup = server.resolve("backups/samsara/world-stack-backup");
        for (int i = 0; i < 4; i++) {
            Path dir = backup.resolve("promote-prom-" + i);
            Files.createDirectories(dir);
            Files.writeString(dir.resolve("marker.txt"), "x", StandardCharsets.UTF_8);
            Files.setLastModifiedTime(dir,
                    java.nio.file.attribute.FileTime.fromMillis(1_700_000_000_000L + i * 1000L));
        }
        Path archive = server.resolve("backups/samsara/world-stack-archive/stack_0-arc-1");
        Files.createDirectories(archive);
        Files.writeString(archive.resolve("marker.txt"), "keep", StandardCharsets.UTF_8);

        int removed = WorldStackAdmin.pruneBackups(world, 2);

        assertEquals(2, removed);
        try (var stream = Files.list(backup)) {
            assertEquals(2, stream.count(), "只保留最新 2 份导入/提升备份");
        }
        assertTrue(Files.isRegularFile(archive.resolve("marker.txt")),
                "归档是用户数据，不能被自动清理");
    }

    @Test
    void archiveQueueIsRejectedForTop() throws Exception {
        Path world = world();
        WorldStackAdmin.Preview preview = WorldStackAdmin.previewArchive(world, "world");
        assertFalse(preview.ok);
        assertTrue(preview.errors.stream().anyMatch(error -> error.contains("栈顶")));
    }

    @Test
    void previewRejectsSourceWithoutWorldGenSettings() throws Exception {
        Path world = world();
        Path source = sourceWorld("no-gen");
        Files.delete(source.resolve("data/minecraft/world_gen_settings.dat"));

        WorldStackAdmin.Preview preview = WorldStackAdmin.previewImport(world, server,
                List.of(), source.toString(), "bottom", "", false);
        assertFalse(preview.ok);
        assertFalse(preview.source.hasGenSettings);
        assertTrue(preview.errors.stream().anyMatch(error -> error.contains("world_gen_settings")),
                String.valueOf(preview.errors));
    }

    @Test
    void previewRejectsWorldFromNewerVersion() throws Exception {
        Path world = world();
        Path source = sourceWorld("newer-version");
        writeLevelDat(source.resolve("level.dat"), 9999);
        writeLevelDat(world.resolve("level.dat"), 100);

        WorldStackAdmin.Preview preview = WorldStackAdmin.previewImport(world, server,
                List.of(), source.toString(), "bottom", "", false);
        assertFalse(preview.ok);
        assertTrue(preview.errors.stream().anyMatch(error -> error.contains("DataVersion")),
                String.valueOf(preview.errors));
    }

    @Test
    void previewRejectsSnapshotWorldOnReleaseServer() throws Exception {
        Path world = world();
        Path source = sourceWorld("snapshot-world");
        writeLevelDat(source.resolve("level.dat"), 5000, true, "26w14a");
        writeLevelDat(world.resolve("level.dat"), 5023, false, "26.3");

        WorldStackAdmin.Preview preview = WorldStackAdmin.previewImport(world, server,
                List.of(), source.toString(), "top", "", false);
        assertFalse(preview.ok);
        assertTrue(preview.errors.stream().anyMatch(error -> error.contains("快照")),
                String.valueOf(preview.errors));
    }

    private static void writeLevelDat(Path file, int dataVersion) throws Exception {
        writeLevelDat(file, dataVersion, false, "26.3");
    }

    private static void writeLevelDat(Path file, int dataVersion, boolean snapshot,
                                      String name) throws Exception {
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putInt("DataVersion", dataVersion);
        CompoundTag version = new CompoundTag();
        version.putBoolean("Snapshot", snapshot);
        version.putString("Name", name);
        data.put("Version", version);
        root.put("Data", data);
        NbtIo.writeCompressed(root, file);
    }

    @Test
    void importAbortsAndKeepsWorldWhenGenSettingsMissingAtStartup() throws Exception {
        Path world = world();
        Path source = sourceWorld("gen-vanishes");
        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "top", "", false);
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));

        // 排队后源目录被移动/清理：启动时必须中止，而不是留下无法启动的世界
        Files.delete(source.resolve("data/minecraft/world_gen_settings.dat"));
        WorldStackAdmin.consumePending(world);

        StackManifest manifest = StackManifest.load(world);
        assertEquals(1, manifest.layers.size());
        assertNotNull(manifest.byId("world"));
        assertTrue(Files.isDirectory(world.resolve("dimensions/minecraft")),
                "中止后原 world 维度必须保留");
        assertFalse(Files.exists(world.resolve("samsara/pending-imports.json")));
        WorldStackAdmin.Model model = WorldStackAdmin.model(world, server, List.of());
        assertNotNull(model.lastReport);
        assertEquals("failed", model.lastReport.status);
        assertTrue(model.lastReport.message.contains("world_gen_settings"));
    }

    @Test
    void reorderRejectsUnknownOrDuplicateIds() throws Exception {
        Path world = world();
        var result = WorldStackAdmin.reorder(world, List.of("stack_9"));
        assertEquals(false, result.get("ok"));
    }
}
