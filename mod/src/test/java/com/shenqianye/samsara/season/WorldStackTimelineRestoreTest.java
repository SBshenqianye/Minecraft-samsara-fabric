package com.shenqianye.samsara.season;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 世界栈操作快照、线性时间线与撤销/回滚。 */
class WorldStackTimelineRestoreTest {
    @TempDir
    Path server;

    private Path world;

    @BeforeEach
    void setUp() throws Exception {
        world = server.resolve("world");
        StackManifest manifest = new StackManifest();
        StackLayer top = layer("world", 0, true, 42);
        manifest.layers.add(top);
        manifest.normalize();
        manifest.save(world);
        Files.createDirectories(world.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(world.resolve("data/minecraft"));
        Files.createDirectories(world.resolve("samsara/claims"));
        Files.createDirectories(world.resolve("players/data"));
        Files.writeString(world.resolve("level.dat"), "old-level", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("data/minecraft/world_gen_settings.dat"), "old-gen",
                StandardCharsets.UTF_8);
        Files.writeString(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "old-region", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/claims/claims.json"), "old-claims",
                StandardCharsets.UTF_8);
    }

    @Test
    void operationJsonRoundTripAndMissingFields() throws Exception {
        StackManifest before = StackManifest.load(world);
        Path backup = WorldStackAdmin.backupRoot(world).resolve("import-imp-1");
        Files.createDirectories(backup);
        WorldStackTimeline.Operation op = WorldStackTimeline.beginOperation(world, "import",
                "imp-1", backup, before, List.of("stack_0"));
        WorldStackTimeline.Operation read = WorldStackTimeline.readOperation(world,
                backup.resolve(WorldStackTimeline.FILE_NAME));
        assertNotNull(read);
        assertEquals("ws-imp-1", read.id);
        assertEquals("import", read.operation);
        assertFalse(read.restorable, "进行中的操作默认不可撤销");

        WorldStackTimeline.finishOperation(world, backup, op, before, true,
                List.of("roundtrip"), null);
        WorldStackTimeline.Operation done = WorldStackTimeline.readOperation(world,
                backup.resolve(WorldStackTimeline.FILE_NAME));
        assertNotNull(done);
        assertTrue(done.restorable);
        assertEquals(1, done.beforeManifest.layers.size());

        Path incomplete = WorldStackAdmin.backupRoot(world).resolve("import-incomplete");
        Files.createDirectories(incomplete);
        Files.writeString(incomplete.resolve(WorldStackTimeline.FILE_NAME),
                "{\"format\":1,\"operation\":\"import\"}", StandardCharsets.UTF_8);
        WorldStackTimeline.Operation missing = WorldStackTimeline.readOperation(world,
                incomplete.resolve(WorldStackTimeline.FILE_NAME));
        assertNotNull(missing);
        assertFalse(missing.restorable);
        assertTrue(missing.beforeManifest.layers.isEmpty());
    }

    @Test
    void legacyBackupWithoutOperationJsonIsReadOnly() throws Exception {
        Path legacy = WorldStackAdmin.backupRoot(world).resolve("import-old-job");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("level.dat"), "legacy", StandardCharsets.UTF_8);

        Map<String, Object> timeline = WorldStackTimeline.timeline(world);
        Map<String, Object> node = findNode(timeline, "ws-old-job");
        assertNotNull(node, String.valueOf(timeline.get("nodes")));
        assertEquals(Boolean.FALSE, node.get("restorable"));
        assertEquals(Boolean.TRUE, node.get("legacy"));
        assertTrue(String.valueOf(node.get("restorableReason")).contains("operation.json"));

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-old-job");
        assertEquals(Boolean.FALSE, preview.get("ok"));
        assertTrue(String.valueOf(preview.get("errors")).contains("operation.json"));
    }

