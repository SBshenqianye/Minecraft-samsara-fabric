package com.shenqianye.samsara.identity;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.server.ServerBackup;
import com.shenqianye.samsara.server.ServerConfigFiles;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 身份组 → 服务器白名单同步。
 *
 * <p>只添加/更新身份组当前登录身份的 UUID（premium、offline），不删除手动白名单，
 * 也不把 data UUID 写进白名单。写文件复用 {@link ServerConfigFiles}，因此会自动备份、
 * 校验 JSON 数组并调用 {@code reloadWhiteList()} 立即生效。
 */
public final class IdentityWhitelistSync {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private IdentityWhitelistSync() {
    }

    public static Map<String, Object> sync(MinecraftServer server, IdentityConfig config) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (server == null) {
            out.put("ok", false);
            out.put("message", "服务器尚未启动，跳过白名单同步");
            return out;
        }
        if (config == null || !config.enabled) {
            out.put("ok", true);
            out.put("added", 0);
            out.put("updated", 0);
            out.put("message", "身份组未启用，跳过白名单同步");
            return out;
        }
        Path root = ServerBackup.serverRoot();
        Path file = root.resolve("whitelist.json");
        JsonArray current;
        if (Files.isRegularFile(file)) {
            try {
                String text = Files.readString(file, StandardCharsets.UTF_8);
                JsonElement parsed = text.isBlank() ? new JsonArray() : JsonParser.parseString(text);
                if (!parsed.isJsonArray()) {
                    out.put("ok", false);
                    out.put("message", "whitelist.json 不是 JSON 数组，已跳过，避免覆盖原文件");
                    return out;
                }
                current = parsed.getAsJsonArray();
            } catch (IOException | RuntimeException e) {
                out.put("ok", false);
                out.put("message", "读取 whitelist.json 失败：" + e.getMessage());
                return out;
            }
        } else {
            current = new JsonArray();
        }

        Map<String, JsonObject> byUuid = new LinkedHashMap<>();
        java.util.List<JsonElement> passthrough = new java.util.ArrayList<>();
        for (JsonElement element : current) {
            if (!element.isJsonObject()) {
                passthrough.add(element);
                continue;
            }
            JsonObject object = element.getAsJsonObject();
            String uuid = object.has("uuid") ? object.get("uuid").getAsString() : "";
            if (!uuid.isBlank()) {
                byUuid.put(uuid.toLowerCase(java.util.Locale.ROOT), object);
            } else {
                passthrough.add(element);
            }
        }

        int added = 0;
        int updated = 0;
        for (IdentityConfig.Group group : config.groups) {
            if (group == null) {
                continue;
            }
            for (UUID uuid : new UUID[]{group.premiumUuid(), group.offlineUuid()}) {
                if (uuid == null) {
                    continue;
                }
                String key = uuid.toString().toLowerCase(java.util.Locale.ROOT);
                JsonObject existing = byUuid.get(key);
                if (existing == null) {
                    JsonObject entry = new JsonObject();
                    entry.addProperty("uuid", uuid.toString());
                    entry.addProperty("name", group.name == null || group.name.isBlank()
                            ? uuid.toString() : group.name);
                    byUuid.put(key, entry);
                    added++;
                } else if (group.name != null && !group.name.isBlank()
                        && (!existing.has("name") || !group.name.equals(existing.get("name").getAsString()))) {
                    existing.addProperty("name", group.name);
                    updated++;
                }
            }
        }

        if (added == 0 && updated == 0) {
            out.put("ok", true);
            out.put("added", 0);
            out.put("updated", 0);
            out.put("message", "白名单已包含全部身份组登录 UUID");
            return out;
        }

        JsonArray next = new JsonArray();
        byUuid.values().forEach(next::add);
        passthrough.forEach(next::add);
        String content = GSON.toJson(next) + "\n";
        ServerConfigFiles.SaveResult saved = ServerConfigFiles.save(root, "whitelist", content, server);
        if (!saved.ok) {
            out.put("ok", false);
            out.put("message", "写入 whitelist.json 失败：" + saved.error);
            return out;
        }
        out.put("ok", true);
        out.put("added", added);
        out.put("updated", updated);
        out.put("message", "白名单已同步：新增 " + added + "，更新 " + updated);
        return out;
    }
}
