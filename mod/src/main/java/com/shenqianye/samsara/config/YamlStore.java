package com.shenqianye.samsara.config;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 单个 samsara 配置目录的 YAML 读写。
 *
 * <h3>规则（F-121/F-122）</h3>
 * <ul>
 *   <li>首次启动写出<b>全部键 + 注释</b>；缺键按默认值运行；</li>
 *   <li>非法值回退默认并记录 WARN，不阻止启动；</li>
 *   <li>保存先写临时文件，再原子替换，并保留 {@code *.yml.bak}；</li>
 *   <li>发现旧 {@code *.json} 时读取 → 写 YAML → 旧文件改名 {@code .json.legacy}，
 *       不静默删除。</li>
 * </ul>
 *
 * <p>本类不依赖 Fabric/Minecraft，便于单测；配置目录由 {@code StoragePaths} 注入。
 */
public final class YamlStore {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");

    private final Path dir;
    /** 旧版配置目录（迁移时读取 JSON；可为 null） */
    private final Path legacyDir;
    private final Map<String, Map<String, Object>> values = new LinkedHashMap<>();
    private final List<String> warnings = new ArrayList<>();

    public YamlStore(Path dir) {
        this(dir, null);
    }

    public YamlStore(Path dir, Path legacyDir) {
        this.dir = dir.toAbsolutePath().normalize();
        this.legacyDir = legacyDir == null ? null : legacyDir.toAbsolutePath().normalize();
    }

    public Path dir() {
        return dir;
    }

    public Path file(String id) {
        return dir.resolve(ConfigSchema.fileName(id) + ".yml");
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }

    public void clearWarnings() {
        warnings.clear();
    }

    /** 读取并规范化一个文件；必要时生成完整默认文件。 */
    public synchronized Map<String, Object> load(String id) {
        if (!ConfigSchema.hasFile(id)) {
            throw new IllegalArgumentException("未知配置文件: " + id);
        }
        FilesDirectories.ensure(dir);
        Path file = file(id);
        Map<String, Object> loaded = new LinkedHashMap<>();
        boolean rewrite = false;

        if (!Files.exists(file)) {
            Map<String, Object> legacy = readLegacyJson(id);
            loaded.putAll(legacy);
            rewrite = true;
        } else {
            Map<String, Object> root = readYaml(file);
            loaded.putAll(flatten(id, root));
        }

        Map<String, Object> normalized = new LinkedHashMap<>();
        for (ConfigEntry entry : ConfigSchema.entries(id)) {
            Object raw = loaded.get(entry.path());
            if (raw == null && !loaded.containsKey(entry.path())) {
                normalized.put(entry.path(), ConfigSchema.deepCopy(entry.defaultValue()));
                continue;
            }
            try {
                Object rawValue = raw;
                if (entry.type() == ConfigType.LEVEL_MAP && raw instanceof Map<?, ?> map) {
                    // 权限映射：默认值 + 文件覆盖，保证“文件里没写的默认规则”也能在
                    // Web 与首次生成里看到；旧 JSON 只迁了用户改过的键时尤其重要。
                    Map<String, Object> merged = new LinkedHashMap<>();
                    if (entry.defaultValue() instanceof Map<?, ?> defaults) {
                        defaults.forEach((k, v) -> merged.put(String.valueOf(k), v));
                    }
                    map.forEach((k, v) -> merged.put(String.valueOf(k), v));
                    rawValue = merged;
                    if (merged.size() > map.size()) {
                        // 文件里缺少默认规则：补齐后重写，保持“全量可见”。
                        rewrite = true;
                    }
                }
                Object value = ConfigSchema.coerceForLoad(id, entry.path(), rawValue);
                normalized.put(entry.path(), value);
            } catch (ConfigValidationException e) {
                warnings.add(id + ".yml 的 " + entry.path() + " 非法（" + e.getMessage()
                        + "），已回退默认");
                normalized.put(entry.path(), ConfigSchema.deepCopy(entry.defaultValue()));
                rewrite = true;
            }
        }
        // 文件里缺键也补齐：让“文件里没有但实际可配”的键重新可见。
        for (ConfigEntry entry : ConfigSchema.entries(id)) {
            if (!loaded.containsKey(entry.path())) {
                rewrite = true;
            }
        }
        for (String key : loaded.keySet()) {
            if (ConfigSchema.entry(id, key) == null) {
                warnings.add(id + ".yml 含未知配置项 " + key + "（已忽略）");
            }
        }

        values.put(id, normalized);
        if (rewrite || !Files.exists(file)) {
            writeFull(id, normalized);
        }
        return new LinkedHashMap<>(normalized);
    }

