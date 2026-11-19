package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 世界导入来源校验。 */
class WorldStackImportGuardTest {

    @TempDir
    Path tmp;

    @Test
    void sourceMustLiveInsideAllowedRoots() throws Exception {
        Path server = tmp.resolve("server");
        Path world = server.resolve("world");
        Files.createDirectories(world);
        Path inside = server.resolve("import/ok");
        Files.createDirectories(inside);
        Path outside = tmp.resolve("elsewhere");
        Files.createDirectories(outside);

        assertTrue(WorldStackImportGuard.allowed(inside.toRealPath(), world, server, List.of()));
        assertFalse(WorldStackImportGuard.allowed(outside.toRealPath(), world, server, List.of()));
    }

    @Test
    void explicitImportRootIsAllowed() throws Exception {
        Path server = tmp.resolve("server");
        Path world = server.resolve("world");
        Files.createDirectories(world);
        Path external = tmp.resolve("external/world");
        Files.createDirectories(external);

        assertFalse(WorldStackImportGuard.allowed(external.toRealPath(), world, server, List.of()));
        assertTrue(WorldStackImportGuard.allowed(external.toRealPath(), world, server,
                List.of(tmp.resolve("external"))));
    }

    @Test
    void conflictFilesOnlyListsExistingTargets() throws Exception {
        Path world = tmp.resolve("server/world");
        Path source = tmp.resolve("server/import/src");
        Files.createDirectories(world.resolve("players/data"));
        Files.createDirectories(source.resolve("players/data"));
        Files.writeString(source.resolve("players/data/a.dat"), "a");
        Files.writeString(source.resolve("players/data/b.dat"), "b");
        Files.writeString(world.resolve("players/data/a.dat"), "existing");

        List<String> conflicts = WorldStackImportGuard.conflictFiles(world, source);

        assertEquals(List.of("a.dat"), conflicts);
        assertEquals(1, WorldStackImportGuard.playerConflicts(world, source));
    }

    @Test
    void missingLockIsNotLocked() {
        assertFalse(WorldStackImportGuard.sessionLocked(tmp.resolve("missing.lock")));
    }
}
