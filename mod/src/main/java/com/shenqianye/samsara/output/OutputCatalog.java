package com.shenqianye.samsara.output;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 后端注册的所有输出消息、变量表、固定按钮与默认模板。
 *
 * <p>这里是输出模板的<b>唯一事实来源</b>：Web 变量面板、默认值、校验、预览样例
 * 与游戏内首次生成全部读这一份定义。
 */
public final class OutputCatalog {
    public static final String PREFIX_SAMPLE = "[周目管理] ▸ ";

    private static final List<OutputTemplate> TEMPLATES = new ArrayList<>();
    private static final Map<String, OutputTemplate> BY_KEY = new LinkedHashMap<>();

    static {
        register(whereResult());
        register(whereBroadcast());
        register(identityStatus());
        register(identityStatusDisabled());
        register(identityStatusInvalid());
        register(identityStatusUngrouped());
        register(claimInfo());
        register(seasonStatusItem());
        register(seasonStatusHeader());
        register(version());
        register(seasonInfo());
        register(claimBoundary());
        register(claimBoundaryFixed());
        register(claimBoundaryDynamic());
        register(whereConsole());
        register(whereAllOff());
        register(whereCooldown());
        register(shutdownCountdown());
        register(shutdownDisconnect());
        register(replyTemplate("season.reply", "周目", "周目命令的动态结果", "[周目管理] ▸ "));
        register(replyTemplate("tpa.reply", "TPA", "TPA 命令的动态结果", "[周目管理] ▸ "));
        register(replyTemplate("track.reply", "回看", "回看命令的动态结果", "[周目管理] ▸ "));
        register(replyTemplate("claim.reply", "圈地", "圈地命令的动态结果", "[周目管理] ▸ "));
        register(replyTemplate("where.reply", "位置", "位置命令的动态结果", "[周目管理] ▸ "));
        register(replyTemplate("debug.reply", "调试", "调试/管理命令的动态结果",
                "[周目管理·调试] ▸ "));
        register(tpaRequestSender());
        register(tpaRequestTarget());
        register(tpaRequestHint());
        register(lotteryStatus());
        register(lotteryBet());
        register(lotteryQuote());
        register(lotteryResult());
        register(lotteryWin());
        register(lotteryCollect());
        register(lotteryRefund());
        register(lotteryCancel());
        register(lotteryError());
        register(lotteryHelp());
        register(lotteryReply());
    }

    private OutputCatalog() {
    }

    private static void register(OutputTemplate template) {
        TEMPLATES.add(template);
        BY_KEY.put(template.key(), template);
    }

    public static List<OutputTemplate> all() {
        return List.copyOf(TEMPLATES);
    }

    public static OutputTemplate template(String key) {
        return BY_KEY.get(key);
    }

    public static boolean has(String key) {
        return BY_KEY.containsKey(key);
    }

    /** 预览使用的样例值（变量 sample → 字符串）。 */
    public static Map<String, Object> sampleValues(String key) {
        OutputTemplate template = template(key);
        Map<String, Object> values = new LinkedHashMap<>();
        if (template == null) {
            return values;
        }
        for (OutputVariable variable : template.variables()) {
            values.put(variable.key(), variable.sample());
        }
        return values;
    }

    // ---- 位置 ----

