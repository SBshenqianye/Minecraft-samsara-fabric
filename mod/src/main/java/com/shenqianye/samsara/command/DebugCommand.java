package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.whitelist.WhitelistStore;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec2;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * /samsara debug —— 调试与测试指令集（COMMANDS_OWNER 权限）。
 * <p>
 * <pre>
 * /samsara                          —— 帮助
 * /samsara debug status             —— 状态机详细状态 + season.json 路径
 * /samsara debug json               —— 打印 season.json 原始内容
 * /samsara debug reload             —— 重新从磁盘加载（测 load 路径）
 * /samsara debug reset              —— 清空全部周目数据（危险，需二次确认）
 * /samsara debug create &lt;名&gt; [模式] [数量] —— 批量创建历史周目（ENDED，每天一个）
 * /samsara debug settime &lt;id&gt; &lt;start|end&gt; &lt;now|epoch毫秒&gt; —— 设置时间戳
 * /samsara debug fakeplayers &lt;数量|前缀 数量|名字1,名字2...|clear&gt;
 *                          —— 注入离线玩家名单（M2 分组测试数据），clear 清空注入池
 * /samsara debug groups [名单]       —— 分组 dry-run（不落盘；名单缺省 = 注册玩家）
 * /samsara debug rewind preview     —— 倒流 dry-run（统计将倒流区块，不启动）
 * /samsara debug rewind status      —— 倒流进行状态
 * /samsara debug rewind abort       —— 中止进行中的倒流
 * /samsara debug rewind set &lt;时长&gt;  —— 设置倒流时长（支持 1h/30m/3600s/1d/0.5d）
 * /samsara debug config [键] [值]   —— 查看/设置配置（无键=全部；键=当前值；键+值=设置并回显）
 * /samsara debug whitelist json     —— 打印 whitelist.json 内容
 * /samsara debug whitelist reload   —— 重新加载 whitelist.json
 * </pre>
 * <p>
 * 26.1 API 约定与 {@link SeasonCommand} 一致（Mojang 类名 + PermissionSet 权限模型 + brigadier 参数类型）。
 */
public class DebugCommand {
    private static final SeasonManager MANAGER = SeasonManager.getInstance();
    /** reset 二次确认标记：首次置位提示，再次执行才真正清空 */
    private static boolean resetArmed = false;

