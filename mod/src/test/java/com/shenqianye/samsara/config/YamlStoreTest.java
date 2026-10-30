package com.shenqianye.samsara.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** YAML 配置：往返、缺键默认、非法值回退、旧 JSON 迁移、原子写/备份。 */
class YamlStoreTest {
    @TempDir
    Path tmp;

    @Test
    void firstLoadWritesFullDefaultsWithComments() throws Exception {
        YamlStore store = new YamlStore(tmp);
        Map<String, Object> values = store.load(ConfigSchema.COMMANDS);

        assertTrue(Files.exists(tmp.resolve("commands.yml")));
        assertEquals(48, values.get("completion.maxItems"));
        String yaml = Files.readString(tmp.resolve("commands.yml"));
        assertTrue(yaml.contains("# 是否启用 /samsara where"), "生成文件必须带注释");
        assertTrue(yaml.contains("completion:"), "必须写完整嵌套结构");
        assertTrue(yaml.contains("fuzzy:"), "未改过的默认键也要写出来");
    }

    @Test
    void missingKeyFallsBackToDefaultAndFileIsCompleted() throws Exception {
        Files.writeString(tmp.resolve("commands.yml"),
                "where:\n  enabled: false\n");
        YamlStore store = new YamlStore(tmp);
        Map<String, Object> values = store.load(ConfigSchema.COMMANDS);

        assertEquals(false, values.get("where.enabled"));
        assertEquals(30, values.get("where.broadcastCooldownSeconds"));
        assertTrue(Files.readString(tmp.resolve("commands.yml")).contains("broadcastCooldownSeconds"),
                "缺键应补回完整默认文件");
    }

    @Test
    void invalidValueFallsBackAndWarns() throws Exception {
        Files.writeString(tmp.resolve("commands.yml"),
                "completion:\n  maxItems: 999\n  fuzzy: not-a-boolean\n");
        YamlStore store = new YamlStore(tmp);
        Map<String, Object> values = store.load(ConfigSchema.COMMANDS);

        assertEquals(48, values.get("completion.maxItems"));
        assertEquals(true, values.get("completion.fuzzy"));
        assertTrue(store.warnings().stream().anyMatch(w -> w.contains("completion.maxItems")));
    }

    @Test
    void saveIsAtomicAndKeepsBak() throws Exception {
        YamlStore store = new YamlStore(tmp);
        store.load(ConfigSchema.COMMANDS);
        YamlStore.SaveResult first = store.save(ConfigSchema.COMMANDS,
                Map.of("completion.maxItems", 20));
        assertTrue(first.ok());
        assertTrue(Files.exists(tmp.resolve("commands.yml.bak")), "保存前保留上一份 .bak");

        YamlStore.SaveResult second = store.save(ConfigSchema.COMMANDS,
                Map.of("completion.maxItems", 24));
        assertTrue(second.ok());
        assertEquals(24, store.values(ConfigSchema.COMMANDS).get("completion.maxItems"));
        assertFalse(Files.exists(tmp.resolve("commands.yml.tmp")), "临时文件必须清理");
    }

    @Test
    void invalidSaveIsRejectedWithoutTouchingFile() throws Exception {
        YamlStore store = new YamlStore(tmp);
        store.load(ConfigSchema.COMMANDS);
        String before = Files.readString(tmp.resolve("commands.yml"));

        YamlStore.SaveResult result = store.save(ConfigSchema.COMMANDS,
                Map.of("completion.maxItems", 1, "where.enabled", "maybe"));

        assertFalse(result.ok());
        assertTrue(result.errors().containsKey("completion.maxItems"));
        assertTrue(result.errors().containsKey("where.enabled"));
        assertEquals(before, Files.readString(tmp.resolve("commands.yml")));
    }

    @Test
    void legacyJsonIsMigratedAndRenamed() throws Exception {
        Path legacy = tmp.resolve("legacy");
        Files.createDirectories(legacy);
        Files.writeString(legacy.resolve("commands.json"), """
                {"where":{"enabled":false,"broadcastCooldownSeconds":5},
                 "completion":{"maxItems":12}}
                """);
        YamlStore store = new YamlStore(tmp, legacy);
        Map<String, Object> values = store.load(ConfigSchema.COMMANDS);

        assertEquals(false, values.get("where.enabled"));
        assertEquals(5, values.get("where.broadcastCooldownSeconds"));
        assertEquals(12, values.get("completion.maxItems"));
        assertTrue(Files.exists(legacy.resolve("commands.json.legacy")),
                "旧 JSON 改名保留，不能静默删除");
        assertFalse(Files.exists(legacy.resolve("commands.json")));
        assertTrue(store.warnings().stream().anyMatch(w -> w.contains("迁移")));
    }

    @Test
    void permissionsLevelMapAndOwnersRoundTrip() {
        YamlStore store = new YamlStore(tmp);
        Map<String, Object> values = store.load(ConfigSchema.PERMISSIONS);
        @SuppressWarnings("unchecked")
        Map<String, String> entries = (Map<String, String>) values.get("entries");
        assertEquals("owner", entries.get("season start"));

        YamlStore.SaveResult result = store.save(ConfigSchema.PERMISSIONS, Map.of(
                "entries", Map.of("season status", "op", "where", "op"),
                "owners", List.of("Alice", "bob")));
        assertTrue(result.ok(), () -> String.valueOf(result.errors()));

        YamlStore reloaded = new YamlStore(tmp);
        Map<String, Object> after = reloaded.load(ConfigSchema.PERMISSIONS);
        assertEquals(List.of("Alice", "bob"), after.get("owners"));
        Map<?, ?> entriesAfter = (Map<?, ?>) after.get("entries");
        assertEquals("op", entriesAfter.get("season status"));
        assertEquals("op", entriesAfter.get("where"));
        assertEquals("owner", entriesAfter.get("season start"),
                "未在文件里写的默认权限也要保持可见");
    }

    @Test
    void storageDefaultsAndOverrideRoundTrip() {
        YamlStore store = new YamlStore(tmp);
        Map<String, Object> values = store.load(ConfigSchema.STORAGE);
        assertEquals(true, values.get("globalFollowWorld"));
        assertEquals("", values.get("worldDataRoot"));
        assertEquals(0, values.get("migration.version"));

        assertTrue(store.save(ConfigSchema.STORAGE, Map.of(
                "globalFollowWorld", false,
                "worldDataRoot", "D:/samsara-data",
                "migration.version", 1)).ok());

        YamlStore reloaded = new YamlStore(tmp);
        Map<String, Object> after = reloaded.load(ConfigSchema.STORAGE);
        assertEquals(false, after.get("globalFollowWorld"));
        assertEquals("D:/samsara-data", after.get("worldDataRoot"));
        assertEquals(1, after.get("migration.version"));
    }
}
