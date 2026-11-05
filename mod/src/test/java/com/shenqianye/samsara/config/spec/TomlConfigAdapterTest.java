package com.shenqianye.samsara.config.spec;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TomlConfigAdapterTest {
    @TempDir
    Path dir;

    @Test
    void parsesTablesArraysAndComments() throws Exception {
        Path file = dir.resolve("mod.toml");
        Files.writeString(file, """
                # 第三方模组配置
                title = "Hello # not a comment"
                enabled = true
                ratio = 0.5
                ports = [25565, 25566]

                [server]
                host = "0.0.0.0"

                [[players]]
                name = "Alice"
                level = 4

                [[players]]
                name = "Bob"
                """);

        Map<String, Object> flat = ConfigPaths.flatten(new TomlConfigAdapter().read(file));
        assertEquals("Hello # not a comment", flat.get("title"));
        assertEquals(true, flat.get("enabled"));
        assertEquals(0.5, flat.get("ratio"));
        assertEquals(List.of(25565L, 25566L), flat.get("ports"));
        assertEquals("0.0.0.0", flat.get("server.host"));
        assertTrue(flat.get("players") instanceof List<?>);
        List<?> players = (List<?>) flat.get("players");
        assertEquals("Alice", ((Map<?, ?>) players.get(0)).get("name"));
    }

    @Test
    void writeKeepsUnknownKeysAndUpdatesNestedPath() throws Exception {
        Path file = dir.resolve("mod.toml");
        Files.writeString(file, """
                title = "keep"
                [server]
                port = 25565
                motd = "old"
                unknown = "still here"
                """);

        new TomlConfigAdapter().write(file, Map.of("server.port", 25566L));
        Map<String, Object> flat = ConfigPaths.flatten(new TomlConfigAdapter().read(file));
        assertEquals(25566L, flat.get("server.port"));
        assertEquals("keep", flat.get("title"));
        assertEquals("still here", flat.get("server.unknown"));
        assertTrue(Files.readString(file).contains("[server]"));
    }

    @Test
    void inlineTableAndLiteralString() throws Exception {
        Path file = dir.resolve("inline.toml");
        Files.writeString(file, """
                point = { x = 1, y = 2 }
                literal = 'C:\\temp'
                """);
        Map<String, Object> flat = ConfigPaths.flatten(new TomlConfigAdapter().read(file));
        assertEquals(1L, flat.get("point.x"));
        assertEquals(2L, flat.get("point.y"));
        assertEquals("C:\\temp", flat.get("literal"));
    }
}
