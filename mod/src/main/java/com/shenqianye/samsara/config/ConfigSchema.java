package com.shenqianye.samsara.config;

import com.shenqianye.samsara.output.OutputCatalog;
import com.shenqianye.samsara.output.OutputTemplate;
import com.shenqianye.samsara.output.TemplateCodec;
import com.shenqianye.samsara.output.TemplatePart;
import com.shenqianye.samsara.permission.PermissionDefaults;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 配置 schema：每个可编辑配置项的<b>唯一事实来源</b>。
 *
 * <p>加载、生成默认 YAML、Web 表单、保存校验全部读这一份定义。新增配置键必须在这里
 * 注册，否则文件不会生成、Web 看不到、校验也会报“未知配置项”。
 *
 * <p>世界状态（season/claims/rewind 等）不在 schema 内：本轮只迁移路径，保持 JSON。
 */
public final class ConfigSchema {
    public static final String COMMANDS = "commands";
    public static final String PERMISSIONS = "permissions";
    public static final String CLAIM_PARTICLES = "claim-particles";
    public static final String CLAIM_RANGE = "claim-range";
    public static final String STORAGE = "storage";
    public static final String WEB = "web";
    public static final String OUTPUT = "output";

    /** 文件 id → 文件名（不含扩展名）。 */
    private static final Map<String, String> FILE_NAMES = new LinkedHashMap<>();
    /** 文件 id → 配置项（按生成顺序）。 */
    private static final Map<String, List<ConfigEntry>> FILES = new LinkedHashMap<>();

    public static final List<String> LEVELS = List.of("user", "op", "owner", "config");
    public static final List<String> PARTICLE_CHOICES = List.of(
            "crit", "enchant", "smoke", "large_smoke", "flame",
            "soul_fire_flame", "end_rod", "happy_villager", "composter",
            "portal", "witch", "cloud", "note", "heart",
            "angry_villager", "snowflake", "ash", "white_ash");

