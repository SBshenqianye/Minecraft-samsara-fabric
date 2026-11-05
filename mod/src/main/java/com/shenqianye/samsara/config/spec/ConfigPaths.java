package com.shenqianye.samsara.config.spec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 嵌套配置文档与“点分扁平路径”之间的转换。 */
public final class ConfigPaths {
    private ConfigPaths() {
    }

    /** 深度展开：嵌套 Map 用点连接；列表、标量、空 Map 原样保留。 */
    public static Map<String, Object> flatten(Map<String, Object> root) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (root == null) {
            return out;
        }
        flattenInto(out, "", root);
        return out;
    }

    private static void flattenInto(Map<String, Object> out, String prefix, Map<?, ?> map) {
        for (Map.Entry<?, ?> e : map.entrySet()) {
            String key = String.valueOf(e.getKey());
            String path = prefix.isEmpty() ? key : prefix + "." + key;
            Object value = e.getValue();
            if (value instanceof Map<?, ?> child && !child.isEmpty()) {
                flattenInto(out, path, child);
            } else {
                out.put(path, value);
            }
        }
    }

    /** 按点分路径写入；中间层不是 Map 时替换为 Map。 */
    @SuppressWarnings("unchecked")
    public static void set(Map<String, Object> root, String path, Object value) {
        if (root == null || path == null || path.isEmpty()) {
            return;
        }
        String[] parts = path.split("\\.", -1);
        Map<String, Object> cursor = root;
        for (int i = 0; i < parts.length - 1; i++) {
            String part = parts[i];
            Object next = cursor.get(part);
            if (!(next instanceof Map<?, ?>)) {
                next = new LinkedHashMap<String, Object>();
                cursor.put(part, next);
            }
            cursor = (Map<String, Object>) next;
        }
        cursor.put(parts[parts.length - 1], value);
    }

    /** 使用扁平路径读取；不存在返回 null。 */
    public static Object get(Map<String, Object> flat, String path) {
        return flat == null ? null : flat.get(path);
    }

    public static String firstSegment(String path) {
        if (path == null || path.isEmpty()) {
            return "常规";
        }
        int dot = path.indexOf('.');
        return dot < 0 ? path : path.substring(0, dot);
    }

    public static String lastSegment(String path) {
        if (path == null || path.isEmpty()) {
            return "";
        }
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    /** 把任意 Gson 风格对象转换为可写入的普通对象；保留 Map/List 结构。 */
    public static Object normalize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return out;
        }
        if (value instanceof List<?> list) {
            List<Object> out = new ArrayList<>(list.size());
            for (Object item : list) {
                out.add(normalize(item));
            }
            return out;
        }
        return value;
    }
}
