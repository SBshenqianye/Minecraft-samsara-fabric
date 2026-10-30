package com.shenqianye.samsara.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 存储分层迁移：全新安装、旧数据、已迁移、冲突、全局数据双向切换。 */
class StorageMigrationTest {
    @TempDir
    Path tmp;

    private Path write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text);
        return file;
    }

    @Test
    void freshInstallJustCreatesRoots() throws Exception {
        Path worldRoot = tmp.resolve("world/samsara");
        Path global = worldRoot.resolve("global");
        String result = StorageMigration.migrate(null, null, worldRoot, global, 0);

        assertTrue(result.contains("全新安装"));
        assertTrue(Files.isDirectory(worldRoot));
        assertTrue(Files.isDirectory(global));
    }

    @Test
    void movesLegacyWorldDataAndStripsWorldLayer() throws Exception {
        Path legacy = tmp.resolve("config/samsara.legacy");
        write(legacy.resolve("season.json"), "{\"id\":3}");
        write(legacy.resolve("claims.json"), "{\"claims\":[]}");
        write(legacy.resolve("track/world/cycle3/2026-10-28/a.bin"), "track");
        write(legacy.resolve("diff/live/world/minecraft/overworld/r.0.0/0_0.dat"), "diff");
        write(legacy.resolve("global/identity.json"), "{}");
        Path worldRoot = tmp.resolve("world/samsara");
        Path global = worldRoot.resolve("global");

        String result = StorageMigration.migrate(legacy, null, worldRoot, global, 0);

        assertTrue(result.startsWith("已迁移"), result);
        assertEquals("{\"id\":3}", Files.readString(worldRoot.resolve("season.json")));
        assertEquals("{\"claims\":[]}", Files.readString(worldRoot.resolve("claims/claims.json")));
        assertTrue(Files.exists(worldRoot.resolve("track/cycle3/2026-10-28/a.bin")),
                "track 旧世界层必须剥离");
        assertTrue(Files.exists(worldRoot.resolve(
                "diff/live/minecraft/overworld/r.0.0/0_0.dat")), "diff 旧世界层必须剥离");
        assertTrue(Files.exists(global.resolve("identity.json")));
        assertFalse(Files.exists(worldRoot.resolve("track/world")));
    }

    @Test
    void alreadyMigratedSkipsAndKeepsSource() throws Exception {
        Path legacy = tmp.resolve("config/samsara.legacy");
        write(legacy.resolve("season.json"), "{}");
        Path worldRoot = tmp.resolve("world/samsara");
        Files.createDirectories(worldRoot);

        String result = StorageMigration.migrate(legacy, null, worldRoot,
                worldRoot.resolve("global"), StorageMigration.CURRENT_VERSION);

        assertTrue(result.contains("已迁移"));
        assertTrue(Files.exists(legacy.resolve("season.json")), "旧目录保留为备份，不删除");
    }

    @Test
    void conflictKeepsBothCopies() throws Exception {
        Path legacy = tmp.resolve("legacy");
        write(legacy.resolve("season.json"), "old");
        Path worldRoot = tmp.resolve("world/samsara");
        write(worldRoot.resolve("season.json"), "new");

        StorageMigration.migrate(legacy, null, worldRoot, worldRoot.resolve("global"), 0);

        assertEquals("new", Files.readString(worldRoot.resolve("season.json")),
                "目标文件不能被覆盖");
        try (var files = Files.list(worldRoot)) {
            assertTrue(files.anyMatch(p -> p.getFileName().toString()
                    .startsWith("season.conflict-")), "冲突文件保留第二份");
        }
    }

    @Test
    void moveGlobalIsBidirectionalAndKeepsData() throws Exception {
        Path serverGlobal = tmp.resolve("server/samsara/global");
        Path worldGlobal = tmp.resolve("world/samsara/global");
        write(serverGlobal.resolve("auth.json"), "auth");

        assertNull(StorageMigration.moveGlobal(serverGlobal, worldGlobal));
        assertEquals("auth", Files.readString(worldGlobal.resolve("auth.json")));
        assertFalse(Files.exists(serverGlobal), "成功迁移后源目录删除");

        assertNull(StorageMigration.moveGlobal(worldGlobal, serverGlobal));
        assertEquals("auth", Files.readString(serverGlobal.resolve("auth.json")));
        assertFalse(Files.exists(worldGlobal));
    }

    @Test
    void globalRootFollowsSwitchAndOverride() {
        Path world = tmp.resolve("world/samsara");
        Path server = tmp.resolve("server/samsara");

        assertEquals(world.resolve("global"),
                StoragePaths.resolveGlobalRoot(world, server, true, ""));
        assertEquals(server.resolve("global"),
                StoragePaths.resolveGlobalRoot(world, server, false, ""));
        Path override = tmp.resolve("custom/global");
        assertEquals(override, StoragePaths.resolveGlobalRoot(world, server, true,
                override.toString()).normalize(), "storage.yml 覆盖优先");
    }

    @Test
    void stripTrackWorldLayerIsIdempotent() throws Exception {
        Path track = tmp.resolve("track");
        write(track.resolve("world/cycle2/2026-10-28/b.bin"), "x");

        assertEquals(1, StorageMigration.stripTrackWorldLayer(track));
        assertTrue(Files.exists(track.resolve("cycle2/2026-10-28/b.bin")));
        assertEquals(0, StorageMigration.stripTrackWorldLayer(track));
        assertNotNull(track);
    }
}
