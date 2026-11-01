package com.shenqianye.samsara.server;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigFilesTest {

    @TempDir
    Path root;

    @Test
    void readsAndWritesServerPropertiesWithBackup() throws Exception {
        Files.writeString(root.resolve("server.properties"), "level-name=world\n", StandardCharsets.UTF_8);
        List<Map<String, Object>> files = ServerConfigFiles.readAll(root);
        assertTrue(files.stream().anyMatch(f -> "server-properties".equals(f.get("id"))));

        ServerConfigFiles.SaveResult invalid = ServerConfigFiles.save(root, "server-properties",
                "not-a-property\n", null);
        assertFalse(invalid.ok);
        assertTrue(invalid.error.contains("key=value"));

        ServerConfigFiles.SaveResult saved = ServerConfigFiles.save(root, "server-properties",
                "# comment\nlevel-name=world\nserver-port=25565", null);
        assertTrue(saved.ok);
        assertTrue(saved.restartRequired);
        assertTrue(saved.backupPath.length() > 0);
        assertTrue(Files.readString(root.resolve("server.properties"), StandardCharsets.UTF_8)
                .endsWith("server-port=25565\n"));
        try (var stream = Files.walk(root.resolve("samsara/server-config-backups"))) {
            assertTrue(stream.anyMatch(Files::isRegularFile));
        }
    }

    @Test
    void validatesJsonArraysAndEula() {
        ServerConfigFiles.SaveResult badJson = ServerConfigFiles.save(root, "whitelist",
                "{not json", null);
        assertFalse(badJson.ok);

        ServerConfigFiles.SaveResult notArray = ServerConfigFiles.save(root, "whitelist",
                "{\"name\":\"A\"}", null);
        assertFalse(notArray.ok);

        ServerConfigFiles.SaveResult okJson = ServerConfigFiles.save(root, "whitelist",
                "[{\"uuid\":\"00000000-0000-0000-0000-000000000000\",\"name\":\"A\"}]", null);
        assertTrue(okJson.ok);
        assertFalse(okJson.restartRequired);

        ServerConfigFiles.SaveResult badEula = ServerConfigFiles.save(root, "eula",
                "eula=maybe\n", null);
        assertFalse(badEula.ok);

        ServerConfigFiles.SaveResult okEula = ServerConfigFiles.save(root, "eula",
                "eula=true\n", null);
        assertTrue(okEula.ok);
        assertTrue(okEula.restartRequired);
    }

    @Test
    void onlyWhitelistedFilesCanBeWritten() {
        ServerConfigFiles.SaveResult result = ServerConfigFiles.save(root, "../evil", "x", null);
        assertFalse(result.ok);
        assertTrue(result.error.contains("未知配置文件"));
    }
}
