package com.shenqianye.samsara.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.season.StackLayer;
import com.shenqianye.samsara.season.StackTeleport;
import com.shenqianye.samsara.season.WorldStackManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/**
 * 种子查询：{@code /samsara seed}。
 *
 * <p>只显示命令来源当前所在栈层的种子。栈顶 {@code world} 与其他
 * {@code stack_<n>} 的种子互相独立，不能把栈顶种子当成全服种子。
 * 控制台没有“所在栈层”，按栈顶处理。
 */
public final class SeedCommand {
    private SeedCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> seedNode() {
        return Commands.literal("seed")
                .requires(PermissionRequirements.path("seed"))
                .executes(ctx -> show(ctx.getSource()));
    }

    private static int show(CommandSourceStack source) {
        MinecraftServer server = source.getServer();
        ServerPlayer player = source.getPlayer();
        StackLayer layer = player == null ? null : StackTeleport.layerOf(server, player);
        if (layer == null) {
            layer = WorldStackManager.getInstance().manifest(server).top();
        }
        if (layer == null) {
            return reply(source, "暂时没有可用的世界栈层。");
        }
        String label = layer.id + (layer.top ? "（栈顶）" : "");
        return reply(source, "当前栈层 " + label + " 的种子：" + layer.seed);
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
