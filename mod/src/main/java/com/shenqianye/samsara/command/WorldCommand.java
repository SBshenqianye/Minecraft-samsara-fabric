package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.season.WorldStackManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

/**
 * 世界栈命令：{@code /samsara world ...}
 *
 * <p>{@code list}/{@code info} 全员可看；{@code enter}/{@code leave} 需要管理员。
 * 切换世界会写 {@code server.properties} 并停服，必须由启动脚本重新开服。
 */
public final class WorldCommand {
    private WorldCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> worldNode() {
        return Commands.literal("world")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                        .check(src, "world"))
                .executes(ctx -> reply(ctx.getSource(), WorldStackManager.getInstance().listText()))
                .then(Commands.literal("list")
                        .executes(ctx -> reply(ctx.getSource(),
                                WorldStackManager.getInstance().listText())))
                .then(Commands.literal("info")
                        .executes(ctx -> reply(ctx.getSource(),
                                WorldStackManager.getInstance().infoText(ctx.getSource().getServer()))))
                .then(Commands.literal("enter")
                        .requires(WorldCommand::isAdmin)
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(SuggestionProviders.worldsProvider())
                                .executes(ctx -> reply(ctx.getSource(),
                                        WorldStackManager.getInstance().enter(
                                                ctx.getSource().getServer(),
                                                StringArgumentType.getString(ctx, "world"))))))
                .then(Commands.literal("leave")
                        .requires(WorldCommand::isAdmin)
                        .executes(ctx -> reply(ctx.getSource(),
                                WorldStackManager.getInstance().leave(ctx.getSource().getServer()))));
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
