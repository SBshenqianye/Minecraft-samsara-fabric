package com.shenqianye.samsara.config.spec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigEntry;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import com.shenqianye.samsara.config.ConfigType;
import com.shenqianye.samsara.config.ConfigValidationException;
import com.shenqianye.samsara.server.ServerConfigFiles;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Stream;

/**
 * PageSpec/FormSpec 配置编辑器后端。
 *
 * <p>覆盖三类来源：samsara 自有 YAML、服务器本体 6 个文件、第三方模组配置
 * （YAML/JSON/TOML/properties）。第三方默认“预览 + revision 校验 + 确认”后才写入，
 * 写前统一备份到 {@code backups/samsara/config-editor/<时间戳>/}。
 */
public final class ConfigEditorService {
    public static final String SAMSARA = "samsara";
    public static final String SERVER = "server";
    public static final String THIRD_PARTY = "third-party";

    private static final int MAX_THIRD_PARTY_FILES = 200;
    private static final long MAX_THIRD_PARTY_BYTES = 1024L * 1024L;
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private static final Map<String, String> TITLES = Map.of(
            ConfigSchema.COMMANDS, "命令开关",
            ConfigSchema.PERMISSIONS, "权限",
            ConfigSchema.CLAIM_PARTICLES, "圈地粒子",
            ConfigSchema.CLAIM_RANGE, "圈地范围",
            ConfigSchema.STORAGE, "存储",
            ConfigSchema.WEB, "Web",
            ConfigSchema.OUTPUT, "输出格式",
            ConfigSchema.LOTTERY, "经济业务",
            ConfigSchema.INSURANCE, "保险"
    );

    private static final Map<String, String> CUSTOM_WIDGETS = Map.of(
            ConfigSchema.PERMISSIONS, "permissions",
            ConfigSchema.OUTPUT, "output",
            ConfigSchema.CLAIM_RANGE, "claim-range"
    );

    private static final ConfigEditorService INSTANCE = new ConfigEditorService();

    private ConfigEditorService() {
    }

    public static ConfigEditorService getInstance() {
        return INSTANCE;
    }

    // ---------------------------------------------------------------- pages

    public Map<String, Object> pages(Path serverRoot) {
        List<PageSpec> pages = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        for (String id : ConfigSchema.fileIds()) {
            try {
                pages.add(buildSamsara(id).page());
            } catch (RuntimeException e) {
                warnings.add("读取 samsara 配置 " + id + " 失败：" + e.getMessage());
            }
        }
        for (ServerConfigFiles.Spec spec : ServerConfigFiles.SPECS) {
            try {
                pages.add(buildServer(serverRoot, spec.id()).page());
            } catch (RuntimeException e) {
                warnings.add("读取服务器配置 " + spec.id() + " 失败：" + e.getMessage());
            }
        }
        pages.addAll(discoverThirdParty(serverRoot, warnings));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("pages", pages);
        out.put("warnings", warnings);
        return out;
    }

