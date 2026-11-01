package com.shenqianye.samsara.web;

import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
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

/** 按 v0.24 栈层读取地图：栈清单、三层维度目录、claims/spawn-history 按层隔离。 */
class WebMapServiceStackTest {
    @TempDir
    Path world;

    @BeforeAll
    static void bootstrapMinecraft() {
        // 维度 key / 注册表需要原版 Bootstrap；单元测试不启动服务器。
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    void modelGroupsDimensionsByStackAndKeepsClaimsSeparate() throws Exception {
        writeManifest();
        writeSpawn(world.resolve("samsara"), 1, 55, 3, 100, -200);
        writeSpawn(world.resolve("samsara/stack/stack_0"), 10, 20);
        writeClaims(world.resolve("samsara"), "minecraft:overworld", 0, 0, "TopOwner");
        writeClaims(world.resolve("samsara/stack/stack_0"),
                "samsara:stack_0/overworld", 5, 6, "OldOwner");
        region(world.resolve("dimensions/minecraft/overworld"), 0, 0);
        region(world.resolve("dimensions/minecraft/the_nether"), 0, 0);
        region(world.resolve("dimensions/minecraft/the_end"), 0, 0);
        region(world.resolve("dimensions/samsara/stack_0/overworld"), -1, 0);
        region(world.resolve("dimensions/samsara/stack_0/the_nether"), 0, 0);

        java.util.List<com.shenqianye.samsara.claim.ClaimNode> parsed =
                com.shenqianye.samsara.claim.AreaService.readClaims(
                        world.resolve("samsara/claims/claims.json"));
        assertEquals(1, parsed.size(), "直接解析 claims 节点数");
        assertEquals(1, parsed.get(0).getBanners().size(),
                "直接解析 banner 数=" + parsed.get(0).getBanners().size());

        WebMapService service = new WebMapService(world);
        Map<String, Object> model = service.model();

        assertEquals(true, model.get("ok"));
        assertEquals("world", model.get("topStackId"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stacks = (List<Map<String, Object>>) model.get("stacks");
        assertEquals(2, stacks.size(), "栈顶在前，旧栈层在后");
        assertEquals("world", stacks.get(0).get("id"));
        assertEquals(true, stacks.get(0).get("top"));
        assertEquals("stack_0", stacks.get(1).get("id"));

        Map<String, Object> worldOverworld = dimension(stacks.get(0), "minecraft:overworld");
        // border 图案向 8 个方向各延伸 1 区块 → 以旗帜区块为中心的 3×3。
        assertEquals(9, worldOverworld.get("protectedCount"));
        assertEquals(1, worldOverworld.get("bannerCount"));
        @SuppressWarnings("unchecked")
        List<int[]> worldChunks = (List<int[]>) worldOverworld.get("protectedChunks");
        assertTrue(containsChunk(worldChunks, 0, 0));

        Map<String, Object> oldOverworld =
                dimension(stacks.get(1), "samsara:stack_0/overworld");
        @SuppressWarnings("unchecked")
        List<int[]> oldChunks = (List<int[]>) oldOverworld.get("protectedChunks");
        assertTrue(containsChunk(oldChunks, 5, 6), "旧栈 claims 应有自己的旗帜区块");
        assertFalse(containsChunk(oldChunks, 0, 0), "旧栈 claims 不能混进栈顶");

        Map<String, Object> oldNether =
                dimension(stacks.get(1), "samsara:stack_0/the_nether");
        assertEquals("the_nether", oldNether.get("dim"));
        assertEquals(1, ((List<?>) oldNether.get("regions")).size());

        Map<String, Object> history = (Map<String, Object>) stacks.get(0).get("spawnHistory");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> cycles = (List<Map<String, Object>>) history.get("cycles");
        assertEquals(2, cycles.size());
        assertEquals(true, cycles.get(1).get("current"));
        Map<String, Object> nether = (Map<String, Object>) cycles.get(0).get("nether");
        assertEquals(6, nether.get("x"), "下界坐标按主世界 /8 推算（55 → 6）");
        assertEquals(0, nether.get("z"), "下界坐标按主世界 /8 推算（3 → 0）");
        assertEquals(true, nether.get("yEstimated"));
        Map<String, Object> nether2 = (Map<String, Object>) cycles.get(1).get("nether");
        assertEquals(12, nether2.get("x"));
        assertEquals(-25, nether2.get("z"), "负数坐标使用 floorDiv");

        Map<String, Object> cache = (Map<String, Object>) model.get("cache");
        assertTrue(String.valueOf(cache.get("dir")).endsWith("web-map-cache"));
        assertTrue(Files.isDirectory(world.resolve("samsara/stack/stack_0")));
        assertFalse(Files.exists(world.resolve("level.dat")), "地图服务不得创建/修改 level.dat");
    }

    @Test
    void missingManifestStillExposesTopWorldWithoutWritingStackJson() throws Exception {
        region(world.resolve("dimensions/minecraft/overworld"), 0, 0);
        writeSpawn(world.resolve("samsara"), 7, 8);

        WebMapService service = new WebMapService(world);
        Map<String, Object> model = service.model();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> stacks = (List<Map<String, Object>>) model.get("stacks");
        assertEquals(1, stacks.size());
        assertEquals("world", stacks.get(0).get("id"));
        assertEquals(true, stacks.get(0).get("top"));
        assertFalse(Files.exists(world.resolve("samsara/stack.json")),
                "缺失栈清单时只在内存里合成栈顶");
    }

    private void writeManifest() throws Exception {
        String top = layer(1, "world", "stack_0", true, 2, 55, 80, 3);
        String old = layer(0, "stack_0", "", false, 1, 10, 70, 20);
        String json = "{\"schemaVersion\":2,\"layers\":[" + old + "," + top + "]}";
        write(world.resolve("samsara/stack.json"), json);
        write(world.resolve("samsara/stack/stack_0/stack.json"), old);
    }

    private static String layer(int index, String id, String parent, boolean top, long seed,
                                int spawnX, int spawnY, int spawnZ) {
        return "{\"schemaVersion\":2,\"index\":" + index + ",\"id\":\"" + id
                + "\",\"parent\":\"" + parent + "\",\"worldId\":\"w" + index
                + "\",\"seed\":" + seed + ",\"levelType\":\"normal\",\"generatorSettings\":\"\","
                + "\"createdAt\":" + (1000 + index) + ",\"spawnX\":" + spawnX
                + ",\"spawnY\":" + spawnY + ",\"spawnZ\":" + spawnZ + ",\"top\":" + top + "}";
    }

    private static void writeSpawn(Path meta, int x, int z) throws Exception {
        writeSpawn(meta, 0, x, z, x, z);
    }

    private static void writeSpawn(Path meta, int index, int x, int z, int x2, int z2)
            throws Exception {
        String cycles = "{\"index\":" + index + ",\"x\":" + x + ",\"z\":" + z
                + ",\"createdAt\":1760000000000,\"source\":\"initial\"},"
                + "{\"index\":" + (index + 1) + ",\"x\":" + x2 + ",\"z\":" + z2
                + ",\"createdAt\":1760000100000,\"source\":\"same-world-reset\"}";
        write(meta.resolve("spawn-history.json"),
                "{\"schemaVersion\":1,\"migratedFromSeasonJson\":false,\"cycles\":["
                        + cycles + "]}");
    }

    private static void writeClaims(Path meta, String dimension, int chunkX, int chunkZ,
                                    String owner) throws Exception {
        int blockX = chunkX * 16 + 8;
        int blockZ = chunkZ * 16 + 8;
        String json = "{\"nodes\":[{\"dimension\":\"" + dimension + "\",\"x\":" + chunkX
                + ",\"z\":" + chunkZ + ",\"y\":70,\"facing\":\"north\",\"ownerName\":\""
                + owner + "\",\"patterns\":[{\"id\":\"border\",\"color\":0}],"
                + "\"banners\":[{\"blockX\":" + blockX + ",\"blockY\":70,\"blockZ\":" + blockZ
                + ",\"facing\":\"north\",\"patterns\":[{\"id\":\"border\",\"color\":0}]}]}]}";
        write(meta.resolve("claims/claims.json"), json);
    }

    private static void region(Path dimension, int x, int z) throws Exception {
        Path dir = dimension.resolve("region");
        Files.createDirectories(dir);
        Files.write(dir.resolve("r." + x + "." + z + ".mca"), new byte[0]);
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }

    private static boolean containsChunk(List<int[]> chunks, int x, int z) {
        for (int[] chunk : chunks) {
            if (chunk[0] == x && chunk[1] == z) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> dimension(Map<String, Object> stack, String id) {
        List<Map<String, Object>> dimensions =
                (List<Map<String, Object>>) stack.get("dimensions");
        Map<String, Object> found = dimensions.stream()
                .filter(dim -> id.equals(dim.get("id")))
                .findFirst().orElse(null);
        assertNotNull(found, "缺少维度 " + id);
        return found;
    }
}
