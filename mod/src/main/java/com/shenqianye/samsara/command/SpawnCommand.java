package com.shenqianye.samsara.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.season.WorldStackManager;
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
                .requires(PermissionRequirements.path("spawn"))
                .executes(ctx -> reply(ctx.getSource(),
                        WorldStackManager.getInstance().spawnText(
                                ctx.getSource().getServer(), ctx.getSource().getPlayer(), false)))
                .then(Commands.literal("history")
                        .requires(PermissionRequirements.path("spawn history"))
                        .executes(ctx -> reply(ctx.getSource(),
                                WorldStackManager.getInstance().spawnText(
                                        ctx.getSource().getServer(),
                                        ctx.getSource().getPlayer(), true))));
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
