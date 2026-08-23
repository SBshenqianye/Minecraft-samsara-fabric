package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.shenqianye.samsara.activity.ActivityManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * 活跃度业务命令：{@code /samsara activity <recent|stats|dead>}。
 * <p>
 * 查询=全员（与 /samsara season 一致）；活跃度概览：近期登录、统计（在线时长/频率/分类）、死服状态。
 * {@code recent} 可选 [天数]（默认 30）。
 * <p>
 * 命令树由 DebugCommand 挂在 {@code /samsara} 根下。
 */
public class ActivityCommand {
    private static final ActivityManager MANAGER = ActivityManager.getInstance();

    /** {@code /samsara activity ...} 子树：由 DebugCommand 挂到 /samsara 根下 */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> activityNode() {
        return Commands.literal("activity")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker.check(src, "activity"))
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

    /**
     * 发送格式化回复消息到聊天栏。
     * <p>
     * 使用 {@link ChatFormat} 统一格式：首行带 {@code [周目管理] ▸ } 前缀，
     * 后续行缩进对齐，自动换行（默认 34 宽度单位）。
     *
     * @param src     命令源
     * @param message 消息文本（支持 {@code \n} 换行）
     * @return 固定返回 1（brigadier 命令执行成功）
     */
    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
