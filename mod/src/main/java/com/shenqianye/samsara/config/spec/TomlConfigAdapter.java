package com.shenqianye.samsara.config.spec;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 第三方 TOML 适配器（零依赖子集实现）。
 *
 * <p>支持：注释、裸键/引号键、点分键、基本/字面字符串、布尔、整数、浮点、
 * 数组、内联表、标准表与数组表。写入时保留未知键与插入顺序，注释不保留。
 * 复杂到无法解析的文件会在页面上标记只读，不会覆盖原文件。
 */
public final class TomlConfigAdapter implements ConfigAdapter {
    @Override
    public ConfigFormat format() {
        return ConfigFormat.TOML;
    }

    @Override
    public boolean nestedPaths() {
        return true;
    }

    @Override
    public Map<String, Object> read(Path file) throws IOException {
        try {
            return TomlCodec.parse(ConfigFileIO.read(file));
        } catch (RuntimeException e) {
            throw new IOException("TOML 解析失败：" + e.getMessage(), e);
        }
    }

    @Override
    public void write(Path file, Map<String, Object> updates) throws IOException {
        Map<String, Object> document = read(file);
        for (Map.Entry<String, Object> e : updates.entrySet()) {
            ConfigPaths.set(document, e.getKey(), e.getValue());
        }
        ConfigFileIO.atomicWrite(file, TomlCodec.write(document));
    }

    /** 供单测使用的最小 TOML 编解码器。 */
    public static final class TomlCodec {
        private TomlCodec() {
        }

        public static Map<String, Object> parse(String text) {
            Map<String, Object> root = new LinkedHashMap<>();
            Map<String, Object> current = root;
            String[] lines = text == null ? new String[0] : text.split("\\R", -1);
            for (int i = 0; i < lines.length; i++) {
                String line = stripComment(lines[i]).trim();
                if (line.isEmpty()) {
                    continue;
                }
                if (line.startsWith("[[")) {
                    int end = line.indexOf("]]");
                    if (end < 0) {
                        throw new IllegalArgumentException("数组表缺少 ]]：" + line);
                    }
                    String path = line.substring(2, end).trim();
                    current = appendArrayTable(root, parseKeyPath(path));
                    continue;
                }
                if (line.startsWith("[")) {
                    int end = line.indexOf(']');
                    if (end < 0) {
                        throw new IllegalArgumentException("表缺少 ]：" + line);
                    }
                    String path = line.substring(1, end).trim();
                    current = getOrCreateTable(root, parseKeyPath(path));
                    continue;
                }
                int eq = indexOfTopLevel(line, '=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("不是 key = value：" + line);
                }
                String keyText = line.substring(0, eq).trim();
                String valueText = line.substring(eq + 1).trim();
                // 多行数组/内联表：继续拼接直到括号闭合。
                while (!balanced(valueText) && i + 1 < lines.length) {
                    i++;
                    valueText += "\n" + stripComment(lines[i]).trim();
                }
                setNested(current, parseKeyPath(keyText), parseValue(valueText));
            }
            return root;
        }

        public static String write(Map<String, Object> root) {
            StringBuilder sb = new StringBuilder();
            writeTable(root, "", sb, false);
            if (sb.length() == 0 || sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            return sb.toString();
        }

        @SuppressWarnings("unchecked")
        private static void writeTable(Map<String, Object> table, String prefix,
                                       StringBuilder sb, boolean headerWritten) {
            for (Map.Entry<String, Object> e : table.entrySet()) {
                Object value = e.getValue();
                if (value instanceof Map<?, ?>) {
                    continue;
                }
                if (value instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?>) {
                    continue;
                }
                sb.append(prefix.isEmpty() ? e.getKey() : e.getKey()).append(" = ")
                        .append(formatValue(value)).append('\n');
            }
            for (Map.Entry<String, Object> e : table.entrySet()) {
                String path = prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey();
                Object value = e.getValue();
                if (value instanceof Map<?, ?> map) {
                    sb.append('\n').append('[').append(path).append("]\n");
                    writeTable((Map<String, Object>) map, path, sb, true);
                } else if (value instanceof List<?> list && !list.isEmpty()
                        && list.get(0) instanceof Map<?, ?>) {
                    for (Object item : list) {
                        sb.append('\n').append("[[").append(path).append("]]\n");
                        writeTable((Map<String, Object>) item, path, sb, true);
                    }
                }
            }
        }

        private static String formatValue(Object value) {
            if (value == null) {
                return "\"\"";
            }
            if (value instanceof Boolean || value instanceof Number) {
                return String.valueOf(value);
            }
            if (value instanceof Map<?, ?> map) {
                StringBuilder sb = new StringBuilder("{");
                boolean first = true;
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(quoteKey(String.valueOf(e.getKey()))).append(" = ")
                            .append(formatValue(e.getValue()));
                }
                return sb.append('}').toString();
            }
            if (value instanceof Iterable<?> list) {
                StringBuilder sb = new StringBuilder("[");
                boolean first = true;
                for (Object item : list) {
                    if (!first) {
                        sb.append(", ");
                    }
                    first = false;
                    sb.append(formatValue(item));
                }
                return sb.append(']').toString();
            }
            return "\"" + escape(String.valueOf(value)) + "\"";
        }

