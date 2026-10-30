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
    /** 被整体关闭的权限节点（父节点会级联到后代） */
    private final Set<String> disabled = ConcurrentHashMap.newKeySet();
    /** owner 玩家名列表（小写） */
    private final Set<String> ownerNames = ConcurrentHashMap.newKeySet();

    /** v0.19 认证层已退役；旧 permissions.yml 里残留的命令路径启动时清理。 */
    private static final Set<String> RETIRED_PATHS = Set.of(
            "login", "register", "password", "bind-premium",
            "identity bind-premium", "identity migrate", "identity rollback",
            "identity kick", "identity reset-pw");

    private static final Map<String, PermissionLevel> DEFAULTS = new LinkedHashMap<>();

    private static boolean retired(String path) {
        return path.equals("activity") || path.startsWith("activity ")
                || path.equals("debug activity") || path.startsWith("debug activity ")
                || RETIRED_PATHS.contains(path);
    }


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
        return PermissionTree.effectiveLevel(commandPath, entries, DEFAULTS);
    }

    /** 根或任意祖先被关闭时返回 true。 */
    public boolean isDisabled(String commandPath) {
        return PermissionTree.disabledBy(commandPath, disabled) != null;
    }

    /** 关闭该节点的最外层祖先；未关闭返回 null。 */
    public String disabledBy(String commandPath) {
        return PermissionTree.disabledBy(commandPath, disabled);
    }

    public Set<String> disabledPaths() {
        return Set.copyOf(disabled);
    }

    public List<PermissionTree.Node> tree() {
        return PermissionTree.build(entries, disabled, DEFAULTS,
                PermissionCommandPaths.paths());
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

    /**
     * Web 权限树批量修改。全部校验通过才落盘；失败返回字段级错误。
     */
    public synchronized Map<String, String> applyChanges(List<NodeChange> changes) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, PermissionLevel> nextEntries = new LinkedHashMap<>(entries);
        Set<String> nextDisabled = ConcurrentHashMap.newKeySet();
        nextDisabled.addAll(disabled);
        if (changes != null) {
            for (int i = 0; i < changes.size(); i++) {
                NodeChange change = changes.get(i);
                String field = "nodes[" + i + "]";
                if (change == null || change.path() == null || change.path().isBlank()) {
                    errors.put(field + ".path", "权限路径不能为空");
                    continue;
                }
                String path = change.path().trim();
                if (change.level() != null && !change.level().isBlank()) {
                    String level = change.level().trim().toLowerCase();
                    if ("inherit".equals(level)) {
                        nextEntries.remove(path);
                    } else {
                        PermissionLevel parsed = parseLevel(level);
                        if (parsed == null) {
                            errors.put(field + ".level",
                                    "等级可选值：inherit / user / op / owner / config");
                        } else {
                            nextEntries.put(path, parsed);
                        }
                    }
                }
                if (change.disabled() != null) {
                    if (change.disabled()) {
                        nextDisabled.add(path);
                    } else {
                        nextDisabled.remove(path);
                    }
                }
            }
        }
        if (!errors.isEmpty()) {
            return errors;
        }
        entries.clear();
        entries.putAll(nextEntries);
        disabled.clear();
        disabled.addAll(nextDisabled);
        save();
        return Map.of();
    }

    private static PermissionLevel parseLevel(String value) {
        return switch (value) {
            case "user" -> PermissionLevel.USER;
            case "op" -> PermissionLevel.OP;
            case "owner" -> PermissionLevel.OWNER;
            case "config" -> PermissionLevel.CONFIG;
            default -> null;
        };
    }

    // ---- 持久化 ----

    private void save() {
        Map<String, String> map = new LinkedHashMap<>();
        entries.forEach((path, level) -> map.put(path, level.name().toLowerCase()));
        YamlStore.SaveResult result = ConfigManager.getInstance().save(ConfigSchema.PERMISSIONS,
                Map.of("entries", map,
                        "disabled", new ArrayList<>(disabled),
                        "owners", new ArrayList<>(ownerNames)));
        if (!result.ok()) {
            Samsara.LOGGER.warn("[周目管理 samsara] permissions.yml 写入失败: {}", result.errors());
        }
    }

    /** 重新读取 YAML（热重载 / Web 保存后）。 */
    public synchronized void reload() {
        ConfigManager config = ConfigManager.getInstance();
        entries.clear();
        disabled.clear();
        ownerNames.clear();
        config.levelMap(ConfigSchema.PERMISSIONS, "entries")
                .forEach((path, level) -> entries.put(path, PermissionLevel.fromString(level)));
        disabled.addAll(config.stringList(ConfigSchema.PERMISSIONS, "disabled"));
        for (String owner : config.stringList(ConfigSchema.PERMISSIONS, "owners")) {
            ownerNames.add(owner.toLowerCase());
        }
        boolean removed = entries.keySet().removeIf(PermissionConfig::retired);
        boolean removedDisabled = disabled.removeIf(PermissionConfig::retired);
        if (removed || removedDisabled) {
            save();
            Samsara.LOGGER.info("[周目管理 samsara] 已清理 permissions.yml 中已退役的命令规则");
        }
        Samsara.LOGGER.info("[周目管理 samsara] 加载权限配置：{} 条命令规则，{} 个关闭节点，{} 个 owner",
                entries.size(), disabled.size(), ownerNames.size());
    }

    public String configPath() {
        return ConfigManager.getInstance().file(ConfigSchema.PERMISSIONS).toAbsolutePath()
                .normalize().toString();
    }

    /** 兼容旧调用的路径访问。 */
    public Path file() {
        return ConfigManager.getInstance().file(ConfigSchema.PERMISSIONS);
    }

    /** Web 权限树的一次节点修改；level 为空表示不改，disabled 为空表示不改。 */
    public record NodeChange(String path, String level, Boolean disabled) {
    }
}
