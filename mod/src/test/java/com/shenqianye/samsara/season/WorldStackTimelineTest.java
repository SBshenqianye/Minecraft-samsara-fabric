package com.shenqianye.samsara.season;

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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 世界栈操作快照、线性时间线与撤销。 */
class WorldStackTimelineTest {
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
        Files.createDirectories(world.resolve("data/minecraft"));
        Files.createDirectories(world.resolve("players/data"));
        Files.writeString(world.resolve("level.dat"), "old-level", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("data/minecraft/world_gen_settings.dat"), "old-gen",
                StandardCharsets.UTF_8);
        Files.writeString(world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "old-region", StandardCharsets.UTF_8);
        return world;
    }

    private Path sourceWorld(String name) throws Exception {
        Path src = server.resolve("imports").resolve(name);
        Files.createDirectories(src.resolve("dimensions/minecraft/overworld/region"));
        Files.createDirectories(src.resolve("players/data"));
        Files.createDirectories(src.resolve("data/minecraft"));
        Files.writeString(src.resolve("level.dat"), "new-level", StandardCharsets.UTF_8);
        Files.write(src.resolve("dimensions/minecraft/overworld/region/r.0.0.mca"),
                "new-region".getBytes(StandardCharsets.UTF_8));
        Files.writeString(src.resolve("data/minecraft/world_gen_settings.dat"), "new-gen",
                StandardCharsets.UTF_8);
        Files.writeString(src.resolve("players/data/00000000-0000-0000-0000-000000000001.dat"),
                "player", StandardCharsets.UTF_8);
        return src;
    }

    private static Map<String, Object> node(Map<String, Object> timeline, String id) {
        Object raw = timeline.get("nodes");
        assertTrue(raw instanceof List<?>, "timeline.nodes 必须是列表");
        for (Object item : (List<?>) raw) {
            @SuppressWarnings("unchecked")
            Map<String, Object> node = (Map<String, Object>) item;
            if (id.equals(node.get("id"))) {
                return node;
            }
        }
        return null;
    }

    private static List<String> strings(Object value) {
        @SuppressWarnings("unchecked")
        List<String> list = (List<String>) value;
        return list;
    }

    @Test
    void operationJsonIsWrittenAndTimelineIsLinearWithoutBranches() throws Exception {
        Path world = world();
        Path source = sourceWorld("first");
        WorldStackAdmin.ScheduleResult first = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "bottom", "", false);
        assertTrue(first.ok, String.valueOf(first.preview.errors));
        WorldStackAdmin.consumePending(world);

        Path opFile = server.resolve("samsara/world-stack-backup/import-" + first.jobId)
                .resolve("operation.json");
        assertTrue(Files.isRegularFile(opFile), "新导入必须写 operation.json");
        WorldStackTimeline.Operation op = WorldStackTimeline.readOperation(world, opFile);
        assertNotNull(op);
        assertEquals("import", op.operation);
        assertEquals(first.jobId, op.jobId);
        assertEquals("ws-" + first.jobId, op.id);
        assertTrue(op.restorable);
        assertTrue(op.affectedIds.contains("stack_0"));
        assertEquals(1, op.beforeManifest.layers.size());
        assertEquals(2, op.afterManifest.layers.size());
        assertTrue(Files.isDirectory(server.resolve("samsara/world-stack-backup/import-"
                + first.jobId)));

        Map<String, Object> timeline = WorldStackTimeline.timeline(world);
        Map<String, Object> firstNode = node(timeline, "ws-" + first.jobId);
        assertNotNull(firstNode, String.valueOf(timeline));
        assertEquals("world-stack", firstNode.get("branchId"));
        assertEquals("", String.valueOf(firstNode.get("parentId")));
        assertEquals(0, ((Number) firstNode.get("depth")).intValue());
        assertEquals(Boolean.TRUE, firstNode.get("current"));
        assertEquals(Boolean.TRUE, firstNode.get("restorable"));
        assertEquals("import", firstNode.get("operation"));
        assertTrue(strings(firstNode.get("beforeSummary")).stream()
                .anyMatch(line -> line.contains("world")));

        Path secondSource = sourceWorld("second");
        WorldStackAdmin.ScheduleResult second = WorldStackAdmin.scheduleImport(world, server,
                List.of(), secondSource.toString(), "bottom", "", false);
        assertTrue(second.ok, String.valueOf(second.preview.errors));
        WorldStackAdmin.consumePending(world);

        Map<String, Object> after = WorldStackTimeline.timeline(world);
        Map<String, Object> secondNode = node(after, "ws-" + second.jobId);
        assertNotNull(secondNode);
        assertEquals("ws-" + first.jobId, String.valueOf(secondNode.get("parentId")));
        assertEquals("world-stack", secondNode.get("branchId"));
        assertEquals(0, ((Number) secondNode.get("depth")).intValue());
        assertEquals(Boolean.TRUE, secondNode.get("current"));
        assertEquals(Boolean.FALSE, node(after, "ws-" + first.jobId).get("restorable"));
    }

    @Test
    void legacyBackupWithoutOperationJsonIsReadOnly() throws Exception {
        Path world = world();
        Path legacy = server.resolve("samsara/world-stack-backup/import-legacy-1");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("marker.txt"), "old", StandardCharsets.UTF_8);

        Map<String, Object> timeline = WorldStackTimeline.timeline(world);
        Map<String, Object> node = node(timeline, "ws-legacy-1");
        assertNotNull(node, String.valueOf(timeline));
        assertEquals(Boolean.TRUE, node.get("legacy"));
        assertEquals(Boolean.FALSE, node.get("restorable"));
        assertTrue(String.valueOf(node.get("restorableReason")).contains("旧"));

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-legacy-1");
        assertEquals(Boolean.FALSE, preview.get("ok"));
        assertTrue(strings(preview.get("errors")).stream()
                .anyMatch(line -> line.contains("operation.json")));
    }

    @Test
    void previewRejectsManifestMismatch() throws Exception {
        Path world = world();
        Path source = sourceWorld("mismatch");
        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "bottom", "", false);
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        WorldStackAdmin.consumePending(world);

        StackManifest manifest = StackManifest.load(world);
        StackLayer extra = new StackLayer();
        extra.id = "stack_9";
        extra.seed = 9;
        manifest.insertBelowTop(extra, 0);
        manifest.save(world);

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-" + scheduled.jobId);
        assertEquals(Boolean.FALSE, preview.get("ok"));
        assertTrue(strings(preview.get("errors")).stream()
                .anyMatch(line -> line.contains("afterManifest") || line.contains("不一致")),
                String.valueOf(preview.get("errors")));
    }

    @Test
    void undoImportRemovesCreatedLayerAndKeepsPreRestore() throws Exception {
        Path world = world();
        Path source = sourceWorld("undo-bottom");
        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "bottom", "", false);
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        WorldStackAdmin.consumePending(world);
        assertNotNull(StackManifest.load(world).byId("stack_0"));

        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-" + scheduled.jobId);
        assertEquals(Boolean.TRUE, preview.get("ok"), String.valueOf(preview.get("errors")));

        Map<String, Object> queued = WorldStackTimeline.schedule(world, "ws-" + scheduled.jobId, true);
        assertEquals(Boolean.TRUE, queued.get("ok"), String.valueOf(queued.get("error")));
        assertTrue(Files.isRegularFile(world.resolve("samsara/pending-stack-restore.json")));

        WorldStackAdmin.consumePending(world);

        StackManifest restored = StackManifest.load(world);
        assertEquals(1, restored.layers.size());
        assertEquals("world", restored.top().id);
        assertNull(restored.byId("stack_0"));
        assertFalse(Files.exists(world.resolve("dimensions/samsara/stack_0")));
        assertFalse(Files.exists(world.resolve("samsara/stack/stack_0")));
        assertFalse(Files.exists(world.resolve("players/data/"
                + "00000000-0000-0000-0000-000000000001.dat")));
        assertFalse(Files.isRegularFile(world.resolve("samsara/pending-stack-restore.json")));
        String jobId = String.valueOf(queued.get("jobId"));
        try (var stream = Files.list(server.resolve("samsara/world-stack-backup"))) {
            assertTrue(stream.anyMatch(path -> path.getFileName().toString()
                    .equals("pre-restore-" + jobId)), "执行前必须保留 pre-restore 快照");
        }
        WorldStackTimeline.Operation op = WorldStackTimeline.find(world,
                "ws-" + scheduled.jobId);
        assertNotNull(op);
        assertTrue(op.restoredAt > 0);
        assertFalse(op.restorable);
        // 撤销后时间线会退回上一个可撤销节点；导入节点保留为已撤销历史。
        Map<String, Object> timeline = WorldStackTimeline.timeline(world);
        assertEquals(Boolean.FALSE, node(timeline, "ws-" + scheduled.jobId).get("restorable"));
    }

    @Test
    void undoTopImportRestoresOldTopDataAndSourceStaysUntouched() throws Exception {
        Path world = world();
        Path source = sourceWorld("undo-top");
        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleImport(world, server,
                List.of(), source.toString(), "top", "", false);
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        WorldStackAdmin.consumePending(world);
        assertEquals("new-region", Files.readString(
                world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));

        Map<String, Object> queued = WorldStackTimeline.schedule(world,
                "ws-" + scheduled.jobId, true);
        assertEquals(Boolean.TRUE, queued.get("ok"), String.valueOf(queued.get("error")));
        WorldStackAdmin.consumePending(world);

        StackManifest restored = StackManifest.load(world);
        assertEquals(1, restored.layers.size());
        assertEquals("old-level", Files.readString(world.resolve("level.dat")));
        assertEquals("old-gen", Files.readString(
                world.resolve("data/minecraft/world_gen_settings.dat")));
        assertEquals("old-region", Files.readString(
                world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertFalse(Files.exists(world.resolve("dimensions/samsara/stack_0")));
        assertTrue(Files.isRegularFile(
                source.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")),
                "源目录必须保持只读");
    }

    @Test
    void undoPromoteRestoresTopAndLayerData() throws Exception {
        Path world = world();
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

        var result = WorldStackAdmin.reorder(world, List.of("stack_0", "world", "stack_1"));
        assertEquals(true, result.get("ok"), String.valueOf(result.get("error")));
        WorldStackAdmin.consumePending(world);
        assertEquals("promoted-level", Files.readString(world.resolve("level.dat")));

        String jobId = String.valueOf(result.get("jobId"));
        Map<String, Object> queued = WorldStackTimeline.schedule(world, "ws-" + jobId, true);
        assertEquals(Boolean.TRUE, queued.get("ok"), String.valueOf(queued.get("error")));
        WorldStackAdmin.consumePending(world);

        assertEquals("old-level", Files.readString(world.resolve("level.dat")));
        assertEquals("old-region", Files.readString(
                world.resolve("dimensions/minecraft/overworld/region/r.0.0.mca")));
        assertEquals("promoted-region", Files.readString(
                world.resolve("dimensions/samsara/stack_1/overworld/region/r.0.0.mca")));
        assertEquals("promoted-level", Files.readString(
                world.resolve("samsara/stack/stack_1/level.dat")));
        assertEquals(List.of("stack_0", "stack_1", "world"),
                StackManifest.load(world).layers.stream().map(layer -> layer.id).toList());
    }

    @Test
    void undoArchiveCopiesLayerBackAndKeepsArchive() throws Exception {
        Path world = world();
        StackManifest manifest = StackManifest.load(world);
        StackLayer old = new StackLayer();
        old.id = "stack_0";
        old.index = 0;
        old.seed = 1;
        manifest.layers.add(0, old);
        manifest.normalize();
        manifest.save(world);
        Files.createDirectories(world.resolve("dimensions/samsara/stack_0/overworld/region"));
        Files.writeString(world.resolve("dimensions/samsara/stack_0/overworld/region/r.0.0.mca"),
                "archived-region", StandardCharsets.UTF_8);
        Files.createDirectories(world.resolve("samsara/stack/stack_0"));
        Files.writeString(world.resolve("samsara/stack/stack_0/marker.txt"), "meta",
                StandardCharsets.UTF_8);

        WorldStackAdmin.ScheduleResult scheduled = WorldStackAdmin.scheduleArchive(world, "stack_0");
        assertTrue(scheduled.ok, String.valueOf(scheduled.preview.errors));
        WorldStackAdmin.consumePending(world);
        assertNull(StackManifest.load(world).byId("stack_0"));

        Map<String, Object> queued = WorldStackTimeline.schedule(world,
                "ws-" + scheduled.jobId, true);
        assertEquals(Boolean.TRUE, queued.get("ok"), String.valueOf(queued.get("error")));
        WorldStackAdmin.consumePending(world);

        assertNotNull(StackManifest.load(world).byId("stack_0"));
        assertEquals("archived-region", Files.readString(
                world.resolve("dimensions/samsara/stack_0/overworld/region/r.0.0.mca")));
        assertEquals("meta", Files.readString(
                world.resolve("samsara/stack/stack_0/marker.txt")));
        Path archiveRoot = server.resolve("samsara/world-stack-archive");
        try (var stream = Files.list(archiveRoot)) {
            Path archive = stream.filter(Files::isDirectory).findFirst().orElseThrow();
            assertTrue(Files.isRegularFile(
                    archive.resolve("dimensions/overworld/region/r.0.0.mca")),
                    "源归档必须保留");
        }
    }

    @Test
    void hierarchyTestDataHasNoWorldGenSettingsStillCountsAsLegacy() throws Exception {
        // 旧备份没有 operation.json 时，即使目录里只有 level.dat 也不能猜测撤销。
        Path world = world();
        Path legacy = server.resolve("samsara/world-stack-backup/promote-old");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("top-level.dat"), "x", StandardCharsets.UTF_8);
        WorldStackTimeline.Operation op = WorldStackTimeline.find(world, "ws-legacy-old");
        assertNull(op);
        Map<String, Object> preview = WorldStackTimeline.preview(world, "ws-legacy-old");
        assertEquals(Boolean.FALSE, preview.get("ok"));
    }
}