    /** 当前内存值（未加载时先加载）。 */
    public synchronized Map<String, Object> values(String id) {
        Map<String, Object> current = values.get(id);
        if (current == null) {
            return load(id);
        }
        return new LinkedHashMap<>(current);
    }

    public synchronized Object value(String id, String path) {
        return values(id).get(path);
    }

    /**
     * 保存字段更新（可只传变更的键）。先全量校验，任一项非法都不落盘。
     */
    public synchronized SaveResult save(String id, Map<String, Object> updates) {
        if (!ConfigSchema.hasFile(id)) {
            return new SaveResult(false, Map.of("_", "未知配置文件：" + id));
        }
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> next = values(id);
        if (updates != null) {
            for (Map.Entry<String, Object> e : updates.entrySet()) {
                try {
                    next.put(e.getKey(), ConfigSchema.validate(id, e.getKey(), e.getValue()));
                } catch (ConfigValidationException ex) {
                    errors.put(ex.path(), ex.getMessage());
                }
            }
        }
        if (!errors.isEmpty()) {
            return new SaveResult(false, errors);
        }
        try {
            writeFull(id, next);
            values.put(id, next);
            return new SaveResult(true, Map.of());
        } catch (RuntimeException e) {
            return new SaveResult(false, Map.of("_", "写入失败：" + e.getMessage()));
        }
    }

    /** 重读文件（丢弃内存值）。 */
    public synchronized Map<String, Object> reload(String id) {
        values.remove(id);
        return load(id);
    }

