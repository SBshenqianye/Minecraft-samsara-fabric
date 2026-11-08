package com.shenqianye.samsara.config.spec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.shenqianye.samsara.server.ServerConfigFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 把服务器本体 6 个文件映射成 FormSpec，并负责字段级校验与内容重建。 */
public final class ServerConfigPageSupport {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Map<String, PropertySpec> PROPERTY_SPECS = propertySpecs();

    private ServerConfigPageSupport() {
    }

    public record PropertySpec(String group, String type, String description,
                               Double min, Double max, List<FormChoice> choices,
                               boolean secret) {
        public PropertySpec {
            choices = choices == null ? List.of() : List.copyOf(choices);
        }
    }

    public record FieldValue(Map<String, Object> values, List<FormFieldSpec> fields) {
    }

    public record BuildResult(boolean ok, Map<String, String> errors, String content) {
    }

    public static FieldValue read(Path serverRoot, ServerConfigFiles.Spec spec) throws IOException {
        Path file = serverRoot.resolve(spec.fileName());
        String content = Files.isRegularFile(file)
                ? stripBom(Files.readString(file, StandardCharsets.UTF_8)) : "";
        Map<String, Object> values = new LinkedHashMap<>();
        List<FormFieldSpec> fields = new ArrayList<>();
        switch (spec.kind()) {
            case PROPERTIES -> {
                for (Map.Entry<String, String> e : parseProperties(content).entrySet()) {
                    String key = e.getKey();
                    String raw = e.getValue();
                    PropertySpec ps = propertySpec(key);
                    String type = ps == null ? inferPropertyType(raw) : ps.type();
                    Object value = coerceProperty(raw, type);
                    values.put(key, value);
                    fields.add(new FormFieldSpec(key, key, type, value,
                            ps == null ? null : ps.min(), ps == null ? null : ps.max(), null,
                            ps == null ? List.of() : ps.choices(),
                            ps == null ? inferGroup(key) : ps.group(),
                            ps == null ? "服务器属性；按文本/数字/布尔自动识别" : ps.description(),
                            spec.restartRequired(), null, ps != null && ps.secret(),
                            false, chatWidget(key), ""));
                }
            }
            case JSON_ARRAY -> {
                Object parsed = parseJsonOrDefault(content, new ArrayList<>());
                values.put("json", parsed);
                fields.add(new FormFieldSpec("json", "JSON 内容", "json", parsed,
                        null, null, null, List.of(), "内容", spec.description(),
                        spec.restartRequired(), null, false, false, "json", ""));
            }
            case EULA -> {
                boolean accepted = content.toLowerCase(java.util.Locale.ROOT).contains("eula=true");
                values.put("eula", accepted);
                fields.add(new FormFieldSpec("eula", "eula", "bool", accepted,
                        null, null, null, List.of(), "其他",
                        "EULA 同意标记；必须为 true 才能启动服务器",
                        spec.restartRequired(), null, false, false, null, ""));
            }
            case TEXT -> {
                values.put("content", content);
                fields.add(new FormFieldSpec("content", "内容", "multiline", content,
                        null, null, null, List.of(), "内容", spec.description(),
                        spec.restartRequired(), null, false, false, null, ""));
            }
        }
        return new FieldValue(values, fields);
    }

