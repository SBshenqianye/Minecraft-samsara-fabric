package com.shenqianye.samsara.permission;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.config.YamlStore;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 权限等级配置（{@code config/samsara/permissions.yml}）。
 *
 * <p>命令路径 → 所需权限等级；同时管理 owner 名单。
 * 默认值来自 {@link PermissionDefaults}，与 {@link ConfigSchema} 共用一份，不再两边各抄。
 */
public class PermissionConfig {
    private static PermissionConfig instance;

    /** 命令路径 → 所需权限等级 */
    private final Map<String, PermissionLevel> entries = new ConcurrentHashMap<>();
    /** owner 玩家名列表（小写） */
    private final Set<String> ownerNames = ConcurrentHashMap.newKeySet();

    private static final Map<String, PermissionLevel> DEFAULTS = new LinkedHashMap<>();

    static {
        PermissionDefaults.entries().forEach((path, level) ->
                DEFAULTS.put(path, PermissionLevel.fromString(level)));
    }

    private PermissionConfig() {
        reload();
    }

    public static synchronized PermissionConfig getInstance() {
        if (instance == null) instance = new PermissionConfig();
        return instance;
    }

    // ---- 查询 ----

    public PermissionLevel getRequired(String commandPath) {
        PermissionLevel level = entries.get(commandPath);
        if (level != null) return level;
        for (var e : entries.entrySet()) {
            if (commandPath.startsWith(e.getKey() + " ")) return e.getValue();
        }
        for (var e : DEFAULTS.entrySet()) {
            if (commandPath.equals(e.getKey()) || commandPath.startsWith(e.getKey() + " ")) {
                return e.getValue();
            }
        }
        return PermissionLevel.USER;
    }

    public Map<String, PermissionLevel> getAllEntries() {
        Map<String, PermissionLevel> result = new LinkedHashMap<>(DEFAULTS);
        result.putAll(entries);
        return result;
    }

    /** 内置默认命令路径（不触发单例、不读盘）。 */
    public static List<String> defaultCommandPaths() {
        return List.copyOf(DEFAULTS.keySet());
    }

    public boolean isOwner(String playerName) {
        return ownerNames.contains(playerName.toLowerCase());
    }

    // ---- 修改 ----

    public String setEntry(String commandPath, PermissionLevel level, PermissionLevel setterLevel) {
        if (commandPath.startsWith("perm ")) {
            return "perm 命令的权限等级不可通过游戏内修改";
        }
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

    public String resetEntry(String commandPath) {
        if (entries.remove(commandPath) != null) {
            save();
            return "已重置 /samsara " + commandPath + " → 默认（" + getRequired(commandPath) + "）";
        }
        return "/samsara " + commandPath + " 未自定义，当前已是默认等级";
    }

    public String addOwner(String name) {
        if (ownerNames.add(name.toLowerCase())) {
            save();
            return "已添加 owner: " + name;
        }
        return name + " 已是 owner";
    }

    public String removeOwner(String name) {
        if (ownerNames.remove(name.toLowerCase())) {
            save();
            return "已移除 owner: " + name;
        }
        return name + " 不在 owner 名单中";
    }

    // ---- 持久化 ----

    private void save() {
        Map<String, String> map = new LinkedHashMap<>();
        entries.forEach((path, level) -> map.put(path, level.name().toLowerCase()));
        YamlStore.SaveResult result = ConfigManager.getInstance().save(ConfigSchema.PERMISSIONS,
                Map.of("entries", map, "owners", new ArrayList<>(ownerNames)));
        if (!result.ok()) {
            Samsara.LOGGER.warn("[周目管理 samsara] permissions.yml 写入失败: {}", result.errors());
        }
    }

    /** 重新读取 YAML（热重载 / Web 保存后）。 */
    public synchronized void reload() {
        ConfigManager config = ConfigManager.getInstance();
        entries.clear();
        ownerNames.clear();
        config.levelMap(ConfigSchema.PERMISSIONS, "entries")
                .forEach((path, level) -> entries.put(path, PermissionLevel.fromString(level)));
        for (String owner : config.stringList(ConfigSchema.PERMISSIONS, "owners")) {
            ownerNames.add(owner.toLowerCase());
        }
        boolean removed = entries.keySet().removeIf(k -> k.equals("activity")
                || k.startsWith("activity ") || k.equals("debug activity")
                || k.startsWith("debug activity "));
        if (removed) {
            save();
            Samsara.LOGGER.info("[周目管理 samsara] 已清理 permissions.yml 中已删除的 activity 规则");
        }
        Samsara.LOGGER.info("[周目管理 samsara] 加载权限配置：{} 条命令规则，{} 个 owner",
                entries.size(), ownerNames.size());
    }

    public String configPath() {
        return ConfigManager.getInstance().file(ConfigSchema.PERMISSIONS).toAbsolutePath()
                .normalize().toString();
    }

    /** 兼容旧调用的路径访问。 */
    public Path file() {
        return ConfigManager.getInstance().file(ConfigSchema.PERMISSIONS);
    }
}
