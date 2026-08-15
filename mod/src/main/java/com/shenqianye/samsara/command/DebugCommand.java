package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.permissions.Permissions;

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
 * /samsara debug fakeplayers &lt;数量|名字1,名字2...&gt; —— 生成离线玩家 UUID（M2 分组测试数据）
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
        dispatcher.register(Commands.literal("samsara")
                .executes(ctx -> reply(ctx.getSource(), help()))
                .then(Commands.literal("debug")
                        .requires(src -> isOwner(src))
                        .then(Commands.literal("status")
                                .executes(ctx -> reply(ctx.getSource(), debugStatus())))
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
                                        .executes(DebugCommand::fakePlayers)))));
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String mode, int count) {
        String name = StringArgumentType.getString(ctx, "name");
        return reply(ctx.getSource(), MANAGER.createBulk(name, mode, count));
    }

    /** debug status：短概要（总数+当前），长列表交给 /season status 分页；配置路径放末尾避免被刷掉 */
    private static String debugStatus() {
        Season current = MANAGER.current();
        String cur = current != null
                ? "#" + current.id + "「" + current.name + "」 " + current.state
                : "无（使用 /season create）";
        return "周目总数: " + MANAGER.seasonCount() + " · 当前: " + cur
                + "\n（周目列表请用 /season status [页码] 分页查看）"
                + "\n配置目录: " + MANAGER.configPath();
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
     * fakeplayers 参数三种形态：
     *  - 纯数字 `3`              → TestPlayer1..3
     *  - 名字序列 `Alice,Bob`     → 按名字逐个生成
     *  - 前缀+数量 `guard 3`      → guard1,guard2,guard3
     */
    private static int fakePlayers(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "names").trim();
        List<String> names = new ArrayList<>();
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
            return reply(ctx.getSource(), "参数为空（数字=批量 TestPlayer1..N；前缀+数字=如 guard 3；或用逗号分隔名字）");
        }
        StringBuilder sb = new StringBuilder("生成 " + names.size() + " 个离线玩家（名 → 离线UUID，M2 分组测试数据）：\n");
        for (String name : names) {
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            sb.append("  ").append(name).append(" → ").append(offlineUuid).append('\n');
        }
        return reply(ctx.getSource(), sb.toString().trim());
    }

    private static String help() {
        return "周目管理 samsara 调试指令（COMMANDS_OWNER）：\n"
                + "/samsara debug status —— 状态机详细状态 + season.json 路径\n"
                + "/samsara debug json —— 打印 season.json 原始内容\n"
                + "/samsara debug reload —— 重新从磁盘加载\n"
                + "/samsara debug reset —— 清空全部周目数据（需二次确认）\n"
                + "/samsara debug create <名> [模式] [数量] —— 批量创建历史周目（ENDED）\n"
                + "/samsara debug settime <id> <start|end> <now|epoch毫秒> —— 设置时间戳\n"
                + "/samsara debug fakeplayers <数字|前缀 数量|名字,名字...> —— 生成离线玩家 UUID（M2 分组测试用）";
    }

    private static boolean isOwner(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> Component.literal("[周目管理·调试] " + message), false);
        return 1;
    }
}
