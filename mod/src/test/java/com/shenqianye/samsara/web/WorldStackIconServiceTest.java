package com.shenqianye.samsara.web;

import com.shenqianye.samsara.season.StackLayer;
import com.shenqianye.samsara.season.StackManifest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorldStackIconServiceTest {
    @TempDir
    Path temp;

    @Test
    void uploadedIconOverridesSaveIconAndDeleteRestoresFallback() throws Exception {
        Path world = world();
        writePng(world.resolve("samsara/stack/stack_0/icon.png"), 64, 64, Color.BLUE);
        WorldStackIconService service = new WorldStackIconService(world);

        Map<String, Object> saveIcon = service.describe("stack_0");
        assertEquals("save", saveIcon.get("source"));
        assertNotNull(service.read("stack_0"));

        byte[] uploaded = png(8, 8, Color.RED);
        Map<String, Object> uploadedResult = service.save("stack_0", "image/png",
                Base64.getEncoder().encodeToString(uploaded));
        assertEquals(Boolean.TRUE, uploadedResult.get("ok"));
        Map<?, ?> uploadedIcon = (Map<?, ?>) uploadedResult.get("icon");
        assertEquals("upload", uploadedIcon.get("source"));
        assertEquals(Boolean.TRUE, uploadedIcon.get("custom"));
        assertTrue(Files.isRegularFile(service.iconsDir().resolve("stack_0.png")));
        assertEquals("image/png", service.read("stack_0").mime());

        byte[] replacement = png(16, 16, Color.GREEN);
        Map<String, Object> replaced = service.save("stack_0", "image/png",
                Base64.getEncoder().encodeToString(replacement));
        assertEquals(Boolean.TRUE, replaced.get("ok"));
        assertTrue(service.read("stack_0").bytes().length == replacement.length,
                "重复上传应原子替换旧图");

        Map<String, Object> bad = service.save("stack_0", "image/svg+xml",
                Base64.getEncoder().encodeToString("<svg/>".getBytes()));
        assertEquals(Boolean.FALSE, bad.get("ok"));
        assertEquals("upload", service.describe("stack_0").get("source"),
                "非法上传不能破坏旧图");

        Map<String, Object> deleted = service.delete("stack_0");
        assertEquals(Boolean.TRUE, deleted.get("ok"));
        assertEquals("save", service.describe("stack_0").get("source"));
        assertNotNull(service.read("stack_0"));
    }

    @Test
    void defaultGrayscaleIconIsSkipped() throws Exception {
        Path world = world();
        Path icon = world.resolve("samsara/stack/stack_0/icon.png");
        writePng(icon, 64, 64, new Color(150, 150, 150));
        WorldStackIconService service = new WorldStackIconService(world);

        assertTrue(WorldStackIconService.isDefaultIcon(icon));
        assertEquals("map", service.describe("stack_0").get("source"));
        assertNull(service.read("stack_0"));
    }

    @Test
    void default128IconIsSkipped() throws Exception {
        Path world = world();
        Path icon = world.resolve("samsara/stack/stack_0/icon.png");
        writePng(icon, 128, 128, new Color(128, 128, 128));
        WorldStackIconService service = new WorldStackIconService(world);

        assertTrue(WorldStackIconService.isDefaultIcon(icon));
        assertEquals("map", service.describe("stack_0").get("source"));
    }

    @Test
    void coloredSaveIconIsUsedAndLargeOrMismatchedUploadRejected() throws Exception {
        Path world = world();
        writePng(world.resolve("samsara/stack/stack_0/icon.png"), 64, 64, Color.GREEN);
        WorldStackIconService service = new WorldStackIconService(world);
        assertEquals("save", service.describe("stack_0").get("source"));

        Map<String, Object> mismatch = service.save("stack_0", "image/jpeg",
                Base64.getEncoder().encodeToString(png(8, 8, Color.RED)));
        assertEquals(Boolean.FALSE, mismatch.get("ok"));
        assertTrue(String.valueOf(mismatch.get("error")).contains("文件头"));

        byte[] tooLarge = new byte[WorldStackIconService.MAX_ICON_BYTES + 1];
        Map<String, Object> large = service.save("stack_0", "image/png",
                Base64.getEncoder().encodeToString(tooLarge));
        assertEquals(Boolean.FALSE, large.get("ok"));

        assertFalse(Boolean.TRUE.equals(service.save("missing", "image/png",
                Base64.getEncoder().encodeToString(png(8, 8, Color.RED))).get("ok")));
    }

    private Path world() throws IOException {
        Path world = temp.resolve("world");
        StackLayer bottom = new StackLayer();
        bottom.index = 0;
        bottom.id = "stack_0";
        bottom.top = false;
        bottom.spawnX = 0;
        StackLayer top = new StackLayer();
        top.index = 1;
        top.id = "world";
        top.top = true;
        StackManifest manifest = new StackManifest();
        manifest.layers = new java.util.ArrayList<>(List.of(bottom, top));
        manifest.save(world);
        Files.createDirectories(world.resolve("samsara/stack/stack_0"));
        return world;
    }

    private static void writePng(Path file, int width, int height, Color color) throws IOException {
        Files.createDirectories(file.getParent());
        Files.write(file, png(width, height, color));
    }

    private static byte[] png(int width, int height, Color color) throws IOException {
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(color);
            graphics.fillRect(0, 0, width, height);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(image, "png", out);
        return out.toByteArray();
    }
}
