package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.season.StackTeleport;
import com.shenqianye.samsara.season.WorldStackManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * 世界栈命令：{@code /samsara world ...}
 *
 * <p>v0.24：{@code world} 固定是栈顶；旧栈层是 {@code world/dimensions/samsara/stack_<n>}
 * 自定义维度。{@code enter/leave} 直接传送玩家，不再停服/改 level-name。
 */
public final class WorldCommand {
    private WorldCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> worldNode() {
        return Commands.literal("world")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                        .check(src, "world"))
                .executes(ctx -> reply(ctx.getSource(),
                        WorldStackManager.getInstance().listText(ctx.getSource().getServer())))
                .then(Commands.literal("list")
                        .executes(ctx -> reply(ctx.getSource(),
                                WorldStackManager.getInstance().listText(ctx.getSource().getServer()))))
                .then(Commands.literal("info")
                        .executes(ctx -> info(ctx.getSource())))
                .then(Commands.literal("enter")
                        .requires(WorldCommand::isAdmin)
                        .then(Commands.argument("world", StringArgumentType.word())
                                .suggests(SuggestionProviders.worldsProvider())
                                .executes(ctx -> reply(ctx.getSource(),
                                        StackTeleport.enter(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "world"))))))
                .then(Commands.literal("leave")
                        .requires(WorldCommand::isAdmin)
                        .executes(ctx -> reply(ctx.getSource(),
                                StackTeleport.leave(ctx.getSource()))));
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static int info(CommandSourceStack src) {
        ServerPlayer player = src.getPlayer();
        return reply(src, WorldStackManager.getInstance()
                .infoText(src.getServer(), player));
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
