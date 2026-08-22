package com.shenqianye.samsara.permission;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限等级配置（设计文档扩展 §18）。
 * <p>
 * 管理命令→所需权限等级的映射，持久化至 {@code config/samsara/permissions.json}。
 * 同时管理 owner 名单（可配置，非写死）。
 */
public class PermissionConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static PermissionConfig instance;

    private final Path file;

    /** 命令路径 → 所需权限等级 */
    private final Map<String, PermissionLevel> entries = new ConcurrentHashMap<>();
    /** owner 玩家名列表（小写） */
    private final Set<String> ownerNames = ConcurrentHashMap.newKeySet();

    // ---- 内置默认等级 ----
    private static final Map<String, PermissionLevel> DEFAULTS = new LinkedHashMap<>();

    static {
        // season 命令
        DEFAULTS.put("season create", PermissionLevel.OWNER);
        DEFAULTS.put("season start", PermissionLevel.OWNER);
        DEFAULTS.put("season end", PermissionLevel.OWNER);
        DEFAULTS.put("season skip", PermissionLevel.OWNER);
        DEFAULTS.put("season status", PermissionLevel.USER);
        DEFAULTS.put("season respawn-groups", PermissionLevel.OWNER);

        // claim 命令
        DEFAULTS.put("claim give", PermissionLevel.OP);
        DEFAULTS.put("claim list", PermissionLevel.USER);
        DEFAULTS.put("claim info", PermissionLevel.USER);
        DEFAULTS.put("claim scan", PermissionLevel.OP);
        DEFAULTS.put("claim rebuild", PermissionLevel.OP);
        DEFAULTS.put("claim preview", PermissionLevel.OP);
        DEFAULTS.put("claim remove", PermissionLevel.OP);
        DEFAULTS.put("claim export", PermissionLevel.OP);

        // activity 命令
        DEFAULTS.put("activity recent", PermissionLevel.USER);
        DEFAULTS.put("activity stats", PermissionLevel.USER);
        DEFAULTS.put("activity dead", PermissionLevel.USER);

        // debug 命令（全部 OWNER）
        DEFAULTS.put("debug flow", PermissionLevel.OWNER);
        DEFAULTS.put("debug diff", PermissionLevel.OWNER);
        DEFAULTS.put("debug rewind", PermissionLevel.OWNER);
        DEFAULTS.put("debug claim", PermissionLevel.OWNER);
        DEFAULTS.put("debug delete", PermissionLevel.OWNER);
        DEFAULTS.put("debug activity", PermissionLevel.OWNER);
        DEFAULTS.put("debug config", PermissionLevel.OWNER);
        DEFAULTS.put("debug whitelist", PermissionLevel.OWNER);
        DEFAULTS.put("debug fakeplayers", PermissionLevel.OWNER);
        DEFAULTS.put("debug groups", PermissionLevel.OWNER);
        DEFAULTS.put("debug create", PermissionLevel.OWNER);
        DEFAULTS.put("debug settime", PermissionLevel.OWNER);
        DEFAULTS.put("debug reload", PermissionLevel.OWNER);
        DEFAULTS.put("debug reset", PermissionLevel.OWNER);
        DEFAULTS.put("debug spawninfo", PermissionLevel.OWNER);

        // perm 命令
        DEFAULTS.put("perm list", PermissionLevel.USER);
        DEFAULTS.put("perm set", PermissionLevel.OWNER);
        DEFAULTS.put("perm reset", PermissionLevel.OWNER);
        DEFAULTS.put("perm info", PermissionLevel.USER);
        DEFAULTS.put("perm owner", PermissionLevel.CONFIG);
    }

    private PermissionConfig() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try { Files.createDirectories(dir); } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("permissions.json");
        load();
    }

    public static synchronized PermissionConfig getInstance() {
        if (instance == null) instance = new PermissionConfig();
        return instance;
    }

    // ---- 查询 ----

    /** 获取命令所需权限等级（优先自定义，其次内置默认） */
    public PermissionLevel getRequired(String commandPath) {
        // 精确匹配
        PermissionLevel level = entries.get(commandPath);
        if (level != null) return level;
        // 前缀匹配（如 "debug flow rewind" 匹配 "debug flow"）
        for (var e : entries.entrySet()) {
            if (commandPath.startsWith(e.getKey() + " ")) return e.getValue();
        }
        // 内置默认
        for (var e : DEFAULTS.entrySet()) {
            if (commandPath.equals(e.getKey()) || commandPath.startsWith(e.getKey() + " ")) {
                return e.getValue();
            }
        }
        return PermissionLevel.USER; // 未配置默认 user
    }

    /** 获取所有条目（自定义 + 默认合并） */
    public Map<String, PermissionLevel> getAllEntries() {
        Map<String, PermissionLevel> result = new LinkedHashMap<>(DEFAULTS);
        result.putAll(entries);
        return result;
    }

    /** 检查 owner 名单 */
    public boolean isOwner(String playerName) {
        return ownerNames.contains(playerName.toLowerCase());
    }

    // ---- 修改 ----

    /**
     * 设置命令所需权限等级。
     * @return 成功信息或错误信息
     */
    public String setEntry(String commandPath, PermissionLevel level, PermissionLevel setterLevel) {
        // CONFIG 级命令不可通过游戏内修改
        if (commandPath.startsWith("perm ")) {
            return "perm 命令的权限等级不可通过游戏内修改";
        }
        // 只能设置 ≤ 自己等级的命令
        PermissionLevel currentRequired = getRequired(commandPath);
        if (currentRequired.getValue() > setterLevel.getValue()) {
            return "无法修改：当前命令需要 " + currentRequired + " 级权限，你只有 " + setterLevel + " 级";
        }
        if (level.getValue() > setterLevel.getValue()) {
            return "无法设置为 " + level + " 级：超出你的权限范围（" + setterLevel + "）";
        }
        entries.put(commandPath, level);
        save();
        return "已设置 /samsara " + commandPath + " → " + level;
    }

    /** 重置为默认等级 */
    public String resetEntry(String commandPath) {
        if (entries.remove(commandPath) != null) {
            save();
            return "已重置 /samsara " + commandPath + " → 默认（" + getRequired(commandPath) + "）";
        }
        return "/samsara " + commandPath + " 未自定义，当前已是默认等级";
    }

    /** 添加 owner（控制台或 config 级命令调用） */
    public String addOwner(String name) {
        if (ownerNames.add(name.toLowerCase())) {
            save();
            return "已添加 owner: " + name;
        }
        return name + " 已是 owner";
    }

    /** 移除 owner */
    public String removeOwner(String name) {
        if (ownerNames.remove(name.toLowerCase())) {
            save();
            return "已移除 owner: " + name;
        }
        return name + " 不在 owner 名单中";
    }

    // ---- 持久化 ----

    private void save() {
        Data data = new Data();
        for (var e : entries.entrySet()) {
            data.entries.put(e.getKey(), e.getValue().name().toLowerCase());
        }
        data.owners.addAll(ownerNames);
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] permissions.json 写入失败: {}", e.getMessage());
        }
    }

    private void load() {
        if (!Files.exists(file)) {
            // 首次运行：写入默认配置
            save();
            return;
        }
        try {
            Data data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), Data.class);
            if (data == null) return;
            if (data.entries != null) {
                for (var e : data.entries.entrySet()) {
                    entries.put(e.getKey(), PermissionLevel.fromString(e.getValue()));
                }
            }
            if (data.owners != null) {
                for (String o : data.owners) ownerNames.add(o.toLowerCase());
            }
            Samsara.LOGGER.info("[周目管理 samsara] 加载权限配置：{} 条命令规则，{} 个 owner",
                    entries.size(), ownerNames.size());
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] permissions.json 解析失败: {}", e.getMessage());
        }
    }

    /** 数据文件绝对路径 */
    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    // ---- JSON 模型 ----

    static class Data {
        Map<String, String> entries = new LinkedHashMap<>();
        List<String> owners = new ArrayList<>();
    }
}
