package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.output.OutputRenderer;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * /samsara debug —— 调试与测试指令集（COMMANDS_OWNER 权限）。
 * <p>
 * <pre>
 * /samsara                          —— 帮助
 * /samsara debug status             —— 状态机详细状态 + season.json 路径
 * /samsara debug json               —— 打印 season.json 原始内容
 * /samsara debug reload             —— 重新从磁盘加载（测 load 路径）
 * /samsara debug reset              —— 清空全部周目数据（危险，需二次确认）
 * /samsara debug create &lt;名&gt; [模式] [数量] —— 批量创建历史周目（ENDED，每天一个）
 * /samsara debug settime &lt;id&gt; &lt;start|end&gt; &lt;now|epoch毫秒&gt; —— 设置时间戳
 * /samsara debug fakeplayers &lt;数量|前缀 数量|名字1,名字2...|clear&gt;
 *                          —— 注入离线玩家名单（分组测试数据），clear 清空注入池
 * /samsara debug groups [名单]       —— 分组 dry-run（不落盘；名单缺省 = 注册玩家）
 * /samsara debug rewind preview     —— 倒流 dry-run（统计将倒流区块，不启动）
 * /samsara debug rewind status      —— 倒流进行状态
 * /samsara debug rewind abort       —— 中止进行中的倒流
 * /samsara debug rewind set &lt;时长&gt;  —— 设置倒流时长（支持 1h/30m/3600s/1d/0.5d）
 * /samsara debug config [键] [值]   —— 查看/设置配置（无键=全部；键=当前值；键+值=设置并回显）
 * </pre>
 */
public class DebugCommand {
    static SeasonManager manager() { return SeasonManager.getInstance(); }
    // 破坏性操作的二次确认统一由 ConfirmGuard 管理（按操作 key 隔离 + 30 秒有效期），
    // 不再使用各命令自己的 boolean 待确认标记。

    public static void register() {
        CommandRegistrationCallback.EVENT.register(DebugCommand::registerCommands);
    }