    private Resolved buildSamsara(String id) {
        ConfigManager config = ConfigManager.getInstance();
        Map<String, Object> raw = config.snapshot(id);
        Map<String, Object> values = new LinkedHashMap<>();
        Map<String, Object> actual = new LinkedHashMap<>();
        List<FormFieldSpec> fields = new ArrayList<>();
        for (ConfigEntry entry : ConfigSchema.entries(id)) {
            Object value = raw.containsKey(entry.path()) ? raw.get(entry.path())
                    : ConfigSchema.deepCopy(entry.defaultValue());
            actual.put(entry.path(), value);
            boolean secret = ConfigSchema.isSensitive(id, entry.path());
            values.put(entry.path(), secret ? "" : ConfigSchema.deepCopy(value));
            fields.add(new FormFieldSpec(
                    entry.path(), entry.path(), fieldType(entry.type()),
                    ConfigSchema.deepCopy(entry.defaultValue()),
                    entry.min(), entry.max(), entry.type() == ConfigType.INT ? 1.0 : null,
                    entry.choices().stream().map(FormChoice::of).toList(),
                    entry.group(), entry.description(),
                    ConfigSchema.requiresRestart(id, entry.path()), entry.visibleWhen(),
                    secret, false, widget(entry.type()),
                    secret ? "已配置；留空表示不修改" : ""));
        }
        Path file = config.file(id);
        String custom = CUSTOM_WIDGETS.getOrDefault(id, "");
        boolean writable = custom.isEmpty();
        PageSpec page = new PageSpec(
                SAMSARA + ":" + id,
                TITLES.getOrDefault(id, id) + " · " + id + ".yml",
                id + ".yml",
                file.toAbsolutePath().normalize().toString(),
                ConfigFormat.YAML.id(), SAMSARA,
                ConfigFileIO.revision(file),
                writable, false,
                writable ? "" : "此页面使用专用编辑器，请在对应标签页修改。",
                custom,
                new FormSpec(id, groups(fields), fields),
                actions(writable, false),
                values);
        return new Resolved(page, file, id, null, null, actual,
                secretPaths(fields), true);
    }

    private Resolved buildServer(Path serverRoot, String id) {
        ServerConfigFiles.Spec spec = ServerConfigFiles.spec(id);
        Path file = serverRoot.resolve(spec.fileName());
        ServerConfigPageSupport.FieldValue read;
        try {
            read = ServerConfigPageSupport.read(serverRoot, spec);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        Map<String, Object> values = new LinkedHashMap<>(read.values());
        Set<String> secrets = secretPaths(read.fields());
        for (String secret : secrets) {
            values.put(secret, "");
        }
        PageSpec page = new PageSpec(
                SERVER + ":" + id,
                spec.fileName(),
                spec.fileName(),
                file.toAbsolutePath().normalize().toString(),
                serverFormat(spec),
                SERVER,
                ConfigFileIO.revision(file),
                true, true, "", "",
                new FormSpec(id, groups(read.fields()), read.fields()),
                actions(true, true),
                values);
        String current = "";
        try {
            current = ConfigFileIO.read(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
        return new Resolved(page, file, null, id, null, read.values(), secrets, true);
    }

    private PageSpec buildThirdParty(Path serverRoot, Path file, String relative) {
        ConfigAdapter adapter = ConfigAdapters.forFile(file);
        Map<String, Object> values = new LinkedHashMap<>();
        List<FormFieldSpec> fields = new ArrayList<>();
        String readOnlyReason = "";
        if (adapter == null) {
            readOnlyReason = "未知配置格式，仅只读";
        } else {
            try {
                Map<String, Object> flat = ConfigPaths.flatten(adapter.read(file));
                for (Map.Entry<String, Object> e : flat.entrySet()) {
                    boolean secret = isSecret(e.getKey());
                    fields.add(inferField(e.getKey(), e.getValue(), secret));
                    values.put(e.getKey(), secret ? "" : e.getValue());
                }
            } catch (IOException e) {
                readOnlyReason = "读取失败，仅只读：" + e.getMessage();
            }
        }
        boolean writable = readOnlyReason.isEmpty();
        String format = adapter == null ? ConfigFormat.UNKNOWN.id() : adapter.format().id();
        PageSpec page = new PageSpec(
                THIRD_PARTY + ":" + relative,
                relative,
                file.getFileName().toString(),
                file.toAbsolutePath().normalize().toString(),
                format, THIRD_PARTY,
                ConfigFileIO.revision(file),
                writable, writable, readOnlyReason, "",
                new FormSpec(relative, groups(fields), fields),
                actions(writable, true),
                values);
        return page;
    }

    private List<PageSpec> discoverThirdParty(Path serverRoot, List<String> warnings) {
        Path root = configRoot(serverRoot);
        if (!Files.isDirectory(root)) {
            return List.of();
        }
        List<String> relatives = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root, 2)) {
            stream.filter(Files::isRegularFile)
                    .filter(file -> !ignoredThirdParty(root, file))
                    .forEach(file -> relatives.add(root.relativize(file).toString().replace('\\', '/')));
        } catch (IOException e) {
            warnings.add("扫描第三方配置失败：" + e.getMessage());
            return List.of();
        }
        relatives.sort(String::compareTo);
        List<PageSpec> pages = new ArrayList<>();
        int count = 0;
        for (String relative : relatives) {
            if (count >= MAX_THIRD_PARTY_FILES) {
                warnings.add("第三方配置超过 " + MAX_THIRD_PARTY_FILES + " 个，已截断");
                break;
            }
            Path file = root.resolve(relative).normalize();
            if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                continue;
            }
            try {
                if (Files.size(file) > MAX_THIRD_PARTY_BYTES) {
                    warnings.add("跳过超过 1MB 的第三方配置：" + relative);
                    continue;
                }
                pages.add(buildThirdParty(serverRoot, file, relative));
                count++;
            } catch (IOException | RuntimeException e) {
                warnings.add("读取第三方配置 " + relative + " 失败：" + e.getMessage());
            }
        }
        return pages;
    }

