package com.shenqianye.samsara.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.season.SeasonManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/**
 * {@code /samsara spawn}：查看当前世界的出生点与「新一轮」。
 * <p>
 * 只读命令。轮次由出生点历史推断，不需要注册周目。
 */
public final class SpawnCommand {
    private SpawnCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> spawnNode() {
        return Commands.literal("spawn")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                        .check(src, "spawn"))
                .executes(ctx -> reply(ctx.getSource(),
                        SeasonManager.getInstance().spawnText(ctx.getSource().getServer(), false)))
                .then(Commands.literal("history")
                        .executes(ctx -> reply(ctx.getSource(),
                                SeasonManager.getInstance().spawnText(
                                        ctx.getSource().getServer(), true))));
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
