package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.season.SeasonManager;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;

/**
 * 周目业务命令：{@code /samsara season ...}
 * <p>
 * 包含 status/info/create/start/end/skip/respawn-groups 子命令。
 * <p>
 * <b>命令命名空间</b>：全部命令以 {@code /samsara} 开头，
 * 正式入口 {@code /samsara season ...}（挂在 /samsara 根下，与其它模组不冲突）。
 * <p>
 * 权限：使用原版 COMMANDS_ADMIN（OP 等级 2），后续可接 fabric-permissions-api。
 */
public class SeasonCommand {
    private static SeasonManager manager() { return SeasonManager.getInstance(); }

    public static void register() {
        // 命令树统一由 DebugCommand 挂在 /samsara 根下（seasonNode()），这里不再单独注册 /season
    }

    /** {@code /samsara season ...} 子树：由 DebugCommand 挂到 /samsara 根下 */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> seasonNode() {
        return Commands.literal("season")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker.check(src, "season"))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource(), 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .suggests(SuggestionProviders.choicesWithHint("页码", "1", "2", "3"))
                                .executes(ctx -> status(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "page")))))
                .then(Commands.literal("info")
                        .executes(ctx -> replyWithRefresh(ctx.getSource(), infoText(ctx.getSource()),
                                "/samsara season info", "重新读取周目与世界信息")))
                .then(Commands.literal("create")
                        .requires(src -> isAdmin(src))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> reply(ctx.getSource(), manager().create(
                                        StringArgumentType.getString(ctx, "name"), "same-world")))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .suggests(SuggestionProviders.seasonModesProvider())
                                        .executes(ctx -> reply(ctx.getSource(), manager().create(
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "mode")))))))
                .then(Commands.literal("start")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), confirmStart(ctx.getSource()))))
                .then(Commands.literal("end")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), manager().end())))
                .then(Commands.literal("skip")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), manager().skip())))
                .then(Commands.literal("respawn-groups")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(),
                                manager().respawnGroups(ctx.getSource().getServer()))));
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    /** 周目状态/信息：只读，附 [刷新] 点击即执行（保留当前页码）。 */
    private static int status(CommandSourceStack src, int page) {
        return replyWithRefresh(src, manager().statusText(page),
                "/samsara season status" + (page > 1 ? " " + page : ""), "重新查询周目状态");
    }

    private static int replyWithRefresh(CommandSourceStack src, String message,
                                        String refreshCommand, String hover) {
        MutableComponent refresh = ChatUi.run("刷新", refreshCommand, hover);
        ChatUi.send(src, ChatFormat.formatComponent(message),
                ChatUi.line(ChatUi.text(ChatFormat.getContinuePrefix(), ChatFormatting.GRAY), refresh));
        return 1;
    }

    /**
     * {@code /samsara season start} —— 不可逆操作，需二次确认
     * （实现《设计规范》〈破坏性操作：二次确认〉与需求 `F-63`）。
     * <p>
     * 一条命令会连锁触发：时间倒流 → <b>物理删除未圈地区块</b> → 全员传送。
     * 误触代价极高，因此首次输入只返回"将要发生什么"，再次输入（30 秒内）才真正执行。
     * <p>
     * 无待开始周目时直接返回错误且<b>不进入待确认状态</b>，避免留下半武装状态。
     */
    private static String confirmStart(CommandSourceStack src) {
        com.shenqianye.samsara.season.Season pending = manager().currentPending();
        if (pending == null) {
            return manager().start(src.getServer()); // 无 PENDING 周目：交给 MANAGER 回错误文案
        }
        if (!ConfirmGuard.confirm("season.start")) {
            long rewindMs = manager().settings().rewindDurationMs;
            String rewind = rewindMs > 0
                    ? "时间倒流 " + com.shenqianye.samsara.season.DurationUtil.formatMs(rewindMs)
                    : "不倒流（配置为 0，直接传送）";
            return "⚠ 危险操作确认：开启周目 #" + pending.id + "「" + pending.name + "」（" + pending.mode + "）\n"
                    + "· " + rewind + "：未圈地区块会被逐格恢复到变化前状态，并在倒流完成后【物理删除】（重启后按当前版本重新生成）\n"
                    + "· 倒流完成后【全员传送】至新出生区\n"
                    + "· 圈地（旗帜白名单）区域受保护，不参与倒流与删除\n"
                    + "· 箱子/潜影盒内容随区块文件一同删除；背包/末影箱/进度安全\n"
                    + "再次输入 /samsara season start（" + ConfirmGuard.ttlSeconds() + " 秒内）才会真正执行。";
        }
        return manager().start(src.getServer());
    }

    /** /season info：模组说明 + 当前世界种子与出生点（测试便利：种子可查地图工具熟悉地形） */
    private static String infoText(CommandSourceStack src) {
        StringBuilder sb = new StringBuilder("周目管理 samsara · 分组出生（设计见 docs/周目系统设计文档.md）");
        MinecraftServer server = src.getServer();
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world != null) {
            BlockPos spawn = world.getRespawnData().pos();
            sb.append("\n主世界种子: ").append(world.getSeed())
                    .append(" · 出生点: ").append(spawn.getX()).append(", ").append(spawn.getY()).append(", ").append(spawn.getZ());
        }
        return sb.toString();
    }

    /**
     * 发送格式化回复消息到聊天栏。
     * <p>
     * 使用 {@link ChatFormat} 统一格式：首行带 {@code [周目管理] ▸ } 前缀，
     * 后续行缩进对齐，自动换行（默认 34 宽度单位）。
     *
     * @param src     命令源
     * @param message 消息文本（支持 {@code \n} 换行）
     * @return 固定返回 1（brigadier 命令执行成功）
     */
    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