    public static void register() {
        CommandRegistrationCallback.EVENT.register(DebugCommand::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                         CommandBuildContext buildContext,
                                         Commands.CommandSelection selection) {
        // diff 命令树（fake 虚假 diff / text 空中字画），独立变量避免括号层级错误
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> diffCmd =
                Commands.literal("diff")
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.diffStatus()))
                        .then(Commands.literal("fake")
                                .then(Commands.argument("cx", IntegerArgumentType.integer())
                                        .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                com.shenqianye.samsara.rewind.DiffRecorder.fakeDiff(
                                                                        IntegerArgumentType.getInteger(ctx, "cx"),
                                                                        IntegerArgumentType.getInteger(ctx, "cz"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        com.shenqianye.samsara.rewind.DiffRecorder.stoneStateId()))) // 默认石头
                                                        .then(Commands.argument("stateId", IntegerArgumentType.integer(0, 10000))
                                                                .executes(ctx -> reply(ctx.getSource(),
                                                                        com.shenqianye.samsara.rewind.DiffRecorder.fakeDiff(
                                                                                IntegerArgumentType.getInteger(ctx, "cx"),
                                                                                IntegerArgumentType.getInteger(ctx, "cz"),
                                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                                IntegerArgumentType.getInteger(ctx, "stateId")))))))))
                        .then(Commands.literal("text")
                                .then(Commands.argument("x", IntegerArgumentType.integer())
                                        .then(Commands.argument("y", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .then(Commands.argument("text", StringArgumentType.string())
                                                                .executes(ctx -> reply(ctx.getSource(),
                                                                        com.shenqianye.samsara.rewind.DiffRecorder.fakeDiffText(
                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                                StringArgumentType.getString(ctx, "text"),
                                                                                com.shenqianye.samsara.rewind.DiffRecorder.stoneStateId(), 2))) // 默认石头, scale=2
                                                                .then(Commands.argument("stateId", IntegerArgumentType.integer(0, 10000))
                                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                                com.shenqianye.samsara.rewind.DiffRecorder.fakeDiffText(
                                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                                        IntegerArgumentType.getInteger(ctx, "y"),
                                                                                        IntegerArgumentType.getInteger(ctx, "z"),
                                                                                        StringArgumentType.getString(ctx, "text"),
                                                                                        IntegerArgumentType.getInteger(ctx, "stateId"), 2)))
                                                                        .then(Commands.argument("scale", IntegerArgumentType.integer(1, 8))
                                                                                .executes(ctx -> reply(ctx.getSource(),
                                                                                        com.shenqianye.samsara.rewind.DiffRecorder.fakeDiffText(
                                                                                                IntegerArgumentType.getInteger(ctx, "x"),
                                                                                                IntegerArgumentType.getInteger(ctx, "y"),
                                                                                                IntegerArgumentType.getInteger(ctx, "z"),
                                                                                                StringArgumentType.getString(ctx, "text"),
                                                                                                IntegerArgumentType.getInteger(ctx, "stateId"),
                                                                                                IntegerArgumentType.getInteger(ctx, "scale")))))))))));
        dispatcher.register(Commands.literal("samsara")
                .executes(ctx -> reply(ctx.getSource(), help()))
                .then(Commands.literal("version")
                        .executes(ctx -> reply(ctx.getSource(), versionInfo())))
                .then(Commands.literal("debug")
                        .requires(src -> isOwner(src))
                        .then(Commands.literal("status")
                                .executes(ctx -> reply(ctx.getSource(), debugStatus())))
                        .then(Commands.literal("spawninfo")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.spawnInfo(
                                        ctx.getSource().getPlayerOrException(),
                                        ctx.getSource().getLevel()))))
                        .then(diffCmd)
                        .then(Commands.literal("flow")
                                .then(Commands.literal("setup")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                MANAGER.setupSeason(ctx.getSource().getServer()))))
                                .then(Commands.literal("rewind")
                                        .then(Commands.argument("teleportAfter", StringArgumentType.word())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        MANAGER.startRewindOnly(ctx.getSource().getServer(),
                                                                Boolean.parseBoolean(StringArgumentType.getString(ctx, "teleportAfter")))))))
                                .then(Commands.literal("teleport")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                MANAGER.teleportOnly(ctx.getSource().getServer())))))
                        .then(Commands.literal("json")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.rawJson()
                                        + "\n\n↑ season.json 位置: " + MANAGER.configPath())))
                        .then(Commands.literal("reload")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.reload())))
                        .then(Commands.literal("reset")
                                .executes(ctx -> reply(ctx.getSource(), doReset())))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> create(ctx, "same-world", 1))
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "mode"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                                        .executes(ctx -> create(ctx,
                                                                StringArgumentType.getString(ctx, "mode"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("settime")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("field", StringArgumentType.word())
                                                .then(Commands.argument("value", StringArgumentType.word())
                                                        .executes(ctx -> reply(ctx.getSource(), MANAGER.setTime(
                                                                IntegerArgumentType.getInteger(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "field"),
                                                                StringArgumentType.getString(ctx, "value"))))))))
                        .then(Commands.literal("fakeplayers")
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .executes(DebugCommand::fakePlayers)))
                        .then(Commands.literal("groups")
                                .executes(ctx -> reply(ctx.getSource(), previewGroups(ctx, null)))
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                previewGroups(ctx, StringArgumentType.getString(ctx, "names"))))))
                        .then(Commands.literal("rewind")
                                .then(Commands.literal("preview")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                RewindEngine.getInstance().preview(ctx.getSource().getServer()))))
                                .then(Commands.literal("status")
                                        .executes(ctx -> reply(ctx.getSource(), RewindEngine.getInstance().status())))
                                .then(Commands.literal("abort")
                                        .executes(ctx -> reply(ctx.getSource(), RewindEngine.getInstance().abort())))
                                .then(Commands.literal("purge")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                RewindEngine.getInstance().purgeNow(ctx.getSource().getServer()))))
                                .then(Commands.literal("restore")
                                        .then(Commands.argument("cx", IntegerArgumentType.integer())
                                                .then(Commands.argument("cz", IntegerArgumentType.integer())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                RewindEngine.getInstance().restoreChunkNow(
                                                                        ctx.getSource().getServer(),
                                                                        IntegerArgumentType.getInteger(ctx, "cx"),
                                                                        IntegerArgumentType.getInteger(ctx, "cz")))))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("dur", StringArgumentType.greedyString())
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setRewindDuration(
                                                        StringArgumentType.getString(ctx, "dur")))))))
                        .then(Commands.literal("config")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.config(null, null)))
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ctx -> replyOrErr(ctx, MANAGER.config(
                                                StringArgumentType.getString(ctx, "key"), null)))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> replyOrErr(ctx, MANAGER.config(
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value")))))))
                        .then(Commands.literal("settings")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.settingsText()))
                                .then(Commands.literal("groupdist")
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 10000))
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setGroupMinDistance(
                                                        IntegerArgumentType.getInteger(ctx, "blocks"))))))
                                .then(Commands.literal("birthjitter")
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 1000))
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setBirthpointJitter(
                                                        IntegerArgumentType.getInteger(ctx, "blocks"))))))
                                .then(Commands.literal("birthreset")
                                        .then(Commands.argument("on", StringArgumentType.word())
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setResetIndividualBirthpoint(
                                                        Boolean.parseBoolean(StringArgumentType.getString(ctx, "on"))))))))
                        .then(Commands.literal("claim")
                                .then(Commands.literal("list")
                                        .executes(ctx -> reply(ctx.getSource(), WhitelistStore.getInstance().claimList())))
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        WhitelistStore.getInstance().claimRemove(
                                                                StringArgumentType.getString(ctx, "name"))))))
                                .then(Commands.literal("add")
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .then(Commands.argument("p1", Vec2Argument.vec2())
                                                        .then(Commands.argument("p2", Vec2Argument.vec2())
                                                                .executes(ctx -> claimAddFromVec2(ctx, "name", "p1", "p2", false))))))
                                .then(Commands.literal("addc")
                                        .then(Commands.argument("name", StringArgumentType.string())
                                                .then(Commands.argument("p1", Vec2Argument.vec2())
                                                        .then(Commands.argument("p2", Vec2Argument.vec2())
                                                                .executes(ctx -> claimAddFromVec2(ctx, "name", "p1", "p2", true))))))
                        .then(Commands.literal("whitelist")
                                .then(Commands.literal("json")
                                        .executes(ctx -> reply(ctx.getSource(), WhitelistStore.getInstance().rawJson()
                                                + "\n\n↑ whitelist.json 位置: " + WhitelistStore.getInstance().configPath())))
                                .then(Commands.literal("reload")
                                        .executes(ctx -> reply(ctx.getSource(), WhitelistStore.getInstance().reload())))))));
    }

    /** claim add/addc：从 Vec2 参数解析坐标（支持 ~ 相对偏移 / 数字），chunkMode=true 时按区块坐标×16 */
    private static int claimAddFromVec2(CommandContext<CommandSourceStack> ctx, String nameArg,
                                        String p1Arg, String p2Arg, boolean chunkMode) {
        String name = StringArgumentType.getString(ctx, nameArg);
        Vec2 p1 = Vec2Argument.getVec2(ctx, p1Arg);
        Vec2 p2 = Vec2Argument.getVec2(ctx, p2Arg);
        int x1 = (int) Math.round(p1.x);
        int z1 = (int) Math.round(p1.y);
        int x2 = (int) Math.round(p2.x);
        int z2 = (int) Math.round(p2.y);
        if (chunkMode) {
            x1 *= 16;
            z1 *= 16;
            x2 *= 16;
            z2 *= 16;
        }
        return reply(ctx.getSource(), WhitelistStore.getInstance().claimAdd(name, x1, z1, x2, z2));
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String mode, int count) {
        String name = StringArgumentType.getString(ctx, "name");
        return reply(ctx.getSource(), MANAGER.createBulk(name, mode, count));
    }

    /** debug status：短概要（总数+当前），长列表交给 /season status 分页；配置路径放末尾避免被刷掉 */
    private static String debugStatus() {
        try {
            Season current = MANAGER.current();
            String cur = current != null
                    ? "#" + current.id + "「" + current.name + "」 " + current.state
                    : "无（使用 /season create）";
            return "周目总数: " + MANAGER.seasonCount() + " · 当前: " + cur
                    + " · 注入测试玩家: " + PlayerRegistry.getInstance().injectedCount()
                    + "\n（周目列表请用 /season status [页码] 分页查看）"
                    + "\n配置目录: " + MANAGER.configPath();
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[周目管理 samsara] debugStatus 异常: ", e);
            return "debugStatus 异常: " + e;
        }
    }

    /** reset：需二次确认（首次置位提示，再次输入才真正清空） */
    private static String doReset() {
        if (!resetArmed) {
            resetArmed = true;
            return "⚠ 危险操作确认：清空全部周目数据。\n再次输入 /samsara debug reset 才会真正执行。";
        }
        resetArmed = false;
        return MANAGER.reset();
    }

    /**
     * fakeplayers 三种形态 + clear：
     *  - `clear`             → 清空注入池
     *  - 纯数字 `3`          → TestPlayer1..3
     *  - 前缀+数量 `guard 3` → guard1,guard2,guard3
     *  - 名字序列 `Alice,Bob` → 按名字逐个生成
     * 注入后即进入注册玩家池（视为刚上线），供 /samsara debug groups 与 /season start 分组使用。
     */
    private static int fakePlayers(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "names").trim();
        List<String> names = new ArrayList<>();
        if ("clear".equalsIgnoreCase(arg)) {
            PlayerRegistry.getInstance().clearInjected();
            return reply(ctx.getSource(), "已清空注入的测试玩家（当前注入池 0 人）");
        }
        if (arg.matches("\\d+")) {
            int n = Integer.parseInt(arg);
            for (int i = 1; i <= n; i++) {
                names.add("TestPlayer" + i);
            }
        } else {
            String[] tokens = arg.split("\\s+");
            if (tokens.length == 2 && !tokens[0].isEmpty() && !tokens[0].matches("\\d+") && tokens[1].matches("\\d+")) {
                String prefix = tokens[0];
                int n = Integer.parseInt(tokens[1]);
                for (int i = 1; i <= n; i++) {
                    names.add(prefix + i);
                }
            } else {
                for (String s : arg.split("[,，]")) {
                    s = s.trim();
                    if (!s.isEmpty()) {
                        names.add(s);
                    }
                }
            }
        }
        if (names.isEmpty()) {
            return reply(ctx.getSource(), "参数为空（clear=清空；数字=批量 TestPlayer1..N；前缀+数字=如 guard 3；逗号分隔名字）");
        }
        PlayerRegistry.getInstance().inject(names);
        StringBuilder sb = new StringBuilder("注入 " + names.size() + " 名测试玩家（名 → 离线UUID，M2 分组数据）：\n");
        for (String name : names) {
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            sb.append("  ").append(name).append(" → ").append(offlineUuid).append('\n');
        }
        sb.append("当前注入池共 ").append(PlayerRegistry.getInstance().injectedCount()).append(" 人（clear 清空）");
        return reply(ctx.getSource(), sb.toString().trim());
    }

    /** 分组 dry-run：名单缺省用注册玩家；指定名单用 [] 临时构造（不影响注入池） */
    private static String previewGroups(CommandContext<CommandSourceStack> ctx, String spec) {
        List<PlayerRegistry.RegPlayer> players;
        if (spec != null && !spec.isBlank()) {
            List<String> names = new ArrayList<>();
            for (String s : spec.split("[,，]")) {
                s = s.trim();
                if (!s.isEmpty()) {
                    names.add(s);
                }
            }
            List<PlayerRegistry.RegPlayer> tmp = new ArrayList<>();
            for (String n : names) {
                tmp.add(new PlayerRegistry.RegPlayer(n, PlayerRegistry.offlineUuid(n), System.currentTimeMillis()));
            }
            if (tmp.isEmpty()) {
                return "名单为空";
            }
            players = tmp;
        } else {
            players = PlayerRegistry.getInstance().read(ctx.getSource().getServer());
            if (players.isEmpty()) {
                return "当前没有注册玩家（先用 /samsara debug fakeplayers 注入名单）";
            }
        }
        return MANAGER.previewGroups(ctx.getSource().getServer(), players);
    }

    private static String help() {
        return "周目管理 samsara 指令：\n"
                + "/samsara version —— 模组版本号\n"
                + "/samsara 调试指令（COMMANDS_OWNER）：\n"
                + "/samsara debug status —— 状态机概要 + season.json 路径\n"
                + "/samsara debug spawninfo —— 查看你当前的重生点（pos/维度/forced，定位重生 bug）\n"
                + "/samsara debug json —— 打印 season.json 原始内容\n"
                + "/samsara debug reload —— 重新从磁盘加载\n"
                + "/samsara debug reset —— 清空全部周目数据（需二次确认）\n"
                + "/samsara debug create <名> [模式] [数量] —— 批量创建历史周目（ENDED）\n"
                + "/samsara debug settime <id> <start|end> <now|epoch毫秒> —— 设置时间戳\n"
                + "/samsara debug fakeplayers <clear|数字|前缀 数量|名字,名字...> —— 注入离线测试玩家（M2 分组用）\n"
                + "/samsara debug groups [名单] —— 分组 dry-run（缺省用注册玩家）\n"
                + "/samsara debug rewind preview|status|abort|purge|restore <cx> <cz>|set <时长> —— 倒流控制：预览/状态/中止/立即删未保留区块/强制恢复某区块/时长（1h 30m 3600s 1d）\n"
                + "/samsara debug diff [fake <cx> <cz> <数量> [stateId]] —— 档3差异库状态；fake=注入虚假diff测试可视化倒流\n"
                + "/samsara debug flow setup|rewind <true|false>|teleport —— 拆分start：只准备/只倒流(完成后是否传送)/只传送\n"
                + "/samsara debug config [键] [值] —— 查看/设置配置（无键=全部含类型；键: groupdist/birthjitter/birthreset/rewind/groupmin/groupmax/spawnradius/centermin/centermax）\n"
                + "/samsara debug claim add|addc <名> <p1> <p2>|remove <名>|list —— 圈地保护不删区（坐标支持 ~/~N；add=方块坐标，addc=区块坐标×16；中文名用引号如 \"主城\"）\n"
                + "/samsara debug whitelist json|reload —— 白名单查看/重载";
    }

    /** /samsara version：从 FabricLoader 取模组版本 + 显示名 + 简略配置 */
    private static String versionInfo() {
        String version = "unknown";
        String name = "samsara";
        try {
            var container = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("samsara");
            if (container.isPresent()) {
                version = container.get().getMetadata().getVersion().getFriendlyString();
                name = container.get().getMetadata().getName();
            }
        } catch (Exception e) {
            // 取不到版本就用 unknown
        }
        return "周目管理（" + name + "） · 版本 " + version
                + " · MC 26.2 / Fabric —— 空间/时间/逆熵";
    }

    private static boolean isOwner(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> Component.literal("[周目管理·调试] " + message), false);
        return 1;
    }

    /** config 命令：message==null 表示未知配置项 */
    private static int replyOrErr(CommandContext<CommandSourceStack> ctx, String message) {
        if (message == null) {
            ctx.getSource().sendSuccess(() -> Component.literal(
                    "[周目管理·调试] 未知配置项（可用 /samsara debug config 查看全部）"), false);
            return 0;
        }
        return reply(ctx.getSource(), message);
    }
}