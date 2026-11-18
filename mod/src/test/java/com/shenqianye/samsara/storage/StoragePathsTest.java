package com.shenqianye.samsara.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 存储布局：服务器根下的配置目录、config 文件名约束、游戏 config 根。 */
class StoragePathsTest {
    @TempDir
    Path tmp;

    @Test
    void configDirAtIsFixedUnderServerRoot() {
        Path server = tmp.resolve("server");
        Path config = StoragePaths.configDirAt(server);

        assertTrue(config.isAbsolute(), "配置目录必须是绝对路径");
        assertEquals(server.toAbsolutePath().normalize()
                .resolve("config").resolve("samsara"), config);
        assertEquals("config/samsara", StoragePaths.CONFIG_DIR);
    }

    @Test
    void configDirAtNormalizesDotSegments() {
        Path server = tmp.resolve("server");
        assertEquals(StoragePaths.configDirAt(server),
                StoragePaths.configDirAt(server.resolve("world/..")));
    }

    @Test
    void configFileRejectsNestedAndParentPaths() {
        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.configFile("sub/agent-tokens.json"));
        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.configFile("sub\\agent-tokens.json"));
        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.configFile("../agent-tokens.json"));
        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.configFile(".."));
        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.configFile(""));
        assertThrows(IllegalArgumentException.class,
                () -> StoragePaths.configFile(null));
    }

    @Test
    void gameConfigDirFallsBackToServerConfig() {
        Path server = tmp.resolve("server");
        Path config = StoragePaths.gameConfigDir(server);

        assertTrue(config.isAbsolute(), "游戏 config 根必须是绝对路径");
        assertTrue(config.endsWith("config"), "游戏 config 根必须以 config 结尾: " + config);
    }

    @Test
    void fixedConfigFileNamesArePlainNames() {
        assertEquals("agent-tokens.json", StoragePaths.AGENT_TOKENS_FILE);
        assertEquals("op-auth.yml", StoragePaths.OP_AUTH_FILE);
    }
}
