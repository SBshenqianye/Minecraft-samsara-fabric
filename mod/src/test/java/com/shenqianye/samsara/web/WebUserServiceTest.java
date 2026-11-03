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
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 用户端只读模型：不把管理端的路径、缓存、旗帜主人等字段透传出去。 */
class WebUserServiceTest {
    @TempDir
    Path world;

    @BeforeAll
    static void bootstrapMinecraft() {
        net.minecraft.SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapModelIsWhitelistedForPublicView() throws Exception {
        write(world.resolve("samsara/stack.json"),
                "{\"schemaVersion\":2,\"layers\":["
                        + "{\"schemaVersion\":2,\"index\":0,\"id\":\"world\",\"parent\":\"\","
                        + "\"worldId\":\"w0\",\"seed\":123456,\"levelType\":\"normal\","
                        + "\"generatorSettings\":\"\",\"createdAt\":1000,"
                        + "\"spawnX\":10,\"spawnY\":64,\"spawnZ\":20,\"top\":true}]}");
        write(world.resolve("samsara/claims/claims.json"),
                "{\"nodes\":[{\"dimension\":\"minecraft:overworld\",\"x\":0,\"z\":0,"
                        + "\"y\":70,\"facing\":\"north\",\"ownerName\":\"TopOwner\","
                        + "\"patterns\":[{\"id\":\"border\",\"color\":0}],"
                        + "\"banners\":[{\"blockX\":8,\"blockY\":70,\"blockZ\":8,"
                        + "\"facing\":\"north\",\"patterns\":[{\"id\":\"border\",\"color\":0}]}]}]}");
        write(world.resolve("samsara/spawn-history.json"),
                "{\"schemaVersion\":1,\"migratedFromSeasonJson\":false,\"cycles\":["
                        + "{\"index\":1,\"x\":60,\"z\":-96,\"createdAt\":1760000000000,"
                        + "\"source\":\"initial\"}]}");
        Path regions = world.resolve("dimensions/minecraft/overworld/region");
        Files.createDirectories(regions);
        Files.write(regions.resolve("r.0.0.mca"), new byte[0]);

        WebMapService mapService = new WebMapService(world);
        WebUserService userService = new WebUserService(mapService, null);
        Map<String, Object> model = userService.mapModel();

        assertEquals("user", model.get("scope"));
        assertFalse(model.containsKey("worldRoot"), "公开模型不能带服务器绝对路径");
        assertFalse(model.containsKey("cache"), "公开模型不能带缓存目录/统计");
        List<Map<String, Object>> stacks = (List<Map<String, Object>>) model.get("stacks");
        assertEquals(1, stacks.size());
        Map<String, Object> stack = stacks.get(0);
        assertEquals(123456L, stack.get("seed"), "用户端需要按栈层显示种子");
        assertFalse(stack.containsKey("metaPath"), "公开模型不能带栈层 meta 路径");

        Map<String, Object> history = (Map<String, Object>) stack.get("spawnHistory");
        assertFalse(history.containsKey("file"), "公开模型不能带 spawn-history 文件路径");
        List<Map<String, Object>> dims = (List<Map<String, Object>>) stack.get("dimensions");
        Map<String, Object> overworld = dims.stream()
                .filter(dim -> "minecraft:overworld".equals(dim.get("id")))
                .findFirst().orElseThrow();
        List<Map<String, Object>> banners =
                (List<Map<String, Object>>) overworld.get("banners");
        assertEquals(1, banners.size());
        Map<String, Object> banner = banners.get(0);
        assertFalse(banner.containsKey("owner"), "公开模型不能带旗帜主人");
        assertFalse(banner.containsKey("ownerUUID"), "公开模型不能带旗帜主人 UUID");
        assertTrue(banner.containsKey("patterns"), "地图仍需图案信息");
        assertTrue(banner.containsKey("facing"), "地图仍需朝向信息");
    }

    @Test
    void statusWithoutServerIsStillPublicSafe() {
        WebUserService userService = new WebUserService(new WebMapService(world), null);
        Map<String, Object> status = userService.status();
        assertEquals("user", status.get("scope"));
        assertEquals(0, status.get("online"));
        assertFalse(status.containsKey("worldRoot"));
    }

    private static void write(Path file, String text) throws Exception {
        Files.createDirectories(file.getParent());
        Files.writeString(file, text, StandardCharsets.UTF_8);
    }
}
