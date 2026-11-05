package com.shenqianye.samsara.config.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PropertiesAndJsonAdapterTest {
    @TempDir
    Path dir;

    @Test
    void propertiesWritePreservesCommentsAndUnknownKeys() throws Exception {
        Path file = dir.resolve("mod.properties");
        Files.writeString(file, """
                # 头部注释
                server-port=25565
                motd=hello
                unknown=keep
                """);

        PropertiesConfigAdapter adapter = new PropertiesConfigAdapter();
        adapter.write(file, Map.of("motd", "world", "new-key", "42"));
        String text = Files.readString(file);
        assertTrue(text.contains("# 头部注释"));
        assertTrue(text.contains("server-port=25565"));
        assertTrue(text.contains("motd=world"));
        assertTrue(text.contains("unknown=keep"));
        assertTrue(text.contains("new-key=42"));
        assertEquals("world", adapter.read(file).get("motd"));
    }

    @Test
    void jsonWritePreservesUnknownKeys() throws Exception {
        Path file = dir.resolve("mod.json");
        Files.writeString(file, """
                {
                  "server": {"port": 25565, "unknown": true},
                  "list": [1, 2]
                }
                """);
        JsonConfigAdapter adapter = new JsonConfigAdapter();
        adapter.write(file, Map.of("server.port", 25566L));
        Map<String, Object> flat = ConfigPaths.flatten(adapter.read(file));
        assertEquals(25566L, flat.get("server.port"));
        assertEquals(true, flat.get("server.unknown"));
        assertEquals(List.of(1L, 2L), flat.get("list"));
        assertFalse(Files.readString(file).contains("25565"));
    }

    @Test
    void yamlWritePreservesUnknownKeys() throws Exception {
        Path file = dir.resolve("mod.yml");
        Files.writeString(file, """
                server:
                  port: 25565
                  unknown: keep
                list:
                  - a
                  - b
                """);
        YamlConfigAdapter adapter = new YamlConfigAdapter();
        adapter.write(file, Map.of("server.port", 25566));
        Map<String, Object> flat = ConfigPaths.flatten(adapter.read(file));
        assertEquals(25566, flat.get("server.port"));
        assertEquals("keep", flat.get("server.unknown"));
        assertEquals(List.of("a", "b"), flat.get("list"));
    }
}
