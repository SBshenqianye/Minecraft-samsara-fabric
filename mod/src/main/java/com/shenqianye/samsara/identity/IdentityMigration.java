package com.shenqianye.samsara.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.storage.StoragePaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * v0.19 {@code identity.json} → v0.20 {@code identity.yml}。
 *
 * <p>规则（《设计文档》9.13）：
 * <ul>
 *   <li>{@code data = 旧 canonicalUuid}；</li>
 *   <li>{@code premium = 第一个 premium alias}，{@code offline = 第一个 offline alias}；</li>
 *   <li>多余 alias 写迁移报告与备份，不进入新配置；</li>
 *   <li>旧 {@code auth.yml} 归档，不删除；</li>
 *   <li>幂等：新 identity.yml 已存在时跳过。</li>
 * </ul>
 */
public final class IdentityMigration {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private IdentityMigration() {
    }

    public static String migrate(StoragePaths paths, IdentityConfigStore store) {
        if (paths == null || store == null) {
            return "身份迁移跳过：存储尚未初始化";
        }
        Path identityRoot = paths.identityDataRoot();
        Path archiveRoot = identityRoot.resolve("archive");
        Path oldFile = findOldIdentity(paths);
        boolean already = Files.exists(store.file()) && store.config() != null
                && !store.config().groups.isEmpty();
        String authArchive = already ? "" : archiveAuth(paths, archiveRoot);
        return migrateOld(identityRoot, oldFile, authArchive, store);
    }

