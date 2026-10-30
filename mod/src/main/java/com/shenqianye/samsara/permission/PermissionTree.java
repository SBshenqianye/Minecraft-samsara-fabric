package com.shenqianye.samsara.permission;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 权限配置树：纯数据构建与祖先级联计算。
 *
 * <p>规则：
 * <ul>
 *   <li>任意祖先被禁用 → 后代全部禁用；</li>
 *   <li>叶子的实际所需等级 = 路径上所有祖先与自身的最高等级；</li>
 *   <li>树枝之间互不影响（路径按空格分层，不做字符串前缀误匹配）。</li>
 * </ul>
 */
public final class PermissionTree {
    public static final String ROOT = "*";

    private PermissionTree() {
    }

    /** 从短到长的完整路径链，包含根 {@code *}。 */
    public static List<String> chain(String path) {
        List<String> out = new ArrayList<>();
        out.add(ROOT);
        String value = path == null ? "" : path.trim();
        if (value.isEmpty() || ROOT.equals(value)) {
            return out;
        }
        String[] parts = value.split("\\s+");
        StringBuilder current = new StringBuilder();
        for (String part : parts) {
            if (part.isBlank()) {
                continue;
            }
            if (current.length() > 0) {
                current.append(' ');
            }
            current.append(part);
            out.add(current.toString());
        }
        return out;
    }

    /** 返回关闭该节点的最近（最外层）祖先；未被禁用返回 null。 */
    public static String disabledBy(String path, Set<String> disabled) {
        if (disabled == null || disabled.isEmpty()) {
            return null;
        }
        for (String ancestor : chain(path)) {
            if (disabled.contains(ancestor)) {
                return ancestor;
            }
        }
        return null;
    }

    /** 路径上的有效等级 = 所有祖先与自身的最高等级。 */
    public static PermissionLevel effectiveLevel(String path,
                                                 Map<String, PermissionLevel> entries,
                                                 Map<String, PermissionLevel> defaults) {
        PermissionLevel result = null;
        for (String ancestor : chain(path)) {
            PermissionLevel level = directLevel(ancestor, entries, defaults);
            if (level != null && (result == null || level.getValue() > result.getValue())) {
                result = level;
            }
        }
        return result == null ? PermissionLevel.USER : result;
    }

    public static PermissionLevel directLevel(String path,
                                              Map<String, PermissionLevel> entries,
                                              Map<String, PermissionLevel> defaults) {
        if (entries != null && entries.containsKey(path)) {
            return entries.get(path);
        }
        return defaults == null ? null : defaults.get(path);
    }

    public static String source(String path, Map<String, PermissionLevel> entries,
                                Map<String, PermissionLevel> defaults) {
        if (entries != null && entries.containsKey(path)) {
            PermissionLevel value = entries.get(path);
            PermissionLevel fallback = defaults == null ? null : defaults.get(path);
            if (fallback == null || fallback != value) {
                return "custom";
            }
            return "default";
        }
        return defaults != null && defaults.containsKey(path) ? "default" : "inherit";
    }

    public static List<Node> build(Map<String, PermissionLevel> entries,
                                   Set<String> disabled,
                                   Map<String, PermissionLevel> defaults) {
        return build(entries, disabled, defaults, Set.of());
    }

    public static List<Node> build(Map<String, PermissionLevel> entries,
                                   Set<String> disabled,
                                   Map<String, PermissionLevel> defaults,
                                   Set<String> extraPaths) {
        Map<String, Builder> index = new TreeMap<>();
        Builder root = new Builder(ROOT);
        index.put(ROOT, root);

        java.util.Set<String> paths = new java.util.TreeSet<>();
        if (defaults != null) {
            paths.addAll(defaults.keySet());
        }
        if (entries != null) {
            paths.addAll(entries.keySet());
        }
        if (extraPaths != null) {
            paths.addAll(extraPaths);
        }
        paths.remove(ROOT);
        for (String path : paths) {
            if (path == null || path.isBlank()) {
                continue;
            }
            List<String> chain = chain(path);
            Builder parent = root;
            for (int i = 1; i < chain.size(); i++) {
                String current = chain.get(i);
                Builder builder = index.get(current);
                if (builder == null) {
                    builder = new Builder(current);
                    index.put(current, builder);
                    parent.children.put(current, builder);
                }
                parent = builder;
            }
        }
        return List.of(toNode(root, entries, disabled, defaults));
    }

    private static Node toNode(Builder builder, Map<String, PermissionLevel> entries,
                               Set<String> disabled, Map<String, PermissionLevel> defaults) {
        List<Node> children = new ArrayList<>();
        for (Builder child : builder.children.values()) {
            children.add(toNode(child, entries, disabled, defaults));
        }
        children.sort((a, b) -> a.path().compareToIgnoreCase(b.path()));
        PermissionLevel direct = directLevel(builder.path, entries, defaults);
        PermissionLevel effective = effectiveLevel(builder.path, entries, defaults);
        String disabledBy = disabledBy(builder.path, disabled);
        return new Node(builder.path,
                ROOT.equals(builder.path) ? "/samsara" : builder.path,
                direct == null ? "inherit" : direct.name().toLowerCase(),
                effective.name().toLowerCase(),
                disabled != null && disabled.contains(builder.path),
                disabledBy == null ? "" : disabledBy,
                source(builder.path, entries, defaults),
                List.copyOf(children));
    }

    private static final class Builder {
        final String path;
        final Map<String, Builder> children = new LinkedHashMap<>();

        Builder(String path) {
            this.path = path;
        }
    }

    public record Node(String path, String label, String level, String effectiveLevel,
                       boolean disabled, String disabledBy, String source,
                       List<Node> children) {
    }
}
