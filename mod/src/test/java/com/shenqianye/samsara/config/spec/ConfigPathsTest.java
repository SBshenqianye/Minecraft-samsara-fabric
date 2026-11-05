package com.shenqianye.samsara.config.spec;

import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ConfigPathsTest {
    @Test
    void flattenAndSetRoundTrip() {
        Map<String, Object> root = new LinkedHashMap<>();
        ConfigPaths.set(root, "server.port", 25565);
        ConfigPaths.set(root, "server.motd", "hello");
        ConfigPaths.set(root, "players", List.of("a", "b"));

        Map<String, Object> flat = ConfigPaths.flatten(root);
        assertEquals(25565, flat.get("server.port"));
        assertEquals("hello", flat.get("server.motd"));
        assertEquals(List.of("a", "b"), flat.get("players"));

        ConfigPaths.set(root, "server.motd", "changed");
        assertEquals("changed", ConfigPaths.flatten(root).get("server.motd"));
    }

    @Test
    void replacingScalarWithMapKeepsWritablePath() {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("server", "old");
        ConfigPaths.set(root, "server.port", 25565);
        assertEquals(25565, ConfigPaths.flatten(root).get("server.port"));
    }
}
