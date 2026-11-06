package com.shenqianye.samsara.config;

import com.shenqianye.samsara.claim.ClaimRangeConfig;
import com.shenqianye.samsara.output.OutputCatalog;
import com.shenqianye.samsara.output.OutputTemplate;
import com.shenqianye.samsara.output.TemplateCodec;
import com.shenqianye.samsara.output.TemplatePart;
import com.shenqianye.samsara.permission.PermissionDefaults;
import com.shenqianye.samsara.track.TrackRetention;

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
    public static final String LOTTERY = "lottery";
    public static final String INSURANCE = "insurance";

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

    /** 15 种旗帜图案的固定 ID 与中文名；与 {@code Extension.PATTERN_DIR} 对应。 */
    private static final List<String> CLAIM_DIRECTION_NAMES = List.of(
            "north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest", "center");

    private static final String[][] CLAIM_PATTERN_LABELS = {
            { "border", "全边框" },
            { "cross", "交叉" },
            { "straight_cross", "正向十字" },
            { "stripe_top", "顶部条纹" },
            { "stripe_bottom", "底部条纹" },
            { "stripe_left", "左侧条纹" },
            { "stripe_right", "右侧条纹" },
            { "square_top_left", "左上角" },
            { "square_top_right", "右上角" },
            { "square_bottom_left", "左下角" },
            { "square_bottom_right", "右下角" },
            { "stripe_downleft", "左上斜纹" },
            { "stripe_downright", "右上斜纹" },
            { "stripe_center", "居中竖纹" },
            { "stripe_middle", "居中横纹" },
    };

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
                ConfigEntry.bool("view.scrub.enabled", true, "回看",
                        "回看默认开启拖动（按住 Ctrl 进入拖动模式，W/S 前后、Shift 加速、空格暂停；命令 --scrub 可覆盖）"),
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
                ConfigEntry.choice("view.aim.mode", "snap", List.of("snap", "smooth"), "回看",
                        "自动瞄准方式：snap = 按 view.aim.hz 快速对准（默认，避免缓慢拖动导致头晕）；"
                                + "smooth = 按最大角速度平滑转动"),
                ConfigEntry.integer("view.aim.hz", 5, 2, 20, "回看",
                        "snap 模式每秒对准次数（2~5 最自然，20 接近每 tick 跟踪）"),
                ConfigEntry.bool("view.allowEquipmentDrop", true, "回看",
                        "捕捉后变成普通实体的人偶死亡时是否掉落录制装备（F-107；"
                                + "回看停止/人偶 discard 永不掉落）"),
                ConfigEntry.integer("view.groupMaxTargets", 16, 1, 64, "回看",
                        "群组时段回放一次最多目标数（F-97）"),
                ConfigEntry.integer("view.groupMaxSamples", 2_000_000, 10_000, 20_000_000, "回看",
                        "群组回放所有目标合计最多样本数；超限拒绝开始（F-97）"),
                ConfigEntry.integer("view.dedupeWindowMs", 2000, 0, 60_000, "回看",
                        "多个观看者回看同一目标时，轨迹时间相差不超过该毫秒数则共享一个人偶（F-99）"),
                ConfigEntry.bool("view.chatToConsole", false, "回看",
                        "回看聊天模拟是否同时写服务端日志（F-111；默认只发给观看者）"),
                ConfigEntry.bool("rewind.chatHolograms", true, "倒流",
                        "倒流期间是否把玩家发言以全息文字留在发言位置（F-110）"),
                ConfigEntry.integer("rewind.chatMaxDisplays", 64, 1, 256, "倒流",
                        "倒流留言同时存在的最大全息文字数（F-110）"),
                ConfigEntry.integer("rewind.chatRadius", 96, 16, 256, "倒流",
                        "倒流留言只在距离玩家该半径内生成（F-110）"),
                ConfigEntry.integer("rewind.chatWindowMs", 600_000, 1_000, 86_400_000, "倒流",
                        "倒流留言在虚拟时间轴上的可见窗口；越新的发言先出现，离开窗口后消失（F-110）"),
                ConfigEntry.integer("track.chatRetentionDays", 0, 0, 36_500, "聊天",
                        "聊天记录保留天数；0 = 永久保留（F-109）"),
                ConfigEntry.integer("track.clip.maxDurationMs", 7_200_000, 1_000, 7_200_000, "片段收藏",
                        "单个收藏片段最长毫秒数；默认 2 小时（F-103）"),
                ConfigEntry.integer("track.clip.maxPerPlayer", 100, 1, 10_000, "片段收藏",
                        "每个创建者最多收藏片段数（F-103）"),
                ConfigEntry.bool("track.recordInventory", false, "轨迹",
                        "是否额外记录完整背包快照（F-108）；只用于只读查看，"
                                + "不会贴到实体库存、不会产生可拾取物品"),
                ConfigEntry.choice("track.retention.mode", "adaptive",
                        List.of("adaptive", "keepAll", "curve"), "轨迹保留",
                        "保留策略：adaptive = 与 v1 等价的 1/4/16/64/256 天档位；"
                                + "keepAll = 不降采样；curve = 按 track.retention.curve 的比例"),
                ConfigEntry.decimal("track.retention.adaptive.q", 0.9,
                        0.000001, 0.999999, "轨迹保留",
                        "adaptive 的每日保留率（(0,1)，用于容量预估；默认档位与 v1 等价）"),
                ConfigEntry.integer("track.retention.adaptive.skeletonPerDay", 2000,
                        1, 1_000_000, "轨迹保留",
                        "每天至少保留的骨架点数"),
                ConfigEntry.bool("track.retention.keepAll", false, "轨迹保留",
                        "是否保存全部原始采样（与 mode=keepAll 等价；可能无限增长）"),
                ConfigEntry.integer("track.retention.maxBytesPerPlayer", 0,
                        0, 2_000_000_000, "轨迹保留",
                        "单个玩家 .bin 轨迹总字节上限；0 = 不限制。超出先降级到 adaptive，"
                                + "仍超出则拒绝新归档"),
                ConfigEntry.curve("track.retention.curve",
                        TrackRetention.defaultCurveConfig(), "轨迹保留",
                        "curve 模式的时间曲线：ageDays 严格递增（最后一档可为 0 = 更老），"
                                + "keepRatio 在 [0,1] 且单调不增"),
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
                                + "关闭后完全按模板文字输出，行首由你自己控制"),
                ConfigEntry.bool("chain.voidDropSlowFalling", true, "传送链",
                        "下界虚空 → 下层主世界高空坠落时是否给予缓降效果。"
                                + "默认开启，避免 240 格以上自由落体必死；"
                                + "关闭后保留真实摔落伤害"),
                ConfigEntry.integer("chain.voidDropSlowFallingSeconds", 60, 0, 300, "传送链",
                        "虚空高空落点的缓降持续时间（秒）；0 = 不施加缓降。"
                                + "仅在“缓降”开启时生效")
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

        file(CLAIM_RANGE, "圈地范围", claimRangeEntries());

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
                        "访问令牌，由程序在首次启动时生成；请求需 Authorization: Bearer <token>"),
                ConfigEntry.stringList("importRoots", List.of(), "世界栈",
                        "允许 Web 导入世界栈的服务器本地目录（绝对路径）；服务器根目录始终允许。"
                                + "留空时只允许服务器根目录下的 world；来源目录只读，不会被修改"),
                ConfigEntry.integer("backupKeep", 5, 0, 100, "世界栈",
                        "服务器级世界栈备份保留份数：只清理 backups/samsara/world-stack-backup 下的"
                                + "导入/提升栈顶备份，按时间保留最新 N 份；0 表示不自动清理；"
                                + "world-stack-archive 是用户数据，永不自动删除"),
                ConfigEntry.integer("serverBackupKeep", 5, 0, 100, "本体",
                        "本体存档备份保留份数：只清理 backups/samsara/server-backups 下由本功能创建的"
                                + " zip 备份，按时间保留最新 N 份；0 表示不自动清理；"
                                + "源 world 与 pre-restore 回滚目录永不自动删除")
        ));

        file(LOTTERY, "死人乐透", List.of(
                ConfigEntry.bool("enabled", false, "开关",
                        "是否启用死人乐透。关闭后不统计死亡、不接受下注、不自动开奖"),
                ConfigEntry.choice("day.mode", "game", List.of("game", "real"), "时间",
                        "game = 每游戏日一轮（20 分钟）；real = 每现实日一轮"),
                ConfigEntry.string("day.drawTime", "07:00", "时间",
                        "开奖时间。game 模式按游戏内时钟；real 模式按服务器本地时间"),
                ConfigEntry.string("day.cutoffTime", "00:00", "时间",
                        "下注截止时间。默认结算日 00:00，即结算日开始前截止（盲押）"),
                ConfigEntry.bool("count.players", true, "统计",
                        "是否统计玩家死亡"),
                ConfigEntry.bool("count.villagers", true, "统计",
                        "是否统计村民死亡"),
                ConfigEntry.bool("count.wanderingTraders", true, "统计",
                        "是否统计流浪商人死亡"),
                ConfigEntry.bool("count.namedMobs", true, "统计",
                        "是否统计命名怪死亡（任何带自定义名的生物）"),
                ConfigEntry.bool("count.repeatDeaths", true, "统计",
                        "同一玩家/生物重复死亡是否重复计数"),
                ConfigEntry.stringList("count.excludeNames", List.of(), "统计",
                        "不计入死亡数的名字前缀（支持 * 通配，如 Bot*；测试服可用来排除假人）"),
                ConfigEntry.bool("count.revealLiveCount", false, "统计",
                        "是否向公开 API/计分板暴露实时死亡数。默认 false（防开卷）"),
                ConfigEntry.decimal("bet.price", 8.0, 0.0, 1_000_000.0, "下注",
                        "每张票的价值（VP）。1 VP = 1 铁锭；超额默认进奖池"),
                ConfigEntry.integer("bet.maxTicketsPerPlayer", 3, 1, 100, "下注",
                        "每人每轮最多买几张票"),
                ConfigEntry.integer("bet.maxTicketsPerRound", 128, 1, 10_000, "下注",
                        "单轮最多多少张票"),
                ConfigEntry.choice("bet.overpay", "pot", List.of("pot", "reject"), "下注",
                        "模型 B 超额处理：pot = 超额进奖池；reject = 超额拒绝"),
                ConfigEntry.decimal("rake.percent", 5.0, 0.0, 100.0, "抽成",
                        "中奖时抽取的百分比，默认 5%"),
                ConfigEntry.choice("rake.destination", "fund", List.of("fund", "burn", "rollover"), "抽成",
                        "抽成去向：fund = 服务器基金；burn = 销毁；rollover = 并入下期奖池"),
                ConfigEntry.bool("rake.onlyOnPayout", true, "抽成",
                        "是否只在中奖时抽成；滚存不抽"),
                ConfigEntry.choice("settlement.split", "ticket", List.of("ticket", "player"), "开奖",
                        "多人中奖：ticket = 按中奖票数平分；player = 按中奖人平分"),
                ConfigEntry.choice("settlement.noWinner", "rollover", List.of("rollover", "refund"), "开奖",
                        "无人中奖：rollover = 全额滚存；refund = 退款"),
                ConfigEntry.integer("settlement.maxPotValue", 4096, 1, 1_000_000, "开奖",
                        "单轮奖池价值上限（VP），达到后停止售票"),
                ConfigEntry.bool("seed.enabled", true, "种子",
                        "每轮开始时是否从服务器基金抽取一部分作为初始奖池"),
                ConfigEntry.decimal("seed.percent", 10.0, 0.0, 100.0, "种子",
                        "每轮从基金抽取的百分比"),
                ConfigEntry.decimal("seed.maxValue", 512.0, 0.0, 1_000_000.0, "种子",
                        "单轮抽取价值上限（VP）；0 = 不限制"),
                ConfigEntry.decimal("seed.minFundValue", 0.0, 0.0, 1_000_000.0, "种子",
                        "基金价值低于该值时本轮不抽种子"),
                ConfigEntry.bool("gold.syncEnabled", false, "金价",
                        "国际金价开关；开启后金粒/金锭/金块/金压力板按国际金价估值"),
                ConfigEntry.string("gold.sourceUrl", "https://api.gold-api.com/price/XAU", "金价",
                        "金价 JSON 接口；读取其中的 price 字段（USD/盎司）"),
                ConfigEntry.decimal("gold.baseBlockValue", 36.0, 0.0, 1_000_000.0, "金价",
                        "参考金价下的金块价值（VP）；金锭 = 金块/9，金粒 = 金块/81"),
                ConfigEntry.decimal("gold.referenceUsdPerOunce", 4000.0, 0.01, 10_000_000.0, "金价",
                        "基准国际金价（USD/盎司）；当前价相对它缩放"),
                ConfigEntry.decimal("gold.minFactor", 0.5, 0.01, 100.0, "金价",
                        "金价缩放下限（防暴跌导致经济异常）"),
                ConfigEntry.decimal("gold.maxFactor", 2.0, 0.01, 100.0, "金价",
                        "金价缩放上限（防暴涨导致经济异常）"),
                ConfigEntry.integer("gold.syncIntervalMinutes", 360, 1, 10080, "金价",
                        "自动拉取金价的间隔（分钟）"),
                ConfigEntry.bool("audit.enabled", true, "审计",
                        "是否记录乐透审计日志"),
                ConfigEntry.bool("web.publicVisible", true, "Web",
                        "公开用户端是否显示奖池/上期结果（不显示实时死亡数）")
        ));

        file(INSURANCE, "保险", List.of(
                ConfigEntry.bool("enabled", false, "开关",
                        "是否启用物品保险；关闭后不能投保/理赔"),
                ConfigEntry.decimal("premiumMultiplier", 2.0, 0.0, 100.0, "保费",
                        "保费 = 物品价值 × 该倍数；保费从副手物品扣除"),
                ConfigEntry.integer("policyDays", 30, 1, 3650, "保单",
                        "保单有效期（天）"),
                ConfigEntry.integer("claimCooldownMinutes", 60, 0, 100000, "理赔",
                        "两次理赔之间的冷却（分钟）"),
                ConfigEntry.integer("maxClaimsPerDay", 3, 1, 1000, "理赔",
                        "每个玩家每日最多理赔次数"),
                ConfigEntry.decimal("largeClaimValue", 512.0, 0.0, 1000000.0, "理赔",
                        "超过该价值（VP）的理赔进入管理员审核"),
                ConfigEntry.bool("allowStackable", false, "物品",
                        "是否允许堆叠物品投保（默认只保单件，防部分理赔复制）"),
                ConfigEntry.integer("scanRadius", 128, 16, 512, "理赔",
                        "理赔前扫描周围已加载容器/掉落物的半径（格）"),
                ConfigEntry.bool("scanOfflinePlayers", true, "理赔",
                        "理赔前只读扫描离线玩家 .dat 的背包/末影箱"),
                ConfigEntry.bool("scanOtherDimensions", true, "理赔",
                        "理赔前扫描其他维度的已加载容器/掉落物与容器索引缓存"),
                ConfigEntry.bool("useContainerIndex", true, "理赔",
                        "理赔前查询共享容器索引（未加载区块的缓存内容）"),
                ConfigEntry.bool("entities.enabled", false, "生物",
                        "是否启用生物/宠物保险（本版暂未实现，保留开关）")
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

    private static List<ConfigEntry> claimRangeEntries() {
        List<ConfigEntry> entries = new ArrayList<>();
        entries.add(ConfigEntry.choice("mode", "dye", List.of("dye", "fixed"), "保护范围",
                "dye = 每个图案颜色对应一个延伸距离；fixed = 所有图案颜色都使用 fixedDistance"));
        entries.add(ConfigEntry.integer("fixedDistance", 4, 1, 32, "保护范围",
                "固定模式下的单方向延伸距离（区块）；仅 mode=fixed 时生效")
                .when("mode=fixed"));
        entries.add(ConfigEntry.intList("colorDistances",
                List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16),
                1, 32, "保护范围",
                "图案颜色 0~15（白→黑）各自对应的单方向延伸距离（区块）；"
                        + "这是图案层颜色，不是旗面底色；Web 圈地范围页按色块可视化编辑并实时预览保护形状")
                .when("mode=dye"));
        entries.add(ConfigEntry.integer("maxDistance", 16, 1, 32, "保护范围",
                "所有模式下的单方向距离上限；超出会截断，用来限制最大圈地规模"));
        for (String[] pattern : CLAIM_PATTERN_LABELS) {
            entries.add(ConfigEntry.stringList("patternDirections." + pattern[0],
                    ClaimRangeConfig.DEFAULT_PATTERN_DIRECTIONS.get(pattern[0]), "图案方向",
                    pattern[1] + "（" + pattern[0] + "）：该图案在单位长度上的延伸方向"
                            + "（north/northeast/east/southeast/south/southwest/west/northwest/center）；"
                            + "center = 边框，按图案颜色距离画正方形边框（颜色 = 边框半径），"
                            + "不填充内部；只有多面旗帜的射线闭合才会填充内部。颜色只负责延长这些方向，图案本身不决定距离"));
        }
        return entries;
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
                String value = String.valueOf(raw).trim();
                for (String choice : entry.choices()) {
                    if (choice.equalsIgnoreCase(value)) {
                        return choice;
                    }
                }
                throw new ConfigValidationException(path,
                        "可选值：" + String.join(" / ", entry.choices()));
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
                if (path.startsWith("patternDirections.")) {
                    List<String> normalized = new ArrayList<>();
                    for (String name : CLAIM_DIRECTION_NAMES) {
                        if (values.stream().anyMatch(item -> item.equalsIgnoreCase(name))) {
                            normalized.add(name);
                        }
                    }
                    if (normalized.isEmpty()) {
                        throw new ConfigValidationException(path, "至少选择一个方向");
                    }
                    values = normalized;
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
            case CURVE -> {
                if (!(raw instanceof List<?> list) || list.isEmpty()) {
                    throw new ConfigValidationException(path, "应为非空列表（ageDays + keepRatio）");
                }
                List<Map<String, Object>> out = new ArrayList<>();
                for (Object item : list) {
                    if (!(item instanceof Map<?, ?> map)) {
                        throw new ConfigValidationException(path, "每一档应为 ageDays + keepRatio 对象");
                    }
                    Object ageRaw = map.get("ageDays");
                    Object ratioRaw = map.get("keepRatio");
                    if (ageRaw == null || ratioRaw == null) {
                        throw new ConfigValidationException(path, "每一档必须同时包含 ageDays 与 keepRatio");
                    }
                    int age;
                    double ratio;
                    try {
                        double value = Double.parseDouble(String.valueOf(ageRaw).trim());
                        if (value != Math.rint(value)) {
                            throw new NumberFormatException();
                        }
                        age = (int) value;
                        ratio = Double.parseDouble(String.valueOf(ratioRaw).trim());
                    } catch (NumberFormatException e) {
                        throw new ConfigValidationException(path, "ageDays 应为整数、keepRatio 应为数字");
                    }
                    if (age < 0) {
                        throw new ConfigValidationException(path, "ageDays 不能为负");
                    }
                    if (!Double.isFinite(ratio) || ratio < 0 || ratio > 1) {
                        throw new ConfigValidationException(path, "keepRatio 必须在 [0,1] 内");
                    }
                    Map<String, Object> normalized = new LinkedHashMap<>();
                    normalized.put("ageDays", age);
                    normalized.put("keepRatio", ratio);
                    out.add(normalized);
                }
                List<TrackRetention.CurvePoint> points = new ArrayList<>();
                for (Map<String, Object> map : out) {
                    points.add(new TrackRetention.CurvePoint(
                            (int) map.get("ageDays"), (double) map.get("keepRatio")));
                }
                List<String> errors = TrackRetention.validateCurve(points);
                if (!errors.isEmpty()) {
                    throw new ConfigValidationException(path, errors.get(0));
                }
                return List.copyOf(out);
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