        private static String quoteKey(String key) {
            if (key.matches("[A-Za-z0-9_-]+")) {
                return key;
            }
            return "\"" + escape(key) + "\"";
        }

        private static Object parseValue(String text) {
            String value = text.trim();
            if (value.isEmpty()) {
                return "";
            }
            if (value.startsWith("\"\"\"")) {
                int end = value.lastIndexOf("\"\"\"");
                String body = end > 3 ? value.substring(3, end) : value.substring(3);
                return unescape(body);
            }
            if (value.startsWith("'''")) {
                int end = value.lastIndexOf("'''");
                return end > 3 ? value.substring(3, end) : value.substring(3);
            }
            if (value.startsWith("\"")) {
                return unescape(value.substring(1, closeQuote(value, '"')));
            }
            if (value.startsWith("'")) {
                return value.substring(1, closeQuote(value, '\''));
            }
            if (value.equals("true")) {
                return true;
            }
            if (value.equals("false")) {
                return false;
            }
            if (value.startsWith("[")) {
                return parseArray(value.substring(1, value.lastIndexOf(']')));
            }
            if (value.startsWith("{")) {
                return parseInlineTable(value.substring(1, value.lastIndexOf('}')));
            }
            String number = value.replace("_", "");
            try {
                if (number.contains(".") || number.contains("e") || number.contains("E")) {
                    return Double.parseDouble(number);
                }
                return Long.parseLong(number);
            } catch (NumberFormatException ignored) {
                return value;
            }
        }

        private static List<Object> parseArray(String body) {
            List<Object> out = new ArrayList<>();
            for (String item : splitTopLevel(body, ',')) {
                if (!item.trim().isEmpty()) {
                    out.add(parseValue(item));
                }
            }
            return out;
        }