    private boolean ignoredThirdParty(Path root, Path file) {
        String relative = root.relativize(file).toString().replace('\\', '/');
        String lower = relative.toLowerCase(Locale.ROOT);
        String first = lower.contains("/") ? lower.substring(0, lower.indexOf('/')) : lower;
        // samsara 自有配置、legacy 与 preNNN 备份目录都不是第三方配置。
        if (first.equals("samsara") || first.startsWith("samsara.") || first.startsWith("samsara-")) {
            return true;
        }
        if (lower.contains("/.") || lower.startsWith(".")) {
            return true;
        }
        if (lower.endsWith(".bak") || lower.endsWith(".tmp") || lower.endsWith(".legacy")
                || lower.endsWith(".lock")) {
            return true;
        }
        return ConfigFormat.of(file) == ConfigFormat.UNKNOWN;
    }

    // -------------------------------------------------------------- preview

    public Map<String, Object> preview(Path serverRoot, String id, Map<String, Object> updates) {
        Resolved resolved = resolve(serverRoot, id);
        if (resolved == null) {
            return error("未知配置页面：" + id);
        }
        Validation validation = validate(resolved, updates);
        if (!validation.ok()) {
            Map<String, Object> out = error("配置校验失败");
            out.put("fields", validation.errors());
            return out;
        }
        List<Map<String, Object>> changes = new ArrayList<>();
        StringBuilder diff = new StringBuilder();
        for (Map.Entry<String, Object> e : validation.proposed().entrySet()) {
            Object before = resolved.actualValues().get(e.getKey());
            Object after = e.getValue();
            if (Objects.equals(before, after)) {
                continue;
            }
            Map<String, Object> change = new LinkedHashMap<>();
            change.put("path", e.getKey());
            change.put("before", before);
            change.put("after", after);
            changes.add(change);
            if (diff.length() < 16_000) {
                diff.append("- ").append(e.getKey()).append(": ").append(shortValue(before)).append('\n');
                diff.append("+ ").append(e.getKey()).append(": ").append(shortValue(after)).append('\n');
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("file", id);
        out.put("revision", resolved.page().revision());
        out.put("requiresConfirmation", resolved.page().requiresConfirmation());
        out.put("changed", changes.size());
        out.put("changes", changes);
        out.put("diff", diff.toString());
        out.put("fields", Map.of());
        return out;
    }

    // ----------------------------------------------------------------- save

    public Map<String, Object> save(MinecraftServer server, Path serverRoot, String id,
                                    Map<String, Object> updates, boolean confirm, String revision) {
        Resolved resolved = resolve(serverRoot, id);
        if (resolved == null) {
            return error("未知配置页面：" + id);
        }
        PageSpec page = resolved.page();
        if (!page.writable()) {
            return error(page.readOnlyReason().isBlank() ? "此页面只读" : page.readOnlyReason());
        }
        if (page.requiresConfirmation() && !confirm) {
            Map<String, Object> out = error("需要先预览并确认后再写入");
            out.put("requiresConfirmation", true);
            return out;
        }
        if (revision != null && !revision.isBlank() && !revision.equals(page.revision())) {
            return error("文件已被其他程序修改，请刷新后重新预览");
        }
        Validation validation = validate(resolved, updates);
        if (!validation.ok()) {
            Map<String, Object> out = error("配置校验失败");
            out.put("fields", validation.errors());
            return out;
        }
        try {
            ConfigFileIO.backup(resolved.file(), serverRoot);
        } catch (IOException e) {
            return error("写前备份失败：" + e.getMessage());
        }
        if (resolved.adapter() != null) {
            try {
                resolved.adapter().write(resolved.file(), validation.effectiveUpdates());
            } catch (IOException e) {
                return error("写入失败：" + e.getMessage());
            }
        } else if (resolved.samsaraId() != null) {
            var result = ConfigManager.getInstance().save(resolved.samsaraId(),
                    validation.effectiveUpdates());
            if (!result.ok()) {
                Map<String, Object> out = error("配置校验失败");
                out.put("fields", result.errors());
                return out;
            }
        } else if (resolved.serverId() != null) {
            ServerConfigFiles.SaveResult result = ServerConfigFiles.save(
                    serverRoot, resolved.serverId(), validation.content(), server);
            if (!result.ok) {
                return error(result.error);
            }
            Map<String, Object> out = success(result.message);
            out.put("page", buildServer(serverRoot, resolved.serverId()).page());
            out.put("backupPath", result.backupPath);
            return out;
        }
        Map<String, Object> out = success("已保存 " + page.fileName());
        out.put("page", buildPage(serverRoot, id));
        return out;
    }

    // ---------------------------------------------------------------- helpers

    private Resolved resolve(Path serverRoot, String id) {
        if (id == null) {
            return null;
        }
        int colon = id.indexOf(':');
        if (colon <= 0) {
            return null;
        }
        String source = id.substring(0, colon);
        String rest = id.substring(colon + 1);
        try {
            if (SAMSARA.equals(source) && ConfigSchema.hasFile(rest)) {
                return buildSamsara(rest);
            }
            if (SERVER.equals(source) && ServerConfigFiles.spec(rest) != null) {
                return buildServer(serverRoot, rest);
            }
            if (THIRD_PARTY.equals(source)) {
                Path root = configRoot(serverRoot);
                Path file = root.resolve(rest).normalize();
                if (!file.startsWith(root) || !Files.isRegularFile(file)) {
                    return null;
                }
                ConfigAdapter adapter = ConfigAdapters.forFile(file);
                PageSpec page = buildThirdParty(serverRoot, file, rest);
                Map<String, Object> actual = new LinkedHashMap<>();
                Set<String> secrets = new LinkedHashSet<>();
                if (adapter != null) {
                    try {
                        actual.putAll(ConfigPaths.flatten(adapter.read(file)));
                    } catch (IOException ignored) {
                        // read-only page；保存路径会因 writable=false 提前拒绝
                    }
                }
                for (FormFieldSpec field : page.form().fields()) {
                    if (field.secret()) {
                        secrets.add(field.path());
                    }
                }
                return new Resolved(page, file, null, null, adapter, actual, secrets, true);
            }
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 解析配置页面 {} 失败: {}", id, e.getMessage());
        }
        return null;
    }

    private Validation validate(Resolved resolved, Map<String, Object> updates) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, Object> proposed = new LinkedHashMap<>(resolved.actualValues());
        Map<String, Object> effective = new LinkedHashMap<>();
        if (resolved.samsaraId() != null) {
            for (Map.Entry<String, Object> e : safe(updates).entrySet()) {
                String path = e.getKey();
                if (resolved.secrets().contains(path) && isBlank(e.getValue())) {
                    continue;
                }
                if (ConfigSchema.entry(resolved.samsaraId(), path) == null) {
                    errors.put(path, "未知配置项");
                    continue;
                }
                try {
                    Object value = ConfigSchema.validate(resolved.samsaraId(), path, e.getValue());
                    proposed.put(path, value);
                    effective.put(path, value);
                } catch (ConfigValidationException ex) {
                    errors.put(path, ex.getMessage());
                }
            }
            return new Validation(errors.isEmpty(), errors, proposed, effective, "");
        }
        if (resolved.serverId() != null) {
            ServerConfigFiles.Spec spec = ServerConfigFiles.spec(resolved.serverId());
            String current = "";
            try {
                current = ConfigFileIO.read(resolved.file());
            } catch (IOException e) {
                errors.put("_", "读取失败：" + e.getMessage());
            }
            ServerConfigPageSupport.BuildResult built =
                    ServerConfigPageSupport.build(spec, current, safe(updates));
            if (!built.ok()) {
                errors.putAll(built.errors());
                return new Validation(false, errors, proposed, effective, current);
            }
            proposed.putAll(safe(updates));
            effective.putAll(safe(updates));
            return new Validation(true, Map.of(), proposed, effective, built.content());
        }
        if (resolved.adapter() != null) {
            Map<String, FormFieldSpec> specs = new LinkedHashMap<>();
            for (FormFieldSpec field : resolved.page().form().fields()) {
                specs.put(field.path(), field);
            }
            for (Map.Entry<String, Object> e : safe(updates).entrySet()) {
                String path = e.getKey();
                FormFieldSpec field = specs.get(path);
                if (field == null) {
                    errors.put(path, "未知配置项");
                    continue;
                }
                if (field.secret() && isBlank(e.getValue())) {
                    continue;
                }
                try {
                    Object value = coerceExternal(field, e.getValue());
                    proposed.put(path, value);
                    effective.put(path, value);
                } catch (IllegalArgumentException ex) {
                    errors.put(path, ex.getMessage());
                }
            }
            return new Validation(errors.isEmpty(), errors, proposed, effective, "");
        }
        errors.put("_", "没有可用的写入适配器");
        return new Validation(false, errors, proposed, effective, "");
    }

    private Object coerceExternal(FormFieldSpec field, Object raw) {
        String type = field.type();
        Object value = raw;
        if (value instanceof String text) {
            String trimmed = text.trim();
            switch (type) {
                case "bool" -> {
                    if (!trimmed.equalsIgnoreCase("true") && !trimmed.equalsIgnoreCase("false")) {
                        throw new IllegalArgumentException("必须是 true 或 false");
                    }
                    value = Boolean.parseBoolean(trimmed);
                }
                case "int" -> {
                    try {
                        value = Long.parseLong(trimmed);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("必须是整数");
                    }
                }
                case "number" -> {
                    try {
                        value = Double.parseDouble(trimmed);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("必须是数字");
                    }
                }
                case "json" -> value = parseJson(trimmed);
                default -> value = text;
            }
        } else if ("json".equals(type) && !(value instanceof Map<?, ?>) && !(value instanceof List<?>)) {
            value = parseJson(String.valueOf(value));
        }
        if (value instanceof Number number) {
            if ("int".equals(type)) {
                value = number.longValue();
            } else if ("number".equals(type)) {
                value = number.doubleValue();
            }
            number = (Number) value;
            if (field.min() != null && number.doubleValue() < field.min()) {
                throw new IllegalArgumentException("不能小于 " + format(field.min()));
            }
            if (field.max() != null && number.doubleValue() > field.max()) {
                throw new IllegalArgumentException("不能大于 " + format(field.max()));
            }
        }
        return value;
    }

    private Object parseJson(String text) {
        try {
            JsonElement element = JsonParser.parseString(text);
            return GSON.fromJson(element, Object.class);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage());
        }
    }

