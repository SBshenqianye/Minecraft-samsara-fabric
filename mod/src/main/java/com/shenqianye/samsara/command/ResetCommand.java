package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.BoolArgumentType;
import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.shenqianye.samsara.season.DurationUtil;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.season.SpawnHistory;
import com.shenqianye.samsara.season.StackTeleport;
import com.shenqianye.samsara.season.WorldStackManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.server.permissions.Permissions;

/**
 * 两个「开新一轮」动作：
 * <ul>
 *   <li>{@code /samsara reset same}：同一世界、同一栈层；倒流 + 删未保护区 + 排斥点；</li>
 *   <li>{@code /samsara reset new [seed] [levelType] [isolate]}：把当前 world 压入栈层，
 *       生成新栈顶；levelType 支持 normal/flat/amplified/...，isolate 控制 data/minecraft 归档。</li>
 * </ul>
 */
public final class ResetCommand {
    private ResetCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> resetNode() {
        RequiredArgumentBuilder<CommandSourceStack, Boolean> isolate =
                Commands.argument("isolate", BoolArgumentType.bool())
                        .executes(ctx -> confirmNew(ctx.getSource(),
                                LongArgumentType.getLong(ctx, "seed"),
                                StringArgumentType.getString(ctx, "levelType"),
                                BoolArgumentType.getBool(ctx, "isolate")));

        RequiredArgumentBuilder<CommandSourceStack, String> levelType =
                Commands.argument("levelType", StringArgumentType.word())
                        .suggests((ctx, b) -> SharedSuggestionProvider.suggest(
                                new String[]{"normal", "flat", "amplified", "large_biomes",
                                        "single_biome_surface"}, b))
                        .executes(ctx -> confirmNew(ctx.getSource(),
                                LongArgumentType.getLong(ctx, "seed"),
                                StringArgumentType.getString(ctx, "levelType"), false))
                        .then(isolate);

        RequiredArgumentBuilder<CommandSourceStack, Long> seed =
                Commands.argument("seed", LongArgumentType.longArg())
                        .executes(ctx -> confirmNew(ctx.getSource(),
                                LongArgumentType.getLong(ctx, "seed"), "normal", false))
                        .then(levelType);

        LiteralArgumentBuilder<CommandSourceStack> newNode = Commands.literal("new")
                .requires(ResetCommand::isAdmin)
                .executes(ctx -> confirmNew(ctx.getSource(), null, "normal", false))
                .then(seed);

        return Commands.literal("reset")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                        .check(src, "reset"))
                .then(Commands.literal("same")
                        .requires(ResetCommand::isAdmin)
                        .executes(ctx -> confirmSame(ctx.getSource())))
                .then(newNode);
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static int confirmSame(CommandSourceStack src) {
        SeasonManager manager = SeasonManager.getInstance();
        WorldStackManager stack = WorldStackManager.getInstance();
        stack.refresh(src.getServer(), null);
        if (src.getPlayer() != null) {
            var layer = StackTeleport.layerOf(src.getServer(), src.getPlayer());
            if (layer != null && !layer.top) {
                return reply(src, "同世界重置目前只支持栈顶 world；旧栈层是历史世界，请先 /samsara world enter world");
            }
        }
        SpawnHistory history = stack.currentSpawnHistory(src.getServer());
        if (!ConfirmGuard.confirm("reset.same")) {
            long rewindMs = manager.settings().rewindDurationMs;
            String rewind = rewindMs > 0
                    ? "时间倒流 " + DurationUtil.formatMs(rewindMs)
                    : "立即重置（倒流时长配置为 0，仍会执行清理）";
            return reply(src, "⚠ 危险操作确认：同世界重置（栈顶 world）\n"
                    + "· " + rewind + "：未圈地区块会被逐格恢复，并在倒流完成后【物理删除】\n"
                    + "· 圈地（旗帜白名单）区域受保护，不参与倒流与删除\n"
                    + "· 倒流完成后用排斥算法选新出生点：当前 " + history.summaryText() + "\n"
                    + "· 全员传送到新出生区，世界重生点改到该点，并追加一条出生点历史\n"
                    + "· 不产生新栈层；旧栈世界请回到 world 后再重置\n"
                    + "再次输入 /samsara reset same（" + ConfirmGuard.ttlSeconds()
                    + " 秒内）才会真正执行。");
        }
        return reply(src, manager.resetSame(src.getServer()));
    }

    private static int confirmNew(CommandSourceStack src, Long seed, String levelType, boolean isolate) {
        WorldStackManager stack = WorldStackManager.getInstance();
        stack.refresh(src.getServer(), null);
        if (src.getPlayer() != null) {
            var layer = StackTeleport.layerOf(src.getServer(), src.getPlayer());
            if (layer != null && !layer.top) {
                return reply(src, "新世界会生成新的栈顶；请先 /samsara world enter world 再操作");
            }
        }
        if (!ConfirmGuard.confirm("reset.new")) {
            return reply(src, "⚠ 危险操作确认：把当前 world 压入栈层并生成新栈顶\n"
                    + "· 当前 world 变成 world/samsara/stack/<n> 维度与元数据\n"
                    + "· 新 world 使用独立种子/生成器，旧世界原位保留\n"
                    + "· 种子：" + (seed == null ? "随机" : seed) + "\n"
                    + "· level-type：" + levelType + "（flat=超平坦，amplified=巨大化）\n"
                    + "· data/minecraft：" + (isolate ? "隔离（旧数据归档到旧栈层）" : "共享") + "\n"
                    + "· 服务器会在 5 秒后停服；再次输入确认才会执行\n"
                    + "再次输入 /samsara reset new（" + ConfirmGuard.ttlSeconds() + " 秒内）才会真正执行。");
        }
        return reply(src, SeasonManager.getInstance().resetNew(src.getServer(), seed, levelType, isolate));
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