    /** 可单测入口：不依赖 StoragePaths。 */
    static String migrateOld(Path identityRoot, Path oldFile, String authArchive,
                             IdentityConfigStore store) {
        Path archiveRoot = identityRoot.resolve("archive");
        if (Files.exists(store.file()) && store.config() != null
                && !store.config().groups.isEmpty()) {
            return "identity.yml 已有身份组，跳过 v0.19 迁移"
                    + (authArchive == null || authArchive.isBlank() ? "" : "；" + authArchive);
        }
        if (oldFile == null) {
            return "未发现 v0.19 identity.json，无需迁移"
                    + (authArchive == null || authArchive.isBlank() ? "" : "；" + authArchive);
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(oldFile, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonArray identities = root.has("identities") && root.get("identities").isJsonArray()
                    ? root.getAsJsonArray("identities") : new JsonArray();
            IdentityConfig config = new IdentityConfig();
            config.enabled = false; // 迁移不自动开启；由服主显式打开
            config.syncPlayerData = true;
            config.maxOwners = IdentityConfig.DEFAULT_MAX_OWNERS;
            List<Map<String, Object>> reportGroups = new ArrayList<>();
            for (JsonElement element : identities) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject group = element.getAsJsonObject();
                UUID data = parse(group, "canonicalUuid");
                JsonArray aliases = group.has("aliases") && group.get("aliases").isJsonArray()
                        ? group.getAsJsonArray("aliases") : new JsonArray();
                UUID premium = null;
                UUID offline = null;
                List<String> extras = new ArrayList<>();
                String name = string(group, "name");
                for (JsonElement aliasElement : aliases) {
                    if (!aliasElement.isJsonObject()) {
                        continue;
                    }
                    JsonObject alias = aliasElement.getAsJsonObject();
                    UUID uuid = parse(alias, "uuid");
                    if (uuid == null) {
                        continue;
                    }
                    String type = string(alias, "uuidType");
                    if (name == null && string(alias, "name") != null) {
                        name = string(alias, "name");
                    }
                    if ("premium".equalsIgnoreCase(type) && premium == null) {
                        premium = uuid;
                    } else if ("offline".equalsIgnoreCase(type) && offline == null) {
                        offline = uuid;
                    } else {
                        extras.add(uuid + (type == null ? "" : " (" + type + ")"));
                    }
                }
                if (data == null) {
                    data = premium != null ? premium : offline;
                }
                if (data == null) {
                    continue;
                }
                if (name == null || name.isBlank()) {
                    name = "迁移组-" + data.toString().substring(0, 8);
                }
                config.groups.add(new IdentityConfig.Group(name, data, premium, offline,
                        "由 v0.19 identity.json 迁移"));
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("name", name);
                report.put("data", data.toString());
                report.put("premium", premium == null ? null : premium.toString());
                report.put("offline", offline == null ? null : offline.toString());
                report.put("extraAliases", extras);
                reportGroups.add(report);
            }
            IdentityConfigStore.SaveResult saved = store.save(config);
            if (!saved.ok()) {
                return "v0.19 身份迁移失败（identity.yml 未写入）：" + saved.errors();
            }
            Path archive = archiveRoot.resolve("v019-" + LocalDateTime.now().format(TS));
            Files.createDirectories(archive);
            Files.copy(oldFile, archive.resolve("identity.json"), StandardCopyOption.REPLACE_EXISTING);
            Map<String, Object> report = new LinkedHashMap<>();
            report.put("source", oldFile.toString());
            report.put("migratedAt", System.currentTimeMillis());
            report.put("groups", reportGroups);
            Files.writeString(archive.resolve("migration-report.json"), GSON.toJson(report),
                    StandardCharsets.UTF_8);
            try {
                Files.move(oldFile, oldFile.resolveSibling(oldFile.getFileName() + ".v019.migrated"),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 旧 identity.json 改名失败（归档副本已保留）：{}",
                        e.getMessage());
            }
            IdentityAudit.log(identityRoot, "migrate_v019", "system", null,
                    "groups=" + reportGroups.size() + " source=" + oldFile);
            return "v0.19 身份迁移完成：" + reportGroups.size() + " 个组 → identity.yml（归档 "
                    + archive + "）" + (authArchive.isBlank() ? "" : "；" + authArchive);
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] v0.19 身份迁移失败，源文件未删除：{}",
                    e.getMessage());
            return "v0.19 身份迁移失败，源文件未删除：" + e.getMessage();
        }
    }

    private static Path findOldIdentity(StoragePaths paths) {
        List<Path> candidates = new ArrayList<>();
        candidates.add(paths.globalDataRoot().resolve("identity.json"));
        candidates.add(paths.worldDataRoot().resolve("global/identity.json"));
        candidates.add(paths.serverDataRoot().resolve("global/identity.json"));
        Path legacy = StoragePaths.legacyConfigRoot();
        if (legacy != null) {
            candidates.add(legacy.resolve("identity.json"));
            candidates.add(legacy.resolve("global/identity.json"));
        }
        for (Path candidate : candidates) {
            if (Files.isRegularFile(candidate)) {
                return candidate;
            }
        }
        return null;
    }

    private static String archiveAuth(StoragePaths paths, Path archiveRoot) {
        List<Path> candidates = List.of(
                paths.globalDataRoot().resolve("auth.yml"),
                paths.serverDataRoot().resolve("global/auth.yml"),
                paths.worldDataRoot().resolve("global/auth.yml"));
        for (Path candidate : candidates) {
            if (!Files.isRegularFile(candidate)) {
                continue;
            }
            try {
                Path archive = archiveRoot.resolve("v019-" + LocalDateTime.now().format(TS));
                Files.createDirectories(archive);
                Files.copy(candidate, archive.resolve("auth.yml"), StandardCopyOption.REPLACE_EXISTING);
                return "旧 auth.yml 已归档到 " + archive.resolve("auth.yml");
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] auth.yml 归档失败：{}", e.getMessage());
                return "auth.yml 归档失败：" + e.getMessage();
            }
        }
        return "";
    }

    private static UUID parse(JsonObject object, String key) {
        String text = string(object, key);
        if (text == null) {
            return null;
        }
        try {
            return UUID.fromString(text);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String string(JsonObject object, String key) {
        if (object == null || !object.has(key) || object.get(key).isJsonNull()) {
            return null;
        }
        String text = object.get(key).getAsString();
        return text == null || text.isBlank() ? null : text;
    }
}