    private PageSpec buildPage(Path serverRoot, String id) {
        Resolved resolved = resolve(serverRoot, id);
        return resolved == null ? null : resolved.page();
    }

    private static Map<String, Object> safe(Map<String, Object> values) {
        return values == null ? Map.of() : values;
    }

    private static boolean isBlank(Object value) {
        return value == null || String.valueOf(value).isBlank();
    }

    private static boolean isSecret(String path) {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("password") || lower.contains("passwd") || lower.contains("secret")
                || lower.contains("token") || lower.contains("api_key") || lower.contains("apikey");
    }

    private static Set<String> secretPaths(List<FormFieldSpec> fields) {
        Set<String> out = new LinkedHashSet<>();
        for (FormFieldSpec field : fields) {
            if (field.secret()) {
                out.add(field.path());
            }
        }
        return out;
    }

    private static String fieldType(ConfigType type) {
        return switch (type) {
            case BOOLEAN -> "bool";
            case INT -> "int";
            case DOUBLE -> "number";
            case ENUM -> "enum";
            case STRING_LIST, INT_LIST, LEVEL_MAP, TEMPLATE, CURVE -> "json";
            case STRING -> "text";
        };
    }

    private static String widget(ConfigType type) {
        return switch (type) {
            case CURVE -> "curve";
            case TEMPLATE -> "template";
            case INT_LIST -> "int-list";
            case STRING_LIST -> "string-list";
            case LEVEL_MAP -> "level-map";
            default -> "";
        };
    }

