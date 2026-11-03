package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.rewind.ChunkRestoreService;
import com.shenqianye.samsara.rewind.DiffViewService;
import com.shenqianye.samsara.track.view.ViewTime;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;

import java.util.Map;

/**
 * 单区块回溯/回看命令（F-165 / F-166）。
 * <pre>
 * /samsara chunk restore &lt;x&gt; &lt;z&gt; [维度] [--to &lt;时间&gt;] [--preview] [--confirm]
 * /samsara chunk view &lt;x&gt; &lt;z&gt; [维度] [--from &lt;时间&gt;] [--to &lt;时间&gt;] [--speed &lt;0.1..64&gt;]
 * /samsara chunk view stop [玩家|all]
 * /samsara chunk view status
 * </pre>
 * 保存型回溯与只读回看使用不同服务，命令也分开；不能用同一个开关混用。
 */
public final class ChunkCommand {
    private ChunkCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> chunkNode() {
        return Commands.literal("chunk")
                .requires(PermissionRequirements.adminOrOwnerAnd("chunk"))
                .then(restoreNode())
                .then(viewNode());
    }

    private static LiteralArgumentBuilder<CommandSourceStack> restoreNode() {
        return Commands.literal("restore")
                .requires(PermissionRequirements.adminOrOwnerAnd("chunk restore"))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> restore(ctx, ""))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .executes(ctx -> restore(ctx,
                                                StringArgumentType.getString(ctx, "options"))))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> viewNode() {
        return Commands.literal("view")
                .requires(PermissionRequirements.adminOrOwnerAnd("chunk view"))
                .then(Commands.literal("stop")
                        .requires(PermissionRequirements.adminOrOwnerAnd("chunk view stop"))
                        .executes(ctx -> stopView(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .executes(ctx -> stopView(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("status")
                        .requires(PermissionRequirements.adminOrOwnerAnd("chunk view status"))
                        .executes(ctx -> reply(ctx.getSource(), DiffViewService.getInstance().status())))
                .then(Commands.argument("x", IntegerArgumentType.integer())
                        .then(Commands.argument("z", IntegerArgumentType.integer())
                                .executes(ctx -> view(ctx, ""))
                                .then(Commands.argument("options", StringArgumentType.greedyString())
                                        .executes(ctx -> view(ctx,
                                                StringArgumentType.getString(ctx, "options"))))));
    }

    private static int restore(CommandContext<CommandSourceStack> ctx, String raw) {
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        RestoreOptions options;
        try {
            options = parseRestore(raw);
        } catch (IllegalArgumentException e) {
            return reply(ctx.getSource(), "参数错误：" + e.getMessage());
        }
        ChunkRestoreService service = ChunkRestoreService.getInstance();
        String dimension = options.dimension;
        String stackId = null;
        var player = ctx.getSource().getPlayer();
        if (dimension == null || dimension.isBlank()) {
            dimension = player != null
                    ? player.level().dimension().identifier().toString()
                    : "overworld";
        }
        if (player != null) {
            stackId = com.shenqianye.samsara.season.StackPaths.layerIdOf(
                    player.level().dimension());
        }
        int cx = x;
        int cz = z;
        Map<String, Object> summary = service.summaryMap(ctx.getSource().getServer(),
                stackId, dimension, cx, cz, options.to, false);
        String preview = describe(summary, cx, cz);
        if (!Boolean.TRUE.equals(summary.get("ok"))) {
            return reply(ctx.getSource(), preview);
        }
        if (!options.confirm) {
            return reply(ctx.getSource(), preview
                    + "\n执行：/samsara chunk restore " + x + " " + z
                    + (options.dimension == null ? "" : " " + options.dimension)
                    + (options.to == null ? "" : " --to " + options.to) + " --confirm");
        }
        Map<String, Object> result = service.restore(ctx.getSource().getServer(),
                stackId, dimension, cx, cz, options.to);
        if (!Boolean.TRUE.equals(result.get("ok"))) {
            return reply(ctx.getSource(), "回溯失败：" + result.get("error"));
        }
        return reply(ctx.getSource(), String.valueOf(result.get("message")));
    }

    private static String describe(Map<String, Object> summary, int cx, int cz) {
        if (!Boolean.TRUE.equals(summary.get("ok"))) {
            return "区块 (" + cx + "," + cz + ") 不可回溯：" + summary.get("error");
        }
        long earliest = ((Number) summary.get("earliest")).longValue();
        long latest = ((Number) summary.get("latest")).longValue();
        long target = ((Number) summary.get("target")).longValue();
        int changed = ((Number) summary.get("changedBlocks")).intValue();
        String changedText = changed < 0 ? "未加载（执行时临时加载）" : String.valueOf(changed);
        return "单区块回溯预览 · " + summary.get("layer") + " / " + summary.get("dimension")
                + " · 区块 (" + cx + "," + cz + ")\n"
                + "diff 记录 " + summary.get("editCount") + " 条 · 候选方块 "
                + summary.get("candidateBlocks") + " 个 · 将改动 " + changedText + " 格\n"
                + "时间窗 " + ViewTime.format(earliest) + " → " + ViewTime.format(latest)
                + " · 目标 " + ViewTime.format(target)
                + "\n圈地保护：" + yesNo(summary.get("protected"))
                + " · 倒流进行中：" + yesNo(summary.get("rewindRunning"))
                + " · 已加载：" + yesNo(summary.get("loaded"))
                + " · 磁盘存在：" + yesNo(summary.get("onDisk"));
    }

    private static int view(CommandContext<CommandSourceStack> ctx, String raw) {
        var player = ctx.getSource().getPlayer();
        if (player == null) {
            return reply(ctx.getSource(), "只有玩家可以进入回看");
        }
        int x = IntegerArgumentType.getInteger(ctx, "x");
        int z = IntegerArgumentType.getInteger(ctx, "z");
        ViewOptions options;
        try {
            options = parseView(raw);
        } catch (IllegalArgumentException e) {
            return reply(ctx.getSource(), "参数错误：" + e.getMessage());
        }
        String result = DiffViewService.getInstance().startChunk(ctx.getSource().getServer(),
                player, null, options.dimension, x, z,
                options.from, options.to, options.speed);
        return reply(ctx.getSource(), result);
    }

    private static int stopView(CommandContext<CommandSourceStack> ctx, String target) {
        var source = ctx.getSource();
        return reply(source, DiffViewService.getInstance().stop(source.getServer(),
                source.getPlayer(), target, PermissionRequirements.isAdmin(source)
                        || PermissionRequirements.isOwner(source)));
    }

    private static int reply(CommandSourceStack source, String message) {
        source.sendSuccess(() -> Component.literal(message), false);
        return 1;
    }

    private static String yesNo(Object value) {
        return Boolean.TRUE.equals(value) ? "是" : "否";
    }

    private record RestoreOptions(String dimension, String to, boolean confirm) {
    }

    private record ViewOptions(String dimension, String from, String to, double speed) {
    }

    private static RestoreOptions parseRestore(String raw) {
        String dimension = null;
        String to = null;
        boolean confirm = false;
        String[] parts = tokens(raw);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            switch (part) {
                case "--to" -> to = value(parts, ++i, "--to");
                case "--preview" -> {
                }
                case "--confirm" -> confirm = true;
                default -> {
                    if (part.startsWith("--")) {
                        throw new IllegalArgumentException("未知选项 " + part);
                    }
                    if (dimension != null) {
                        throw new IllegalArgumentException("多余参数 " + part);
                    }
                    dimension = part;
                }
            }
        }
        return new RestoreOptions(dimension, to, confirm);
    }

    private static ViewOptions parseView(String raw) {
        String dimension = null;
        String from = null;
        String to = null;
        double speed = 1.0;
        String[] parts = tokens(raw);
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i];
            switch (part) {
                case "--dimension", "--dim" -> dimension = value(parts, ++i, part);
                case "--from" -> from = value(parts, ++i, "--from");
                case "--to" -> to = value(parts, ++i, "--to");
                case "--speed" -> {
                    String value = value(parts, ++i, "--speed");
                    try {
                        speed = Double.parseDouble(value);
                    } catch (NumberFormatException e) {
                        throw new IllegalArgumentException("--speed 应为 0.1~64 的数字");
                    }
                    if (!(speed >= 0.1 && speed <= 64.0)) {
                        throw new IllegalArgumentException("--speed 应在 0.1~64 之间");
                    }
                }
                default -> {
                    if (part.startsWith("--")) {
                        throw new IllegalArgumentException("未知选项 " + part);
                    }
                    if (dimension != null) {
                        throw new IllegalArgumentException("多余参数 " + part);
                    }
                    dimension = part;
                }
            }
        }
        return new ViewOptions(dimension, from, to, speed);
    }

    private static String[] tokens(String raw) {
        if (raw == null || raw.isBlank()) {
            return new String[0];
        }
        return raw.trim().split("\\s+");
    }

    private static String value(String[] parts, int index, String option) {
        if (index >= parts.length) {
            throw new IllegalArgumentException(option + " 缺少值");
        }
        return parts[index];
    }
}