    private static void registerCommands(CommandDispatcher<CommandSourceStack> dispatcher,
                                         CommandBuildContext buildContext,
                                         Commands.CommandSelection selection) {
        // diff 命令树（fake 虚假 diff / text 空中字画），独立变量避免括号层级错误。
        // 坐标参数一律用原版参数类型支持 ~ / ~N 语法糖：
        //   fake/restore 用 ColumnPosArgument（柱坐标 x z → 自动 ÷16 转区块）
        //   text 用 BlockPosArgument（方块坐标 x y z）
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> diffCmd =
                Commands.literal("diff")
                        .requires(PermissionRequirements.ownerAnd("debug diff"))
                        .executes(ctx -> reply(ctx.getSource(), manager().diffStatus()))
                        .then(Commands.literal("fake")
                                .requires(PermissionRequirements.ownerAnd("debug diff fake"))
                                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.ColumnPosArgument.columnPos())
                                        .then(Commands.argument("count", IntegerArgumentType.integer(1, 4096))
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        com.shenqianye.samsara.rewind.DiffRecorder.fakeDiff(
                                                                posToChunkX(ctx, "pos"),
                                                                posToChunkZ(ctx, "pos"),
                                                                IntegerArgumentType.getInteger(ctx, "count"),
                                                                com.shenqianye.samsara.rewind.DiffRecorder.stoneStateId()))) // 默认石头
                                                .then(Commands.argument("stateId", IntegerArgumentType.integer(0, 10000))
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                com.shenqianye.samsara.rewind.DiffRecorder.fakeDiff(
                                                                        posToChunkX(ctx, "pos"),
                                                                        posToChunkZ(ctx, "pos"),
                                                                        IntegerArgumentType.getInteger(ctx, "count"),
                                                                        IntegerArgumentType.getInteger(ctx, "stateId"))))))))
                        .then(Commands.literal("text")
                                .requires(PermissionRequirements.ownerAnd("debug diff text"))
                                .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.BlockPosArgument.blockPos())
                                        .then(Commands.argument("text", StringArgumentType.string())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        com.shenqianye.samsara.rewind.DiffRecorder.fakeDiffText(
                                                                levelOf(ctx),
                                                                posX(ctx, "pos"),
                                                                posY(ctx, "pos"),
                                                                posZ(ctx, "pos"),
                                                                StringArgumentType.getString(ctx, "text"),
                                                                com.shenqianye.samsara.rewind.DiffRecorder.stoneStateId(), 2))) // 默认石头, scale=2
                                                .then(Commands.argument("stateId", IntegerArgumentType.integer(0, 10000))
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                com.shenqianye.samsara.rewind.DiffRecorder.fakeDiffText(
                                                                        levelOf(ctx),
                                                                        posX(ctx, "pos"),
                                                                        posY(ctx, "pos"),
                                                                        posZ(ctx, "pos"),
                                                                        StringArgumentType.getString(ctx, "text"),
                                                                        IntegerArgumentType.getInteger(ctx, "stateId"), 2)))
                                                        .then(Commands.argument("scale", IntegerArgumentType.integer(1, 8))
                                                                .executes(ctx -> reply(ctx.getSource(),
                                                                        com.shenqianye.samsara.rewind.DiffRecorder.fakeDiffText(
                                                                                levelOf(ctx),
                                                                                posX(ctx, "pos"),
                                                                                posY(ctx, "pos"),
                                                                                posZ(ctx, "pos"),
                                                                                StringArgumentType.getString(ctx, "text"),
                                                                                IntegerArgumentType.getInteger(ctx, "stateId"),
                                                                                IntegerArgumentType.getInteger(ctx, "scale")))))))));
        var samsaraNode = Commands.literal("samsara")
                .executes(ctx -> DebugHelpCommand.send(ctx.getSource()))
                .then(DebugHelpCommand.helpNode())
                .then(DebugMetaCommands.versionNode())
                .then(DebugHelpCommand.restartNode())
                .then(IdentityCommand.identityNode())
                .then(com.shenqianye.samsara.opauth.OpAuthCommand.registerNode())
                .then(com.shenqianye.samsara.opauth.OpAuthCommand.loginNode())
                .then(com.shenqianye.samsara.opauth.OpAuthCommand.adminNode())
                .then(DebugMetaCommands.msptNode())
                .then(DebugMetaCommands.tpsNode())
                .then(SeasonCommand.seasonNode())
                .then(WorldCommand.worldNode())
                .then(SeedCommand.seedNode())
                .then(ResetCommand.resetNode())
                .then(SpawnCommand.spawnNode())
                .then(ClaimCommand.claimNode())
                .then(TrackViewCommand.trackNode())
                .then(TrackViewCommand.cameraNode())
                .then(ChunkCommand.chunkNode())
                .then(DiffViewCommand.diffNode())
                .then(WhereCommand.whereNode())
                .then(LotteryCommand.lotteryNode())
                .then(TpaCommand.tpaNode())
                .then(TpaCommand.tpaHereNode())
                .then(TpaCommand.acceptNode())
                .then(TpaCommand.denyNode())
                .then(TpaCommand.cancelNode())
                .then(Commands.literal("debug")
                        .requires(PermissionRequirements.ownerAnd("debug"))
                        // debug info — 切换式
                        .then(Commands.literal("info")
                                .requires(PermissionRequirements.ownerAnd("debug info"))
                                .executes(ctx -> {
                                    boolean now = com.shenqianye.samsara.DebugToggle.toggle();
                                    return reply(ctx.getSource(), "调试信息已" + (now ? "开启" : "关闭"));
                                })
                                // debug info on|off — 指定式
                                .then(Commands.literal("on")
                                        .executes(ctx -> { com.shenqianye.samsara.DebugToggle.set(true); return reply(ctx.getSource(), "调试信息已开启"); }))
                                .then(Commands.literal("off")
                                        .executes(ctx -> { com.shenqianye.samsara.DebugToggle.set(false); return reply(ctx.getSource(), "调试信息已关闭"); })))
                        .then(Commands.literal("status")
                                .requires(PermissionRequirements.ownerAnd("debug status"))
                                .executes(ctx -> reply(ctx.getSource(), debugStatus())))
                        .then(Commands.literal("spawninfo")
                                .requires(PermissionRequirements.ownerAnd("debug spawninfo"))
                                .executes(ctx -> reply(ctx.getSource(), manager().spawnInfo(
                                        ctx.getSource().getPlayerOrException(),
                                        ctx.getSource().getLevel()))))
                        .then(Commands.literal("world")
                                .requires(PermissionRequirements.ownerAnd("debug world"))
                                .then(Commands.literal("push")
                                        .requires(PermissionRequirements.ownerAnd("debug world push"))
                                        .then(Commands.argument("world", StringArgumentType.word())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        com.shenqianye.samsara.season.WorldStackManager
                                                                .getInstance().debugPush(
                                                                        ctx.getSource().getServer(),
                                                                        StringArgumentType.getString(ctx, "world"))))))
                                .then(Commands.literal("pop")
                                        .requires(PermissionRequirements.ownerAnd("debug world pop"))
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.season.WorldStackManager
                                                        .getInstance().debugPop(
                                                                ctx.getSource().getServer()))))
                                .then(Commands.literal("reset")
                                        .requires(PermissionRequirements.ownerAnd("debug world reset"))
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.season.WorldStackManager
                                                        .getInstance().debugReset(
                                                                ctx.getSource().getServer()))))
                                .then(Commands.literal("multi")
                                        .requires(PermissionRequirements.ownerAnd("debug world multi"))
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.season.WorldChainTeleport
                                                        .statusText())))
                                // 测试/演示：强制某栈层加载失败，或立即重试（不等待退避）
                                .then(Commands.literal("failstack")
                                        .requires(PermissionRequirements.ownerAnd("debug world failstack"))
                                        .then(Commands.argument("world", StringArgumentType.word())
                                                .then(Commands.argument("state", StringArgumentType.word())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                com.shenqianye.samsara.season.StackDimensionLoader
                                                                        .setDebugFailure(
                                                                                StringArgumentType.getString(ctx, "world"),
                                                                                !"off".equalsIgnoreCase(
                                                                                        StringArgumentType.getString(ctx, "state"))))))))
                                .then(Commands.literal("retrystack")
                                        .requires(PermissionRequirements.ownerAnd("debug world retrystack"))
                                        .then(Commands.argument("world", StringArgumentType.word())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        com.shenqianye.samsara.season.StackDimensionLoader
                                                                .retryNow(ctx.getSource().getServer(),
                                                                        StringArgumentType.getString(ctx, "world")))))))
                        .then(diffCmd)
                        .then(Commands.literal("flow")
                                .requires(PermissionRequirements.ownerAnd("debug flow"))
                                .then(Commands.literal("setup")
                                        .requires(PermissionRequirements.ownerAnd("debug flow setup"))
                                        .executes(ctx -> reply(ctx.getSource(),
                                                manager().setupSeason(ctx.getSource().getServer()))))
                                .then(Commands.literal("rewind")
                                        .requires(PermissionRequirements.ownerAnd("debug flow rewind"))
                                        .then(Commands.argument("teleportAfter", StringArgumentType.word())
                                                .suggests(SuggestionProviders.boolProvider())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        manager().startRewindOnly(ctx.getSource().getServer(),
                                                                Boolean.parseBoolean(StringArgumentType.getString(ctx, "teleportAfter")))))))
                                .then(Commands.literal("teleport")
                                        .requires(PermissionRequirements.ownerAnd("debug flow teleport"))
                                        .executes(ctx -> reply(ctx.getSource(),
                                                manager().teleportOnly(ctx.getSource().getServer())))))
                        .then(Commands.literal("json")
                                .requires(PermissionRequirements.ownerAnd("debug json"))
                                .executes(ctx -> reply(ctx.getSource(), manager().rawJson()
                                        + "\n\n↑ season.json 位置: " + manager().configPath())))
                        .then(Commands.literal("reload")
                                .requires(PermissionRequirements.ownerAnd("debug reload"))
                                .executes(ctx -> {
                                    // 权限配置也走同一条热重载链路：先让 ConfigManager 重读 YAML，
                                    // 再由 reloadHook 刷新 PermissionConfig，补全谓词立即跟随。
                                    com.shenqianye.samsara.config.ConfigManager.getInstance()
                                            .reload(com.shenqianye.samsara.config.ConfigSchema.PERMISSIONS);
                                    // track v2 的 view.drop / recordInventory / retention 也走同一条磁盘重读
                                    com.shenqianye.samsara.config.ConfigManager.getInstance()
                                            .reload(com.shenqianye.samsara.config.ConfigSchema.COMMANDS);
                                    com.shenqianye.samsara.config.ConfigManager.getInstance()
                                            .reload(com.shenqianye.samsara.config.ConfigSchema.LOTTERY);
                                    com.shenqianye.samsara.config.ConfigManager.getInstance()
                                            .reload(com.shenqianye.samsara.config.ConfigSchema.INSURANCE);
                                    com.shenqianye.samsara.permission.PermissionConfig.getInstance().reload();
                                    return reply(ctx.getSource(), manager().reload()
                                            + "\n" + com.shenqianye.samsara.config.CommandConfig
                                            .getInstance().reload()
                                            + "\n权限配置已重载："
                                            + com.shenqianye.samsara.permission.PermissionConfig
                                            .getInstance().getAllEntries().size() + " 条规则");
                                }))
                        .then(Commands.literal("reset")
                                .requires(PermissionRequirements.ownerAnd("debug reset"))
                                .executes(ctx -> reply(ctx.getSource(), doReset())))
                        .then(Commands.literal("create")
                                .requires(PermissionRequirements.ownerAnd("debug create"))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> create(ctx, "same-world", 1))
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "mode"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                                        .executes(ctx -> create(ctx,
                                                                StringArgumentType.getString(ctx, "mode"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("settime")
                                .requires(PermissionRequirements.ownerAnd("debug settime"))
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("field", StringArgumentType.word())
                                                .suggests(SuggestionProviders.choicesWithHint("时间字段", "start", "end"))
                                                .then(Commands.argument("value", StringArgumentType.word())
                                                        .suggests(SuggestionProviders.choicesWithHint("now = 当前时间；也可填 epoch 毫秒", "now"))
                                                        .executes(ctx -> reply(ctx.getSource(), manager().setTime(
                                                                IntegerArgumentType.getInteger(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "field"),
                                                                StringArgumentType.getString(ctx, "value"))))))))
                        .then(DebugPlayerCommands.fakePlayersNode())
                        .then(DebugPlayerCommands.groupsNode())
                        .then(DebugRewindCommands.node())
                        .then(DebugTrackCommands.node())
                        .then(Commands.literal("config")
                                .requires(PermissionRequirements.ownerAnd("debug config"))
                                .executes(ctx -> reply(ctx.getSource(), manager().config(null, null)))
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ctx -> replyOrErr(ctx, manager().config(
                                                StringArgumentType.getString(ctx, "key"), null)))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> replyOrErr(ctx, manager().config(
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value")))))))
                        .then(DebugPlayerCommands.settingsNode())
                        .then(DebugClaimCommands.node())
                        .then(IdentityCommand.debugIdentityNode())
                        .then(permNode())
                        .then(completionNode()));

        dispatcher.register(samsaraNode);
        CommandAliases.markRoot("samsara");
        // 短别名：全部按〈命令谦让〉条件注册；根节点已被其他模组占用时自动让位
        CommandAliases.register(dispatcher, "season", SeasonCommand::seasonNode);
        CommandAliases.register(dispatcher, "reg",
                com.shenqianye.samsara.opauth.OpAuthCommand::registerAliasNode);
        CommandAliases.register(dispatcher, "l",
                com.shenqianye.samsara.opauth.OpAuthCommand::loginAliasNode);
        CommandAliases.register(dispatcher, "claim", ClaimCommand::claimNode);
        CommandAliases.register(dispatcher, "where", WhereCommand::whereNode);
        CommandAliases.register(dispatcher, "lottery", LotteryCommand::lotteryNode);
        CommandAliases.register(dispatcher, "version", DebugMetaCommands::versionNode);
        CommandAliases.register(dispatcher, "tpa", TpaCommand::tpaNode);
        CommandAliases.register(dispatcher, "tpahere", TpaCommand::tpaHereNode);
        CommandAliases.register(dispatcher, "tpaccept", TpaCommand::acceptNode);
        CommandAliases.register(dispatcher, "tpyes", () -> TpaCommand.acceptNode("tpyes"));
        CommandAliases.register(dispatcher, "tpdeny", TpaCommand::denyNode);
        CommandAliases.register(dispatcher, "tpno", () -> TpaCommand.denyNode("tpno"));
        CommandAliases.register(dispatcher, "tpcancel", TpaCommand::cancelNode);
        // 身份组只读短别名（同一让位策略）；组管理走本地 Web
        CommandAliases.register(dispatcher, "identity", IdentityCommand::identityNode);
        // 短别名：/track ...（同一让位策略）
        TrackViewCommand.registerAlias(dispatcher);

        // 条件别名：/mspt 和 /tps（仅当其他模组未注册时）
        DebugMetaCommands.registerAliases(dispatcher);
    }

    /** 构建 /samsara debug completion 子命令（补全诊断） */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> completionNode() {
        return Commands.literal("completion")
                .requires(PermissionRequirements.ownerAnd("debug completion"))
                .then(Commands.literal("page")
                        .requires(PermissionRequirements.ownerAnd("debug completion page"))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 5))
                                .then(Commands.argument("input", StringArgumentType.greedyString())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                IncompleteCommandHandler.debugPage(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "page"),
                                                        StringArgumentType.getString(ctx, "input")))))))
                .then(Commands.literal("send")
                        .requires(PermissionRequirements.ownerAnd("debug completion send"))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .executes(ctx -> debugCompletionSend(ctx.getSource(),
                                        StringArgumentType.getString(ctx, "input")))))
                .then(Commands.argument("input", StringArgumentType.greedyString())
                        .executes(ctx -> debugCompletion(ctx.getSource(),
                                StringArgumentType.getString(ctx, "input"))));
    }

    /** `/samsara debug completion <input>`：只打印候选，便于 RCON/日志核对。 */
    private static int debugCompletion(CommandSourceStack src, String raw) {
        String input = IncompleteCommandHandler.normalize(raw);
        var dispatcher = src.getServer().getCommands().getDispatcher();
        var parse = dispatcher.parse(input, src);
        var node = IncompleteCommandHandler.deepestNode(parse);
        boolean childMode = false;
        if (!parse.getReader().canRead() && node.getCommand() == null
                && !node.getChildren().isEmpty()) {
            parse = dispatcher.parse(input + " ", src);
            node = IncompleteCommandHandler.deepestNode(parse);
            childMode = true;
        }
        var suggestions = dispatcher.getCompletionSuggestions(parse).join();
        boolean fuzzy = false;
        if (suggestions.getList().isEmpty() && !childMode) {
            suggestions = IncompleteCommandHandler.fuzzySuggestions(input, parse, node);
            fuzzy = !suggestions.getList().isEmpty();
        }
        var cells = IncompleteCommandHandler.cells(input, suggestions, node, childMode);
        StringBuilder sb = new StringBuilder("补全诊断：/").append(input)
                .append("\n解析：").append(IncompleteCommandHandler.isIncomplete(parse) ? "未完成/未知" : "完整")
                .append(" · samsara 根节点：")
                .append(CommandAliases.isSamsaraRoot(IncompleteCommandHandler.firstToken(input)) ? "是" : "否")
                .append(" · 候选 ").append(cells.size()).append(" 项")
                .append(fuzzy ? "（近似匹配）" : "");
        int limit = Math.min(cells.size(), 16);
        for (int i = 0; i < limit; i++) {
            sb.append("\n· ").append(cells.get(i).command());
            String tooltip = cells.get(i).tooltip();
            if (tooltip != null && !tooltip.isBlank()) {
                sb.append("  —  ").append(tooltip);
            }
        }
        if (cells.size() > limit) {
            sb.append("\n… 还有 ").append(cells.size() - limit).append(" 项");
        }
        return reply(src, sb.toString());
    }

    /** `/samsara debug completion send <input>`：用当前玩家身份真正触发一次聊天栏候选。 */
    private static int debugCompletionSend(CommandSourceStack src, String raw) {
        String input = IncompleteCommandHandler.normalize(raw);
        var dispatcher = src.getServer().getCommands().getDispatcher();
        var parse = dispatcher.parse(input, src);
        if (!IncompleteCommandHandler.isIncomplete(parse)) {
            return reply(src, "只用于未完成指令；该输入已经是完整指令，未执行");
        }
        String before = IncompleteCommandHandler.lastHandled();
        // 走真正的 performPrefixedCommand 路径，确保 mixin 拦截也被验证到。
        src.getServer().getCommands().performPrefixedCommand(src, input);
        String after = IncompleteCommandHandler.lastHandled();
        boolean handled = !after.equals(before) && !after.isBlank();
        return reply(src, handled
                ? "聊天栏候选：已发送（" + after + "）"
                : "聊天栏候选：未接管（控制台/非 samsara/无候选/配置关闭）");
    }

    /** 构建 /samsara perm 子命令树 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> permNode() {
        return DebugPermissionCommands.node();
    }

    // ---- 坐标语法糖辅助（坐标参数一律用原版参数类型支持 ~ / ~N）----

    /** 命令执行所在的维度（供 fakeDiffText 立即放置字画方块；支持栈维度）。 */
    private static net.minecraft.server.level.ServerLevel levelOf(CommandContext<CommandSourceStack> ctx) {
        return ctx.getSource().getLevel();
    }

    /** BlockPosArgument 的 x */
    private static int posX(CommandContext<CommandSourceStack> ctx, String name) {
        return net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, name).getX();
    }

    /** BlockPosArgument 的 y */
    private static int posY(CommandContext<CommandSourceStack> ctx, String name) {
        return net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, name).getY();
    }

    /** BlockPosArgument 的 z */
    private static int posZ(CommandContext<CommandSourceStack> ctx, String name) {
        return net.minecraft.commands.arguments.coordinates.BlockPosArgument.getBlockPos(ctx, name).getZ();
    }

    /** ColumnPosArgument 的 x（方块坐标）→ 区块 X（÷16） */
    private static int posToChunkX(CommandContext<CommandSourceStack> ctx, String name) {
        return Math.floorDiv(net.minecraft.commands.arguments.coordinates.ColumnPosArgument.getColumnPos(ctx, name).x(), 16);
    }

    /** ColumnPosArgument 的 z（方块坐标）→ 区块 Z（÷16） */
    private static int posToChunkZ(CommandContext<CommandSourceStack> ctx, String name) {
        return Math.floorDiv(net.minecraft.commands.arguments.coordinates.ColumnPosArgument.getColumnPos(ctx, name).z(), 16);
    }

    private static int create(CommandContext<CommandSourceStack> ctx, String mode, int count) {
        String name = StringArgumentType.getString(ctx, "name");
        return reply(ctx.getSource(), manager().createBulk(name, mode, count));
    }

    /** debug status：短概要（总数+当前），长列表交给 /season status 分页；配置路径放末尾避免被刷掉 */
    private static String debugStatus() {
        try {
            Season current = manager().current();
            String cur = current != null
                    ? "#" + current.id + "「" + current.name + "」 " + current.state
                    : "无（使用 /season create）";
            return "周目总数: " + manager().seasonCount() + " · 当前: " + cur
                    + " · 注入测试玩家: " + PlayerRegistry.getInstance().injectedCount()
                    + "\n（周目列表请用 /season status [页码] 分页查看）"
                    + "\n配置目录: " + manager().configPath();
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[周目管理 samsara] debugStatus 异常: ", e);
            return "debugStatus 异常: " + e;
        }
    }

    /** reset：需二次确认（首次置位提示，再次输入才真正清空） */
    private static String doReset() {
        if (!ConfirmGuard.confirm("debug.reset")) {
            return "⚠ 危险操作确认：清空全部周目数据。\n"
                    + "再次输入 /samsara debug reset（" + ConfirmGuard.ttlSeconds() + " 秒内）才会真正执行。";
        }
        return manager().reset();
    }
    /**
     * 发送格式化调试回复消息到聊天栏。
     * <p>
     * 使用 {@link ChatFormat} 统一格式：首行带 {@code [周目管理·调试] ▸ } 前缀，
     * 后续行缩进对齐，自动换行（默认 34 宽度单位）。
     *
     * @param src     命令源
     * @param message 消息文本（支持 {@code \n} 换行）
     * @return 固定返回 1（brigadier 命令执行成功）
     */
    static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> OutputRenderer.renderLegacy("debug.reply", message, src), false);
        return 1;
    }

    /** config 命令：message==null 表示未知配置项 */
    static int replyOrErr(CommandContext<CommandSourceStack> ctx, String message) {
        if (message == null) {
            return reply(ctx.getSource(),
                    "参数不足或未知配置项（perm set 用法：/samsara debug perm set <命令路径> <等级>；"
                            + "配置项可用 /samsara debug config 查看全部）");
        }
        return reply(ctx.getSource(), message);
    }
}