    private static List<FormGroupSpec> groups(List<FormFieldSpec> fields) {
        Map<String, FormGroupSpec> out = new LinkedHashMap<>();
        for (FormFieldSpec field : fields) {
            out.computeIfAbsent(field.group(), g -> new FormGroupSpec(g, g));
        }
        return new ArrayList<>(out.values());
    }

    private static List<PageAction> actions(boolean writable, boolean confirm) {
        List<PageAction> actions = new ArrayList<>();
        actions.add(new PageAction("preview", "预览", "POST", "/api/config/preview", confirm));
        if (writable) {
            actions.add(new PageAction("save", "保存", "PUT", "/api/config", confirm));
        }
        actions.add(new PageAction("reload", "重载", "POST", "/api/reload", false));
        return actions;
    }

    private static FormFieldSpec inferField(String path, Object value, boolean secret) {
        String type;
        if (secret) {
            type = "password";
        } else if (value instanceof Boolean) {
            type = "bool";
        } else if (value instanceof Integer || value instanceof Long) {
            type = "int";
        } else if (value instanceof Number) {
            type = "number";
        } else if (value instanceof Map<?, ?> || value instanceof List<?>) {
            type = "json";
        } else if (value instanceof String text && text.contains("\n")) {
            type = "multiline";
        } else {
            type = "text";
        }
        return new FormFieldSpec(path, ConfigPaths.lastSegment(path), type,
                value == null ? "" : value,
                null, null, null, List.of(), ConfigPaths.firstSegment(path),
                "推断字段；确认后写入", false, null, secret, false,
                "json".equals(type) ? "json" : "", "");
    }

    private static String serverFormat(ServerConfigFiles.Spec spec) {
        return switch (spec.kind()) {
            case JSON_ARRAY -> ConfigFormat.JSON.id();
            case PROPERTIES, EULA, TEXT -> ConfigFormat.PROPERTIES.id();
        };
    }

    private static String shortValue(Object value) {
        String text = value == null ? "null" : String.valueOf(value);
        return text.length() > 240 ? text.substring(0, 240) + "…" : text;
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static Path configRoot(Path serverRoot) {
        try {
            return FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
        } catch (RuntimeException | LinkageError e) {
            return serverRoot.resolve("config").toAbsolutePath().normalize();
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", false);
        out.put("error", message == null ? "" : message);
        return out;
    }

    private static Map<String, Object> success(String message) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("message", message == null ? "" : message);
        return out;
    }

    private record Validation(boolean ok, Map<String, String> errors,
                              Map<String, Object> proposed, Map<String, Object> effectiveUpdates,
                              String content) {
    }

    private record Resolved(PageSpec page, Path file, String samsaraId, String serverId,
                            ConfigAdapter adapter, Map<String, Object> actualValues,
                            Set<String> secrets, boolean nestedPaths) {
    }
}
