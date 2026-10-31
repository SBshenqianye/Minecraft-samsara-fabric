package com.shenqianye.samsara.season;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 旧 world/world_sN 布局 → v0.24 world=栈顶 + stack_<n> 维度，数据不丢。 */
class StackLegacyMigrationTest {
    @TempDir
    Path tmp;

    @Test
    void migratesOldSiblingWorldsWithoutLosingData() throws Exception {
        Path root = tmp.resolve("server");
        Path oldWorld = root.resolve("world");
        Path s1 = root.resolve("world_s1");
        Path s2 = root.resolve("world_s2");
        Files.createDirectories(root);
        Files.writeString(root.resolve("server.properties"),
                "level-name=world_s2\nlevel-seed=999\nlevel-type=normal\n", StandardCharsets.UTF_8);

        seedLayer(oldWorld, 101, 10, "bottom");
        seedLayer(s1, 102, 20, "middle");
        seedLayer(s2, 103, 30, "top");

        // 玩家数据：三份不同玩家 + 同名冲突（26.3 布局 players/data/<uuid>.dat）
        Files.createDirectories(oldWorld.resolve("players/data"));
        Files.createDirectories(s1.resolve("players/data"));
        Files.createDirectories(s2.resolve("players/data"));
        Files.writeString(oldWorld.resolve("players/data/b.dat"), "bottom-b");
        Files.writeString(s1.resolve("players/data/c.dat"), "middle-c");
        Files.writeString(s2.resolve("players/data/a.dat"), "top-a");
        Files.writeString(oldWorld.resolve("players/data/shared.dat"), "old-shared");
        Files.writeString(s2.resolve("players/data/shared.dat"), "new-shared");
        Files.setLastModifiedTime(oldWorld.resolve("players/data/shared.dat"),
                FileTime.fromMillis(1_000L));
        Files.setLastModifiedTime(s2.resolve("players/data/shared.dat"),
                FileTime.fromMillis(2_000L));

        // samsara 世界数据：旧层与栈顶各一份
        for (Path dir : List.of(oldWorld, s1, s2)) {
            Files.createDirectories(dir.resolve("samsara/claims"));
            Files.createDirectories(dir.resolve("samsara/diff/live/minecraft/overworld"));
            Files.createDirectories(dir.resolve("samsara/track/cycle1/2026-11-01"));
            String name = dir.getFileName().toString();
            Files.writeString(dir.resolve("samsara/claims/claims.json"), "claims-" + name);
            Files.writeString(dir.resolve("samsara/diff/live/minecraft/overworld/marker.dat"),
                    "diff-" + name);
            Files.writeString(dir.resolve("samsara/track/cycle1/2026-11-01/track.bin"),
                    "track-" + name);
            Files.writeString(dir.resolve("samsara/spawn-history.json"), "spawn-" + name);
        }

        assertTrue(StackLegacyMigration.migrate(root.resolve("world"), root));

        StackManifest manifest = StackManifest.load(root.resolve("world"));
        assertEquals(3, manifest.layers.size());
        assertEquals("stack_0", manifest.layers.get(0).id);
        assertEquals("stack_1", manifest.layers.get(1).id);
        assertEquals("world", manifest.top().id);
        assertEquals(101, manifest.layers.get(0).seed);
        assertEquals(102, manifest.layers.get(1).seed);
        assertEquals(103, manifest.top().seed);
        assertEquals(30, manifest.top().spawnX);

        // 栈顶仍是 world，旧层是独立自定义维度。
        assertTrue(Files.isRegularFile(root.resolve("world/level.dat")));
        assertEquals("top", Files.readString(
                root.resolve("world/dimensions/minecraft/overworld/marker")));
        assertEquals("bottom", Files.readString(
                root.resolve("world/dimensions/samsara/stack_0/overworld/marker")));
        assertEquals("middle", Files.readString(
                root.resolve("world/dimensions/samsara/stack_1/overworld/marker")));

        // 旧层元数据 + 数据根。
        assertTrue(Files.isRegularFile(
                root.resolve("world/samsara/stack/stack_0/level.dat")));
        assertTrue(Files.isRegularFile(
                root.resolve("world/samsara/stack/stack_0/stack.json")));
        assertTrue(Files.isRegularFile(
                root.resolve("world/samsara/stack/stack_0/data/minecraft/world_gen_settings.dat")));
        assertEquals("claims-world", Files.readString(
                root.resolve("world/samsara/stack/stack_0/claims/claims.json")));
        assertEquals("diff-world_s1", Files.readString(
                root.resolve("world/samsara/stack/stack_1/diff/live/minecraft/overworld/marker.dat")));
        assertTrue(Files.isRegularFile(
                root.resolve("world/samsara/stack/stack_1/track/cycle1/2026-11-01/track.bin")));
        assertEquals("spawn-world", Files.readString(
                root.resolve("world/samsara/stack/stack_0/spawn-history.json")));

        // 栈顶数据仍在 world/samsara 与 world/data。
        assertEquals("claims-world_s2", Files.readString(
                root.resolve("world/samsara/claims/claims.json")));
        assertEquals("diff-world_s2", Files.readString(
                root.resolve("world/samsara/diff/live/minecraft/overworld/marker.dat")));
        assertEquals("spawn-world_s2", Files.readString(
                root.resolve("world/samsara/spawn-history.json")));
        assertEquals("scoreboard-top", Files.readString(
                root.resolve("world/data/minecraft/scoreboard.dat")));
        assertEquals("scoreboard-bottom", Files.readString(
                root.resolve("world/samsara/stack/stack_0/data/minecraft/scoreboard.dat")));

        // 玩家共享目录：三份玩家都在，同名冲突保留旧版本。
        assertEquals("top-a", Files.readString(root.resolve("world/players/data/a.dat")));
        assertEquals("middle-c", Files.readString(root.resolve("world/players/data/c.dat")));
        assertEquals("bottom-b", Files.readString(root.resolve("world/players/data/b.dat")));
        assertEquals("new-shared", Files.readString(root.resolve("world/players/data/shared.dat")));
        assertTrue(Files.isDirectory(root.resolve("world/samsara/legacy-player-conflicts")));

        // 旧兄弟目录已移走，不再残留被误当世界扫描。
        assertFalse(Files.exists(s1));
        assertFalse(Files.exists(s2));
        assertFalse(Files.exists(root.resolve("legacy-layout.migrated").resolve("world_s1")));

        String props = Files.readString(root.resolve("server.properties"));
        assertTrue(props.contains("level-name=world"));
        assertTrue(props.contains("level-seed=103"));
    }

    private static void seedLayer(Path dir, long seed, int spawnX, String marker) throws Exception {
        Files.createDirectories(dir.resolve("dimensions/minecraft/overworld"));
        Files.createDirectories(dir.resolve("data/minecraft"));
        Files.createDirectories(dir.resolve("players"));
        Files.createDirectories(dir.resolve("samsara"));
        Files.writeString(dir.resolve("dimensions/minecraft/overworld/marker"), marker);
        Files.writeString(dir.resolve("data/minecraft/scoreboard.dat"), "scoreboard-" + marker);

        CompoundTag level = new CompoundTag();
        CompoundTag data = new CompoundTag();
        data.putInt("SpawnX", spawnX);
        data.putInt("SpawnY", 64);
        data.putInt("SpawnZ", spawnX);
        level.put("Data", data);
        NbtIo.writeCompressed(level, dir.resolve("level.dat"));

        CompoundTag gen = new CompoundTag();
        CompoundTag genData = new CompoundTag();
        genData.putLong("seed", seed);
        gen.put("data", genData);
        NbtIo.writeCompressed(gen, dir.resolve("data/minecraft/world_gen_settings.dat"));
    }
}
