package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.output.OutputRenderer;
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
    private static SeasonManager manager() { return SeasonManager.getInstance(); }
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
                        .executes(ctx -> reply(ctx.getSource(), manager().diffStatus()))
                        .then(Commands.literal("fake")
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
                .executes(ctx -> sendHelp(ctx.getSource()))
                .then(Commands.literal("help")
                        .executes(ctx -> sendHelp(ctx.getSource())))
                .then(Commands.literal("version")
                        .executes(ctx -> replyVersion(ctx.getSource())))
                .then(Commands.literal("restart")
                        .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                                .check(src, "restart"))
                        .executes(ctx -> restart(ctx.getSource(),
                                com.shenqianye.samsara.config.CommandConfig.getInstance()
                                        .shutdownCountdownSeconds()))
                        .then(Commands.argument("seconds", IntegerArgumentType.integer(1, 300))
                                .executes(ctx -> restart(ctx.getSource(),
                                        IntegerArgumentType.getInteger(ctx, "seconds")))))
                .then(IdentityCommand.identityNode())
                .then(Commands.literal("mspt")
                        .executes(ctx -> reply(ctx.getSource(), mspt())))
                .then(Commands.literal("tps")
                        .executes(ctx -> reply(ctx.getSource(), tps())))
                .then(SeasonCommand.seasonNode())
                .then(ClaimCommand.claimNode())
                .then(TrackViewCommand.trackNode())
                .then(WhereCommand.whereNode())
                .then(TpaCommand.tpaNode())
                .then(TpaCommand.tpaHereNode())
                .then(TpaCommand.acceptNode())
                .then(TpaCommand.denyNode())
                .then(TpaCommand.cancelNode())
                .then(Commands.literal("debug")
                        .requires(src -> isOwner(src)
                                && com.shenqianye.samsara.permission.PermissionChecker.check(src, "debug"))
                        // debug info — 切换式
                        .then(Commands.literal("info")
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
                                .executes(ctx -> reply(ctx.getSource(), debugStatus())))
                        .then(Commands.literal("spawninfo")
                                .executes(ctx -> reply(ctx.getSource(), manager().spawnInfo(
                                        ctx.getSource().getPlayerOrException(),
                                        ctx.getSource().getLevel()))))
                        .then(diffCmd)
                        .then(Commands.literal("flow")
                                .then(Commands.literal("setup")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                manager().setupSeason(ctx.getSource().getServer()))))
                                .then(Commands.literal("rewind")
                                        .then(Commands.argument("teleportAfter", StringArgumentType.word())
                                                .suggests(SuggestionProviders.boolProvider())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        manager().startRewindOnly(ctx.getSource().getServer(),
                                                                Boolean.parseBoolean(StringArgumentType.getString(ctx, "teleportAfter")))))))
                                .then(Commands.literal("teleport")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                manager().teleportOnly(ctx.getSource().getServer())))))
                        .then(Commands.literal("json")
                                .executes(ctx -> reply(ctx.getSource(), manager().rawJson()
                                        + "\n\n↑ season.json 位置: " + manager().configPath())))
                        .then(Commands.literal("reload")
                                .executes(ctx -> reply(ctx.getSource(), manager().reload()
                                        + "\n" + com.shenqianye.samsara.config.CommandConfig
                                        .getInstance().reload())))
                        .then(Commands.literal("reset")
                                .executes(ctx -> reply(ctx.getSource(), doReset())))
                        .then(Commands.literal("create")
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .executes(ctx -> create(ctx, "same-world", 1))
                                        .then(Commands.argument("mode", StringArgumentType.word())
                                                .executes(ctx -> create(ctx, StringArgumentType.getString(ctx, "mode"), 1))
                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 50))
                                                        .executes(ctx -> create(ctx,
                                                                StringArgumentType.getString(ctx, "mode"),
                                                                IntegerArgumentType.getInteger(ctx, "count")))))))
                        .then(Commands.literal("settime")
                                .then(Commands.argument("id", IntegerArgumentType.integer(1))
                                        .then(Commands.argument("field", StringArgumentType.word())
                                                .suggests(SuggestionProviders.choicesWithHint("时间字段", "start", "end"))
                                                .then(Commands.argument("value", StringArgumentType.word())
                                                        .suggests(SuggestionProviders.choicesWithHint("now = 当前时间；也可填 epoch 毫秒", "now"))
                                                        .executes(ctx -> reply(ctx.getSource(), manager().setTime(
                                                                IntegerArgumentType.getInteger(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "field"),
                                                                StringArgumentType.getString(ctx, "value"))))))))
                        .then(Commands.literal("fakeplayers")
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .suggests(SuggestionProviders.fakePlayersProvider())
                                        .executes(DebugCommand::fakePlayers)))
                        .then(Commands.literal("groups")
                                .executes(ctx -> reply(ctx.getSource(), previewGroups(ctx, null)))
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .suggests(SuggestionProviders.playerListProvider())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                previewGroups(ctx, StringArgumentType.getString(ctx, "names"))))))
                        .then(Commands.literal("rewind")
                                .then(Commands.literal("preview")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                RewindEngine.getInstance().preview(ctx.getSource().getServer()))))
                                .then(Commands.literal("status")
                                        .executes(ctx -> reply(ctx.getSource(), RewindEngine.getInstance().status())))
                                .then(Commands.literal("abort")
                                        .executes(ctx -> reply(ctx.getSource(), RewindEngine.getInstance().abort())))
                                .then(Commands.literal("purge")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                debugRewindPurge(ctx.getSource()))))
                                .then(Commands.literal("compact")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                RewindEngine.getInstance().compactNow(ctx.getSource().getServer()))))
                                .then(Commands.literal("restore")
                                        .then(Commands.argument("pos", net.minecraft.commands.arguments.coordinates.ColumnPosArgument.columnPos())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        RewindEngine.getInstance().restoreChunkNow(
                                                                ctx.getSource().getServer(),
                                                                posToChunkX(ctx, "pos"),
                                                                posToChunkZ(ctx, "pos"))))))
                                .then(Commands.literal("set")
                                        .then(Commands.argument("dur", StringArgumentType.greedyString())
                                                .suggests(SuggestionProviders.durationsProvider())
                                                .executes(ctx -> reply(ctx.getSource(), manager().setRewindDuration(
                                                        StringArgumentType.getString(ctx, "dur")))))))
                        .then(Commands.literal("track")
                                .then(Commands.literal("replay")
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(SuggestionProviders.knownPlayersProvider())
                                                .then(Commands.argument("start", StringArgumentType.word())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugTrackReplay(ctx, 1.0)))
                                                        .then(Commands.argument("speed", StringArgumentType.word())
                                                                .suggests(SuggestionProviders.speedProvider())
                                                                .executes(ctx -> reply(ctx.getSource(),
                                                                        debugTrackReplay(ctx,
                                                                                parseDoubleArg(ctx, "speed"))))
                                                                .then(Commands.argument("cycle", StringArgumentType.word())
                                                                        .suggests(SuggestionProviders.cyclesProvider())
                                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                                debugTrackReplay(ctx,
                                                                                        parseDoubleArg(ctx, "speed")))))))))
                                .then(Commands.literal("stop")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.track.TrackReplay.stop()))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(SuggestionProviders.knownPlayersProvider())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        com.shenqianye.samsara.track.TrackReplay.stop(
                                                                StringArgumentType.getString(ctx, "player"))))))
                                .then(Commands.literal("status")
                                        .executes(ctx -> reply(ctx.getSource(), debugTrackStatus(ctx.getSource(), null)))
                                        .then(Commands.argument("player", StringArgumentType.word())
                                                .suggests(SuggestionProviders.knownPlayersProvider())
                                                .executes(ctx -> reply(ctx.getSource(), debugTrackStatus(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "player"))))))
                                .then(TrackViewCommand.debugViewNode())
                                .then(TrackViewCommand.listNode())
                                .then(Commands.literal("sweep")
                                        .executes(ctx -> {
                                            com.shenqianye.samsara.track.TrackRecorder.getInstance().sweepAsync();
                                            return reply(ctx.getSource(), "已在后台启动轨迹保留扫描（降采样）");
                                        })))
                        .then(Commands.literal("config")
                                .executes(ctx -> reply(ctx.getSource(), manager().config(null, null)))
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ctx -> replyOrErr(ctx, manager().config(
                                                StringArgumentType.getString(ctx, "key"), null)))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> replyOrErr(ctx, manager().config(
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value")))))))
                        .then(Commands.literal("settings")
                                .executes(ctx -> reply(ctx.getSource(), manager().settingsText()))
                                .then(Commands.literal("groupdist")
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 10000))
                                                .executes(ctx -> reply(ctx.getSource(), manager().setGroupMinDistance(
                                                        IntegerArgumentType.getInteger(ctx, "blocks"))))))
                                .then(Commands.literal("birthjitter")
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 1000))
                                                .executes(ctx -> reply(ctx.getSource(), manager().setBirthpointJitter(
                                                        IntegerArgumentType.getInteger(ctx, "blocks"))))))
                                .then(Commands.literal("birthreset")
                                        .then(Commands.argument("on", StringArgumentType.word())
                                                .suggests(SuggestionProviders.boolProvider())
                                                .executes(ctx -> reply(ctx.getSource(), manager().setResetIndividualBirthpoint(
                                                        Boolean.parseBoolean(StringArgumentType.getString(ctx, "on"))))))))
                        .then(Commands.literal("claim")
                                // debug claim give <dir> <dist>
                                .then(Commands.literal("give")
                                        .then(Commands.argument("dir", StringArgumentType.word())
                                                .suggests(SuggestionProviders.choices(DebugCommand::claimDirTooltip,
                                                        "f", "b", "l", "r",
                                                        "fl", "fr", "bl", "br",
                                                        "dl", "dr", "c", "m",
                                                        "sc", "x", "a"))
                                                .then(Commands.argument("dist", IntegerArgumentType.integer(1, 16))
                                                        .executes(ctx -> reply(ctx.getSource(), debugGiveBanner(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "dir"),
                                                                IntegerArgumentType.getInteger(ctx, "dist")))))))
                                // debug claim list
                                .then(Commands.literal("list")
                                        .executes(ctx -> reply(ctx.getSource(), debugListNodes())))
                                // debug claim rebuild
                                .then(Commands.literal("rebuild")
                                        .executes(ctx -> reply(ctx.getSource(), debugRebuild())))
                                // debug claim scan
                                .then(Commands.literal("scan")
                                        .executes(ctx -> reply(ctx.getSource(), debugScan(ctx.getSource()))))
                                // debug claim remove <x> <z>
                                .then(Commands.literal("remove")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .suggests(SuggestionProviders.chunkXProvider())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .suggests(SuggestionProviders.chunkZProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugRemoveNode(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))
                                // debug claim info <x> <z>
                                .then(Commands.literal("info")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .suggests(SuggestionProviders.chunkXProvider())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .suggests(SuggestionProviders.chunkZProvider())
                                                        .executes(ctx -> {
                                                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = net.minecraft.world.level.Level.OVERWORLD;
                                                            try { dim = ctx.getSource().getPlayerOrException().level().dimension(); }
                                                            catch (Exception ignored) {}
                                                            return reply(ctx.getSource(),
                                                                    ClaimCommand.infoAt(dim,
                                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                                            IntegerArgumentType.getInteger(ctx, "z")));
                                                        }))))
                                // debug claim particle ...
                                .then(Commands.literal("particle")
                                        .then(Commands.literal("day")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("day", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("night")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("night", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("font")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("font", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("beam")
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("beam", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("list")
                                                .executes(ctx -> reply(ctx.getSource(), debugListParticles())))
                                        .then(Commands.literal("shared")
                                                .then(Commands.argument("on_off", StringArgumentType.word())
                                                        .suggests(SuggestionProviders.boolProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetSharedDisplay(StringArgumentType.getString(ctx, "on_off"))))))
                                        .then(Commands.literal("global")
                                                .then(Commands.argument("on_off", StringArgumentType.word())
                                                        .suggests(SuggestionProviders.boolProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetGlobalDisplay(StringArgumentType.getString(ctx, "on_off")))))))
                                // debug claim delete dryrun|backup|execute|report
                                // dryrun: 列出将被删除的区块（不实际操作）
                                // backup: 备份受影响区域的 .mca 文件到 config/samsara/backups/
                                // execute: 报告待删除区块数（服务端运行时不直接删除，需停服手动操作）
                                // report: 生成删除报告（白名单数、待删数、示例坐标）
                                .then(Commands.literal("delete")
                                        .then(Commands.literal("dryrun")
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteDryRun(ctx.getSource()))))
                                        .then(Commands.literal("backup")
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteBackup(ctx.getSource()))))
                                        .then(Commands.literal("execute")
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteExecute(ctx.getSource()))))
                                        .then(Commands.literal("report")
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteReport(ctx.getSource())))))
                                // debug claim owner <x> <z> — 查看指定区块的旗帜归属
                                .then(Commands.literal("owner")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .suggests(SuggestionProviders.chunkXProvider())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .suggests(SuggestionProviders.chunkZProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugClaimOwner(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"))))))))
                        .then(IdentityCommand.debugIdentityNode())
                        .then(permNode())
                        .then(completionNode()));

        dispatcher.register(samsaraNode);
        CommandAliases.markRoot("samsara");
        // 短别名：全部按〈命令谦让〉条件注册；根节点已被其他模组占用时自动让位
        CommandAliases.register(dispatcher, "season", SeasonCommand::seasonNode);
        CommandAliases.register(dispatcher, "claim", ClaimCommand::claimNode);
        CommandAliases.register(dispatcher, "where", WhereCommand::whereNode);
        CommandAliases.register(dispatcher, "version", () -> Commands.literal("version")
                .executes(ctx -> replyVersion(ctx.getSource())));
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
        boolean hasMspt = dispatcher.getRoot().getChild("mspt") != null;
        boolean hasTps = dispatcher.getRoot().getChild("tps") != null;
        if (!hasMspt) {
            dispatcher.register(Commands.literal("mspt")
                    .executes(ctx -> reply(ctx.getSource(), mspt())));
            CommandAliases.markRoot("mspt");
        }
        if (!hasTps) {
            dispatcher.register(Commands.literal("tps")
                    .executes(ctx -> reply(ctx.getSource(), tps())));
            CommandAliases.markRoot("tps");
        }
    }

    /** 构建 /samsara debug completion 子命令（补全诊断） */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> completionNode() {
        return Commands.literal("completion")
                .then(Commands.literal("page")
                        .then(Commands.argument("page", IntegerArgumentType.integer(1, 5))
                                .then(Commands.argument("input", StringArgumentType.greedyString())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                IncompleteCommandHandler.debugPage(ctx.getSource(),
                                                        IntegerArgumentType.getInteger(ctx, "page"),
                                                        StringArgumentType.getString(ctx, "input")))))))
                .then(Commands.literal("send")
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
        return Commands.literal("perm")
                .then(Commands.literal("list")
                        .executes(ctx -> reply(ctx.getSource(), permList())))
                // 语法说明：greedyString 必须位于末尾。旧写法 greedyString("command") + word("level")
                // 的 level 分支永远解析不到，这里改为单个 greedyString 自己拆「路径 + 等级」。
                .then(Commands.literal("set")
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.permInputProvider())
                                .executes(ctx -> replyOrErr(ctx,
                                        permSet(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "input"))))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.commandPathProvider())
                                .executes(ctx -> reply(ctx.getSource(),
                                        permReset(StringArgumentType.getString(ctx, "command"))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.commandPathProvider())
                                .executes(ctx -> reply(ctx.getSource(),
                                        permInfo(StringArgumentType.getString(ctx, "command"))))))
                .then(Commands.literal("owner")
                        .then(Commands.argument("sub", StringArgumentType.word())
                                .suggests(SuggestionProviders.choicesWithHint("add=加入 owner 名单；remove=移出",
                                        "add", "remove"))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SuggestionProviders.knownPlayersProvider())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                permOwner(StringArgumentType.getString(ctx, "sub"),
                                                        StringArgumentType.getString(ctx, "name")))))));
    }

    // ---- 坐标语法糖辅助（坐标参数一律用原版参数类型支持 ~ / ~N）----

    /** 命令执行所在的主世界（供 fakeDiffText 立即放置字画方块） */
    private static net.minecraft.server.level.ServerLevel levelOf(CommandContext<CommandSourceStack> ctx) {
        net.minecraft.server.MinecraftServer server = ctx.getSource().getServer();
        return server == null ? null : server.getLevel(net.minecraft.world.level.Level.OVERWORLD);
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
     * debug rewind purge：立即物理删除「已到倒流时刻」的未保留区块（备份 + 清零，不可逆）
     * ——需二次确认（《设计规范》〈破坏性操作：二次确认〉/ 需求 `F-63`）。
     */
    private static String debugRewindPurge(CommandSourceStack src) {
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

    // ---- perm 权限管理 ----

    private static String permList() {
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        var entries = config.getAllEntries();
        StringBuilder sb = new StringBuilder("命令权限配置（" + entries.size() + " 条）：\n");
        for (var e : entries.entrySet()) {
            sb.append("  /samsara ").append(e.getKey())
              .append(" → ").append(e.getValue()).append("\n");
        }
        sb.append("\n数据文件: ").append(config.configPath());
        return sb.toString().trim();
    }

    /**
     * perm set：单个 greedyString 拆成「命令路径 + 等级」。
     * 路径本身可能含空格（如 {@code season create}），因此以最后一个空格为界拆等级。
     *
     * @return 成功/错误信息；参数不足时返回 null 让 replyOrErr 回错误
     */
    private static String permSet(CommandSourceStack src, String input) {
        String trimmed = input == null ? "" : input.trim();
        int split = trimmed.lastIndexOf(' ');
        if (split <= 0 || split >= trimmed.length() - 1) {
            return null;
        }
        String command = trimmed.substring(0, split).trim();
        String levelStr = trimmed.substring(split + 1).trim();
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        var playerLevel = com.shenqianye.samsara.permission.PermissionChecker.getPlayerLevel(src);
        var newLevel = com.shenqianye.samsara.permission.PermissionLevel.fromString(levelStr);
        return config.setEntry(command, newLevel, playerLevel);
    }

    private static String permReset(String command) {
        return com.shenqianye.samsara.permission.PermissionConfig.getInstance().resetEntry(command);
    }

    private static String permInfo(String command) {
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        var level = config.getRequired(command);
        return "/samsara " + command + " → " + level + "（当前所需等级）";
    }

    private static String permOwner(String sub, String name) {
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        return switch (sub.toLowerCase()) {
            case "add" -> config.addOwner(name);
            case "remove", "rm" -> config.removeOwner(name);
            default -> "用法: /samsara debug perm owner <add|remove> <玩家名>";
        };
    }

    /**
     * fakeplayers 三种形态 + clear：
     *  - `clear`             → 清空注入池
     *  - 纯数字 `3`          → TestPlayer1..3
     *  - 前缀+数量 `guard 3` → guard1,guard2,guard3
     *  - 名字序列 `Alice,Bob` → 按名字逐个生成
     * 注入后即进入注册玩家池（视为刚上线），供 /samsara debug groups 与 /season start 分组使用。
     */
    private static int fakePlayers(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "names").trim();
        List<String> names = new ArrayList<>();
        if ("clear".equalsIgnoreCase(arg)) {
            PlayerRegistry.getInstance().clearInjected();
            return reply(ctx.getSource(), "已清空注入的测试玩家（当前注入池 0 人）");
        }
        if (arg.matches("\\d+")) {
            int n = Integer.parseInt(arg);
            for (int i = 1; i <= n; i++) {
                names.add("TestPlayer" + i);
            }
        } else {
            String[] tokens = arg.split("\\s+");
            if (tokens.length == 2 && !tokens[0].isEmpty() && !tokens[0].matches("\\d+") && tokens[1].matches("\\d+")) {
                String prefix = tokens[0];
                int n = Integer.parseInt(tokens[1]);
                for (int i = 1; i <= n; i++) {
                    names.add(prefix + i);
                }
            } else {
                for (String s : arg.split("[,，]")) {
                    s = s.trim();
                    if (!s.isEmpty()) {
                        names.add(s);
                    }
                }
            }
        }
        if (names.isEmpty()) {
            return reply(ctx.getSource(), "参数为空（clear=清空；数字=批量 TestPlayer1..N；前缀+数字=如 guard 3；逗号分隔名字）");
        }
        PlayerRegistry.getInstance().inject(names);
        StringBuilder sb = new StringBuilder("注入 " + names.size() + " 名测试玩家（名 → 离线UUID，分组数据）：\n");
        for (String name : names) {
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
            sb.append("  ").append(name).append(" → ").append(offlineUuid).append('\n');
        }
        sb.append("当前注入池共 ").append(PlayerRegistry.getInstance().injectedCount()).append(" 人（clear 清空）");
        return reply(ctx.getSource(), sb.toString().trim());
    }

    /** 分组 dry-run：名单缺省用注册玩家；指定名单用 [] 临时构造（不影响注入池） */
    private static String previewGroups(CommandContext<CommandSourceStack> ctx, String spec) {
        List<PlayerRegistry.RegPlayer> players;
        if (spec != null && !spec.isBlank()) {
            List<String> names = new ArrayList<>();
            for (String s : spec.split("[,，]")) {
                s = s.trim();
                if (!s.isEmpty()) {
                    names.add(s);
                }
            }
            List<PlayerRegistry.RegPlayer> tmp = new ArrayList<>();
            for (String n : names) {
                tmp.add(new PlayerRegistry.RegPlayer(n, PlayerRegistry.offlineUuid(n), System.currentTimeMillis()));
            }
            if (tmp.isEmpty()) {
                return "名单为空";
            }
            players = tmp;
        } else {
            players = PlayerRegistry.getInstance().read(ctx.getSource().getServer());
            if (players.isEmpty()) {
                return "当前没有注册玩家（先用 /samsara debug fakeplayers 注入名单）";
            }
        }
        return manager().previewGroups(ctx.getSource().getServer(), players);
    }

    // ---- debug claim 命令实现 ----

    /**
     * debug give：给予玩家圈地旗帜。
     * <p>
     * 方向字母按**图案在旗帜上的位置**命名（观看者视角，与 MC 图案 id 同一视角）：
     * <pre>
     *   f 前   → stripe_top          b 后   → stripe_bottom
     *   l 左   → stripe_left         r 右   → stripe_right
     *   fl 前左 → square_top_left     fr 前右 → square_top_right
     *   bl 后左 → square_bottom_left  br 后右 → square_bottom_right
     *   dl 斜左下 → stripe_downleft  dr 斜右下 → stripe_downright
     *   c 竖条 → stripe_center        m 横条 → stripe_middle
     *   sc 四正 → straight_cross      x 四斜 → cross        a 全部8方向 → border
     * </pre>
     * 形状提示（延伸距离同为 d）：`a`/`x` 围成**正方形**、`sc` 围成**菱形**、
     * 单条条纹只有**线**；放好旗帜后右键可看到实际世界方向与"是否围成面"。
     */
    private static String debugGiveBanner(CommandSourceStack src, String dir, int dist) {
        String key = dir.toLowerCase();
        String patternId = switch (key) {
            case "f"  -> "stripe_top";
            case "b"  -> "stripe_bottom";
            case "l"  -> "stripe_left";
            case "r"  -> "stripe_right";
            case "fl" -> "square_top_left";
            case "fr" -> "square_top_right";
            case "bl" -> "square_bottom_left";
            case "br" -> "square_bottom_right";
            case "dl" -> "stripe_downleft";
            case "dr" -> "stripe_downright";
            case "c"  -> "stripe_center";
            case "m"  -> "stripe_middle";
            case "sc" -> "straight_cross";
            case "x"  -> "cross";
            case "a"  -> "border";
            default -> null;
        };
        if (patternId == null) {
            return "方向无效。可用：f/b/l/r（单条纹）· fl/fr/bl/br（角）· dl/dr（斜条纹）· "
                    + "c/m（竖条/横条）· sc（四正）· x（四斜）· a（全部8方向）";
        }
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "需要以玩家身份执行";
        }

        int colorValue = dist - 1;
        net.minecraft.world.item.DyeColor dye = net.minecraft.world.item.DyeColor.byId(colorValue);

        var bp = new net.minecraft.world.level.block.entity.BannerPattern(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", patternId),
                "block.minecraft.banner." + patternId);
        var layer = new net.minecraft.world.level.block.entity.BannerPatternLayers.Layer(
                net.minecraft.core.Holder.direct(bp), dye);
        var layers = new net.minecraft.world.level.block.entity.BannerPatternLayers(List.of(layer));

        net.minecraft.world.item.ItemStack banner = new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.BANNER.white());
        banner.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, layers);
        banner.setCount(1);
        player.getInventory().add(banner);

        return "已给予圈地旗：" + dir + " " + dist + " 区块";
    }

    /**
     * debug give 的方向补全说明。
     * <p>
     * 方向字母按**图案在旗帜上的位置**命名（观看者视角，与 MC 图案 id 同一视角）。
     */
    private static String claimDirTooltip(String dir) {
        return switch (dir) {
            case "f" -> "前：stripe_top（单条纹·线）";
            case "b" -> "后：stripe_bottom（单条纹·线）";
            case "l" -> "左：stripe_left（单条纹·线）";
            case "r" -> "右：stripe_right（单条纹·线）";
            case "fl" -> "前左：square_top_left（角）";
            case "fr" -> "前右：square_top_right（角）";
            case "bl" -> "后左：square_bottom_left（角）";
            case "br" -> "后右：square_bottom_right（角）";
            case "dl" -> "斜左下：stripe_downleft";
            case "dr" -> "斜右下：stripe_downright";
            case "c" -> "竖条：stripe_center";
            case "m" -> "横条：stripe_middle";
            case "sc" -> "四正：straight_cross（实心菱形）";
            case "x" -> "四斜：cross（实心正方形）";
            case "a" -> "全部 8 方向：border（实心正方形）";
            default -> "方向字母";
        };
    }

    /** debug list：列出所有已注册的圈地旗帜 */
    private static String debugListNodes() {
        var allNodes = com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes();
        if (allNodes.isEmpty()) {
            return "当前无圈地旗帜";
        }
        int totalNodes = allNodes.values().stream().mapToInt(java.util.Map::size).sum();
        int totalChunks = 0;
        for (var dimEntry : allNodes.keySet()) {
            totalChunks += com.shenqianye.samsara.claim.AreaService.getInstance()
                    .getWhitelist(dimEntry).size();
        }
        StringBuilder sb = new StringBuilder("圈地旗帜（" + totalNodes + " 面）· 保护区块 " + totalChunks + " 个：\n");
        for (var dimEntry : allNodes.entrySet()) {
            sb.append("  [").append(dimEntry.getKey().toString()).append("]\n");
            for (var e : dimEntry.getValue().entrySet()) {
                com.shenqianye.samsara.geometry.ChunkCoord loc = e.getKey();
                com.shenqianye.samsara.claim.ClaimNode n = e.getValue();
                sb.append("  ").append(loc).append(" 朝向").append(com.shenqianye.samsara.claim.ClaimInteractions.facingToDisplay(n.getFacing()))
                        .append(" 图案").append(n.getPatterns().size()).append(" 个");
                if (n.getOwnerName() != null) sb.append(" 主人:").append(n.getOwnerName());
                sb.append("\n");
            }
        }
        sb.append("数据文件: ").append(com.shenqianye.samsara.claim.AreaService.getInstance().configPath());
        return sb.toString().trim();
    }

    /** debug rebuild：强制重算白名单 */
    private static String debugRebuild() {
        // 重建所有维度
        for (var dimEntry : com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes().keySet()) {
            com.shenqianye.samsara.claim.AreaService.getInstance().recompute(dimEntry);
        }
        int totalChunks = 0;
        int totalNodes = 0;
        for (var dimEntry : com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes().keySet()) {
            totalChunks += com.shenqianye.samsara.claim.AreaService.getInstance()
                    .getWhitelist(dimEntry).size();
            totalNodes += com.shenqianye.samsara.claim.AreaService.getInstance()
                    .getNodes(dimEntry).size();
        }
        return "白名单已重算：" + totalNodes
                + " 面旗帜 → " + totalChunks + " 个保护区块";
    }

    /** debug scan：扫描世界中已放置的圈地旗帜 */
    private static String debugScan(CommandSourceStack src) {
        var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (level == null) return "主世界不可用";
        return com.shenqianye.samsara.command.ClaimCommand.scanLevel(level);
    }

    /** debug remove：移除指定位置的圈地旗帜（默认 overworld） */
    private static String debugRemoveNode(int x, int z) {
        return com.shenqianye.samsara.claim.AreaService.getInstance().removeNode(
                net.minecraft.world.level.Level.OVERWORLD,
                new com.shenqianye.samsara.geometry.ChunkCoord(x, z));
    }

    // ---- debug claim delete（生命周期说明） ----
    // 生命周期：dryrun（预览）→ backup（备份）→ execute（入队 + 立即删除未加载区块）→ report（报告）
    // execute 会真正删除区块（不可逆），因此带二次确认；删除动作在后台线程执行备份 + 清零。

    /** debug claim delete dryrun：预览将被删除的区块（不实际操作） */
    private static String debugDeleteDryRun(CommandSourceStack src) {
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.dryRun(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteDryRun 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** debug claim delete backup：备份受影响区域的 .mca 文件到 config/samsara/backups/ */
    private static String debugDeleteBackup(CommandSourceStack src) {
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.backup(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteBackup 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * debug claim delete execute：把全部非白名单区块加入待删队列，并立即删除其中<b>未加载</b>的区块
     * （备份 + 清零，不可逆）——因此需二次确认（《设计规范》〈破坏性操作：二次确认〉/ 需求 `F-63`）。
     * <p>
     * <b>修正历史注释</b>：旧注释写作"execute（停服后手动删 .mca）/ 服务端运行时不会直接删除文件"，
     * 与实际实现不符——{@code SafeDeletion.execute()} 会入队 {@code PendingDeleteQueue} 并
     * <b>立即删除所有未加载区块</b>，无需停服手动操作。
     */
    private static String debugDeleteExecute(CommandSourceStack src) {
        if (!ConfirmGuard.confirm("claim.delete.execute")) {
            return "⚠ 危险操作确认：删除全部未圈地（非白名单）区块\n"
                    + "· 立即删除【未加载】区块（备份 + 清零，不可逆）；仍加载的区块入待删队列、卸载即删\n"
                    + "· 备份只作人工恢复用，不能自动回滚\n"
                    + "· 建议先执行 dryrun 预览、backup 备份\n"
                    + "再次输入 /samsara debug claim delete execute（" + ConfirmGuard.ttlSeconds()
                    + " 秒内）才会真正执行。";
        }
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.execute(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteExecute 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** debug track replay <player> <start> [speed] [cycle] */
    private static String debugTrackReplay(CommandContext<CommandSourceStack> ctx, double speed) {
        String name = StringArgumentType.getString(ctx, "player");
        String start = StringArgumentType.getString(ctx, "start");
        String cycle = null;
        try {
            cycle = StringArgumentType.getString(ctx, "cycle");
        } catch (IllegalArgumentException ignored) {
            // 未指定周目：按开始时刻反查
        }
        var server = ctx.getSource().getServer();
        UUID uuid = com.shenqianye.samsara.track.TrackRecorder.resolveUuid(server, name);
        if (uuid == null) {
            return "找不到玩家 " + name + "（需在线或出现在 usercache.json）";
        }
        return com.shenqianye.samsara.track.TrackReplay.start(server, uuid, name, start, speed, cycle);
    }

    private static double parseDoubleArg(CommandContext<CommandSourceStack> ctx, String name) {
        try {
            return Double.parseDouble(StringArgumentType.getString(ctx, name));
        } catch (Exception e) {
            return 1.0;
        }
    }

    /** debug track status [player] */
    private static String debugTrackStatus(CommandSourceStack src, String name) {
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
                    .append(com.shenqianye.samsara.track.TrackStore.countSamples(f)).append(" 点");
        }
        return sb.toString();
    }

    /** debug claim delete report：生成删除报告（白名单数、待删数、示例坐标） */
    private static String debugDeleteReport(CommandSourceStack src) {
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.report(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteReport 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** debug claim owner <x> <z>：查看指定区块的旗帜归属 */
    private static String debugClaimOwner(int x, int z) {
        // 搜索所有维度
        com.shenqianye.samsara.claim.ClaimNode node = null;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> foundDim = null;
        for (var dimEntry : com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes().entrySet()) {
            var n = dimEntry.getValue().get(new com.shenqianye.samsara.geometry.ChunkCoord(x, z));
            if (n != null) { node = n; foundDim = dimEntry.getKey(); break; }
        }
        if (node == null) {
            return "区块 (" + x + "," + z + ") 无注册圈地旗";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("区块 (").append(x).append(",").append(z).append(") 圈地旗归属：\n");
        sb.append("  朝向: ").append(com.shenqianye.samsara.claim.ClaimInteractions.facingToDisplay(node.getFacing())).append("\n");
        if (node.getOwnerUUID() != null) {
            sb.append("  主人 UUID: ").append(node.getOwnerUUID()).append("\n");
            sb.append("  主人名称: ").append(node.getOwnerName() != null ? node.getOwnerName() : "未知").append("\n");
        } else {
            sb.append("  主人: 无（命令创建或旧版数据）\n");
        }
        sb.append("  图案: ");
        for (var pe : node.getPatterns()) {
            sb.append(pe.patternId()).append("(色").append(pe.color()).append(") ");
        }
        return sb.toString().trim();
    }

    /** debug particle：设置粒子类型 */
    private static String debugSetParticle(String type, String name) {
        var config = com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance();
        if (!com.shenqianye.samsara.claim.ClaimParticleConfig.isValidParticle(name)) {
            return "无效粒子类型: " + name + "\n可用: " + com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticles();
        }
        return switch (type) {
            case "day" -> { config.setDayParticle(name); yield "昼间边界粒子已设为: " + name; }
            case "night" -> { config.setNightParticle(name); yield "夜间边界粒子已设为: " + name; }
            case "font" -> { config.setFontParticle(name); yield "字体粒子已设为: " + name; }
            case "beam" -> { config.setBeamParticle(name); yield "光束粒子已设为: " + name; }
            default -> "未知类型: " + type;
        };
    }

    /** debug particle list：显示当前粒子配置 */
    private static String debugListParticles() {
        var config = com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance();
        return "当前粒子配置：\n  昼间边界: " + config.getDayParticleName()
                + "\n  夜间边界: " + config.getNightParticleName()
                + "\n  字体: " + config.getFontParticleName()
                + "\n  光束: " + config.getBeamParticleName()
                + "\n  共享显示: " + (config.isSharedDisplay() ? "ON（所有人可见）" : "OFF（仅持有者可见）")
                + "\n  全图显示: " + (config.isGlobalDisplay() ? "ON（不在管理区域内也显示）" : "OFF（仅管理区域内显示）")
                + "\n可用粒子: " + com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticles();
    }

    /** debug particle shared：设置共享显示 */
    private static String debugSetSharedDisplay(String onOff) {
        boolean shared = onOff.equalsIgnoreCase("on") || onOff.equalsIgnoreCase("true");
        com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance().setSharedDisplay(shared);
        return "共享显示已设为: " + (shared ? "ON（所有人可见 TextDisplay）" : "OFF（仅手持旗帜者可见粒子）");
    }

    /** debug particle global：设置全图显示 */
    private static String debugSetGlobalDisplay(String onOff) {
        boolean global = onOff.equalsIgnoreCase("on") || onOff.equalsIgnoreCase("true");
        com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance().setGlobalDisplay(global);
        return "全图显示已设为: " + (global ? "ON（不在管理区域内也显示边界）" : "OFF（仅管理区域内显示）");
    }

    /**
     * {@code /samsara}：可点击指令列表。
     * <p>
     * 只读命令用 {@link ChatUi#run}（点击即执行），破坏性/管理员/需要参数的命令用
     * {@link ChatUi#suggest}（点击只填入）。管理员按钮再按 {@code PermissionChecker} 决定是否显示，
     * 避免普通玩家看到点了也会被拒绝的按钮。
     */
    private static int sendHelp(CommandSourceStack src) {
        String head = ChatFormat.getFirstPrefix();
        String cont = ChatFormat.getContinuePrefix();
        ChatUi.send(src,
                ChatUi.text(head + "指令列表（青色=点击执行 · 黄色=点击填入，不执行）", ChatFormatting.WHITE),
                ChatUi.text(cont + "玩家：", ChatFormatting.GRAY),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.run("版本", "/samsara version", "查看模组与游戏版本"),
                        ChatUi.run("MSPT", "/samsara mspt", "查看服务器 MSPT"),
                        ChatUi.run("TPS", "/samsara tps", "查看服务器 TPS")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.run("周目状态", "/samsara season status", "查看当前周目与周目列表"),
                        ChatUi.run("周目信息", "/samsara season info", "查看世界种子与出生点")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.run("我的位置", "/samsara where", "查看自己的维度、坐标、区块与朝向"),
                        ChatUi.run("圈地信息", "/samsara claim info", "查看当前区块的圈地信息"),
                        ChatUi.run("边界开关", "/samsara claim boundary", "切换边界持续显示")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.run("身份状态", "/samsara identity status", "查看自己的身份组与 data UUID")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggest("回看时段", "/samsara track list ", "填入 /samsara track list <玩家>"),
                        ChatUi.suggest("向玩家传送", "/samsara tpa ", "填入 /samsara tpa <玩家>"),
                        ChatUi.suggest("请玩家过来", "/samsara tpahere ", "填入 /samsara tpahere <玩家>")),
                ChatUi.text(cont + "管理员（点击只填入，仍受权限检查）：", ChatFormatting.GRAY),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggestIfPermitted(src, "season create", "新周目",
                                "/samsara season create ", "填入 /samsara season create <名称> [模式]"),
                        ChatUi.suggestIfPermitted(src, "season start", "开始周目",
                                "/samsara season start", "危险：需二次确认"),
                        ChatUi.suggestIfPermitted(src, "season end", "结束周目",
                                "/samsara season end", "结束当前周目"),
                        ChatUi.suggestIfPermitted(src, "season skip", "跳过周目",
                                "/samsara season skip", "跳过当前周目")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggestIf(isOwner(src), "调试状态",
                                "/samsara debug status", "查看调试总览"),
                        ChatUi.suggestIf(isOwner(src), "圈地管理",
                                "/samsara debug claim list", "查看全部圈地旗帜"),
                        ChatUi.suggestIf(isOwner(src), "权限配置",
                                "/samsara debug perm list", "查看命令权限等级"),
                        ChatUi.suggestIf(isOwner(src), "身份映射",
                                "/samsara debug identity map", "查看全部身份组"),
                        ChatUi.suggestIf(isOwner(src), "身份审计",
                                "/samsara debug identity audit", "检查重复 UUID、空组、非法 data"),
                        ChatUi.suggestIf(isOwner(src), "倒流预览",
                                "/samsara debug rewind preview", "预览将倒流的区块（不执行）")),
                ChatUi.line(ChatUi.text(cont, ChatFormatting.GRAY),
                        ChatUi.suggestIf(isOwner(src), "轨迹回放",
                                "/samsara debug track replay ", "填入 /samsara debug track replay <玩家> <开始时刻>"),
                        ChatUi.suggestIf(isOwner(src), "全部调试命令",
                                "/samsara debug ", "点击填入后自行补参数")),
                ChatUi.text(cont + "完整命令树：输入 /samsara <Tab> 逐级补全；玩家名、周目、时长、回看参数均支持 Tab。",
                        ChatFormatting.GRAY));
        return 1;
    }

    /** /samsara version：从 FabricLoader 取模组版本 + 显示名 + 游戏版本 */
    private static Map<String, Object> versionValues() {
        String version = "unknown";
        String name = "samsara";
        String gameVersion = "unknown";
        try {
            var loader = net.fabricmc.loader.api.FabricLoader.getInstance();
            var container = loader.getModContainer("samsara");
            if (container.isPresent()) {
                version = container.get().getMetadata().getVersion().getFriendlyString();
                name = container.get().getMetadata().getName();
            }
            // 游戏版本从 minecraft 容器读取，升级 MC 时这里不会再漏改
            var minecraft = loader.getModContainer("minecraft");
            if (minecraft.isPresent()) {
                gameVersion = minecraft.get().getMetadata().getVersion().getFriendlyString();
            }
        } catch (Exception e) {
            // 取不到版本就用 unknown
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("name", name);
        values.put("version", version);
        values.put("minecraft", gameVersion);
        return values;
    }

    private static String versionInfo() {
        Map<String, Object> values = versionValues();
        return "周目管理（" + values.get("name") + "） · 版本 " + values.get("version")
                + " · MC " + values.get("minecraft") + " / Fabric —— 空间/时间/逆熵";
    }

    private static int replyVersion(CommandSourceStack src) {
        ChatUi.send(src, OutputRenderer.render("version", versionValues(), src, null));
        return 1;
    }

    private static int restart(CommandSourceStack src, int seconds) {
        if (!com.shenqianye.samsara.shutdown.ShutdownAnnouncer.request(
                src.getServer(), seconds)) {
            return reply(src, "已有正在进行的关服倒计时");
        }
        return 1;
    }

    /** MSPT 追踪：由 Samsara.onTick() 每 tick 调用 */
    private static long lastTickNanos = System.nanoTime();
    private static double cachedMspt = 50.0;

    /** 由服务端 tick 事件调用，更新 MSPT */
    public static void onTick() {
        long now = System.nanoTime();
        cachedMspt = (now - lastTickNanos) / 1_000_000.0;
        lastTickNanos = now;
    }

    /** /samsara mspt */
    private static String mspt() {
        String color = cachedMspt < 40 ? "§a" : cachedMspt < 50 ? "§e" : "§c";
        return "MSPT: " + color + String.format("%.1f", cachedMspt) + "ms§r"
                + "（目标 50ms = 20 TPS）";
    }

    /** /samsara tps */
    private static String tps() {
        double tps = Math.min(20.0, 1000.0 / Math.max(cachedMspt, 0.1));
        String color = tps >= 19.5 ? "§a" : tps >= 15.0 ? "§e" : "§c";
        return "TPS: " + color + String.format("%.1f", tps) + "§r"
                + "（MSPT=" + String.format("%.1f", cachedMspt) + "ms）";
    }

    private static boolean isOwner(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_OWNER);
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
    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> OutputRenderer.renderLegacy("debug.reply", message, src), false);
        return 1;
    }

    /** config 命令：message==null 表示未知配置项 */
    private static int replyOrErr(CommandContext<CommandSourceStack> ctx, String message) {
        if (message == null) {
            return reply(ctx.getSource(),
                    "参数不足或未知配置项（perm set 用法：/samsara debug perm set <命令路径> <等级>；"
                            + "配置项可用 /samsara debug config 查看全部）");
        }
        return reply(ctx.getSource(), message);
    }
}