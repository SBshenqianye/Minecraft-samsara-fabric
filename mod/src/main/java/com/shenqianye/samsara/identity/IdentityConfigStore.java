package com.shenqianye.samsara.identity;

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
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code config/samsara/identity.yml} 的读写。
 *
 * <p>与普通扁平配置不同，身份组是嵌套结构，因此不复用 {@code YamlStore}，
 * 但保持同一套规则：首次生成完整默认文件、非法值不落盘、临时文件 + 原子替换 + {@code .bak}。
 */
public final class IdentityConfigStore {
    private static final int MAX_ALIASES = 20;
    private static final int MAX_CODE_POINTS = 1_000_000;

    private final Path file;
    private IdentityConfig config = new IdentityConfig();
    private List<IdentityValidator.Issue> issues = List.of();
    private String loadWarning = "";

    public IdentityConfigStore(Path file) {
        this.file = file.toAbsolutePath().normalize();
    }

    public Path file() {
        return file;
    }

    public synchronized IdentityConfig config() {
        return config;
    }

    public synchronized List<IdentityValidator.Issue> issues() {
        return List.copyOf(issues);
    }

    public synchronized boolean valid() {
        return issues.isEmpty();
    }

    public synchronized String loadWarning() {
        return loadWarning;
    }

    /** 读取；文件不存在时生成默认文件。 */
    public synchronized IdentityConfig load() {
        loadWarning = "";
        if (!Files.exists(file)) {
            config = new IdentityConfig();
            issues = IdentityValidator.validate(config);
            try {
                write(config);
            } catch (RuntimeException e) {
                loadWarning = "identity.yml 默认文件生成失败：" + e.getMessage();
                issues = List.of(new IdentityValidator.Issue("identity", loadWarning));
            }
            return config;
        }
        try {
            Map<String, Object> root = readYaml();
            IdentityConfig parsed = parse(root);
            List<IdentityValidator.Issue> found = IdentityValidator.validate(parsed);
            config = parsed;
            issues = List.copyOf(found);
        } catch (RuntimeException | IOException e) {
            config = new IdentityConfig();
            String message = "identity.yml 读取失败：" + e.getMessage();
            issues = List.of(new IdentityValidator.Issue("identity", message));
            loadWarning = message;
        }
        return config;
    }

    public synchronized SaveResult save(IdentityConfig next) {
        List<IdentityValidator.Issue> found = IdentityValidator.validate(next);
        if (!found.isEmpty()) {
            Map<String, String> errors = new LinkedHashMap<>();
            for (IdentityValidator.Issue issue : found) {
                errors.putIfAbsent(issue.field(), issue.message());
            }
            return new SaveResult(false, errors, List.copyOf(found));
        }
        try {
            write(next);
            config = next.copy();
            issues = List.of();
            loadWarning = "";
            return new SaveResult(true, Map.of(), List.of());
        } catch (RuntimeException e) {
            return new SaveResult(false, Map.of("_", "写入失败：" + e.getMessage()),
                    List.of(new IdentityValidator.Issue("_", e.getMessage())));
        }
    }

    public synchronized SaveResult reload() {
        load();
        if (issues.isEmpty()) {
            return new SaveResult(true, Map.of(), List.of());
        }
        Map<String, String> errors = new LinkedHashMap<>();
        for (IdentityValidator.Issue issue : issues) {
            errors.putIfAbsent(issue.field(), issue.message());
        }
        return new SaveResult(false, errors, issues);
    }

    // ---- YAML ----

