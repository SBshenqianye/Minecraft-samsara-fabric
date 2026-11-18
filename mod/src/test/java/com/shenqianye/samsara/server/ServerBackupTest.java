package com.shenqianye.samsara.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerBackupTest {

    @TempDir
    Path root;

    private void prepareWorld() throws Exception {
        Files.writeString(root.resolve("server.properties"), "level-name=world\n", StandardCharsets.UTF_8);
        Path world = root.resolve("world");
        Files.createDirectories(world.resolve("region"));
        Files.createDirectories(world.resolve("samsara/claims"));
        Files.createDirectories(world.resolve("samsara/web-map-cache"));
        Files.writeString(world.resolve("level.dat"), "level-original", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("region/r.0.0.mca"), "region", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/claims/a.json"), "{}", StandardCharsets.UTF_8);
        Files.writeString(world.resolve("samsara/web-map-cache/derived.png"), "cache",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("ops.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("whitelist.json"), "[]", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("config/samsara"));
        Files.writeString(root.resolve("config/samsara/web.yml"), "token: secret\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("config/samsara/identity.yml"), "enabled: false\n",
                StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("samsara/shared/lottery"));
        Files.createDirectories(root.resolve("samsara/shared/insurance"));
        Files.writeString(root.resolve("samsara/shared/lottery/state.json"), "{\"fund\":100}\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("samsara/shared/insurance/policies.json"), "[]\n",
                StandardCharsets.UTF_8);
    }

    @Test
    void createBackupAndRestoreWorldAndConfigs() throws Exception {
        prepareWorld();
        Map<String, Object> scheduled = ServerBackup.scheduleCreate(root);
        assertTrue((Boolean) scheduled.get("ok"), String.valueOf(scheduled.get("error")));
        assertFalse(ServerBackup.list(root).size() > 0);

        ServerBackup.prepare(root);

        List<ServerBackup.BackupInfo> backups = ServerBackup.list(root);
        assertEquals(1, backups.size());
        ServerBackup.BackupInfo info = backups.get(0);
        assertTrue(Files.isRegularFile(Path.of(info.file)));
        assertTrue(info.fileCount > 0);
        assertTrue(info.totalBytes > 0);
        assertFalse(info.sha256.isBlank());
        assertFalse(Files.exists(ServerBackup.pendingBackupFile(root)));

        Files.writeString(root.resolve("world/level.dat"), "level-changed", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("config/samsara/identity.yml"), "enabled: true\n",
                StandardCharsets.UTF_8);

        ServerBackup.Preview preview = ServerBackup.previewRestore(root, info.id);
        assertTrue(preview.ok, String.join(";", preview.errors));
        Map<String, Object> restore = ServerBackup.scheduleRestore(root, info.id);
        assertTrue((Boolean) restore.get("ok"), String.valueOf(restore.get("error")));

        ServerBackup.prepare(root);

        assertEquals("level-original",
                Files.readString(root.resolve("world/level.dat"), StandardCharsets.UTF_8));
        assertEquals("enabled: false\n",
                Files.readString(root.resolve("config/samsara/identity.yml"), StandardCharsets.UTF_8));
        assertTrue(Files.isRegularFile(root.resolve("config/samsara/web.yml.restored-" + restore.get("jobId"))));
        assertTrue(Files.isRegularFile(root.resolve("server.properties.restored-" + restore.get("jobId"))));
        assertTrue(Files.isDirectory(ServerBackup.backupRoot(root)
                .resolve("pre-restore-" + restore.get("jobId")).resolve("world")));
        assertFalse(Files.exists(ServerBackup.pendingRestoreFile(root)));
    }

    @Test
    void restoreFailureRollsBackCurrentWorld() throws Exception {
        prepareWorld();
        ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        ServerBackup.BackupInfo info = ServerBackup.list(root).get(0);

        Files.writeString(root.resolve("world/level.dat"), "level-changed", StandardCharsets.UTF_8);
        Map<String, Object> restore = ServerBackup.scheduleRestore(root, info.id);
        assertTrue((Boolean) restore.get("ok"), String.valueOf(restore.get("error")));

        // 排队后破坏 zip，模拟解压失败；应回滚到当前 world，而不是留下半个世界。
        Files.writeString(Path.of(info.file), "corrupt", StandardCharsets.UTF_8);
        ServerBackup.prepare(root);

        assertEquals("level-changed",
                Files.readString(root.resolve("world/level.dat"), StandardCharsets.UTF_8));
        assertFalse(Files.exists(ServerBackup.pendingRestoreFile(root)));
        ServerBackup.Job job = ServerBackup.job(root, (String) restore.get("jobId"));
        assertNotNull(job);
        assertEquals("failed", job.status);
    }

    @Test
    void restoreRejectsDifferentLevelName() throws Exception {
        prepareWorld();
        ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        ServerBackup.BackupInfo info = ServerBackup.list(root).get(0);

        Files.writeString(root.resolve("server.properties"), "level-name=other\n", StandardCharsets.UTF_8);
        Files.createDirectories(root.resolve("other"));

        ServerBackup.Preview preview = ServerBackup.previewRestore(root, info.id);
        assertFalse(preview.ok);
        assertTrue(preview.errors.stream().anyMatch(e -> e.contains("level-name")));
    }

    @Test
    void backupSkipsSessionLockAndMapCache() throws Exception {
        prepareWorld();
        Files.writeString(root.resolve("world/session.lock"), "locked", StandardCharsets.UTF_8);
        ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        ServerBackup.BackupInfo info = ServerBackup.list(root).get(0);
        assertNotNull(info);
        try (var zip = new java.util.zip.ZipFile(info.file)) {
            assertTrue(zip.getEntry("world/level.dat") != null);
            assertTrue(zip.getEntry("world/session.lock") == null);
            assertTrue(zip.getEntry("world/samsara/web-map-cache/derived.png") == null);
            assertTrue(zip.getEntry("manifest.json") != null);
        }
    }

    @Test
    void timelineBranchesAfterRestore() throws Exception {
        prepareWorld();
        Map<String, Object> a = ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        Map<String, Object> b = ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);

        Map<String, Object> firstTree = timeline(root);
        assertEquals(2, ((List<?>) firstTree.get("nodes")).size());
        Map<String, Object> nodeB = findNode(firstTree, (String) b.get("id"));
        assertEquals(a.get("id"), nodeB.get("parentId"));
        assertEquals(0, nodeB.get("depth"));

        // 回滚到 A，再创建备份 C：C 应挂在 A 下，并另起分支。
        ServerBackup.scheduleRestore(root, (String) a.get("id"));
        ServerBackup.prepare(root);
        Map<String, Object> c = ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);

        Map<String, Object> secondTree = timeline(root);
        Map<String, Object> nodeA = findNode(secondTree, (String) a.get("id"));
        Map<String, Object> nodeC = findNode(secondTree, (String) c.get("id"));
        assertEquals(a.get("id"), nodeC.get("parentId"));
        assertNotEquals(nodeA.get("branchId"), nodeC.get("branchId"));
        assertEquals(1, nodeC.get("depth"));
        assertEquals(c.get("id"), secondTree.get("headId"));
        assertEquals(Boolean.TRUE, nodeC.get("current"));
    }

    @Test
    void deleteRejectsTimelineHeadAndParents() throws Exception {
        prepareWorld();
        Map<String, Object> a = ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        Map<String, Object> b = ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);

        Map<String, Object> headDelete = ServerBackup.delete(root, (String) b.get("id"), true);
        assertEquals(Boolean.FALSE, headDelete.get("ok"));

        Map<String, Object> parentDelete = ServerBackup.delete(root, (String) a.get("id"), true);
        assertEquals(Boolean.FALSE, parentDelete.get("ok"));
        assertTrue(String.valueOf(parentDelete.get("error")).contains("分支"));
    }

    @Test
    void backupIncludesSharedAndRestoreRestoresIt() throws Exception {
        prepareWorld();
        Map<String, Object> preview = ServerBackup.previewCreate(root);
        assertEquals(Boolean.TRUE, preview.get("ok"), String.valueOf(preview.get("error")));
        assertEquals(2, preview.get("sharedFiles"));

        ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        ServerBackup.BackupInfo info = ServerBackup.list(root).get(0);
        try (var zip = new java.util.zip.ZipFile(info.file)) {
            assertNotNull(zip.getEntry("shared/lottery/state.json"), "新备份必须包含奖池/基金");
            assertNotNull(zip.getEntry("shared/insurance/policies.json"), "新备份必须包含保险基金");
        }

        // 模拟换周目后数据变化，再恢复：shared 应回到备份时的值。
        Files.writeString(root.resolve("samsara/shared/lottery/state.json"), "{\"fund\":999}\n",
                StandardCharsets.UTF_8);
        ServerBackup.Preview restorePreview = ServerBackup.previewRestore(root, info.id);
        assertTrue(restorePreview.ok, String.join(";", restorePreview.errors));
        assertTrue(restorePreview.includesShared);
        assertEquals(2, restorePreview.sharedFiles);

        Map<String, Object> restore = ServerBackup.scheduleRestore(root, info.id);
        assertTrue((Boolean) restore.get("ok"), String.valueOf(restore.get("error")));
        ServerBackup.prepare(root);

        assertEquals("{\"fund\":100}\n",
                Files.readString(root.resolve("samsara/shared/lottery/state.json"), StandardCharsets.UTF_8));
        // 恢复前的 shared 也保留在回滚目录，可人工找回。
        assertEquals("{\"fund\":999}\n", Files.readString(
                ServerBackup.backupRoot(root).resolve("pre-restore-" + restore.get("jobId"))
                        .resolve("shared/lottery/state.json"), StandardCharsets.UTF_8));
    }

    @Test
    void restoreOldZipWithoutSharedKeepsCurrentShared() throws Exception {
        prepareWorld();
        ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        ServerBackup.BackupInfo info = ServerBackup.list(root).get(0);

        stripSharedFromZip(Path.of(info.file));
        Files.writeString(root.resolve("samsara/shared/lottery/state.json"), "{\"fund\":777}\n",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("world/level.dat"), "level-changed", StandardCharsets.UTF_8);

        ServerBackup.Preview preview = ServerBackup.previewRestore(root, info.id);
        assertTrue(preview.ok, String.join(";", preview.errors));
        assertFalse(preview.includesShared);
        assertEquals(0, preview.sharedFiles);

        Map<String, Object> restore = ServerBackup.scheduleRestore(root, info.id);
        assertTrue((Boolean) restore.get("ok"), String.valueOf(restore.get("error")));
        ServerBackup.prepare(root);

        assertEquals("level-original",
                Files.readString(root.resolve("world/level.dat"), StandardCharsets.UTF_8));
        assertEquals("{\"fund\":777}\n", Files.readString(
                root.resolve("samsara/shared/lottery/state.json"), StandardCharsets.UTF_8),
                "旧备份不含 shared 时不得清零当前奖池/保险基金");
    }

    @Test
    void restoreFailureRollsBackShared() throws Exception {
        prepareWorld();
        ServerBackup.scheduleCreate(root);
        ServerBackup.prepare(root);
        ServerBackup.BackupInfo info = ServerBackup.list(root).get(0);

        Files.writeString(root.resolve("samsara/shared/lottery/state.json"), "{\"fund\":555}\n",
                StandardCharsets.UTF_8);
        Map<String, Object> restore = ServerBackup.scheduleRestore(root, info.id);
        assertTrue((Boolean) restore.get("ok"), String.valueOf(restore.get("error")));

        Files.writeString(Path.of(info.file), "corrupt", StandardCharsets.UTF_8);
        ServerBackup.prepare(root);

        assertEquals("{\"fund\":555}\n", Files.readString(
                root.resolve("samsara/shared/lottery/state.json"), StandardCharsets.UTF_8),
                "恢复失败必须把 shared 放回原处");
        ServerBackup.Job job = ServerBackup.job(root, (String) restore.get("jobId"));
        assertNotNull(job);
        assertEquals("failed", job.status);
    }

    /** 把新格式 zip 改写成旧格式：去掉 shared/，manifest 回到 format 1。 */
    private static void stripSharedFromZip(Path zip) throws Exception {
        Path tmp = zip.resolveSibling(zip.getFileName() + ".old");
        try (var in = new java.util.zip.ZipFile(zip.toFile());
             var out = new java.util.zip.ZipOutputStream(Files.newOutputStream(tmp))) {
            var entries = in.entries();
            while (entries.hasMoreElements()) {
                var entry = entries.nextElement();
                if (entry.isDirectory() || entry.getName().startsWith("shared/")) {
                    continue;
                }
                out.putNextEntry(new java.util.zip.ZipEntry(entry.getName()));
                if ("manifest.json".equals(entry.getName())) {
                    String json;
                    try (var stream = in.getInputStream(entry)) {
                        json = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                    }
                    var manifest = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
                    manifest.addProperty("format", 1);
                    manifest.remove("sharedBytes");
                    manifest.remove("sharedFileCount");
                    out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
                } else {
                    try (var stream = in.getInputStream(entry)) {
                        stream.transferTo(out);
                    }
                }
                out.closeEntry();
            }
        }
        Files.move(tmp, zip, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
    }

    private static Map<String, Object> timeline(Path root) {
        return ServerBackup.timeline(root);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> findNode(Map<String, Object> tree, String id) {
        for (Map<String, Object> node : (List<Map<String, Object>>) tree.get("nodes")) {
            if (id.equals(node.get("id"))) {
                return node;
            }
        }
        return null;
    }
}
