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
                text("premium", "正版 UUID", "（未设置）", true),
                text("offline", "离线 UUID", "（未设置）", true),
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
                        new TextPart("\npremium: "), new VariablePart("premium"),
                        new TextPart("\noffline: "), new VariablePart("offline"),
                        new VariablePart("noteLine"),
                        new VariablePart("lastWriterLine"),
                        new VariablePart("blockedLine")
                ));
    }

    private static OutputTemplate identityStatusDisabled() {
        return new OutputTemplate("identity.status.disabled", "身份", "身份服务关闭时的提示",
                List.of(prefix()), List.of(),
                List.of(new PrefixPart(PREFIX_SAMPLE), new TextPart(
                        "身份验证已关闭（identity.enabled=false）。身份组只做账号映射，不提供注册/登录。")));
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