    @Test
    void timelineIsLinearWithFixedBranchAndParent() throws Exception {
        StackManifest before = StackManifest.load(world);
        for (int i = 1; i <= 3; i++) {
            Path backup = WorldStackAdmin.backupRoot(world).resolve("import-imp-" + i);
            Files.createDirectories(backup);
            WorldStackTimeline.Operation op = WorldStackTimeline.beginOperation(world, "import",
                    "imp-" + i, backup, before, List.of("world"));
            op.createdAt = 1_700_000_000_000L + i * 1000L;
            WorldStackTimeline.finishOperation(world, backup, op, before, true, List.of(), null);
        }
        Map<String, Object> timeline = WorldStackTimeline.timeline(world);
        List<Map<String, Object>> nodes = nodes(timeline);
        assertEquals(3, nodes.size());
        assertEquals("ws-imp-1", nodes.get(0).get("id"));
        assertEquals("ws-imp-2", nodes.get(1).get("id"));
        assertEquals("ws-imp-3", nodes.get(2).get("id"));
        assertEquals("", nodes.get(0).get("parentId"));
        assertEquals("ws-imp-1", nodes.get(1).get("parentId"));
        assertEquals("ws-imp-2", nodes.get(2).get("parentId"));
        for (Map<String, Object> node : nodes) {
            assertEquals(WorldStackTimeline.BRANCH_ID, node.get("branchId"));
            assertEquals(0, ((Number) node.get("depth")).intValue());
        }
        assertEquals("ws-imp-3", timeline.get("headId"));
        assertEquals(Boolean.TRUE, nodes.get(2).get("restorable"));
        assertEquals(Boolean.FALSE, nodes.get(0).get("restorable"));
    }

    @Test
    void previewRejectsManifestMismatch() throws Exception {
        recordImportLayer("imp-mismatch", "stack_0", 7L, null);
        StackManifest current = StackManifest.load(world);
        current.layers.get(0).seed = 999;
        current.save(world);

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-imp-mismatch");
        assertEquals(Boolean.FALSE, preview.get("ok"));
        assertTrue(String.valueOf(preview.get("errors")).contains("afterManifest"));
        Map<String, Object> schedule = WorldStackTimeline.schedule(world, "ws-imp-mismatch", true);
        assertEquals(Boolean.FALSE, schedule.get("ok"));
        assertFalse(Files.exists(WorldStackTimeline.pendingFile(world)));
    }

    @Test
    void undoImportLayerRemovesNewLayerAndKeepsPreRestore() throws Exception {
        Path addedPlayer = world.resolve("players/data/aa/player.dat");
        Files.createDirectories(addedPlayer.getParent());
        Files.writeString(addedPlayer, "new-player", StandardCharsets.UTF_8);
        String jobId = recordImportLayer("imp-undo", "stack_0", 7L, addedPlayer);

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-imp-undo");
        assertEquals(Boolean.TRUE, preview.get("ok"), String.valueOf(preview.get("errors")));
        Map<String, Object> scheduled = WorldStackTimeline.schedule(world, "ws-imp-undo", true);
        assertEquals(Boolean.TRUE, scheduled.get("ok"), String.valueOf(scheduled.get("error")));
        String restoreJob = String.valueOf(scheduled.get("jobId"));
        WorldStackAdmin.consumePending(world);

        assertEquals(List.of("world"), StackManifest.load(world).layers.stream()
                .map(layer -> layer.id).toList());
        assertFalse(Files.exists(world.resolve("dimensions/samsara/stack_0")));
        assertFalse(Files.exists(world.resolve("samsara/stack/stack_0")));
        assertFalse(Files.exists(addedPlayer));
        assertTrue(Files.isDirectory(WorldStackAdmin.backupRoot(world)
                .resolve("pre-restore-" + restoreJob)));
        assertFalse(Files.exists(WorldStackTimeline.pendingFile(world)));
        assertTrue(restored("ws-" + jobId));
        assertEquals("done", restoreJob(restoreJob).status);
    }

