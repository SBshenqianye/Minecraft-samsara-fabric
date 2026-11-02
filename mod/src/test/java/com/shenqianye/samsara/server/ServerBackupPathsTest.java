package com.shenqianye.samsara.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 服务器级备份根迁移：旧 <server>/samsara → <server>/backups/samsara。 */
class ServerBackupPathsTest {

    @Test
    void rootIsBackupsSamsara(@TempDir Path dir) {
        assertEquals(dir.resolve("backups/samsara").toAbsolutePath().normalize(),
                ServerBackupPaths.root(dir));
    }

    @Test
    void migratesOnlyBackupEntriesAndKeepsGlobal(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("samsara");
        Files.createDirectories(legacy.resolve("server-backups"));
        Files.writeString(legacy.resolve("server-backups/sb-1.zip"), "zip", StandardCharsets.UTF_8);
        Files.writeString(legacy.resolve("pending-server-backup.json"), "{}", StandardCharsets.UTF_8);
        Files.createDirectories(legacy.resolve("global"));
        Files.writeString(legacy.resolve("global/audit.jsonl"), "keep", StandardCharsets.UTF_8);

        ServerBackupPaths.migrateLegacyRoot(dir);

        assertTrue(Files.isRegularFile(dir.resolve(
                "backups/samsara/server-backups/sb-1.zip")));
        assertTrue(Files.isRegularFile(dir.resolve(
                "backups/samsara/pending-server-backup.json")));
        assertFalse(Files.exists(legacy.resolve("server-backups")));
        assertFalse(Files.exists(legacy.resolve("pending-server-backup.json")));
        // 运行时 global 不属于备份，不能搬走
        assertTrue(Files.isRegularFile(legacy.resolve("global/audit.jsonl")));
    }

    @Test
    void doesNotOverwriteExistingTarget(@TempDir Path dir) throws Exception {
        Path legacy = dir.resolve("samsara/server-backups");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("old.zip"), "old", StandardCharsets.UTF_8);
        Path target = dir.resolve("backups/samsara/server-backups");
        Files.createDirectories(target);
        Files.writeString(target.resolve("new.zip"), "new", StandardCharsets.UTF_8);

        ServerBackupPaths.migrateLegacyRoot(dir);

        assertTrue(Files.isRegularFile(target.resolve("new.zip")));
        assertTrue(Files.isRegularFile(legacy.resolve("old.zip")), "冲突时保留旧文件");
    }
}