    public static BuildResult build(ServerConfigFiles.Spec spec, String currentContent,
                                    Map<String, Object> updates) {
        Map<String, String> errors = new LinkedHashMap<>();
        Map<String, String> current = parseProperties(currentContent);
        switch (spec.kind()) {
            case PROPERTIES -> {
                for (Map.Entry<String, Object> e : updates.entrySet()) {
                    String key = e.getKey();
                    if (key == null || key.isBlank()) {
                        errors.put(key, "键不能为空");
                        continue;
                    }
                    PropertySpec ps = propertySpec(key);
                    String type = ps == null ? inferPropertyType(String.valueOf(current.getOrDefault(key, ""))) : ps.type();
                    try {
                        Object coerced = coerceProperty(e.getValue(), type);
                        if (ps != null) {
                            if (ps.choices() != null && !ps.choices().isEmpty()
                                    && ps.choices().stream().noneMatch(c -> c.value().equals(String.valueOf(coerced)))) {
                                errors.put(key, "必须是：" + ps.choices().stream()
                                        .map(FormChoice::value).toList());
                            }
                            if (coerced instanceof Number number && ps.min() != null
                                    && number.doubleValue() < ps.min()) {
                                errors.put(key, "不能小于 " + format(ps.min()));
                            }
                            if (coerced instanceof Number number && ps.max() != null
                                    && number.doubleValue() > ps.max()) {
                                errors.put(key, "不能大于 " + format(ps.max()));
                            }
                        }
                        current.put(key, propertyText(coerced));
                    } catch (IllegalArgumentException ex) {
                        errors.put(key, ex.getMessage());
                    }
                }
                if (!errors.isEmpty()) {
                    return new BuildResult(false, errors, currentContent);
                }
                return new BuildResult(true, Map.of(), renderProperties(currentContent, current));
            }
            case JSON_ARRAY -> {
                Object raw = updates.containsKey("json") ? updates.get("json") : currentContent;
                try {
                    JsonElement element = raw instanceof String text
                            ? JsonParser.parseString(text) : GSON.toJsonTree(raw);
                    if (!element.isJsonArray()) {
                        errors.put("json", "必须是 JSON 数组");
                        return new BuildResult(false, errors, currentContent);
                    }
                    return new BuildResult(true, Map.of(), GSON.toJson(element) + "\n");
                } catch (RuntimeException e) {
                    errors.put("json", "JSON 解析失败：" + e.getMessage());
                    return new BuildResult(false, errors, currentContent);
                }
            }
            case EULA -> {
                Object raw = updates.getOrDefault("eula", false);
                boolean accepted;
                if (raw instanceof Boolean bool) {
                    accepted = bool;
                } else {
                    String text = String.valueOf(raw).trim();
                    if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                        errors.put("eula", "只能是 true 或 false");
                        return new BuildResult(false, errors, currentContent);
                    }
                    accepted = Boolean.parseBoolean(text);
                }
                return new BuildResult(true, Map.of(), "eula=" + accepted + "\n");
            }
            case TEXT -> {
                Object raw = updates.getOrDefault("content", currentContent);
                return new BuildResult(true, Map.of(), String.valueOf(raw));
            }
        }
        return new BuildResult(false, Map.of("_", "不支持的服务器文件类型"), currentContent);
    }

    private static Map<String, PropertySpec> propertySpecs() {
        Map<String, PropertySpec> m = new LinkedHashMap<>();
        prop(m, "accepts-transfers", "网络与状态", "bool", "是否接受其它服务器通过 transfer 协议转入的玩家");
        prop(m, "allow-flight", "世界与玩法", "bool", "允许生存模式飞行；关闭时飞行会被反作弊踢出");
        prop(m, "broadcast-console-to-ops", "管理与 RCON", "bool", "把控制台命令的输出广播给 OP");
        prop(m, "broadcast-rcon-to-ops", "管理与 RCON", "bool", "把 RCON 命令的输出广播给 OP");
        prop(m, "bug-report-link", "网络与状态", "url", "客户端崩溃时显示的反馈链接；留空使用默认");
        prop(m, "chat-spam-threshold-seconds", "世界与玩法", "int", "聊天刷屏判定窗口（秒）", 0.0, null);
        prop(m, "command-spam-threshold-seconds", "世界与玩法", "int", "命令刷屏判定窗口（秒）", 0.0, null);
        enumProp(m, "difficulty", "世界与玩法", "游戏难度",
                List.of("peaceful", "easy", "normal", "hard"));
        prop(m, "enable-code-of-conduct", "网络与状态", "bool", "是否要求玩家遵守行为准则");
        prop(m, "enable-jmx-monitoring", "管理与 RCON", "bool", "开启 JMX 监控（性能诊断）");
        prop(m, "enable-query", "管理与 RCON", "bool", "开启 GameSpy4 查询协议");
        prop(m, "enable-rcon", "管理与 RCON", "bool", "开启 RCON 远程控制台");
        prop(m, "enable-status", "网络与状态", "bool", "在服务器列表显示在线状态");
        prop(m, "enforce-secure-profile", "网络与状态", "bool", "要求正版签名聊天；离线服通常关闭");
        prop(m, "enforce-whitelist", "世界与玩法", "bool", "白名单开启时踢出不在名单的玩家");
        prop(m, "entity-broadcast-range-percentage", "性能与区块", "int",
                "实体广播范围百分比（10-1000）", 10.0, 1000.0);
        prop(m, "force-gamemode", "世界与玩法", "bool", "加入时强制切换为默认游戏模式");
        prop(m, "function-permission-level", "管理与 RCON", "int",
                "函数与数据包的默认权限等级（0-4）", 0.0, 4.0);
        enumProp(m, "gamemode", "世界与玩法", "新玩家的默认游戏模式",
                List.of("survival", "creative", "adventure", "spectator"));
        prop(m, "generate-structures", "世界与玩法", "bool", "生成村庄、要塞等结构");
        prop(m, "generator-settings", "世界与玩法", "multiline", "自定义世界生成参数（JSON）；留空使用默认");
        prop(m, "hardcore", "世界与玩法", "bool", "极限模式：死亡后不可复活");
        prop(m, "hide-online-players", "网络与状态", "bool", "在服务器列表隐藏在线玩家");
        prop(m, "initial-disabled-packs", "世界与玩法", "multiline", "默认禁用的数据包列表（逗号分隔）");
        prop(m, "initial-enabled-packs", "世界与玩法", "multiline", "默认启用的数据包列表（逗号分隔）");
        prop(m, "level-name", "世界与玩法", "text", "世界目录名；本体恢复要求与备份的 level-name 一致");
        prop(m, "level-seed", "世界与玩法", "text", "世界种子；留空随机生成");
        enumProp(m, "level-type", "世界与玩法", "世界生成器类型",
                List.of("minecraft:normal", "minecraft:flat", "minecraft:large_biomes",
                        "minecraft:amplified", "minecraft:single_biome_surface"));
        prop(m, "log-ips", "网络与状态", "bool", "在日志中记录玩家 IP");
        prop(m, "management-server-allowed-origins", "管理与 RCON", "multiline",
                "管理服务允许的 Origin；逗号分隔");
        prop(m, "management-server-enabled", "管理与 RCON", "bool", "开启内置管理服务");
        prop(m, "management-server-host", "管理与 RCON", "text", "管理服务监听地址");
        prop(m, "management-server-port", "管理与 RCON", "int", "管理服务端口", 1.0, 65535.0);
        secret(m, "management-server-secret", "管理与 RCON", "管理服务密钥（敏感）");
        prop(m, "management-server-tls-enabled", "管理与 RCON", "bool", "管理服务启用 TLS");
        prop(m, "management-server-tls-keystore", "管理与 RCON", "text", "TLS keystore 文件路径");
        secret(m, "management-server-tls-keystore-password", "管理与 RCON", "TLS keystore 密码（敏感）");
        prop(m, "max-chained-neighbor-updates", "性能与区块", "int", "链式方块更新上限；-1 不限制", -1.0, null);
        prop(m, "max-players", "网络与状态", "int", "最大在线玩家数", 0.0, null);
        prop(m, "max-tick-time", "性能与区块", "int", "单 tick 超时（毫秒）；-1 关闭看门狗", -1.0, null);
        prop(m, "max-world-size", "世界与玩法", "int", "世界边界半径（方块）", 1.0, null);
        prop(m, "motd", "网络与状态", "text", "服务器列表显示的描述；支持 § 颜色代码");
        prop(m, "network-compression-threshold", "性能与区块", "int", "网络压缩阈值（字节）；-1 关闭", -1.0, null);
        prop(m, "online-mode", "网络与状态", "bool", "正版验证；离线服设为 false");
        prop(m, "op-permission-level", "管理与 RCON", "int", "OP 的默认权限等级（0-4）", 0.0, 4.0);
        prop(m, "pause-when-empty-seconds", "世界与玩法", "int", "无人在线多少秒后暂停；0 不暂停", 0.0, null);
        prop(m, "player-idle-timeout", "世界与玩法", "int", "挂机踢出时间（分钟）；0 不踢", 0.0, null);
        prop(m, "prevent-proxy-connections", "网络与状态", "bool", "拒绝通过代理 / VPN 连接的玩家");
        prop(m, "query.port", "管理与 RCON", "int", "GameSpy4 查询端口", 1.0, 65535.0);
        prop(m, "rate-limit", "网络与状态", "int", "每玩家每秒数据包上限；0 不限制", 0.0, null);
        secret(m, "rcon.password", "管理与 RCON", "RCON 密码（敏感）；建议只在本机使用");
        prop(m, "rcon.port", "管理与 RCON", "int", "RCON 端口", 1.0, 65535.0);
        enumProp(m, "region-file-compression", "性能与区块", "region 文件压缩算法",
                List.of("deflate", "lz4", "none"));
        prop(m, "require-resource-pack", "网络与状态", "bool", "强制玩家使用资源包");
        prop(m, "resource-pack", "网络与状态", "url", "资源包下载地址");
        prop(m, "resource-pack-id", "网络与状态", "text", "资源包唯一标识");
        prop(m, "resource-pack-prompt", "网络与状态", "text", "资源包提示文本");
        prop(m, "resource-pack-sha1", "网络与状态", "text", "资源包 SHA-1 校验值");
        prop(m, "server-ip", "网络与状态", "text", "监听地址；留空表示所有网卡");
        prop(m, "server-port", "网络与状态", "int", "游戏端口", 1.0, 65535.0);
        prop(m, "simulation-distance", "性能与区块", "int", "模拟距离（区块）", 2.0, 32.0);
        prop(m, "spawn-protection", "世界与玩法", "int", "出生点保护半径（方块）；0 关闭", 0.0, null);
        prop(m, "status-heartbeat-interval", "网络与状态", "int", "服务器列表状态心跳间隔（秒）", 0.0, null);
        prop(m, "sync-chunk-writes", "性能与区块", "bool", "强制同步区块写入（更安全但更慢）");
        prop(m, "text-filtering-config", "世界与玩法", "text", "聊天过滤服务配置路径");
        prop(m, "text-filtering-version", "世界与玩法", "int", "聊天过滤服务协议版本");
        prop(m, "use-native-transport", "性能与区块", "bool", "Linux 上使用原生网络传输");
        prop(m, "view-distance", "性能与区块", "int", "视距（区块）", 2.0, 32.0);
        prop(m, "white-list", "世界与玩法", "bool", "启用白名单");
        prop(m, "eula", "其他", "bool", "EULA 同意标记；必须为 true 才能启动服务器");
        return Map.copyOf(m);
    }

    private static void prop(Map<String, PropertySpec> m, String key, String group,
                             String type, String desc) {
        prop(m, key, group, type, desc, null, null);
    }

    private static void prop(Map<String, PropertySpec> m, String key, String group,
                             String type, String desc, Double min, Double max) {
        m.put(key, new PropertySpec(group, type, desc, min, max, List.of(), false));
    }

    private static void enumProp(Map<String, PropertySpec> m, String key, String group,
                                 String desc, List<String> choices) {
        m.put(key, new PropertySpec(group, "enum", desc, null, null,
                choices.stream().map(FormChoice::of).toList(), false));
    }

    private static void secret(Map<String, PropertySpec> m, String key, String group, String desc) {
        m.put(key, new PropertySpec(group, "password", desc, null, null, List.of(), true));
    }

    private static PropertySpec propertySpec(String key) {
        return PROPERTY_SPECS.get(key);
    }

    private static String inferPropertyType(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.equalsIgnoreCase("true") || text.equalsIgnoreCase("false")) {
            return "bool";
        }
        if (text.matches("-?\\d+")) {
            return "int";
        }
        if (text.matches("-?\\d+\\.\\d+")) {
            return "number";
        }
        return "text";
    }

    private static String chatWidget(String key) {
        return "motd".equals(key) || "resource-pack-prompt".equals(key) ? "chat" : null;
    }

    private static String inferGroup(String key) {
        return key.contains(".") ? key.substring(0, key.indexOf('.')) : "其他";
    }

    private static Object coerceProperty(String raw, String type) {
        String text = raw == null ? "" : raw.trim();
        return switch (type) {
            case "bool" -> {
                if (!text.equalsIgnoreCase("true") && !text.equalsIgnoreCase("false")) {
                    throw new IllegalArgumentException("必须是 true 或 false");
                }
                yield Boolean.parseBoolean(text);
            }
            case "int" -> {
                try {
                    yield Integer.parseInt(text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("必须是整数");
                }
            }
            case "number" -> {
                try {
                    yield Double.parseDouble(text);
                } catch (NumberFormatException e) {
                    throw new IllegalArgumentException("必须是数字");
                }
            }
            default -> text;
        };
    }

    private static Object coerceProperty(Object raw, String type) {
        if (raw instanceof Boolean bool) {
            return bool;
        }
        if (raw instanceof Number number) {
            return switch (type) {
                case "int" -> number.intValue();
                case "number" -> number.doubleValue();
                default -> String.valueOf(raw);
            };
        }
        return coerceProperty(String.valueOf(raw == null ? "" : raw), type);
    }

    private static String propertyText(Object value) {
        if (value instanceof Boolean bool) {
            return bool ? "true" : "false";
        }
        return String.valueOf(value == null ? "" : value);
    }

    private static String format(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    private static Map<String, String> parseProperties(String content) {
        Map<String, String> out = new LinkedHashMap<>();
        for (String line : stripBom(content).split("\\R", -1)) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            out.put(trimmed.substring(0, eq).trim(), trimmed.substring(eq + 1).trim());
        }
        return out;
    }

    private static String renderProperties(String original, Map<String, String> values) {
        List<String> lines = new ArrayList<>(List.of(stripBom(original)
                .split("\\R", -1)));
        Map<String, Integer> seen = new LinkedHashMap<>();
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.startsWith("!")) {
                continue;
            }
            int eq = trimmed.indexOf('=');
            if (eq > 0) {
                seen.putIfAbsent(trimmed.substring(0, eq).trim(), i);
            }
        }
        for (Map.Entry<String, String> e : values.entrySet()) {
            Integer index = seen.get(e.getKey());
            if (index == null) {
                lines.add(e.getKey() + "=" + e.getValue());
                seen.put(e.getKey(), lines.size() - 1);
            } else {
                String originalLine = lines.get(index);
                int eq = originalLine.indexOf('=');
                lines.set(index, (eq < 0 ? originalLine : originalLine.substring(0, eq))
                        + "=" + e.getValue());
            }
        }
        String out = String.join("\n", lines);
        if (!out.endsWith("\n")) {
            out += "\n";
        }
        return out;
    }

    private static String stripBom(String text) {
        String value = text == null ? "" : text;
        return value.startsWith("\uFEFF") ? value.substring(1) : value;
    }

    private static Object parseJsonOrDefault(String content, Object fallback) {        try {
            String text = stripBom(content).trim();
            if (text.isEmpty()) {
                return fallback;
            }
            JsonElement element = JsonParser.parseString(text);
            return GSON.fromJson(element, Object.class);
        } catch (RuntimeException e) {
            return fallback;
        }
    }
}
