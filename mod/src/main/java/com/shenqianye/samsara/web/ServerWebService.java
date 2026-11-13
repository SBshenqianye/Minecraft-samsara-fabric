package com.shenqianye.samsara.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.identity.IdentityConfig;
import com.shenqianye.samsara.identity.IdentityResolver;
import com.shenqianye.samsara.identity.IdentityService;
import com.shenqianye.samsara.server.ServerBackup;
import com.shenqianye.samsara.server.ServerConfigFiles;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Web「本体」页：服务器配置 + 存档备份。 */
public class ServerWebService {
    private final MinecraftServer server;
    private final Path serverRoot;
    private final WebConsoleService console;

    public ServerWebService(MinecraftServer server) {
        this.server = server;
        this.serverRoot = ServerBackup.serverRoot();
        this.console = new WebConsoleService(server);
    }

    public ServerWebService(MinecraftServer server, Path serverRoot) {
        this.server = server;
        this.serverRoot = serverRoot.toAbsolutePath().normalize();
        this.console = new WebConsoleService(server);
    }

    public Path serverRoot() {
        return serverRoot;
    }

    public MinecraftServer server() {
        return server;
    }

    public Map<String, Object> console(Map<String, Object> body) {
        Object value = body == null ? null : body.get("command");
        return console.execute(value == null ? "" : String.valueOf(value));
    }

    public Map<String, Object> completeConsole(Map<String, Object> body) {
        Object value = body == null ? null : body.get("command");
        Integer cursor = null;
        if (body != null && body.get("cursor") instanceof Number number) {
            cursor = number.intValue();
        }
        return console.complete(value == null ? "" : String.valueOf(value), cursor);
    }

    public Map<String, Object> model() {
        Map<String, Object> out = new LinkedHashMap<>(ServerBackup.model(serverRoot));
        Path world = worldDir();
        out.put("worldDir", world.toString());
        out.put("worldExists", Files.isDirectory(world));
        return out;
    }

    public Map<String, Object> saveConfig(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        String id = str(body, "id");
        String content = str(body, "content");
        ServerConfigFiles.SaveResult result = ServerConfigFiles.save(serverRoot, id, content, server);
        out.put("ok", result.ok);
        out.put("error", result.error);
        out.put("restartRequired", result.restartRequired);
        out.put("applied", result.applied);
        out.put("message", result.message);
        out.put("backupPath", result.backupPath);
        if (result.ok) {
            out.put("configFiles", ServerConfigFiles.readAll(serverRoot));
        }
        return out;
    }

    public Map<String, Object> previewCreate() {
        return ServerBackup.previewCreate(serverRoot);
    }

