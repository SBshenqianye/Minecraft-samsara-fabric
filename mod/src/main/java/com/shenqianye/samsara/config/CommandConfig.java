package com.shenqianye.samsara.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.Samsara;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 命令功能配置（持久化至 {@code config/samsara/commands.json}）。
 * <p>
 * 设计目标：命令的<b>启用</b>与<b>功能细节</b>都不写死在代码里，服主可直接改 JSON：
 * <ul>
 *   <li>{@code where.enabled} —— 是否启用 {@code /samsara where}</li>
 *   <li>{@code where.broadcast} —— 是否允许 {@code /samsara where broadcast}</li>
 *   <li>{@code where.broadcastCooldownSeconds} —— 广播冷却（0 = 不限）</li>
 *   <li>{@code where.showCoordinates/showDimension/showChunk/showFacing} —— 输出内容开关</li>
 * </ul>
 * 修改后可用 {@code /samsara debug reload} 重新读取（无需重启）。
 * <p>
 * 读取方式与 {@code claim-particles.json} 一致：直接读 {@link JsonObject} 并逐字段取默认值，
 * 避免 Gson 反射实例化在 Java 25+ 上失败，也避免缺字段被反序列化成 false/0。
 */
public final class CommandConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static CommandConfig instance;

    private static final String DEFAULT_JSON = """
            {
              "where": {
                "enabled": true,
                "broadcast": true,
                "broadcastCooldownSeconds": 30,
                "showCoordinates": true,
                "showDimension": true,
                "showChunk": true,
                "showFacing": true
              },
              "tpa": {
                "enabled": true,
                "requestTimeoutSeconds": 60,
                "crossDimension": false
              }
            }
            """;

    private final Path file;

    private boolean whereEnabled = true;
    private boolean whereBroadcast = true;
    private int whereBroadcastCooldownSeconds = 30;
    private boolean whereShowCoordinates = true;
    private boolean whereShowDimension = true;
    private boolean whereShowChunk = true;
    private boolean whereShowFacing = true;

    private boolean tpaEnabled = true;
    private int tpaRequestTimeoutSeconds = 60;
    private boolean tpaCrossDimension = false;

    private CommandConfig() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("commands.json");
        load();
    }

    public static synchronized CommandConfig getInstance() {
        if (instance == null) instance = new CommandConfig();
        return instance;
    }

    // ---- where 命令查询 ----

    /** {@code /samsara where} 是否启用（未启用时命令不出现在命令树中） */
    public boolean isWhereEnabled() {
        return whereEnabled;
    }

    /** {@code /samsara where broadcast} 功能是否启用 */
    public boolean isWhereBroadcastEnabled() {
        return whereBroadcast;
    }

    /** 广播冷却秒数（0 = 不限） */
    public int whereBroadcastCooldownSeconds() {
        return whereBroadcastCooldownSeconds;
    }

    public boolean whereShowCoordinates() {
        return whereShowCoordinates;
    }

    public boolean whereShowDimension() {
        return whereShowDimension;
    }

    public boolean whereShowChunk() {
        return whereShowChunk;
    }

    public boolean whereShowFacing() {
        return whereShowFacing;
    }

    // ---- tpa 命令查询 ----

    /** {@code /samsara tpa ...} 系列是否启用 */
    public boolean isTpaEnabled() {
        return tpaEnabled;
    }

    /** 传送请求有效期（秒） */
    public int tpaRequestTimeoutSeconds() {
        return tpaRequestTimeoutSeconds;
    }

    /** 是否允许跨维度传送（false = 仅同一维度，设计默认） */
    public boolean tpaCrossDimension() {
        return tpaCrossDimension;
    }

    /** 配置文件绝对路径 */
    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    // ---- 持久化 ----

    private void save() {
        JsonObject root = new JsonObject();
        JsonObject where = new JsonObject();
        where.addProperty("enabled", whereEnabled);
        where.addProperty("broadcast", whereBroadcast);
        where.addProperty("broadcastCooldownSeconds", whereBroadcastCooldownSeconds);
        where.addProperty("showCoordinates", whereShowCoordinates);
        where.addProperty("showDimension", whereShowDimension);
        where.addProperty("showChunk", whereShowChunk);
        where.addProperty("showFacing", whereShowFacing);
        root.add("where", where);

        JsonObject tpa = new JsonObject();
        tpa.addProperty("enabled", tpaEnabled);
        tpa.addProperty("requestTimeoutSeconds", tpaRequestTimeoutSeconds);
        tpa.addProperty("crossDimension", tpaCrossDimension);
        root.add("tpa", tpa);
        try {
            Files.writeString(file, GSON.toJson(root), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] commands.json 写入失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            try {
                Files.writeString(file, GSON.toJson(JsonParser.parseString(DEFAULT_JSON)), StandardCharsets.UTF_8);
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] commands.json 写入失败: {}", e.getMessage());
            }
            return;
        }
        try {
            JsonObject root = JsonParser.parseString(Files.readString(file, StandardCharsets.UTF_8))
                    .getAsJsonObject();
            JsonElement whereElement = root.get("where");
            JsonObject where = whereElement != null && whereElement.isJsonObject()
                    ? whereElement.getAsJsonObject()
                    : new JsonObject();
            whereEnabled = readBoolean(where, "enabled", whereEnabled);
            whereBroadcast = readBoolean(where, "broadcast", whereBroadcast);
            whereBroadcastCooldownSeconds = readInt(where, "broadcastCooldownSeconds",
                    whereBroadcastCooldownSeconds, 0, 86_400);
            whereShowCoordinates = readBoolean(where, "showCoordinates", whereShowCoordinates);
            whereShowDimension = readBoolean(where, "showDimension", whereShowDimension);
            whereShowChunk = readBoolean(where, "showChunk", whereShowChunk);
            whereShowFacing = readBoolean(where, "showFacing", whereShowFacing);

            JsonElement tpaElement = root.get("tpa");
            JsonObject tpa = tpaElement != null && tpaElement.isJsonObject()
                    ? tpaElement.getAsJsonObject()
                    : new JsonObject();
            tpaEnabled = readBoolean(tpa, "enabled", tpaEnabled);
            tpaRequestTimeoutSeconds = readInt(tpa, "requestTimeoutSeconds",
                    tpaRequestTimeoutSeconds, 5, 3600);
            tpaCrossDimension = readBoolean(tpa, "crossDimension", tpaCrossDimension);
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] commands.json 解析失败，使用默认值: {}", e.getMessage());
        }
    }

    /** 重新读取 commands.json（{@code /samsara debug reload}） */
    public synchronized String reload() {
        load();
        return "命令配置已重载：" + (whereEnabled ? "where 启用" : "where 禁用")
                + " · 广播" + (whereBroadcast ? "允许" : "禁止")
                + " · tpa " + (tpaEnabled ? "启用" : "禁用")
                + "（超时 " + tpaRequestTimeoutSeconds + "s）"
                + " · 来源 " + configPath();
    }

    private static boolean readBoolean(JsonObject json, String key, boolean fallback) {
        JsonElement value = json.get(key);
        return value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean()
                ? value.getAsBoolean()
                : fallback;
    }

    private static int readInt(JsonObject json, String key, int fallback, int min, int max) {
        JsonElement value = json.get(key);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            return fallback;
        }
        return Math.max(min, Math.min(max, value.getAsInt()));
    }
}