    static {
        file(COMMANDS, "命令开关", List.of(
                ConfigEntry.bool("where.enabled", true, "where",
                        "是否启用 /samsara where（关闭后命令不出现在命令树中）"),
                ConfigEntry.bool("where.broadcast", true, "where",
                        "是否允许 /samsara where broadcast 全服广播"),
                ConfigEntry.integer("where.broadcastCooldownSeconds", 30, 0, 86_400, "where",
                        "广播冷却秒数（0 = 不限）"),
                ConfigEntry.bool("where.showCoordinates", true, "where",
                        "输出是否包含方块坐标"),
                ConfigEntry.bool("where.showDimension", true, "where",
                        "输出是否包含维度名"),
                ConfigEntry.bool("where.showChunk", true, "where",
                        "输出是否包含区块坐标"),
                ConfigEntry.bool("where.showFacing", true, "where",
                        "输出是否包含朝向"),
                ConfigEntry.bool("tpa.enabled", true, "tpa",
                        "是否启用 /samsara tpa 系列传送请求"),
                ConfigEntry.integer("tpa.requestTimeoutSeconds", 60, 5, 3600, "tpa",
                        "传送请求有效期（秒）"),
                ConfigEntry.bool("tpa.crossDimension", false, "tpa",
                        "是否允许跨维度传送（false = 仅同一维度）"),
                ConfigEntry.bool("view.protectViewer", true, "回看",
                        "回看时保护观看者（临时无敌 + 安全跟随点）"),
                ConfigEntry.integer("view.freeRoamRadius", 32, 8, 256, "回看",
                        "回看时允许自由活动的半径（格），超出后自动跟随"),
                ConfigEntry.bool("view.aim.enabled", false, "回看",
                        "回看默认自动瞄准（命令 --aim 可覆盖）"),
                ConfigEntry.decimal("view.aim.deadzone", 6.0, 2.0, 45.0, "回看",
                        "自动瞄准死区角度"),
                ConfigEntry.decimal("view.aim.edge", 24.0, 2.0, 75.0, "回看",
                        "自动瞄准外圈角度"),
                ConfigEntry.decimal("view.aim.maxDegPerSec", 90.0, 10.0, 360.0, "回看",
                        "自动瞄准最大角速度（度/秒）"),
                ConfigEntry.bool("view.aim.pitch", true, "回看",
                        "自动瞄准是否包含俯仰角"),
                ConfigEntry.bool("completion.chatList", true, "补全",
                        "未完成指令是否在聊天栏列出可点击候选"),
                ConfigEntry.bool("completion.fuzzy", true, "补全",
                        "没有前缀匹配时是否做拼写近似（seasn → season）"),
                ConfigEntry.integer("completion.maxItems", 48, 4, 200, "补全",
                        "聊天栏单页最多候选数；超过后显示翻页按钮"),
                ConfigEntry.integer("shutdown.countdownSeconds", 10, 1, 300, "关服",
                        "执行 /samsara restart 后先广播倒计时多少秒再停服"),
                ConfigEntry.bool("output.autoContinuePrefix", true, "输出",
                        "是否为聊天输出的续行自动添加“  ▸ ”前缀；"
                                + "关闭后完全按模板文字输出，行首由你自己控制")
        ));

        file(PERMISSIONS, "权限", List.of(
                ConfigEntry.levelMap("entries", PermissionDefaults.entries(), "权限等级",
                        "命令路径 → 所需权限等级（user/op/owner/config）。"
                                + "游戏内 /samsara perm set 也写这里"),
                ConfigEntry.stringList("disabled", List.of(), "权限等级",
                        "被整体关闭的权限节点；父节点关闭会级联到所有后代。"
                                + "由本地 Web 权限树维护"),
                ConfigEntry.stringList("owners", List.of(), "权限等级",
                        "始终拥有 owner 权限的玩家名（不区分大小写）")
        ));

        file(CLAIM_PARTICLES, "圈地粒子", List.of(
                ConfigEntry.choice("dayParticle", "smoke", PARTICLE_CHOICES, "粒子",
                        "昼间边界粒子"),
                ConfigEntry.choice("nightParticle", "end_rod", PARTICLE_CHOICES, "粒子",
                        "夜间边界粒子"),
                ConfigEntry.choice("fontParticle", "crit", PARTICLE_CHOICES, "粒子",
                        "粒子文字使用的粒子"),
                ConfigEntry.choice("beamParticle", "enchant", PARTICLE_CHOICES, "粒子",
                        "方向光束使用的粒子"),
                ConfigEntry.bool("sharedDisplay", false, "显示",
                        "true = TextDisplay 所有人可见；false = 仅持有者可见"),
                ConfigEntry.bool("globalDisplay", false, "显示",
                        "true = 不在管理区域内也显示边界；false = 仅管理区域内显示")
        ));

        file(CLAIM_RANGE, "圈地范围", List.of(
                ConfigEntry.choice("mode", "dye", List.of("dye", "fixed"), "保护范围",
                        "dye = 每个染料颜色对应一个延伸距离；"
                                + "fixed = 所有颜色都使用 fixedDistance"),
                ConfigEntry.integer("fixedDistance", 4, 1, 32, "保护范围",
                        "固定模式下的单方向延伸距离（区块）"),
                ConfigEntry.intList("colorDistances",
                        List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
                        1, 32, "保护范围",
                        "染料颜色 0~15（白→黑）各自对应的单方向延伸距离（区块）；"
                                + "Web 配置页按染料色块可视化编辑并实时预览保护形状"),
                ConfigEntry.integer("maxDistance", 16, 1, 32, "保护范围",
                        "所有模式下的单方向距离上限；超出会截断，用来限制最大圈地规模")
        ));

        file(STORAGE, "存储", List.of(
                ConfigEntry.bool("globalFollowWorld", true, "全局数据",
                        "true = 全局数据随存档（<存档根>/samsara/global）；"
                                + "false = 服务器级（<服务器根>/samsara/global）。"
                                + "切换时程序自动双向迁移"),
                ConfigEntry.string("worldDataRoot", "", "路径",
                        "世界数据根覆盖；留空 = <存档根>/samsara"),
                ConfigEntry.string("globalDataRoot", "", "路径",
                        "全局数据根覆盖；留空 = 按 globalFollowWorld 推导"),
                ConfigEntry.integer("migration.version", 0, 0, 1_000_000, "迁移",
                        "迁移版本标记，由程序维护，请勿手改"),
                ConfigEntry.string("global.migration", "", "迁移",
                        "最近一次全局数据迁移的方向与结果，由程序维护")
        ));

        file(WEB, "Web", List.of(
                ConfigEntry.bool("enabled", false, "服务",
                        "是否启动本地配置面板（仅监听 127.0.0.1）。"
                                + "默认关闭；未勾选时整个 Web 服务不启动，也不会生成访问 token"),
                ConfigEntry.integer("port", 8720, 1024, 65_535, "服务",
                        "本地面板端口"),
                ConfigEntry.string("token", "", "鉴权",
                        "访问令牌，由程序在首次启动时生成；请求需 Authorization: Bearer <token>")
        ));

        List<ConfigEntry> outputEntries = new ArrayList<>();
        for (OutputTemplate template : OutputCatalog.all()) {
            outputEntries.add(ConfigEntry.template(template.key(),
                    TemplateCodec.toConfig(template.defaultParts()),
                    template.group(), template.description()));
        }
        file(OUTPUT, "输出格式", outputEntries);
    }

