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
}