    public Map<String, Object> createBackup(Map<String, Object> body) {
        if (!bool(body, "confirm")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "创建备份需要确认");
            return out;
        }
        return ServerBackup.scheduleCreate(serverRoot, str(body, "note"));
    }

    public Map<String, Object> previewRestore(Map<String, Object> body) {
        Map<String, Object> out = new LinkedHashMap<>();
        ServerBackup.Preview preview = ServerBackup.previewRestore(serverRoot, str(body, "id"));
        out.put("ok", preview.ok);
        out.put("preview", preview);
        if (!preview.errors.isEmpty()) {
            out.put("error", String.join("；", preview.errors));
        }
        return out;
    }

    public Map<String, Object> restore(Map<String, Object> body) {
        if (!bool(body, "confirm")) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ok", false);
            out.put("error", "恢复需要二次确认");
            return out;
        }
        return ServerBackup.scheduleRestore(serverRoot, str(body, "id"));
    }

    public Map<String, Object> deleteBackup(Map<String, Object> body) {
        return ServerBackup.delete(serverRoot, str(body, "id"), bool(body, "confirm"));
    }

    public Map<String, Object> jobs() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("jobs", ServerBackup.jobs(serverRoot, 20));
        out.put("lastReport", ServerBackup.job(serverRoot, "last-result"));
        return out;
    }

    /** 已知玩家：在线玩家 + usercache.json，供白名单/OP/封禁选择。 */
    public Map<String, Object> players() {
        Map<String, Map<String, Object>> byUuid = new LinkedHashMap<>();
        if (server != null) {
            try {
                for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                    String uuid = player.getStringUUID();
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("uuid", uuid);
                    row.put("name", player.getName().getString());
                    row.put("online", true);
                    row.put("lastSeen", "");
                    row.put("dimension", player.level().dimension().identifier().toString());
                    row.put("x", Math.round(player.getX() * 10.0) / 10.0);
                    row.put("y", Math.round(player.getY() * 10.0) / 10.0);
                    row.put("z", Math.round(player.getZ() * 10.0) / 10.0);
                    byUuid.put(uuid, row);
                }
            } catch (RuntimeException ignored) {
            }
        }
        Path cache = serverRoot.resolve("usercache.json");
        if (Files.isRegularFile(cache)) {
            try {
                JsonElement parsed = JsonParser.parseString(
                        Files.readString(cache, StandardCharsets.UTF_8));
                if (parsed.isJsonArray()) {
                    for (JsonElement element : parsed.getAsJsonArray()) {
                        if (!element.isJsonObject()) {
                            continue;
                        }
                        JsonObject object = element.getAsJsonObject();
                        String uuid = object.has("uuid") ? object.get("uuid").getAsString() : "";
                        String name = object.has("name") ? object.get("name").getAsString() : "";
                        if (uuid.isBlank() || name.isBlank() || byUuid.containsKey(uuid)) {
                            continue;
                        }
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("uuid", uuid);
                        row.put("name", name);
                        row.put("online", false);
                        row.put("lastSeen", object.has("expiresOn")
                                ? object.get("expiresOn").getAsString() : "");
                        byUuid.put(uuid, row);
                    }
                }
            } catch (Exception ignored) {
            }
        }
        // 白名单 / OP 名单里的玩家也算“已知玩家”，方便直接选择。
        for (String fileName : new String[]{"whitelist.json", "ops.json"}) {
            Path file = serverRoot.resolve(fileName);
            if (!Files.isRegularFile(file)) {
                continue;
            }
            try {
                JsonElement parsed = JsonParser.parseString(
                        Files.readString(file, StandardCharsets.UTF_8));
                if (!parsed.isJsonArray()) {
                    continue;
                }
                for (JsonElement element : parsed.getAsJsonArray()) {
                    if (!element.isJsonObject()) {
                        continue;
                    }
                    JsonObject object = element.getAsJsonObject();
                    String uuid = object.has("uuid") ? object.get("uuid").getAsString() : "";
                    String name = object.has("name") ? object.get("name").getAsString() : "";
                    if (uuid.isBlank() || name.isBlank() || byUuid.containsKey(uuid)) {
                        continue;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("uuid", uuid);
                    row.put("name", name);
                    row.put("online", false);
                    row.put("lastSeen", fileName);
                    byUuid.put(uuid, row);
                }
            } catch (Exception ignored) {
            }
        }
        List<Map<String, Object>> list = new ArrayList<>(byUuid.values());
        for (Map<String, Object> row : list) {
            if (Boolean.TRUE.equals(row.get("online"))) continue;
            decorateOfflinePosition(row, String.valueOf(row.get("uuid")));
        }
        // 同一身份组只保留当前服务器验证方式对应的账号入口：
        // 正版模式优先正版 UUID，离线模式优先离线 UUID；若另一个账号正在线则优先在线者。
        dedupeIdentityGroups(list);
        // 身份组之外的历史账号（改名/离线模式切换留下的 usercache/白名单）也按名字收口。
        dedupeNames(list);
        for (Map<String, Object> row : list) {
            row.putIfAbsent("skinUuid", row.getOrDefault("uuid", ""));
            row.putIfAbsent("skinName", row.getOrDefault("name", ""));
        }
        list.sort(Comparator
                .comparing((Map<String, Object> row) -> Boolean.TRUE.equals(row.get("online")) ? 0 : 1)
                .thenComparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("players", list);
        return out;
    }

    /**
     * 同一身份组只保留一个“当前服务器验证方式”的账号入口，避免正版/离线两个 UUID
     * 同时出现在天网玩家列表；若另一个账号正在线则优先在线者。
     */
    private void dedupeIdentityGroups(List<Map<String, Object>> rows) {
        IdentityService identity = IdentityService.getInstance();
        IdentityConfig config = identity.config();
        if (config == null || !config.enabled || config.groups == null || config.groups.isEmpty()) {
            return;
        }
        boolean onlineMode = true;
        if (server != null) {
            try {
                onlineMode = server.usesAuthentication();
            } catch (RuntimeException ignored) {
                onlineMode = true;
            }
        }
        Map<String, Map<String, Object>> chosen = new LinkedHashMap<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            UUID uuid = uuidOf(row.get("uuid"));
            IdentityConfig.Group group = IdentityResolver.groupOf(config, uuid);
            if (group == null) {
                merged.add(row);
                continue;
            }
            String key = group.dataUuid() != null ? group.dataUuid().toString()
                    : "name:" + String.valueOf(group.name).toLowerCase(java.util.Locale.ROOT);
            Map<String, Object> previous = chosen.get(key);
            if (previous == null) {
                decorateSkin(row, group);
                chosen.put(key, row);
                merged.add(row);
                continue;
            }
            if (preferAccount(row, previous, group, onlineMode)) {
                decorateSkin(row, group);
                int index = merged.indexOf(previous);
                if (index >= 0) merged.set(index, row);
                chosen.put(key, row);
            }
        }
        // 非身份组玩家用自己的 UUID 查皮肤；前端统一读取 skinUuid/skinName。
        for (Map<String, Object> row : merged) {
            if (!row.containsKey("skinUuid")) {
                row.put("skinUuid", row.getOrDefault("uuid", ""));
                row.put("skinName", row.getOrDefault("name", ""));
            }
        }
        rows.clear();
        rows.addAll(merged);
    }

    private static void decorateSkin(Map<String, Object> row, IdentityConfig.Group group) {
        UUID skin = group.premiumUuid();
        if (skin == null) skin = group.dataUuid();
        if (skin == null) skin = group.offlineUuid();
        if (skin != null) row.put("skinUuid", skin.toString());
        row.put("skinName", row.getOrDefault("name", ""));
    }

    private static boolean preferAccount(Map<String, Object> candidate, Map<String, Object> current,
                                         IdentityConfig.Group group, boolean onlineMode) {
        boolean candidateOnline = Boolean.TRUE.equals(candidate.get("online"));
        boolean currentOnline = Boolean.TRUE.equals(current.get("online"));
        if (candidateOnline != currentOnline) {
            return candidateOnline;
        }
        UUID preferred = onlineMode ? group.premiumUuid() : group.offlineUuid();
        if (preferred == null) {
            preferred = onlineMode ? group.offlineUuid() : group.premiumUuid();
        }
        if (preferred == null) return false;
        String id = preferred.toString();
        boolean candidatePreferred = id.equalsIgnoreCase(String.valueOf(candidate.get("uuid")));
        boolean currentPreferred = id.equalsIgnoreCase(String.valueOf(current.get("uuid")));
        return candidatePreferred && !currentPreferred;
    }

    /** 同名历史账号只保留一个：在线者优先，其次最后在线时间更新的。 */
    private static void dedupeNames(List<Map<String, Object>> rows) {
        Map<String, Map<String, Object>> chosen = new LinkedHashMap<>();
        List<Map<String, Object>> merged = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String name = String.valueOf(row.getOrDefault("name", "")).trim().toLowerCase(java.util.Locale.ROOT);
            if (name.isEmpty()) {
                merged.add(row);
                continue;
            }
            Map<String, Object> previous = chosen.get(name);
            if (previous == null) {
                chosen.put(name, row);
                merged.add(row);
                continue;
            }
            if (preferByName(row, previous)) {
                int index = merged.indexOf(previous);
                if (index >= 0) merged.set(index, row);
                chosen.put(name, row);
            }
        }
        rows.clear();
        rows.addAll(merged);
    }

    private static boolean preferByName(Map<String, Object> candidate, Map<String, Object> current) {
        boolean candidateOnline = Boolean.TRUE.equals(candidate.get("online"));
        boolean currentOnline = Boolean.TRUE.equals(current.get("online"));
        if (candidateOnline != currentOnline) {
            return candidateOnline;
        }
        long candidateSeen = seenScore(candidate.get("lastSeen"));
        long currentSeen = seenScore(current.get("lastSeen"));
        if (candidateSeen != currentSeen) return candidateSeen > currentSeen;
        // 有地图位置的条目通常比仅存在于白名单里的旧快照更可信。
        boolean candidatePosition = candidate.get("dimension") != null && candidate.get("x") != null;
        boolean currentPosition = current.get("dimension") != null && current.get("x") != null;
        return candidatePosition && !currentPosition;
    }

    private static long seenScore(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (!text.matches("^\\d{4}-\\d{2}-\\d{2}.*")) return 0L;
        try {
            return java.time.LocalDateTime.parse(text.length() >= 19
                            ? text.substring(0, 19).replace(' ', 'T')
                            : text)
                    .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (RuntimeException ignored) {
            return 0L;
        }
    }

    private static UUID uuidOf(Object value) {
        String text = value == null ? "" : String.valueOf(value).trim();
        if (text.isEmpty()) return null;
        try {
            return UUID.fromString(text);
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    /** 离线玩家沿用最后退出的位置，地图上保留灰色图标。 */
    private void decorateOfflinePosition(Map<String, Object> row, String uuid) {
        if (uuid == null || uuid.isBlank()) return;
        Path dir;
        if (server != null) {
            try {
                dir = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            } catch (RuntimeException e) {
                dir = worldDir().resolve("players").resolve("data");
            }
        } else {
            dir = worldDir().resolve("players").resolve("data");
        }
        Path file = dir.resolve(uuid + ".dat");
        if (!Files.isRegularFile(file)) return;
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.create(16L * 1024 * 1024));
            String dimension = tag.getStringOr("Dimension", "");
            ListTag pos = tag.getListOrEmpty("Pos");
            if (pos.size() >= 3) {
                row.put("dimension", dimension);
                row.put("x", Math.round(pos.getDoubleOr(0, 0.0) * 10.0) / 10.0);
                row.put("y", Math.round(pos.getDoubleOr(1, 0.0) * 10.0) / 10.0);
                row.put("z", Math.round(pos.getDoubleOr(2, 0.0) * 10.0) / 10.0);
            }
        } catch (Exception ignored) {
            // 数据文件不存在/版本不兼容时只显示玩家，不上地图。
        }
    }

    public Path backupFile(String id) {
        Path file = ServerBackup.backupFile(serverRoot, id == null ? "" : id.trim());
        return Files.isRegularFile(file) ? file : null;
    }

    private Path worldDir() {
        if (server != null) {
            try {
                return server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
            } catch (RuntimeException ignored) {
            }
        }
        return ServerBackup.worldDir(serverRoot);
    }

    private static String str(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        return value == null ? "" : String.valueOf(value);
    }

    private static boolean bool(Map<String, Object> body, String key) {
        Object value = body == null ? null : body.get(key);
        if (value instanceof Boolean b) {
            return b;
        }
        return value != null && "true".equalsIgnoreCase(String.valueOf(value));
    }
}