    private static OutputTemplate whereResult() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("dimension", "维度", "主世界（minecraft:overworld）", true),
                number("x", "X 坐标", "55"),
                number("y", "Y 坐标", "48"),
                number("z", "Z 坐标", "4"),
                number("chunkX", "区块 X", "3"),
                number("chunkZ", "区块 Z", "0"),
                text("facing", "朝向", "北", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("where.result", "位置", "位置查询结果（/samsara where）",
                variables,
                List.of(new OutputButton("broadcast", "广播位置", "/samsara where broadcast",
                        "把你的位置广播给全服（有冷却）", "run", "where broadcast", false)),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new VariablePart("dimension"),
                        new TextPart("\n坐标 "), new VariablePart("x"),
                        new TextPart(", "), new VariablePart("y"),
                        new TextPart(", "), new VariablePart("z"),
                        new TextPart("\n区块 "), new VariablePart("chunkX"),
                        new TextPart(", "), new VariablePart("chunkZ"),
                        new TextPart(" · 朝向 "), new VariablePart("facing"),
                        new TextPart("\n"), new ButtonPart("broadcast")
                ));
    }

    private static OutputTemplate whereBroadcast() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("player", "玩家名", "Alice", true),
                text("dimension", "维度", "主世界（minecraft:overworld）", true),
                number("x", "X 坐标", "55"),
                number("y", "Y 坐标", "48"),
                number("z", "Z 坐标", "4"),
                number("chunkX", "区块 X", "3"),
                number("chunkZ", "区块 Z", "0"),
                text("facing", "朝向", "北", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("where.broadcast", "位置", "位置广播（/samsara where broadcast）",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new VariablePart("player"), new TextPart(" 的位置\n"),
                        new VariablePart("dimension"),
                        new TextPart("\n坐标 "), new VariablePart("x"),
                        new TextPart(", "), new VariablePart("y"),
                        new TextPart(", "), new VariablePart("z"),
                        new TextPart("\n区块 "), new VariablePart("chunkX"),
                        new TextPart(", "), new VariablePart("chunkZ"),
                        new TextPart(" · 朝向 "), new VariablePart("facing")
                ));
    }

    // ---- 身份组 ----

    private static OutputTemplate identityStatus() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("groupName", "身份组名", "Bot", true),
                uuid("dataUuid", "data UUID", "00000000-0000-0000-0000-000000000000"),
                text("premium", "正版账号 UUID", "（未设置）", true),
                text("offline", "离线账号 UUID", "（未设置）", true),
                optional("noteLine", "备注行", "\n备注: 测试账号"),
                optional("lastWriterLine", "最后写入账号行", "\n最后写入账号: 00000000-0000-0000-0000-000000000000"),
                optional("blockedLine", "同步阻断行", "\n⚠ 同步已阻断（sync-blocked），请联系管理员")
        ));
        variables.add(prefix());
        return new OutputTemplate("identity.status", "身份", "身份组状态（已加入身份组）",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("身份组「"), new VariablePart("groupName"),
                        new TextPart("」\ndata UUID: "), new VariablePart("dataUuid"),
                        new TextPart("\n正版账号 UUID: "), new VariablePart("premium"),
                        new TextPart("\n离线账号 UUID: "), new VariablePart("offline"),
                        new VariablePart("noteLine"),
                        new VariablePart("lastWriterLine"),
                        new VariablePart("blockedLine")
                ));
    }

    private static OutputTemplate identityStatusDisabled() {
        return new OutputTemplate("identity.status.disabled", "身份", "身份服务关闭时的提示",
                List.of(prefix()), List.of(),
                List.of(new PrefixPart(PREFIX_SAMPLE), new TextPart(
                        "身份组已关闭（identity.enabled=false）。现在只做账号映射，不做登录验证。")));
    }

    private static OutputTemplate identityStatusInvalid() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("issues", "配置问题列表", "\n  · groups[0].data：UUID 格式不正确", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("identity.status.invalid", "身份", "身份组配置非法时的提示",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("身份组配置非法，身份解析已停用："),
                        new VariablePart("issues")
                ));
    }

    private static OutputTemplate identityStatusUngrouped() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                uuid("uuid", "玩家 UUID", "00000000-0000-0000-0000-000000000000")
        ));
        variables.add(prefix());
        return new OutputTemplate("identity.status.ungrouped", "身份", "玩家未加入身份组时的提示",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("未加入身份组 · data = 自身 UUID "),
                        new VariablePart("uuid")
                ));
    }

    // ---- 圈地 ----

    private static OutputTemplate claimInfo() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                number("chunkX", "区块 X", "3"),
                number("chunkZ", "区块 Z", "0"),
                text("dimension", "维度", "minecraft:overworld", true),
                text("status", "保护状态", "✓ 受保护", true),
                optional("detailLine", "旗帜详情行",
                        "\n  旗帜朝向: 北 · 主人: Alice · 图案: base(1区块)")
        ));
        variables.add(prefix());
        return new OutputTemplate("claim.info", "圈地", "圈地信息（/samsara claim info）",
                variables,
                List.of(new OutputButton("refresh", "查看当前区块", "/samsara claim info",
                        "查看你当前所在区块的圈地信息", "run", "claim info", false)),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("区块 ("), new VariablePart("chunkX"),
                        new TextPart(","), new VariablePart("chunkZ"),
                        new TextPart(") "), new VariablePart("dimension"),
                        new TextPart(" "), new VariablePart("status"),
                        new VariablePart("detailLine"),
                        new TextPart("\n"), new ButtonPart("refresh")
                ));
    }

    // ---- 周目 ----

    private static OutputTemplate seasonStatusItem() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                number("id", "周目 ID", "1"),
                text("name", "周目名", "初始", true),
                text("state", "状态", "ACTIVE", true),
                text("mode", "世界模式", "same-world", true),
                optional("startSuffix", "开始时间后缀", " · 始于 2026-10-30 12:00:00"),
                optional("endSuffix", "结束时间后缀", " · 止于 2026-11-01 12:00:00"),
                optional("groupSuffix", "分组后缀", " · 分组 3 组")
        ));
        variables.add(prefix());
        return new OutputTemplate("season.status.item", "周目", "周目状态列表的单行",
                variables, List.of(),
                List.of(
                        new TextPart("#"), new VariablePart("id"),
                        new TextPart("「"), new VariablePart("name"),
                        new TextPart("」 "), new VariablePart("state"),
                        new TextPart(" · "), new VariablePart("mode"),
                        new VariablePart("startSuffix"),
                        new VariablePart("endSuffix"),
                        new VariablePart("groupSuffix")
                ));
    }

    private static OutputTemplate seasonStatusHeader() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                optional("pageLine", "分页行", "\n--- 第 1/2 页，共 9 条 ---"),
                optional("currentLine", "当前周目行", "\n当前：#1「初始」 ACTIVE"),
                optional("worldStackLine", "世界栈行", "\n世界栈：world"),
                optional("rewindLine", "倒流状态行", "\n时间倒流进行中（50%）")
        ));
        variables.add(prefix());
        return new OutputTemplate("season.status.header", "周目", "周目状态列表末尾的汇总块",
                variables,
                List.of(new OutputButton("refresh", "刷新", "/samsara season status",
                        "重新查询周目状态", "run", "season status", true)),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new VariablePart("pageLine"),
                        new VariablePart("currentLine"),
                        new VariablePart("worldStackLine"),
                        new VariablePart("rewindLine"),
                        new TextPart("\n"), new ButtonPart("refresh")
                ));
    }

    // ---- 其他玩家命令 ----

    private static OutputTemplate version() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("name", "模组名称", "服务端周目管理 (Samsara)", true),
                text("version", "模组版本", "0.21.0", true),
                text("minecraft", "Minecraft 版本", "26.3", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("version", "版本", "版本信息（/samsara version）",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("周目管理（"), new VariablePart("name"),
                        new TextPart("） · 版本 "), new VariablePart("version"),
                        new TextPart(" · MC "), new VariablePart("minecraft"),
                        new TextPart(" / Fabric —— 空间/时间/逆熵")
                ));
    }

    private static OutputTemplate seasonInfo() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                number("seed", "主世界种子", "1077061463"),
                number("spawnX", "出生点 X", "55"),
                number("spawnY", "出生点 Y", "91"),
                number("spawnZ", "出生点 Z", "3")
        ));
        variables.add(prefix());
        return new OutputTemplate("season.info", "周目", "周目与世界信息（/samsara season info）",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("周目管理 samsara · 分组出生（设计见 docs/周目系统设计文档.md）"),
                        new TextPart("\n主世界种子: "), new VariablePart("seed"),
                        new TextPart(" · 出生点: "), new VariablePart("spawnX"),
                        new TextPart(", "), new VariablePart("spawnY"),
                        new TextPart(", "), new VariablePart("spawnZ")
                ));
    }

    private static OutputTemplate claimBoundary() {
        return boundaryTemplate("claim.boundary", "边界持续显示", "边界持续显示开关");
    }

    private static OutputTemplate claimBoundaryFixed() {
        return boundaryTemplate("claim.boundary.fixed", "固定边界（旗帜高度）", "固定边界开关");
    }

    private static OutputTemplate claimBoundaryDynamic() {
        return boundaryTemplate("claim.boundary.dynamic", "跟随边界（玩家高度）", "跟随边界开关");
    }

    private static OutputTemplate boundaryTemplate(String key, String label, String description) {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("state", "状态", "开启", true),
                text("detail", "状态后缀", " ✓（进入管理区域自动显示）", true)
        ));
        variables.add(prefix());
        return new OutputTemplate(key, "圈地", description, variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_SAMPLE),
                        new TextPart(label + "已"), new VariablePart("state"),
                        new VariablePart("detail")
                ));
    }

    private static OutputTemplate whereConsole() {
        return new OutputTemplate("where.console", "位置", "控制台执行位置命令时的提示",
                List.of(prefix()), List.of(),
                List.of(new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("该命令需要以玩家身份执行（控制台没有位置）")));
    }

    private static OutputTemplate whereAllOff() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("file", "配置文件名", "commands.yml", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("where.allOff", "位置", "位置输出字段全部关闭时的提示",
                variables, List.of(),
                List.of(new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("（位置输出已在 "), new VariablePart("file"),
                        new TextPart(" 中全部关闭）")));
    }

    private static OutputTemplate whereCooldown() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                number("seconds", "剩余秒数", "24")
        ));
        variables.add(prefix());
        return new OutputTemplate("where.cooldown", "位置", "位置广播冷却提示",
                variables, List.of(),
                List.of(new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("广播冷却中，请 "), new VariablePart("seconds"),
                        new TextPart(" 秒后再试")));
    }

    private static OutputTemplate shutdownCountdown() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                number("seconds", "剩余秒数", "10")
        ));
        variables.add(prefix());
        return new OutputTemplate("shutdown.countdown", "关服", "关服倒计时提示",
                variables, List.of(),
                List.of(new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("服务器将在 "), new VariablePart("seconds"),
                        new TextPart(" 秒后关闭，请做好准备。")));
    }

    private static OutputTemplate shutdownDisconnect() {
        return new OutputTemplate("shutdown.disconnect", "关服", "退出界面的关服提示",
                List.of(prefix()), List.of(),
                List.of(new PrefixPart(PREFIX_SAMPLE),
                        new TextPart("服务器已关闭，请稍后重新连接。")));
    }

    /**
     * 动态报告类消息：内部文本由程序生成，先作为 message 变量迁移到输出系统。
     * 这样至少可编辑前缀、布局和附加文案；逐字段拆变量后续按命令继续细化。
     */
    private static OutputTemplate replyTemplate(String key, String group, String description,
                                                String prefix) {
        return new OutputTemplate(key, group, description,
                List.of(text("message", "命令结果", "命令执行结果", true), prefix()),
                List.of(),
                List.of(new PrefixPart(prefix), new VariablePart("message")));
    }

    private static OutputTemplate tpaRequestSender() {
        return new OutputTemplate("tpa.request.sender", "TPA", "TPA 发起者请求行",
                List.of(text("message", "请求文案", "已向 Bot2 发送传送请求（60 秒内有效）", true),
                        prefix()),
                List.of(new OutputButton("cancel", "取消请求", "/samsara tpcancel",
                        "取消尚未处理的请求（对方会收到提示）", "run", "tpcancel", false)),
                List.of(new PrefixPart(PREFIX_SAMPLE),
                        new VariablePart("message"), new TextPart(" "),
                        new ButtonPart("cancel")));
    }

    private static OutputTemplate tpaRequestTarget() {
        return new OutputTemplate("tpa.request.target", "TPA", "TPA 收件人请求行",
                List.of(text("message", "请求文案", "Bot 请求传送到你这里。", true),
                        prefix()),
                List.of(
                        new OutputButton("accept", "同意", "/samsara tpaccept",
                                "同意传送请求（接受后立即执行）", "run", "tpaccept", false),
                        new OutputButton("deny", "拒绝", "/samsara tpdeny",
                                "拒绝传送请求", "run", "tpdeny", false)),
                List.of(new PrefixPart(PREFIX_SAMPLE),
                        new VariablePart("message"), new TextPart(" "),
                        new ButtonPart("accept"), new TextPart(" "), new ButtonPart("deny")));
    }

    private static OutputTemplate tpaRequestHint() {
        return new OutputTemplate("tpa.request.hint", "TPA", "TPA 请求有效期提示",
                List.of(text("message", "提示文案",
                        "请求在 60 秒内有效；也可直接输入 /tpaccept 或 /tpdeny", true)),
                List.of(),
                List.of(new PrefixPart("  ▸ "), new VariablePart("message")));
    }

    // ---- 死人乐透 ----

    private static final String PREFIX_LOTTERY = "[死人乐透] ▸ ";
    private static final String PREFIX_LOTTERY_NEWS = "[死人乐透·早报] ▸ ";

    private static OutputTemplate lotteryStatus() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("dayLabel", "结算日", "2026-11-05", true),
                text("drawTime", "开奖时间", "07:00", true),
                text("cutoffTime", "截止时间", "00:00", true),
                text("potLine", "奖池", "64 钻石", true),
                number("ticketCount", "总票数", "18"),
                number("playerCount", "参与人数", "6"),
                text("myTickets", "我的票", "5 号 × 2 张；7 号 × 1 张", true),
                optional("lastResultLine", "上期摘要", "上期：7 个，2 人中奖，奖池 64 钻石")
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.status", "乐透", "死人乐透状态（/samsara lottery status）",
                variables,
                List.of(
                        new OutputButton("bet", "下注", "/samsara lottery bet ", "猜下一轮死亡人数",
                                "suggest", "lottery bet", false),
                        new OutputButton("history", "历史", "/samsara lottery list", "查看历史开奖",
                                "run", "lottery list", false),
                        new OutputButton("collect", "领奖", "/samsara lottery collect", "领取待领取奖金",
                                "run", "lottery collect", false)),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new TextPart("早上好，夜之城！\n"),
                        new TextPart("结算日 "), new VariablePart("dayLabel"),
                        new TextPart(" · 开奖 "), new VariablePart("drawTime"),
                        new TextPart(" · 截止 "), new VariablePart("cutoffTime"),
                        new TextPart("\n奖池 "), new VariablePart("potLine"),
                        new TextPart(" · 已售 "), new VariablePart("ticketCount"),
                        new TextPart(" 张 / "), new VariablePart("playerCount"), new TextPart(" 人"),
                        new TextPart("\n我的票："), new VariablePart("myTickets"),
                        new VariablePart("lastResultLine")
                ));
    }

    private static OutputTemplate lotteryBet() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("dayLabel", "结算日", "2026-11-05", true),
                number("guess", "猜测数字", "7"),
                number("tickets", "张数", "2"),
                text("costLine", "花费", "16 铁锭", true),
                text("potLine", "奖池", "32 钻石", true),
                text("drawTime", "开奖时间", "07:00", true),
                text("cutoffTime", "截止时间", "00:00", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.bet", "乐透", "买票回执（/samsara lottery bet）",
                variables,
                List.of(
                        new OutputButton("again", "再加一张", "/samsara lottery bet ", "继续下注",
                                "suggest", "lottery bet", false),
                        new OutputButton("status", "看奖池", "/samsara lottery status", "查看当前奖池",
                                "run", "lottery status", false)),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new VariablePart("dayLabel"), new TextPart(" 的 "),
                        new VariablePart("guess"), new TextPart(" 号票已登记："),
                        new VariablePart("tickets"), new TextPart(" 张，花费 "),
                        new VariablePart("costLine"), new TextPart("。\n当前奖池 "),
                        new VariablePart("potLine"), new TextPart("。开奖 "),
                        new VariablePart("drawTime"), new TextPart("，截止 "),
                        new VariablePart("cutoffTime"), new TextPart("。\n"),
                        new TextPart("夜之城不缺尸体，也不缺赌徒。")
                ));
    }

    private static OutputTemplate lotteryQuote() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("dayLabel", "结算日", "2026-11-05", true),
                number("guess", "猜测数字", "7"),
                number("tickets", "张数", "1"),
                number("affordable", "最多可买", "3"),
                text("itemLine", "主手物品", "附魔钻石镐 × 1", true),
                text("unitLine", "单价", "37.39 VP", true),
                text("availableLine", "总价值", "37.39 VP", true),
                text("costLine", "票价", "8 VP", true),
                text("consumeLine", "将消耗", "1 × 附魔钻石镐", true),
                text("overpayLine", "超额", "超额 29.39 VP 将进入奖池（不找零）。", true),
                text("potLine", "下注后奖池", "37.39 VP", true),
                text("drawTime", "开奖时间", "07:00", true),
                text("cutoffTime", "截止时间", "00:00", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.quote", "乐透", "下注估值（/samsara lottery bet）",
                variables,
                List.of(
                        new OutputButton("confirm", "确认下注", "/samsara lottery bet confirm",
                                "确认后才会扣除物品", "run", "lottery bet", false),
                        new OutputButton("cancel", "取消", "/samsara lottery bet cancel",
                                "放弃本次报价", "run", "lottery bet", false),
                        new OutputButton("status", "看奖池", "/samsara lottery status",
                                "查看当前奖池", "run", "lottery status", false)),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new TextPart("下注估值（未扣物品）\n"),
                        new TextPart("主手："), new VariablePart("itemLine"),
                        new TextPart(" · 单价 "), new VariablePart("unitLine"),
                        new TextPart(" · 总价值 "), new VariablePart("availableLine"),
                        new TextPart("\n当前最多可买 "), new VariablePart("affordable"),
                        new TextPart(" 张；本次 "), new VariablePart("tickets"),
                        new TextPart(" 张，票价 "), new VariablePart("costLine"),
                        new TextPart("\n将消耗："), new VariablePart("consumeLine"),
                        new TextPart("\n"), new VariablePart("overpayLine"),
                        new TextPart("\n下注后奖池："), new VariablePart("potLine"),
                        new TextPart("\n开奖 "), new VariablePart("drawTime"),
                        new TextPart("，截止 "), new VariablePart("cutoffTime"),
                        new TextPart("。\n60 秒内点击「确认下注」才会扣除物品；"
                                + "快速下注用 /samsara lottery bet now <猜测> [张数]。")
                ));
    }

    private static OutputTemplate lotteryResult() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("periodLabel", "周期", "昨天", true),
                number("deathCount", "死亡数", "7"),
                number("winnerCount", "中奖人数", "2"),
                text("potLine", "奖池", "64 钻石", true),
                text("nextDraw", "下一轮", "2026-11-06 07:00", true),
                optional("causeLine", "死因摘要", "多亏了没完没了的 PvP，光主世界就贡献了 5 个。"),
                optional("flavorLine", "吐槽", "满打满算 7 个，停尸房的冷柜勉强够用。"),
                optional("winnerLine", "中奖行", "中奖：Alice、Bob。"),
                optional("coverageLine", "统计警告", "⚠ 本轮统计不完整。")
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.result", "乐透", "每日开奖早报（广播）",
                variables,
                List.of(
                        new OutputButton("bet", "下注下一轮", "/samsara lottery bet ", "猜下一轮死亡人数",
                                "suggest", "lottery bet", false),
                        new OutputButton("history", "看历史", "/samsara lottery list", "查看历史开奖",
                                "run", "lottery list", false)),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY_NEWS),
                        new TextPart("早上好，夜之城！\n"),
                        new VariablePart("periodLabel"),
                        new TextPart("的死人乐透结果是满打满算的 "),
                        new VariablePart("deathCount"), new TextPart(" 个！\n"),
                        new VariablePart("causeLine"),
                        new VariablePart("flavorLine"), new TextPart("\n"),
                        new TextPart("中奖 "), new VariablePart("winnerCount"),
                        new TextPart(" 人，奖池 "), new VariablePart("potLine"), new TextPart("。\n"),
                        new VariablePart("winnerLine"), new TextPart("\n"),
                        new TextPart("下一轮结算日 "), new VariablePart("nextDraw"), new TextPart("。"),
                        new VariablePart("coverageLine")
                ));
    }

    private static OutputTemplate lotteryWin() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                number("deathCount", "死亡数", "7"),
                number("guess", "猜测数字", "7"),
                text("shareLine", "分得", "32 钻石", true),
                optional("claimLine", "领取提示", "奖金已放入背包。")
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.win", "乐透", "中奖私聊",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new TextPart("你猜中了："), new VariablePart("deathCount"),
                        new TextPart(" 个。"), new VariablePart("guess"),
                        new TextPart(" 号票中奖，分得 "), new VariablePart("shareLine"),
                        new TextPart("。\n"), new VariablePart("claimLine"),
                        new TextPart("\n夜之城会记住你的名字——至少到下一轮开奖之前。")
                ));
    }

    private static OutputTemplate lotteryCollect() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("itemsLine", "物品清单", "32 钻石、8 铁锭", true),
                optional("remainingLine", "剩余提示", "还有 1 张中奖票待领取。")
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.collect", "乐透", "领取奖金",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new TextPart("奖金已到账："), new VariablePart("itemsLine"),
                        new TextPart("。\n"), new VariablePart("remainingLine")
                ));
    }

    private static OutputTemplate lotteryRefund() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("reason", "原因", "统计不完整", true),
                text("itemsLine", "退还物品", "16 铁锭", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.refund", "乐透", "退款/流局",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new TextPart("本轮退款："), new VariablePart("reason"),
                        new TextPart("。已退还 "), new VariablePart("itemsLine"),
                        new TextPart("。\n至少你还能活着花掉它。")
                ));
    }

    private static OutputTemplate lotteryCancel() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("actor", "操作者", "管理员", true),
                text("refunded", "退款摘要", "18 张票", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.cancel", "乐透", "管理员取消",
                variables, List.of(),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new VariablePart("actor"), new TextPart(" 叫停了本轮开奖，已退款 "),
                        new VariablePart("refunded"),
                        new TextPart("。\n夜之城的庄家偶尔也会讲道理。")
                ));
    }

    private static OutputTemplate lotteryError() {
        List<OutputVariable> variables = new ArrayList<>(List.of(
                text("message", "错误文案", "本轮已截止，等下一轮吧。", true)
        ));
        variables.add(prefix());
        return new OutputTemplate("lottery.error", "乐透", "统一错误",
                variables, List.of(),
                List.of(new PrefixPart(PREFIX_LOTTERY), new VariablePart("message")));
    }

    private static OutputTemplate lotteryHelp() {
        return new OutputTemplate("lottery.help", "乐透", "死人乐透帮助",
                List.of(prefix()), List.of(),
                List.of(
                        new PrefixPart(PREFIX_LOTTERY),
                        new TextPart("早死早超生，晚死有奖池。\n"),
                        new TextPart("/samsara lottery status — 奖池与开奖时间\n"),
                        new TextPart("/samsara lottery bet <人数> [张数] — 估值；确认后才扣物品\n"),
                        new TextPart("/samsara lottery bet confirm / cancel — 确认 / 取消报价\n"),
                        new TextPart("/samsara lottery bet now <人数> [张数] — 跳过估值直接买票\n"),
                        new TextPart("/samsara lottery collect — 领取奖金\n"),
                        new TextPart("/samsara lottery list [页] — 历史开奖\n"),
                        new TextPart("/samsara lottery value <物品> — 查询物品价值\n"),
                        new TextPart("/samsara lottery gold [set <USD/oz>] — 查看/设置国际金价")
                ));
    }

    private static OutputTemplate lotteryReply() {
        return new OutputTemplate("lottery.reply", "乐透", "乐透通用回执",
                List.of(text("message", "回执", "已登记。", true), prefix()), List.of(),
                List.of(new PrefixPart(PREFIX_LOTTERY), new VariablePart("message")));
    }

    // ---- 变量工厂 ----

    private static OutputVariable prefix() {
        return new OutputVariable("prefix", "聊天前缀", PREFIX_SAMPLE, false,
                OutputVariableType.TEXT);
    }

    private static OutputVariable text(String key, String label, String sample, boolean required) {
        return new OutputVariable(key, label, sample, required, OutputVariableType.TEXT);
    }

    private static OutputVariable number(String key, String label, String sample) {
        return new OutputVariable(key, label, sample, true, OutputVariableType.NUMBER);
    }

    private static OutputVariable uuid(String key, String label, String sample) {
        return new OutputVariable(key, label, sample, true, OutputVariableType.UUID);
    }

    private static OutputVariable optional(String key, String label, String sample) {
        return new OutputVariable(key, label, sample, false, OutputVariableType.TEXT);
    }
}