        private static Map<String, Object> parseInlineTable(String body) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (String item : splitTopLevel(body, ',')) {
                if (item.trim().isEmpty()) {
                    continue;
                }
                int eq = indexOfTopLevel(item, '=');
                if (eq <= 0) {
                    throw new IllegalArgumentException("内联表项缺少 =：" + item);
                }
                setNested(out, parseKeyPath(item.substring(0, eq).trim()),
                        parseValue(item.substring(eq + 1)));
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        private static void setNested(Map<String, Object> root, List<String> path, Object value) {
            Map<String, Object> cursor = root;
            for (int i = 0; i < path.size() - 1; i++) {
                Object next = cursor.get(path.get(i));
                if (!(next instanceof Map<?, ?>)) {
                    next = new LinkedHashMap<String, Object>();
                    cursor.put(path.get(i), next);
                }
                cursor = (Map<String, Object>) next;
            }
            cursor.put(path.get(path.size() - 1), value);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> getOrCreateTable(Map<String, Object> root, List<String> path) {
            Map<String, Object> cursor = root;
            for (String part : path) {
                Object next = cursor.get(part);
                if (next instanceof List<?> list && !list.isEmpty()) {
                    next = list.get(list.size() - 1);
                }
                if (!(next instanceof Map<?, ?>)) {
                    next = new LinkedHashMap<String, Object>();
                    cursor.put(part, next);
                }
                cursor = (Map<String, Object>) next;
            }
            return cursor;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> appendArrayTable(Map<String, Object> root, List<String> path) {
            Map<String, Object> cursor = root;
            for (int i = 0; i < path.size() - 1; i++) {
                Object next = cursor.get(path.get(i));
                if (next instanceof List<?> list && !list.isEmpty()) {
                    next = list.get(list.size() - 1);
                }
                if (!(next instanceof Map<?, ?>)) {
                    next = new LinkedHashMap<String, Object>();
                    cursor.put(path.get(i), next);
                }
                cursor = (Map<String, Object>) next;
            }
            String last = path.get(path.size() - 1);
            Object existing = cursor.get(last);
            List<Object> list;
            if (existing instanceof List<?> raw) {
                list = (List<Object>) raw;
            } else {
                list = new ArrayList<>();
                cursor.put(last, list);
            }
            Map<String, Object> table = new LinkedHashMap<>();
            list.add(table);
            return table;
        }

        private static List<String> parseKeyPath(String text) {
            List<String> parts = new ArrayList<>();
            for (String part : splitTopLevel(text, '.')) {
                String key = part.trim();
                if (key.startsWith("\"") && key.endsWith("\"") && key.length() >= 2) {
                    key = unescape(key.substring(1, key.length() - 1));
                } else if (key.startsWith("'") && key.endsWith("'") && key.length() >= 2) {
                    key = key.substring(1, key.length() - 1);
                }
                if (!key.isEmpty()) {
                    parts.add(key);
                }
            }
            if (parts.isEmpty()) {
                throw new IllegalArgumentException("空键：" + text);
            }
            return parts;
        }

        private static List<String> splitTopLevel(String text, char delimiter) {
            List<String> out = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            int square = 0;
            int curly = 0;
            boolean single = false;
            boolean doubled = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'' && !doubled) {
                    single = !single;
                } else if (c == '"' && !single) {
                    doubled = !doubled;
                } else if (!single && !doubled) {
                    if (c == '[') square++;
                    if (c == ']') square--;
                    if (c == '{') curly++;
                    if (c == '}') curly--;
                    if (c == delimiter && square == 0 && curly == 0) {
                        out.add(current.toString());
                        current.setLength(0);
                        continue;
                    }
                }
                current.append(c);
            }
            out.add(current.toString());
            return out;
        }

        private static int indexOfTopLevel(String text, char target) {
            int square = 0;
            int curly = 0;
            boolean single = false;
            boolean doubled = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'' && !doubled) {
                    single = !single;
                } else if (c == '"' && !single) {
                    doubled = !doubled;
                } else if (!single && !doubled) {
                    if (c == '[') square++;
                    if (c == ']') square--;
                    if (c == '{') curly++;
                    if (c == '}') curly--;
                    if (c == target && square == 0 && curly == 0) {
                        return i;
                    }
                }
            }
            return -1;
        }

        private static boolean balanced(String text) {
            int square = 0;
            int curly = 0;
            boolean single = false;
            boolean doubled = false;
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == '\'' && !doubled) {
                    single = !single;
                } else if (c == '"' && !single) {
                    doubled = !doubled;
                } else if (!single && !doubled) {
                    if (c == '[') square++;
                    if (c == ']') square--;
                    if (c == '{') curly++;
                    if (c == '}') curly--;
                }
            }
            return square <= 0 && curly <= 0;
        }

        private static int closeQuote(String text, char quote) {
            boolean escaped = false;
            for (int i = 1; i < text.length(); i++) {
                char c = text.charAt(i);
                if (quote == '"' && c == '\\' && !escaped) {
                    escaped = true;
                    continue;
                }
                if (c == quote && !escaped) {
                    return i;
                }
                escaped = false;
            }
            throw new IllegalArgumentException("字符串未闭合");
        }

        private static String stripComment(String line) {
            boolean single = false;
            boolean doubled = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (c == '\'' && !doubled) {
                    single = !single;
                } else if (c == '"' && !single) {
                    doubled = !doubled;
                } else if (!single && !doubled && c == '#') {
                    return line.substring(0, i);
                }
            }
            return line;
        }

        private static String escape(String value) {
            return value.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
        }

        private static String unescape(String value) {
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (c != '\\' || i + 1 >= value.length()) {
                    sb.append(c);
                    continue;
                }
                char next = value.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case '"' -> sb.append('"');
                    case '\\' -> sb.append('\\');
                    default -> sb.append(next);
                }
            }
            return sb.toString();
        }
    }
}
