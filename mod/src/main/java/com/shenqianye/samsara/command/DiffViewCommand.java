package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.rewind.DiffViewService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

/**
 * 全局 Diff 只读回看（F-166）。
 * <pre>
 * /samsara diff view [--dimension &lt;维度&gt;] [--from &lt;时间&gt;] [--to &lt;时间&gt;]
 *                    [--speed &lt;0.1..64&gt;] [--radius &lt;区块&gt;]
 * /samsara diff view stop [玩家|all]
 * /samsara diff view status
 * </pre>
 * 只发客户端方块更新包，不写世界、不写 diff、不删区块；与保存型回溯分开。
 */
public final class DiffViewCommand {
    private DiffViewCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> diffNode() {
        return Commands.literal("diff")
                .requires(PermissionRequirements.adminOrOwnerAnd("diff"))
                .then(Commands.literal("view")
                        .requires(PermissionRequirements.adminOrOwnerAnd("diff view"))
                        .executes(ctx -> start(ctx, ""))
                        .then(Commands.literal("stop")
                                .requires(PermissionRequirements.adminOrOwnerAnd("diff view stop"))
                                .executes(ctx -> stop(ctx, null))
                                .then(Commands.argument("player", StringArgumentType.word())
                                        .executes(ctx -> stop(ctx,
                                                StringArgumentType.getString(ctx, "player")))))
                        .then(Commands.literal("status")
                                .requires(PermissionRequirements.adminOrOwnerAnd("diff view status"))
                                .executes(ctx -> reply(ctx.getSource(),
                                        DiffViewService.getInstance().status())))
                        .then(Commands.argument("options", StringArgumentType.greedyString())
                                .executes(ctx -> start(ctx,
                                        StringArgumentType.getString(ctx, "options")))));
    }

    private static int start(CommandContext<CommandSourceStack> ctx, String raw) {
        var player = ctx.getSource().getPlayer();
        if (player == null) {
            return reply(ctx.getSource(), "只有玩家可以进入回看");
        }
        Options options;
        try {
            options = parse(raw);
        } catch (IllegalArgumentException e) {
            return reply(ctx.getSource(), "参数错误：" + e.getMessage());
        }
        String result = DiffViewService.getInstance().startGlobal(ctx.getSource().getServer(),
                player, options.dimension, options.from, options.to, options.speed,
                options.radius);
        return reply(ctx.getSource(), result);
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, String target) {
        var source = ctx.getSource();
        return reply(source, DiffViewService.getInstance().stop(source.getServer(),
                source.getPlayer(), target,
                PermissionRequirements.isAdmin(source) || PermissionRequirements.isOwner(source)));
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private record Options(String dimension, String from, String to, double speed, int radius) {
    }

    private static Options parse(String raw) {
        String dimension = null;
        String from = null;
        String to = null;
        double speed = 1.0;
        int radius = DiffViewService.DEFAULT_RADIUS;
        String[] parts = raw == null || raw.isBlank() ? new String[0] : raw.trim().split("\\s+");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            switch (part) {
                case "--dimension", "--dim" -> dimension = value(parts, ++i, part);
                case "--from" -> from = value(parts, ++i, "--from");
                case "--to" -> to = value(parts, ++i, "--to");
                case "--speed" -> {
                    try {
                        speed = Double.parseDouble(value(parts, ++i, "--speed"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--speed 应为 0.1~64 的数字");
                    }
                    if (!(speed >= 0.1 && speed <= 64.0)) {
                        throw new IllegalArgumentException("--speed 应在 0.1~64 之间");
                    }
                }
                case "--radius" -> {
                    try {
                        radius = Integer.parseInt(value(parts, ++i, "--radius"));
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--radius 应为 1~32 的整数");
                    }
                    if (radius < 1 || radius > 32) {
                        throw new IllegalArgumentException("--radius 应在 1~32 之间");
                    }
                }
                default -> throw new IllegalArgumentException("未知选项 " + part);
            }
        }
        return new Options(dimension, from, to, speed, radius);
    }

    private static String value(String[] parts, int index, String option) {
        if (index >= parts.length) {
            throw new IllegalArgumentException(option + " 缺少值");
        }
        return parts[index];
    }
}
