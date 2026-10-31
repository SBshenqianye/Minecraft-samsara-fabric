package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.LongArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.season.DurationUtil;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.season.SpawnHistory;
import com.shenqianye.samsara.season.WorldStackManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

/**
 * 新模型的两个「开新一轮」动作：
 * <ul>
 *   <li>{@code /samsara reset same}：同世界重置（倒流 + 删未保护区 + 排斥点重生）；</li>
 *   <li>{@code /samsara reset new [种子]}：新建独立世界并入栈，全员迁移；</li>
 * </ul>
 * 两者都是不可逆操作，保留 30 秒二次确认。
 */
public final class ResetCommand {
    private ResetCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> resetNode() {
        return Commands.literal("reset")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                        .check(src, "reset"))
                .then(Commands.literal("same")
                        .requires(ResetCommand::isAdmin)
                        .executes(ctx -> confirmSame(ctx.getSource())))
                .then(Commands.literal("new")
                        .requires(ResetCommand::isAdmin)
                        .executes(ctx -> confirmNew(ctx.getSource(), null))
                        .then(Commands.argument("seed", LongArgumentType.longArg())
                                .executes(ctx -> confirmNew(ctx.getSource(),
                                        LongArgumentType.getLong(ctx, "seed")))));
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static int confirmSame(CommandSourceStack src) {
        SeasonManager manager = SeasonManager.getInstance();
        WorldStackManager stack = WorldStackManager.getInstance();
        stack.refresh(src.getServer(), null);
        SpawnHistory history = stack.currentSpawnHistory(src.getServer());
        String world = WorldStackManager.currentWorldName(src.getServer());
        if (!ConfirmGuard.confirm("reset.same")) {
            long rewindMs = manager.settings().rewindDurationMs;
            String rewind = rewindMs > 0
                    ? "时间倒流 " + DurationUtil.formatMs(rewindMs)
                    : "立即重置（倒流时长配置为 0，仍会执行清理）";
            return reply(src, "⚠ 危险操作确认：同世界重置（世界 " + world + "）\n"
                    + "· " + rewind + "：未圈地区块会被逐格恢复，并在倒流完成后【物理删除】\n"
                    + "· 圈地（旗帜白名单）区域受保护，不参与倒流与删除\n"
                    + "· 倒流完成后用排斥算法选新出生点：当前 " + history.summaryText() + "\n"
                    + "· 全员传送到新出生区，世界重生点改到该点，并追加一条出生点历史\n"
                    + "· 箱子/潜影盒内容随区块文件删除；背包/末影箱/进度安全\n"
                    + "再次输入 /samsara reset same（" + ConfirmGuard.ttlSeconds()
                    + " 秒内）才会真正执行。");
        }
        return reply(src, manager.resetSame(src.getServer()));
    }

    private static int confirmNew(CommandSourceStack src, Long seed) {
        WorldStackManager stack = WorldStackManager.getInstance();
        stack.refresh(src.getServer(), null);
        String world = WorldStackManager.currentWorldName(src.getServer());
        if (!stack.isTopCurrent()) {
            ConfirmGuard.disarm("reset.new");
            return reply(src, "只有栈顶世界可以生成新世界（当前 " + world
                    + "，栈顶 " + stack.top() + "）；请先 /samsara world enter " + stack.top());
        }
        if (!ConfirmGuard.confirm("reset.new")) {
            return reply(src, "⚠ 危险操作确认：入栈生成新世界（当前 " + world + "）\n"
                    + "· 新建一个独立世界文件夹（独立种子、独立下界/末地）\n"
                    + "· 旧世界 " + world + " 原地保留，继续留在世界栈下层\n"
                    + "· 服务器会在 5 秒后停服；请用启动脚本重新开服\n"
                    + "· 重启后新世界自动入栈、写第 1 条出生点历史，全员迁移\n"
                    + "· 种子：" + (seed == null ? "随机" : seed) + "（也可 /samsara reset new <种子>）\n"
                    + "再次输入 /samsara reset new（" + ConfirmGuard.ttlSeconds()
                    + " 秒内）才会真正执行。");
        }
        return reply(src, SeasonManager.getInstance().resetNew(src.getServer(), seed));
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
