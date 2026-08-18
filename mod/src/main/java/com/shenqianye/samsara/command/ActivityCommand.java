package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.shenqianye.samsara.activity.ActivityManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * M3 活跃度业务命令（设计文档 §17.3 预留表）：{@code /samsara activity <recent|stats|dead>}。
 * <p>
 * 查询=全员（与 /samsara season 一致）；活跃度概览：近期登录、统计（在线时长/频率/分类）、死服状态。
 * {@code recent} 可选 [天数]（默认 30）。
 * <p>
 * 命令树由 DebugCommand 挂在 {@code /samsara} 根下；调试用 {@code /samsara debug activity ...}（OWNER）。
 * 26.1 API 约定同 {@link SeasonCommand}（Mojang 命名 + PermissionSet）。
 */
public class ActivityCommand {
    private static final ActivityManager MANAGER = ActivityManager.getInstance();

    /** {@code /samsara activity ...} 子树：由 DebugCommand 挂到 /samsara 根下 */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> activityNode() {
        return Commands.literal("activity")
                .executes(ctx -> reply(ctx.getSource(), MANAGER.statsText()))
                .then(Commands.literal("recent")
                        .executes(ctx -> reply(ctx.getSource(),
                                MANAGER.recentText(ActivityManager.DEFAULT_RECENT_DAYS)))
                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                .executes(ctx -> reply(ctx.getSource(),
                                        MANAGER.recentText(IntegerArgumentType.getInteger(ctx, "days"))))))
                .then(Commands.literal("stats")
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.statsText())))
                .then(Commands.literal("dead")
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.deadText())));
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> Component.literal("[周目管理] " + message), false);
        return 1;
    }
}