    @SuppressWarnings("unchecked")
    private Map<String, Object> readYaml() throws IOException {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(MAX_ALIASES);
        options.setCodePointLimit(MAX_CODE_POINTS);
        Yaml yaml = new Yaml(new SafeConstructor(options));
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            Object root = yaml.load(reader);
            if (root == null) {
                return new LinkedHashMap<>();
            }
            if (!(root instanceof Map<?, ?> map)) {
                throw new IOException("顶层不是映射");
            }
            return (Map<String, Object>) map;
        }
    }

    private static IdentityConfig parse(Map<String, Object> root) {
        IdentityConfig config = new IdentityConfig();
        Object identity = root.get("identity");
        if (identity instanceof Map<?, ?> map) {
            Object enabled = map.get("enabled");
            if (enabled != null) {
                config.enabled = Boolean.parseBoolean(String.valueOf(enabled));
            }
            Object sync = map.get("syncPlayerData");
            if (sync != null) {
                config.syncPlayerData = Boolean.parseBoolean(String.valueOf(sync));
            }
        }
        Object pets = root.get("pets");
        if (pets instanceof Map<?, ?> map) {
            Object max = map.get("maxOwners");
            if (max instanceof Number number) {
                config.maxOwners = number.intValue();
            } else if (max != null) {
                try {
                    config.maxOwners = Integer.parseInt(String.valueOf(max).trim());
                } catch (NumberFormatException ignored) {
                    config.maxOwners = -1;
                }
            }
        }
        Object groups = root.get("groups");
        if (groups instanceof List<?> list) {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    config.groups.add(null);
                    continue;
                }
                IdentityConfig.Group group = new IdentityConfig.Group();
                group.name = text(map.get("name"));
                group.data = text(map.get("data"));
                group.premium = nullableText(map.get("premium"));
                group.offline = nullableText(map.get("offline"));
                group.note = text(map.get("note"));
                config.groups.add(group);
            }
        }
        return config;
    }

    private static String text(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private static String nullableText(Object value) {
        if (value == null) {
            return null;
        }
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }

    // ---- 写 ----

    private void write(IdentityConfig config) {
        try {
            Files.createDirectories(file.getParent());
            String text = render(config);
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
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
        } catch (IOException e) {
            throw new RuntimeException("identity.yml 写入失败：" + e.getMessage(), e);
        }
    }

    /** 生成带注释的完整 YAML；空组时也保留 groups: []。 */
    static String render(IdentityConfig config) {
        StringBuilder out = new StringBuilder();
        out.append("# identity.yml · samsara 身份组（v0.20）\n");
        out.append("# 身份组只做“多个账号入口 → 同一个 data UUID”的映射，不提供注册/登录/密码。\n");
        out.append("# 身份安全请交给其他认证模组。修改后可在 Web 身份组页面保存，或 /samsara debug identity reload。\n\n");
        out.append("identity:\n");
        out.append("  enabled: ").append(config.enabled)
                .append("          # 总开关，默认关闭；关闭时不解析、不同步、不显示身份提示\n");
        out.append("  syncPlayerData: ").append(config.syncPlayerData)
                .append("    # 账号切换时同步玩家数据（同账号重连不同步）\n\n");
        out.append("pets:\n");
        out.append("  maxOwners: ").append(config.maxOwners)
                .append("           # 单只宠物最多主人数量\n\n");
        if (config.groups == null || config.groups.isEmpty()) {
            out.append("groups: []\n");
            return out.toString();
        }
        out.append("groups:\n");
        for (IdentityConfig.Group group : config.groups) {
            if (group == null) {
                continue;
            }
            out.append("  - name: ").append(scalar(group.name)).append("\n");
            out.append("    data: ").append(scalar(group.data)).append("\n");
            out.append("    premium: ").append(group.premium == null ? "null" : scalar(group.premium))
                    .append("\n");
            out.append("    offline: ").append(group.offline == null ? "null" : scalar(group.offline))
                    .append("\n");
            if (group.note != null && !group.note.isBlank()) {
                out.append("    note: ").append(scalar(group.note)).append("\n");
            }
        }
        return out.toString();
    }

    private static String scalar(String text) {
        String value = text == null ? "" : text;
        boolean quote = value.isEmpty() || value.equalsIgnoreCase("null")
                || value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false")
                || !value.equals(value.trim());
        if (!quote) {
            for (int i = 0; i < value.length(); i++) {
                char c = value.charAt(i);
                if (":#{}[],&*?|<>=!%@\"'\\`".indexOf(c) >= 0
                        || Character.isWhitespace(c) || c < 0x20) {
                    quote = true;
                    break;
                }
            }
        }
        return quote ? "'" + value.replace("'", "''") + "'" : value;
    }

    /** 保存结果：ok=false 时 errors 为字段级错误。 */
    public record SaveResult(boolean ok, Map<String, String> errors,
                             List<IdentityValidator.Issue> issues) {
        public static SaveResult success() {
            return new SaveResult(true, Map.of(), List.of());
        }
    }

    /** 纯工具：把模型转换成 Web JSON 友好结构。 */
    public static Map<String, Object> toModel(IdentityConfig config) {
        Map<String, Object> model = new LinkedHashMap<>();
        model.put("enabled", config.enabled);
        model.put("syncPlayerData", config.syncPlayerData);
        model.put("maxOwners", config.maxOwners);
        List<Map<String, Object>> groups = new ArrayList<>();
        for (IdentityConfig.Group group : config.groups) {
            if (group == null) {
                continue;
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("name", group.name);
            item.put("data", group.data);
            item.put("premium", group.premium);
            item.put("offline", group.offline);
            item.put("note", group.note);
            groups.add(item);
        }
        model.put("groups", groups);
        return model;
    }
}
