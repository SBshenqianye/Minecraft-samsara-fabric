package com.shenqianye.samsara.web;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
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
        list.sort(Comparator
                .comparing((Map<String, Object> row) -> Boolean.TRUE.equals(row.get("online")) ? 0 : 1)
                .thenComparing(row -> String.valueOf(row.get("name")), String.CASE_INSENSITIVE_ORDER));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("players", list);
        return out;
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
