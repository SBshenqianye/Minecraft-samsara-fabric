package com.shenqianye.samsara.config.spec;

import com.shenqianye.samsara.server.ServerConfigFiles;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ServerConfigPageSupportTest {
    @TempDir
    Path root;

    @Test
    void propertiesBecomeTypedFieldsAndKeepComments() throws Exception {
        Path file = root.resolve("server.properties");
        Files.writeString(file, """
                # 服务器配置
                server-port=25565
                online-mode=false
                motd=Hello
                """);
        ServerConfigFiles.Spec spec = ServerConfigFiles.spec("server-properties");

        ServerConfigPageSupport.FieldValue read = ServerConfigPageSupport.read(root, spec);
        assertEquals(25565, ((Number) read.values().get("server-port")).intValue());
        assertEquals(false, read.values().get("online-mode"));
        assertTrue(read.fields().stream().anyMatch(f -> f.path().equals("server-port")
                && f.type().equals("int")));
        assertFalse(read.fields().stream().anyMatch(f -> f.path().equals("rcon.password")));

        ServerConfigPageSupport.BuildResult built = ServerConfigPageSupport.build(
                spec, Files.readString(file),
                Map.of("server-port", 25566, "online-mode", true));
        assertTrue(built.ok(), built.errors().toString());
        assertTrue(built.content().contains("# 服务器配置"));
        assertTrue(built.content().contains("server-port=25566"));
        assertTrue(built.content().contains("online-mode=true"));
        assertTrue(built.content().contains("motd=Hello"));

        ServerConfigPageSupport.BuildResult invalid = ServerConfigPageSupport.build(
                spec, Files.readString(file), Map.of("server-port", "abc"));
        assertFalse(invalid.ok());
        assertTrue(invalid.errors().containsKey("server-port"));
    }

    @Test
    void jsonAndEulaAreRepresentedAsFields() throws Exception {
        Files.writeString(root.resolve("whitelist.json"),
                "[{\"uuid\":\"u\",\"name\":\"Alice\"}]\n");
        Files.writeString(root.resolve("eula.txt"), "eula=false\n");

        ServerConfigPageSupport.FieldValue json = ServerConfigPageSupport.read(
                root, ServerConfigFiles.spec("whitelist"));
        assertTrue(json.values().get("json") instanceof List<?>);
        assertEquals("json", json.fields().get(0).type());

        ServerConfigPageSupport.BuildResult built = ServerConfigPageSupport.build(
                ServerConfigFiles.spec("whitelist"), "[{\"uuid\":\"u\",\"name\":\"Alice\"}]",
                Map.of("json", List.of(Map.of("uuid", "u", "name", "Alice"))));
        assertTrue(built.ok());
        assertTrue(built.content().contains("\"name\": \"Alice\""));

        ServerConfigPageSupport.FieldValue eula = ServerConfigPageSupport.read(
                root, ServerConfigFiles.spec("eula"));
        assertEquals(false, eula.values().get("eula"));
        ServerConfigPageSupport.BuildResult eulaBuilt = ServerConfigPageSupport.build(
                ServerConfigFiles.spec("eula"), "eula=false\n", Map.of("eula", true));
        assertTrue(eulaBuilt.ok());
        assertEquals("eula=true\n", eulaBuilt.content());
    }
}
