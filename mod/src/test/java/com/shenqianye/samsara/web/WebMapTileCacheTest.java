package com.shenqianye.samsara.web;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Web 地图磁盘缓存：路径、mtime/size 失效、跨实例复用、LRU 淘汰。 */
class WebMapTileCacheTest {
    @TempDir
    Path temp;

    @Test
    void writeAndReadBackAcrossInstances() {
        Path root = temp.resolve("cache");
        WebMapTileCache cache = new WebMapTileCache(root);
        byte[] png = new byte[]{1, 2, 3, 4};
        cache.put("world", "minecraft:overworld", 2, -3, 4, true, 111L, 999L, png);

        Path file = root.resolve("world/minecraft/overworld/4/1/r.2.-3.111.999.png");
        assertTrue(Files.isRegularFile(file), "缓存文件应写到栈层/维度/scale/shade 下");

        WebMapTileCache reopened = new WebMapTileCache(root);
        assertArrayEquals(png, reopened.get("world", "minecraft:overworld",
                2, -3, 4, true, 111L, 999L), "重启后磁盘缓存仍可用");
    }

    @Test
    void changedMtimeOrSizeInvalidatesOldTile() {
        Path root = temp.resolve("cache");
        WebMapTileCache cache = new WebMapTileCache(root);
        cache.put("world", "minecraft:overworld", 0, 0, 1, true, 100L, 10L,
                new byte[]{9, 9});

        assertNull(cache.get("world", "minecraft:overworld", 0, 0, 1, true, 101L, 10L),
                "mtime 变化后不能复用旧图");
        assertNull(cache.get("world", "minecraft:overworld", 0, 0, 1, true, 100L, 11L),
                "size 变化后不能复用旧图");
    }

    @Test
    void newRegionVersionDeletesStaleSibling() throws Exception {
        Path root = temp.resolve("cache");
        WebMapTileCache cache = new WebMapTileCache(root);
        cache.put("world", "minecraft:overworld", 0, 0, 8, true, 100L, 10L,
                new byte[]{1});
        Path stale = root.resolve("world/minecraft/overworld/8/1/r.0.0.100.10.png");
        assertTrue(Files.isRegularFile(stale));

        cache.put("world", "minecraft:overworld", 0, 0, 8, true, 200L, 20L,
                new byte[]{2});
        assertFalse(Files.exists(stale), "同 region 的旧 mtime 文件应被清理");
        assertTrue(Files.isRegularFile(
                root.resolve("world/minecraft/overworld/8/1/r.0.0.200.20.png")));
    }

    @Test
    void bootStackDimensionKeysStaySeparated() {
        Path root = temp.resolve("cache");
        WebMapTileCache cache = new WebMapTileCache(root);
        cache.put("world", "minecraft:overworld", 0, 0, 4, true, 1L, 1L,
                new byte[]{1});
        cache.put("stack_2", "samsara:stack_2/overworld", 0, 0, 4, true, 1L, 1L,
                new byte[]{2});

        assertArrayEquals(new byte[]{1}, cache.get("world", "minecraft:overworld",
                0, 0, 4, true, 1L, 1L));
        assertArrayEquals(new byte[]{2}, cache.get("stack_2", "samsara:stack_2/overworld",
                0, 0, 4, true, 1L, 1L), "不同栈层同坐标不能串图");
    }

    @Test
    void excessBytesEvictLeastRecentlyUsed() throws Exception {
        Path root = temp.resolve("cache");
        WebMapTileCache cache = new WebMapTileCache(root, 10);
        cache.put("world", "minecraft:overworld", 0, 0, 1, true, 1L, 1L,
                new byte[6]);
        cache.put("world", "minecraft:overworld", 1, 0, 1, true, 1L, 1L,
                new byte[6]);

        Path first = root.resolve("world/minecraft/overworld/1/1/r.0.0.1.1.png");
        Path second = root.resolve("world/minecraft/overworld/1/1/r.1.0.1.1.png");
        assertFalse(Files.exists(first), "超过磁盘上限后应淘汰最久未使用的 tile 文件");
        assertTrue(Files.exists(second), "最新 tile 保留");
        try (Stream<Path> files = Files.walk(root)) {
            assertEquals(1, files.filter(Files::isRegularFile)
                    .filter(path -> path.toString().endsWith(".png")).count(),
                    "超过上限后只保留最新 tile 文件");
        }
    }

    @Test
    void unsafeStackIdCannotEscapeCacheRoot() throws Exception {
        Path root = temp.resolve("cache");
        WebMapTileCache cache = new WebMapTileCache(root);
        Path path = cache.pathFor("../evil", "minecraft:overworld", 0, 0, 1, true, 1L, 1L);
        assertTrue(path.normalize().startsWith(root.normalize()));
        assertEquals(".._evil", root.relativize(path).getName(0).toString());
    }
}
