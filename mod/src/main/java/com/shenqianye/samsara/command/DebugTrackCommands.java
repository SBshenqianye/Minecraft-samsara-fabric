package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.permission.PermissionRequirements;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.util.UUID;

/** `/samsara debug track` 子命令组：回放、停止、状态、扫描。 */
final class DebugTrackCommands {

    private DebugTrackCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("track")
                .requires(PermissionRequirements.ownerAnd("debug track"))
                .then(Commands.literal("replay")
                        .requires(PermissionRequirements.ownerAnd("debug track replay"))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.knownPlayersProvider("监控目标"))
                                // 时间参数用 greedyString：`23:28` / ISO 里的 `:` 不能走 word()，
                                // 而自定义 ArgumentType 需要客户端也装模组（本服是服务端模组）→ 登录时会
                                // “Unrecognized argument type”。这里一次收下 start [speed] [cycle] 自行拆分。
                                .then(Commands.argument("args", StringArgumentType.greedyString())
                                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                                replay(ctx))))))
                .then(Commands.literal("stop")
                        .requires(PermissionRequirements.ownerAnd("debug track stop"))
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                com.shenqianye.samsara.track.TrackReplay.stop()))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.knownPlayersProvider("监控目标"))
                                .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                        com.shenqianye.samsara.track.TrackReplay.stop(
                                                StringArgumentType.getString(ctx, "player"))))))
                .then(Commands.literal("status")
                        .requires(PermissionRequirements.ownerAnd("debug track status"))
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                status(ctx.getSource(), null)))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.knownPlayersProvider("监控目标"))
                                .executes(ctx -> DebugCommand.reply(ctx.getSource(), status(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "player"))))))
                .then(TrackViewCommand.debugViewNode())
                .then(TrackViewCommand.debugListNode())
                .then(Commands.literal("sweep")
                        .requires(PermissionRequirements.ownerAnd("debug track sweep"))
                        .executes(ctx -> {
                            com.shenqianye.samsara.track.TrackRecorder.getInstance().sweepAsync();
                            return DebugCommand.reply(ctx.getSource(), "已提交轨迹扫描任务（后台执行）");
                        }));
    }

    static String replay(CommandContext<CommandSourceStack> ctx) {
        String name = StringArgumentType.getString(ctx, "player");
        String raw = StringArgumentType.getString(ctx, "args").trim();
        String[] parts = raw.isEmpty() ? new String[0] : raw.split("\\s+");
        if (parts.length == 0) {
            return "用法：/samsara debug track replay <玩家> <开始时间> [倍速] [周目]";
        }
        String start = parts[0];
        double speed = 1.0;
        if (parts.length > 1) {
            try {
                speed = Double.parseDouble(parts[1]);
            } catch (NumberFormatException e) {
                return "倍速必须是数字：" + parts[1];
            }
        }
        String cycle = parts.length > 2 ? parts[2] : null;
        if (parts.length > 3) {
            return "参数过多：开始时间之后只能跟 [倍速] [周目]";
        }
        var server = ctx.getSource().getServer();
        UUID uuid = com.shenqianye.samsara.track.TrackRecorder.resolveUuid(server, name);
        if (uuid == null) {
            return "找不到玩家 " + name + "（不在线且不在 usercache.json）";
        }
        return com.shenqianye.samsara.track.TrackReplay.start(server, uuid, name, start, speed, cycle);
    }

    /** debug track status [player] */
    static String status(CommandSourceStack src, String name) {
        StringBuilder sb = new StringBuilder(com.shenqianye.samsara.track.TrackReplay.status());
        if (name == null) {
            return sb.toString();
        }
        UUID uuid = com.shenqianye.samsara.track.TrackRecorder.resolveUuid(src.getServer(), name);
        if (uuid == null) {
            return sb.append("\n找不到玩家 ").append(name).toString();
        }
        var files = com.shenqianye.samsara.track.TrackRecorder.getInstance().recentFiles(uuid, 3);
        if (files.isEmpty()) {
            return sb.append("\n").append(name).append(" 最近 7 天没有轨迹文件").toString();
        }
        sb.append("\n").append(name).append(" 最近轨迹（最多 3 个文件）：");
        for (var f : files) {
            long size;
            try {
                size = java.nio.file.Files.size(f);
            } catch (java.io.IOException e) {
                size = -1;
            }
            var parent = f.getParent();
            String where = parent == null ? f.getFileName().toString()
                    : parent.getParent().getFileName() + "/" + parent.getFileName() + "/" + f.getFileName();
            sb.append("\n  ").append(where).append(" · ").append(size / 1024).append(" KB · ")
                    .append(com.shenqianye.samsara.track.TrackStore.countSamples(f)).append(" 条");
        }
        return sb.toString();
    }
}