    // ---- YAML 读取 ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml(Path file) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(20);
        options.setCodePointLimit(1_000_000);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = yaml.load(reader);
            if (root == null) {
                return new LinkedHashMap<>();
            }
            if (!(root instanceof Map<?, ?> map)) {
                warnings.add(file.getFileName() + " 顶层不是映射，已使用默认值");
                return new LinkedHashMap<>();
            }
            return (Map<String, Object>) map;
        } catch (Exception e) {
            warnings.add(file.getFileName() + " 解析失败（" + e.getMessage() + "），已使用默认值");
            return new LinkedHashMap<>();
        }
    }

    /** 把嵌套 YAML 展平成 schema 路径；LEVEL_MAP/STRING_LIST 保持整体。 */
    private static Map<String, Object> flatten(String id, Map<String, Object> root) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigEntry entry : ConfigSchema.entries(id)) {
            Object value = navigate(root, entry.path());
            if (value != null) {
                out.put(entry.path(), value);
            }
        }
        return out;
    }

    private static Object navigate(Map<String, Object> root, String path) {
        Object current = root;
        for (String part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) {
                return null;
            }
            current = map.get(part);
        }
        return current;
    }

    // ---- 旧 JSON 迁移 ----

    private Map<String, Object> readLegacyJson(String id) {
        Path source = legacyJson(id);
        if (source == null) {
            return new LinkedHashMap<>();
        }
        try {
            JsonElement element = JsonParser.parseString(Files.readString(source, StandardCharsets.UTF_8));
            if (!element.isJsonObject()) {
                warnings.add(source.getFileName() + " 顶层不是对象，已忽略");
                return new LinkedHashMap<>();
            }
            Map<String, Object> flat = flattenJson(id, element.getAsJsonObject(), "");
            warnings.add("已从旧配置 " + source.getFileName() + " 迁移到 "
                    + ConfigSchema.fileName(id) + ".yml（原文件保留为 .json.legacy）");
            return flat;
        } catch (Exception e) {
            warnings.add(source.getFileName() + " 读取失败（" + e.getMessage() + "），已使用默认值");
            return new LinkedHashMap<>();
        }
    }

    private Path legacyJson(String id) {
        String name = ConfigSchema.fileName(id) + ".json";
        if (legacyDir != null) {
            Path candidate = legacyDir.resolve(name);
            if (Files.exists(candidate)) {
                return candidate;
            }
        }
        Path candidate = dir.resolve(name);
        return Files.exists(candidate) ? candidate : null;
    }

    /** 把 JSON 中 schema 认识的路径展平；LEVEL_MAP 保留整个对象。 */
    private static Map<String, Object> flattenJson(String id, JsonObject root, String prefix) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigEntry entry : ConfigSchema.entries(id)) {
            if (!entry.path().startsWith(prefix)) {
                continue;
            }
            String rest = prefix.isEmpty() ? entry.path()
                    : entry.path().substring(prefix.length() + 1);
            if (entry.type() == ConfigType.LEVEL_MAP || entry.type() == ConfigType.STRING_LIST) {
                JsonElement element = findJson(root, entry.path());
                if (element != null) {
                    out.put(entry.path(), jsonToJava(element));
                }
                continue;
            }
            if (rest.contains(".")) {
                String head = rest.substring(0, rest.indexOf('.'));
                JsonElement child = root.get(head);
                if (child != null && child.isJsonObject()) {
                    out.putAll(flattenJson(id, child.getAsJsonObject(),
                            prefix.isEmpty() ? head : prefix + "." + head));
                }
                continue;
            }
            JsonElement element = root.get(rest);
            if (element != null) {
                out.put(entry.path(), jsonToJava(element));
            }
        }
        return out;
    }

    private static JsonElement findJson(JsonObject root, String path) {
        JsonElement current = root;
        for (String part : path.split("\\.")) {
            if (current == null || !current.isJsonObject()) {
                return null;
            }
            current = current.getAsJsonObject().get(part);
        }
        return current;
    }

    private static Object jsonToJava(JsonElement element) {
        if (element == null || element.isJsonNull()) {
            return null;
        }
        if (element.isJsonPrimitive()) {
            var primitive = element.getAsJsonPrimitive();
            if (primitive.isBoolean()) {
                return primitive.getAsBoolean();
            }
            if (primitive.isNumber()) {
                return primitive.getAsNumber();
            }
            return primitive.getAsString();
        }
        if (element.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement item : element.getAsJsonArray()) {
                list.add(jsonToJava(item));
            }
            return list;
        }
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<String, JsonElement> e : element.getAsJsonObject().entrySet()) {
            map.put(e.getKey(), jsonToJava(e.getValue()));
        }
        return map;
    }

    private void markLegacyJsonConsumed(String id) {
        Path source = legacyJson(id);
        if (source == null) {
            return;
        }
        try {
            Path target = source.resolveSibling(source.getFileName() + ".legacy");
            if (Files.exists(target)) {
                target = source.resolveSibling(source.getFileName() + "."
                        + LocalDateTime.now().format(TS) + ".legacy");
            }
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            warnings.add("旧配置 " + source.getFileName() + " 改名失败：" + e.getMessage());
        }
    }

    // ---- YAML 写入 ----

    private void writeFull(String id, Map<String, Object> values) {
        Path file = file(id);
        FilesDirectories.ensure(dir);
        String text = render(id, values);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.writeString(tmp, text, StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            markLegacyJsonConsumed(id);
        } catch (IOException e) {
            try {
                Files.deleteIfExists(tmp);
            } catch (IOException ignored) {
            }
            throw new RuntimeException(e);
        }
    }

    /** 生成带注释的完整 YAML。 */
    static String render(String id, Map<String, Object> values) {
        StringBuilder out = new StringBuilder();
        out.append("# ").append(ConfigSchema.fileName(id)).append(".yml · samsara 配置\n");
        out.append("# 可手工编辑；保存请走本地 Web 面板或 /samsara debug reload。\n");
        out.append("# 非法值会在加载时回退默认并记录 WARN。\n\n");
        Node root = new Node();
        for (ConfigEntry entry : ConfigSchema.entries(id)) {
            Object value = values.get(entry.path());
            if (value == null) {
                value = entry.defaultValue();
            }
            insert(root, entry, value, entry.path().split("\\."), 0);
        }
        String group = null;
        for (Node child : root.children.values()) {
            if (child.group != null && !child.group.equals(group)) {
                group = child.group;
                out.append("# ── ").append(group).append(" ──\n");
            }
            renderNode(out, child, 0);
        }
        return out.toString();
    }

    private static void insert(Node root, ConfigEntry entry, Object value, String[] parts, int index) {
        Node node = root.children.computeIfAbsent(parts[index], k -> new Node());
        node.key = parts[index];
        if (node.group == null) {
            node.group = entry.group();
        }
        if (index == parts.length - 1) {
            node.entry = entry;
            node.value = value;
        } else {
            insert(node, entry, value, parts, index + 1);
        }
    }

    private static void renderNode(StringBuilder out, Node node, int indent) {
        appendIndent(out, indent);
        out.append(quoteKey(node.key)).append(":");
        if (node.entry == null) {
            out.append("\n");
            for (Node child : node.children.values()) {
                renderNode(out, child, indent + 2);
            }
            return;
        }
        appendValue(out, node.entry, node.value, indent);
    }

    private static void appendValue(StringBuilder out, ConfigEntry entry, Object value, int indent) {
        if (entry.type() == ConfigType.LEVEL_MAP || entry.type() == ConfigType.STRING_LIST) {
            out.append("\n");
            appendComment(out, entry.description(), indent + 2);
            if (entry.type() == ConfigType.LEVEL_MAP) {
                Map<?, ?> map = value instanceof Map<?, ?> m ? m : Map.of();
                if (map.isEmpty()) {
                    appendIndent(out, indent + 2);
                    out.append("{}\n");
                }
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    appendIndent(out, indent + 2);
                    out.append(quoteKey(String.valueOf(e.getKey()))).append(": ")
                            .append(scalar(e.getValue())).append("\n");
                }
            } else {
                List<?> list = value instanceof List<?> l ? l : List.of();
                if (list.isEmpty()) {
                    appendIndent(out, indent + 2);
                    out.append("[]\n");
                }
                for (Object item : list) {
                    appendIndent(out, indent + 2);
                    out.append("- ").append(scalar(item)).append("\n");
                }
            }
            return;
        }
        out.append(" ").append(scalar(value));
        out.append("  # ").append(oneLine(entry.description())).append("\n");
    }

    /** 简化树节点。 */
    private static final class Node {
        String key;
        String group;
        ConfigEntry entry;
        Object value;
        final Map<String, Node> children = new LinkedHashMap<>();
    }

    private static void appendComment(StringBuilder out, String text, int indent) {
        if (text == null || text.isBlank()) {
            return;
        }
        appendIndent(out, indent);
        out.append("# ").append(oneLine(text)).append("\n");
    }

    private static void appendIndent(StringBuilder out, int indent) {
        out.append(" ".repeat(Math.max(0, indent)));
    }

    private static String quoteKey(String key) {
        return needsQuote(key) ? "'" + key.replace("'", "''") + "'" : key;
    }

    private static String scalar(Object value) {
        if (value instanceof Boolean || value instanceof Number) {
            return String.valueOf(value);
        }
        String text = value == null ? "" : String.valueOf(value);
        return needsQuote(text) ? "'" + text.replace("'", "''") + "'" : text;
    }

    private static boolean needsQuote(String text) {
        if (text == null || text.isEmpty()) {
            return true;
        }
        if (text.equals("true") || text.equals("false") || text.equals("null")
                || text.equals("~")) {
            return true;
        }
        if (Character.isDigit(text.charAt(0)) || text.charAt(0) == '-') {
            return true;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (":#{}[],&*?|<>=!%@\"'\\`".indexOf(c) >= 0
                    || Character.isWhitespace(c) || c < 0x20) {
                return true;
            }
        }
        return false;
    }

    private static String oneLine(String text) {
        return text == null ? "" : text.replace('\n', ' ').replace('\r', ' ').trim();
    }

    /** 保存结果。 */
    public record SaveResult(boolean ok, Map<String, String> errors) {
    }

    private static final class FilesDirectories {
        static void ensure(Path dir) {
            try {
                Files.createDirectories(dir);
            } catch (IOException e) {
                throw new RuntimeException("无法创建配置目录 " + dir + "：" + e.getMessage(), e);
            }
        }
    }
}
