package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.season.SeasonManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** `/samsara debug rewind` 子命令组：预览、状态、中止、清理、压缩、恢复与时长。 */
final class DebugRewindCommands {

    private DebugRewindCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("rewind")
                .requires(PermissionRequirements.ownerAnd("debug rewind"))
                .then(Commands.literal("preview")
                        .requires(PermissionRequirements.ownerAnd("debug rewind preview"))
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                RewindEngine.getInstance().preview(ctx.getSource().getServer()))))
                .then(Commands.literal("status")
                        .requires(PermissionRequirements.ownerAnd("debug rewind status"))
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                RewindEngine.getInstance().status())))
                .then(Commands.literal("abort")
                        .requires(PermissionRequirements.ownerAnd("debug rewind abort"))
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                RewindEngine.getInstance().abort())))
                .then(Commands.literal("purge")
                        .requires(PermissionRequirements.ownerAnd("debug rewind purge"))
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(), purge(ctx.getSource()))))
                .then(Commands.literal("compact")
                        .requires(PermissionRequirements.ownerAnd("debug rewind compact"))
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                RewindEngine.getInstance().compactNow(ctx.getSource().getServer()))))
                .then(Commands.literal("restore")
                        .requires(PermissionRequirements.ownerAnd("debug rewind restore"))
                        .then(Commands.argument("pos",
                                        net.minecraft.commands.arguments.coordinates.ColumnPosArgument.columnPos())
                                .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                        RewindEngine.getInstance().restoreChunkNow(
                                                ctx.getSource().getServer(),
                                                chunkX(ctx, "pos"),
                                                chunkZ(ctx, "pos"))))))
                .then(Commands.literal("set")
                        .requires(PermissionRequirements.ownerAnd("debug rewind set"))
                        .then(Commands.argument("dur", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.durationsProvider())
                                .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                        SeasonManager.getInstance().setRewindDuration(
                                                StringArgumentType.getString(ctx, "dur"))))));
    }

    private static int chunkX(CommandContext<CommandSourceStack> ctx, String name) {
        return Math.floorDiv(net.minecraft.commands.arguments.coordinates.ColumnPosArgument
                .getColumnPos(ctx, name).x(), 16);
    }

    private static int chunkZ(CommandContext<CommandSourceStack> ctx, String name) {
        return Math.floorDiv(net.minecraft.commands.arguments.coordinates.ColumnPosArgument
                .getColumnPos(ctx, name).z(), 16);
    }

    /** 立即物理删除已到倒流时刻的未保留区块；需二次确认。 */
    private static String purge(CommandSourceStack src) {
        if (!ConfirmGuard.confirm("rewind.purge")) {
            return "⚠ 危险操作确认：立即删除已到倒流时刻的未保留区块\n"
                    + "· 备份 + 清零，不可逆（重启后按当前版本重新生成）\n"
                    + "· 仍加载的区块入待删队列，卸载即删\n"
                    + "· 圈地（旗帜白名单）区块不在删除范围内\n"
                    + "再次输入 /samsara debug rewind purge（" + ConfirmGuard.ttlSeconds()
                    + " 秒内）才会真正执行。";
        }
        return RewindEngine.getInstance().purgeNow(src.getServer());
    }
}