    @Test
    void undoPromoteRestoresTopAndLayerData() throws Exception {
        StackManifest before = StackManifest.load(world);
        StackLayer target = layer("stack_1", 0, false, 11);
        before.layers.add(0, target);
        before.normalize();
        before.save(world);
        writeLayer("stack_1", "layer-level", "layer-region");

        Path backup = WorldStackAdmin.backupRoot(world).resolve("promote-prom-undo");
        Files.createDirectories(backup);
        WorldStackTimeline.Operation op = WorldStackTimeline.beginOperation(world, "promote",
                "prom-undo", backup, before, List.of("world", "stack_1"));
        WorldStackTimeline.snapshotTop(world, backup);
        WorldStackTimeline.snapshotLayer(world, backup, "stack_1");

        // 模拟提升后：目标层数据成为 world，旧 world 接管 stack_1。
        Files.writeString(world.resolve("level.dat"), "layer-level", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "layer-region", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/stack/stack_1/level.dat"), "old-level",
                StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/stack/stack_1/overworld-region"), "old-region",
                StandardCharsets.UTF_8);
        StackManifest after = StackManifest.load(world);
        after.layers.get(0).seed = 11;
        after.layers.get(1).seed = 42;
        after.save(world);
        WorldStackTimeline.finishOperation(world, backup, op, after, true, List.of(), null);

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-prom-undo");
        assertEquals(Boolean.TRUE, preview.get("ok"), String.valueOf(preview.get("errors")));
        Map<String, Object> scheduled = WorldStackTimeline.schedule(world, "ws-prom-undo", true);
        assertEquals(Boolean.TRUE, scheduled.get("ok"), String.valueOf(scheduled.get("error")));
        WorldStackAdmin.consumePending(world);

        assertEquals("old-level", Files.readString(world.resolve("level.dat")));
        assertEquals("old-region", Files.readString(
                world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertEquals("layer-level", Files.readString(
                world.resolve("samsara/stack/stack_1/level.dat")));
        assertEquals("layer-region", Files.readString(
                world.resolve("dimensions/samsara/stack_1/overworld/region/r.0.0.mca")));
        assertEquals(42, StackManifest.load(world).top().seed);
    }

    @Test
    void undoArchiveCopiesLayerBackAndKeepsArchive() throws Exception {
        StackManifest before = StackManifest.load(world);
        before.layers.add(0, layer("stack_0", 0, false, 5));
        before.normalize();
        before.save(world);
        writeLayer("stack_0", "layer-level", "layer-region");

        Path archive = WorldStackAdmin.archiveRoot(world).resolve("stack_0-arc-undo");
        Files.createDirectories(archive);
        WorldStackTimeline.Operation op = WorldStackTimeline.beginOperation(world, "archive",
                "arc-undo", archive, before, List.of("stack_0"));
        moveDir(world.resolve("dimensions/samsara/stack_0"), archive.resolve("dimensions"));
        moveDir(world.resolve("samsara/stack/stack_0"), archive.resolve("meta"));
        StackManifest after = StackManifest.load(world);
        after.removeBelowTop("stack_0");
        after.save(world);
        WorldStackTimeline.finishOperation(world, archive, op, after, true,
                List.of("archive kept"), null);

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-arc-undo");
        assertEquals(Boolean.TRUE, preview.get("ok"), String.valueOf(preview.get("errors")));
        Map<String, Object> scheduled = WorldStackTimeline.schedule(world, "ws-arc-undo", true);
        assertEquals(Boolean.TRUE, scheduled.get("ok"), String.valueOf(scheduled.get("error")));
        WorldStackAdmin.consumePending(world);

        assertEquals("layer-level", Files.readString(
                world.resolve("samsara/stack/stack_0/level.dat")));
        assertEquals("layer-region", Files.readString(
                world.resolve("dimensions/samsara/stack_0/overworld/region/r.0.0.mca")));
        assertTrue(Files.isRegularFile(archive.resolve("dimensions/overworld/region/r.0.0.mca")),
                "源归档不能被撤销删除");
        assertNotNull(StackManifest.load(world).byId("stack_0"));
    }

    @Test
    void restoreFailureRollsBackToCurrentState() throws Exception {
        StackManifest before = StackManifest.load(world);
        Path backup = WorldStackAdmin.backupRoot(world).resolve("import-imp-fail");
        Files.createDirectories(backup);
        WorldStackTimeline.Operation op = WorldStackTimeline.beginOperation(world, "import",
                "imp-fail", backup, before, List.of("world", "stack_0"));
        WorldStackTimeline.snapshotTop(world, backup);

        // 模拟栈顶导入后的状态：world = 新世界，stack_0 = 旧世界。
        Files.writeString(world.resolve("level.dat"), "new-level", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "new-region", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("dimensions/samsara/stack_0/overworld/region"));
        Files.createDirectories(world.resolve("samsara/stack/stack_0"));
        Files.writeString(world.resolve("dimensions/samsara/stack_0/overworld/region/r.0.0.mca"),
                "old-region", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/stack/stack_0/level.dat"), "old-level",
                StandardCharsets.UTF_8);
        StackManifest after = StackManifest.load(world);
        after.layers.add(0, layer("stack_0", 0, false, 42));
        after.top().seed = 99;
        after.save(world);
        WorldStackTimeline.finishOperation(world, backup, op, after, true, List.of(), null);

        Map<String, Object> scheduled = WorldStackTimeline.schedule(world, "ws-imp-fail", true);
        assertEquals(Boolean.TRUE, scheduled.get("ok"), String.valueOf(scheduled.get("error")));
        String restoreJob = String.valueOf(scheduled.get("jobId"));

        // 写 stack.json.tmp 时失败，触发“先保存当前状态再回滚”的路径。
        Files.createDirectories(world.resolve("samsara/stack.json.tmp"));
        WorldStackAdmin.consumePending(world);

        assertEquals("new-level", Files.readString(world.resolve("level.dat")));
        assertEquals("new-region", Files.readString(
                world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertEquals("old-level", Files.readString(
                world.resolve("samsara/stack/stack_0/level.dat")));
        assertEquals("failed", restoreJob(restoreJob).status);
        assertTrue(Files.isDirectory(WorldStackAdmin.backupRoot(world)
                .resolve("pre-restore-" + restoreJob)));
        assertFalse(restored("ws-imp-fail"));
    }

    @Test
    void exportContainsBeforeAndAfterManifests() throws Exception {
        recordImportLayer("imp-export", "stack_0", 7L, null);
        Map<String, Object> exported = WorldStackTimeline.export(world, "ws-imp-export");
        assertEquals(Boolean.TRUE, exported.get("ok"));
        assertEquals("ws-imp-export", exported.get("id"));
        assertNotNull(exported.get("beforeManifest"));
        assertNotNull(exported.get("afterManifest"));
        assertTrue(String.valueOf(exported.get("affectedIds")).contains("stack_0"));
        assertTrue(exported.get("beforeSummary") instanceof List<?>);

        Map<String, Object> legacy = WorldStackTimeline.export(world, "ws-missing");
        assertEquals(Boolean.FALSE, legacy.get("ok"));
    }

    @Test
    void snapshotsCanBeListedAndOnlyPreRestoreCanBeCleaned() throws Exception {
        Path pre = WorldStackAdmin.backupRoot(world).resolve("pre-restore-job-1");
        Files.createDirectories(pre);
        Files.writeString(pre.resolve("stack.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(pre.resolve("state.json"),
                "{\"operationId\":\"ws-imp-1\",\"operation\":\"import\"}",
                StandardCharsets.UTF_8);
        Path operation = WorldStackAdmin.backupRoot(world).resolve("import-imp-1");
        Files.createDirectories(operation);
        Files.writeString(operation.resolve("marker.txt"), "keep", StandardCharsets.UTF_8);

        Map<String, Object> listed = WorldStackTimeline.snapshots(world);
        assertEquals(1, ((List<?>) listed.get("snapshots")).size());
        assertEquals(Boolean.FALSE, WorldStackTimeline.cleanup(world, "import-imp-1", true).get("ok"));
        assertEquals(Boolean.FALSE, WorldStackTimeline.cleanup(world, "pre-restore-job-1", false).get("ok"));
        Map<String, Object> removed = WorldStackTimeline.cleanup(world, "pre-restore-job-1", true);
        assertEquals(Boolean.TRUE, removed.get("ok"), String.valueOf(removed.get("error")));
        assertFalse(Files.exists(pre));
        assertTrue(Files.isDirectory(operation), "操作备份不能通过快照清理删除");
    }

    @Test
    void undoTwiceContinuesToPreviousOperation() throws Exception {
        String first = recordImportLayer("imp-first", "stack_0", 7L, null);
        String second = recordImportLayer("imp-second", "stack_1", 8L, null);
        assertEquals(List.of("stack_1", "stack_0", "world"),
                StackManifest.load(world).layers.stream().map(layer -> layer.id).toList());

        Map<String, Object> secondPreview = WorldStackTimeline.preview(world, "ws-" + second);
        assertEquals(Boolean.TRUE, secondPreview.get("ok"), String.valueOf(secondPreview.get("errors")));
        Map<String, Object> secondQueued = WorldStackTimeline.schedule(world, "ws-" + second, true);
        assertEquals(Boolean.TRUE, secondQueued.get("ok"), String.valueOf(secondQueued.get("error")));
        WorldStackAdmin.consumePending(world);

        Map<String, Object> afterFirstUndo = WorldStackTimeline.timeline(world);
        assertEquals(Boolean.FALSE, findNode(afterFirstUndo, "ws-" + second).get("restorable"));
        assertEquals("ws-" + first, afterFirstUndo.get("headId"));
        assertEquals(Boolean.TRUE, findNode(afterFirstUndo, "ws-" + first).get("restorable"));

        Map<String, Object> firstPreview = WorldStackTimeline.preview(world, "ws-" + first);
        assertEquals(Boolean.TRUE, firstPreview.get("ok"), String.valueOf(firstPreview.get("errors")));
        Map<String, Object> firstQueued = WorldStackTimeline.schedule(world, "ws-" + first, true);
        assertEquals(Boolean.TRUE, firstQueued.get("ok"), String.valueOf(firstQueued.get("error")));
        WorldStackAdmin.consumePending(world);

        assertEquals(List.of("world"), StackManifest.load(world).layers.stream()
                .map(layer -> layer.id).toList());
        assertFalse(Files.exists(world.resolve("dimensions/samsara/stack_0")));
        assertFalse(Files.exists(world.resolve("dimensions/samsara/stack_1")));
    }

    // ---- helpers ----

    private String recordImportLayer(String jobId, String layerId, long seed,
                                     Path addedPlayer) throws Exception {
        StackManifest before = StackManifest.load(world);
        Path backup = WorldStackAdmin.backupRoot(world).resolve("import-" + jobId);
        Files.createDirectories(backup);
        WorldStackTimeline.Operation op = WorldStackTimeline.beginOperation(world, "import",
                jobId, backup, before, List.of(layerId));
        writeLayer(layerId, "layer-level", "layer-region");
        StackManifest after = StackManifest.load(world);
        StackLayer layer = layer(layerId, 0, false, seed);
        after.layers.add(0, layer);
        after.normalize();
        after.save(world);
        WorldStackTimeline.finishOperation(world, backup, op, after, true, List.of(),
                addedPlayer == null ? null : List.of(addedPlayer));
        return jobId;
    }

    private void writeLayer(String id, String level, String region) throws Exception {
        StackLayer layer = new StackLayer();
        layer.id = id;
        layer.index = 0;
        layer.top = false;
        layer.seed = 1;
        layer.normalize();
        Files.createDirectories(world.resolve("dimensions/samsara").resolve(id)
                .resolve("overworld/region"));
        Files.createDirectories(world.resolve("samsara/stack").resolve(id)
                .resolve("data/minecraft"));
        Files.writeString(world.resolve("dimensions/samsara").resolve(id)
                .resolve("overworld/region/r.0.0.mca"), region, StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/stack").resolve(id).resolve("level.dat"),
                level, StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/stack").resolve(id)
                .resolve("data/minecraft/world_gen_settings.dat"), "layer-gen",
                StandardCharsets.UTF_8);
    }

    private static StackLayer layer(String id, int index, boolean top, long seed) {
        StackLayer layer = new StackLayer();
        layer.id = id;
        layer.index = index;
        layer.top = top;
        layer.seed = seed;
        layer.spawnX = 1;
        layer.spawnY = 70;
        layer.spawnZ = 2;
        layer.source = top ? "current" : "imported";
        layer.normalize();
        return layer;
    }

    private static void moveDir(Path source, Path target) throws Exception {
        Files.createDirectories(target.getParent());
        Files.move(source, target);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> nodes(Map<String, Object> timeline) {
        return (List<Map<String, Object>>) timeline.get("nodes");
    }

    private static Map<String, Object> findNode(Map<String, Object> timeline, String id) {
        for (Map<String, Object> node : nodes(timeline)) {
            if (id.equals(node.get("id"))) {
                return node;
            }
        }
        return null;
    }

    private boolean restored(String id) {
        WorldStackTimeline.Operation op = WorldStackTimeline.find(world, id);
        return op != null && op.restoredAt > 0;
    }

    private WorldStackAdmin.Job restoreJob(String id) {
        for (WorldStackAdmin.Job job : WorldStackAdmin.jobs(world, 20)) {
            if (id.equals(job.id)) {
                return job;
            }
        }
        return new WorldStackAdmin.Job();
    }
}