    private ConfigSchema() {
    }

    private static void file(String id, String label, List<ConfigEntry> entries) {
        FILE_NAMES.put(id, id);
        FILES.put(id, List.copyOf(entries));
    }

    public static List<String> fileIds() {
        return List.copyOf(FILES.keySet());
    }

    public static boolean hasFile(String id) {
        return FILES.containsKey(id);
    }

    public static String fileName(String id) {
        return FILE_NAMES.getOrDefault(id, id);
    }

    public static List<ConfigEntry> entries(String id) {
        return FILES.getOrDefault(id, List.of());
    }

    public static ConfigEntry entry(String id, String path) {
        for (ConfigEntry entry : entries(id)) {
            if (entry.path().equals(path)) {
                return entry;
            }
        }
        return null;
    }

    /** 默认值（点分路径 → 深拷贝）。 */
    public static Map<String, Object> defaults(String id) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (ConfigEntry entry : entries(id)) {
            out.put(entry.path(), deepCopy(entry.defaultValue()));
        }
        return out;
    }

    /** 令牌等敏感值：Web 只显示占位，不回传明文。 */
    public static boolean isSensitive(String id, String path) {
        return WEB.equals(id) && "token".equals(path);
    }

    /** 是否需要重启。 */
    public static boolean requiresRestart(String id, String path) {
        ConfigEntry entry = entry(id, path);
        return entry != null && entry.restart();
    }

    // ---- 校验 ----

    /** 严格校验（保存/Web PUT 使用）：非法值抛出字段级异常，不写盘。 */
    public static Object validate(String id, String path, Object raw) throws ConfigValidationException {
        ConfigEntry entry = entry(id, path);
        if (entry == null) {
            throw new ConfigValidationException(path, "未知配置项：" + path);
        }
        try {
            return coerce(entry, raw);
        } catch (ConfigValidationException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new ConfigValidationException(path, "值类型不正确");
        }
    }

    /**
     * 宽松读取（加载 YAML 使用）：非法值回退默认，由调用方记录 WARN。
     * 返回 {@code null} 表示需要回退，错误信息通过异常带出。
     */
    public static Object coerceForLoad(String id, String path, Object raw)
            throws ConfigValidationException {
        return validate(id, path, raw);
    }

    private static Object coerce(ConfigEntry entry, Object raw) throws ConfigValidationException {
        String path = entry.path();
        if (raw == null) {
            throw new ConfigValidationException(path, "值不能为空");
        }
        switch (entry.type()) {
            case BOOLEAN -> {
                if (raw instanceof Boolean b) {
                    return b;
                }
                String text = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
                if (text.equals("true")) {
                    return true;
                }
                if (text.equals("false")) {
                    return false;
                }
                throw new ConfigValidationException(path, "应为 true 或 false");
            }
            case INT -> {
                double value;
                if (raw instanceof Number n) {
                    value = n.doubleValue();
                } else {
                    try {
                        value = Double.parseDouble(String.valueOf(raw).trim());
                    } catch (NumberFormatException e) {
                        throw new ConfigValidationException(path, "应为整数");
                    }
                }
                if (!Double.isFinite(value) || value != Math.rint(value)) {
                    throw new ConfigValidationException(path, "应为整数");
                }
                checkRange(entry, value);
                return (int) value;
            }
            case DOUBLE -> {
                double value;
                if (raw instanceof Number n) {
                    value = n.doubleValue();
                } else {
                    try {
                        value = Double.parseDouble(String.valueOf(raw).trim());
                    } catch (NumberFormatException e) {
                        throw new ConfigValidationException(path, "应为数字");
                    }
                }
                if (!Double.isFinite(value)) {
                    throw new ConfigValidationException(path, "应为有限数字");
                }
                checkRange(entry, value);
                return value;
            }
            case STRING -> {
                String value = String.valueOf(raw);
                if (value.length() > 2048) {
                    throw new ConfigValidationException(path, "文本过长（上限 2048 字符）");
                }
                return value;
            }
            case ENUM -> {
                String value = String.valueOf(raw).trim().toLowerCase(Locale.ROOT);
                if (!entry.choices().contains(value)) {
                    throw new ConfigValidationException(path,
                            "可选值：" + String.join(" / ", entry.choices()));
                }
                return value;
            }
            case STRING_LIST -> {
                List<String> values = new ArrayList<>();
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        if (item != null && !String.valueOf(item).isBlank()) {
                            values.add(String.valueOf(item).trim());
                        }
                    }
                } else {
                    String text = String.valueOf(raw);
                    for (String item : text.split(",")) {
                        if (!item.isBlank()) {
                            values.add(item.trim());
                        }
                    }
                }
                return List.copyOf(values);
            }
            case INT_LIST -> {
                List<Integer> values = new ArrayList<>();
                if (raw instanceof List<?> list) {
                    for (Object item : list) {
                        values.add(toInt(entry, item));
                    }
                } else {
                    String text = String.valueOf(raw);
                    for (String item : text.split("[,\\s]+")) {
                        if (!item.isBlank()) {
                            values.add(toInt(entry, item));
                        }
                    }
                }
                if (entry.defaultValue() instanceof List<?> defaults
                        && values.size() != defaults.size()) {
                    throw new ConfigValidationException(path,
                            "应为 " + defaults.size() + " 个整数（当前 " + values.size() + " 个）");
                }
                for (int value : values) {
                    checkRange(entry, value);
                }
                return List.copyOf(values);
            }
            case LEVEL_MAP -> {
                if (!(raw instanceof Map<?, ?> map)) {
                    throw new ConfigValidationException(path, "应为“命令路径: 等级”的映射");
                }
                Map<String, String> out = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : map.entrySet()) {
                    String key = String.valueOf(e.getKey()).trim();
                    String value = String.valueOf(e.getValue()).trim().toLowerCase(Locale.ROOT);
                    if (key.isEmpty()) {
                        throw new ConfigValidationException(path, "命令路径不能为空");
                    }
                    if (!LEVELS.contains(value)) {
                        throw new ConfigValidationException(path + "." + key,
                                "等级可选值：" + String.join(" / ", LEVELS));
                    }
                    out.put(key, value);
                }
                return out;
            }
            case TEMPLATE -> {
                try {
                    List<TemplatePart> parts = TemplateCodec.parse(raw);
                    OutputTemplate template = OutputCatalog.template(entry.path());
                    if (template != null) {
                        Map<String, String> errors = TemplateCodec.validate(template, parts);
                        if (!errors.isEmpty()) {
                            throw new ConfigValidationException(path,
                                    errors.values().iterator().next());
                        }
                    }
                    return TemplateCodec.toConfig(parts);
                } catch (TemplateCodec.FormatException e) {
                    throw new ConfigValidationException(path, e.field() + " " + e.getMessage());
                }
            }
            default -> throw new ConfigValidationException(path, "不支持的类型");
        }
    }

    private static int toInt(ConfigEntry entry, Object raw) throws ConfigValidationException {
        double value;
        if (raw instanceof Number n) {
            value = n.doubleValue();
        } else {
            try {
                value = Double.parseDouble(String.valueOf(raw).trim());
            } catch (NumberFormatException e) {
                throw new ConfigValidationException(entry.path(), "应为整数");
            }
        }
        if (!Double.isFinite(value) || value != Math.rint(value)) {
            throw new ConfigValidationException(entry.path(), "应为整数");
        }
        return (int) value;
    }

    private static void checkRange(ConfigEntry entry, double value)
            throws ConfigValidationException {
        if (entry.min() != null && value < entry.min()) {
            throw new ConfigValidationException(entry.path(), "不能小于 " + trim(entry.min()));
        }
        if (entry.max() != null && value > entry.max()) {
            throw new ConfigValidationException(entry.path(), "不能大于 " + trim(entry.max()));
        }
    }

    private static String trim(double value) {
        return value == Math.rint(value) ? String.valueOf((long) value) : String.valueOf(value);
    }

    @SuppressWarnings("unchecked")
    public static Object deepCopy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<Object, Object> copy = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : map.entrySet()) {
                copy.put(e.getKey(), deepCopy(e.getValue()));
            }
            return copy;
        }
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>();
            for (Object item : list) {
                copy.add(deepCopy(item));
            }
            return copy;
        }
        return value;
    }
}
