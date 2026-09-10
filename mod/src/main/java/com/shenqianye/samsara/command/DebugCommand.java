package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.permissions.Permissions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
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
    private static final SeasonManager MANAGER = SeasonManager.getInstance();
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
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.diffStatus()))
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
                .executes(ctx -> reply(ctx.getSource(), help()))
                .then(Commands.literal("version")
                        .executes(ctx -> reply(ctx.getSource(), versionInfo())))
                .then(Commands.literal("mspt")
                        .executes(ctx -> reply(ctx.getSource(), mspt())))
                .then(Commands.literal("tps")
                        .executes(ctx -> reply(ctx.getSource(), tps())))
                .then(SeasonCommand.seasonNode())
                .then(ActivityCommand.activityNode())
                .then(ClaimCommand.claimNode())
                .then(Commands.literal("debug")
                        .requires(src -> isOwner(src))
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
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.spawnInfo(
                                        ctx.getSource().getPlayerOrException(),
                                        ctx.getSource().getLevel()))))
                        .then(diffCmd)
                        .then(Commands.literal("flow")
                                .then(Commands.literal("setup")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                MANAGER.setupSeason(ctx.getSource().getServer()))))
                                .then(Commands.literal("rewind")
                                        .then(Commands.argument("teleportAfter", StringArgumentType.word())
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        MANAGER.startRewindOnly(ctx.getSource().getServer(),
                                                                Boolean.parseBoolean(StringArgumentType.getString(ctx, "teleportAfter")))))))
                                .then(Commands.literal("teleport")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                MANAGER.teleportOnly(ctx.getSource().getServer())))))
                        .then(Commands.literal("json")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.rawJson()
                                        + "\n\n↑ season.json 位置: " + MANAGER.configPath())))
                        .then(Commands.literal("reload")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.reload())))
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
                                                .then(Commands.argument("value", StringArgumentType.word())
                                                        .executes(ctx -> reply(ctx.getSource(), MANAGER.setTime(
                                                                IntegerArgumentType.getInteger(ctx, "id"),
                                                                StringArgumentType.getString(ctx, "field"),
                                                                StringArgumentType.getString(ctx, "value"))))))))
                        .then(Commands.literal("fakeplayers")
                                .then(Commands.argument("names", StringArgumentType.greedyString())
                                        .executes(DebugCommand::fakePlayers)))
                        .then(Commands.literal("groups")
                                .executes(ctx -> reply(ctx.getSource(), previewGroups(ctx, null)))
                                .then(Commands.argument("names", StringArgumentType.greedyString())
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
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setRewindDuration(
                                                        StringArgumentType.getString(ctx, "dur")))))))
                        .then(Commands.literal("config")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.config(null, null)))
                                .then(Commands.argument("key", StringArgumentType.word())
                                        .executes(ctx -> replyOrErr(ctx, MANAGER.config(
                                                StringArgumentType.getString(ctx, "key"), null)))
                                        .then(Commands.argument("value", StringArgumentType.greedyString())
                                                .executes(ctx -> replyOrErr(ctx, MANAGER.config(
                                                        StringArgumentType.getString(ctx, "key"),
                                                        StringArgumentType.getString(ctx, "value")))))))
                        .then(Commands.literal("settings")
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.settingsText()))
                                .then(Commands.literal("groupdist")
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 10000))
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setGroupMinDistance(
                                                        IntegerArgumentType.getInteger(ctx, "blocks"))))))
                                .then(Commands.literal("birthjitter")
                                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 1000))
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setBirthpointJitter(
                                                        IntegerArgumentType.getInteger(ctx, "blocks"))))))
                                .then(Commands.literal("birthreset")
                                        .then(Commands.argument("on", StringArgumentType.word())
                                                .executes(ctx -> reply(ctx.getSource(), MANAGER.setResetIndividualBirthpoint(
                                                        Boolean.parseBoolean(StringArgumentType.getString(ctx, "on"))))))))
                        .then(Commands.literal("claim")
                                // debug claim give <dir> <dist>
                                .then(Commands.literal("give")
                                        .then(Commands.argument("dir", StringArgumentType.word())
                                                .suggests((ctx, builder) -> {
                                                    for (String d : new String[]{
                                                            "f", "b", "l", "r", "a"}) {
                                                        builder.suggest(d);
                                                    }
                                                    return builder.buildFuture();
                                                })
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
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugRemoveNode(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))
                                // debug claim info <x> <z>
                                .then(Commands.literal("info")
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
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
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetSharedDisplay(StringArgumentType.getString(ctx, "on_off"))))))
                                        .then(Commands.literal("global")
                                                .then(Commands.argument("on_off", StringArgumentType.word())
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
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugClaimOwner(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"))))))))
                        .then(Commands.literal("activity")
                                .then(Commands.literal("recent")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.activity.ActivityManager.getInstance().recentText(
                                                        com.shenqianye.samsara.activity.ActivityManager.DEFAULT_RECENT_DAYS)))
                                        .then(Commands.argument("days", IntegerArgumentType.integer(1, 365))
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        com.shenqianye.samsara.activity.ActivityManager.getInstance().recentText(
                                                                IntegerArgumentType.getInteger(ctx, "days"))))))
                                .then(Commands.literal("stats")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.activity.ActivityManager.getInstance().statsText())))
                                .then(Commands.literal("dead")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.activity.ActivityManager.getInstance().deadText())))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> reply(ctx.getSource(), clearActivity()))))
                        .then(permNode()));

        dispatcher.register(samsaraNode);

        // 条件别名：/mspt 和 /tps（仅当其他模组未注册时）
        boolean hasMspt = dispatcher.getRoot().getChild("mspt") != null;
        boolean hasTps = dispatcher.getRoot().getChild("tps") != null;
        if (!hasMspt) {
            dispatcher.register(Commands.literal("mspt")
                    .executes(ctx -> reply(ctx.getSource(), mspt())));
        }
        if (!hasTps) {
            dispatcher.register(Commands.literal("tps")
                    .executes(ctx -> reply(ctx.getSource(), tps())));
        }
    }

    /** 构建 /samsara perm 子命令树 */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> permNode() {
        return Commands.literal("perm")
                .then(Commands.literal("list")
                        .executes(ctx -> reply(ctx.getSource(), permList())))
                .then(Commands.literal("set")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .then(Commands.argument("level", StringArgumentType.word())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                permSet(ctx.getSource(),
                                                        StringArgumentType.getString(ctx, "command"),
                                                        StringArgumentType.getString(ctx, "level")))))))
                .then(Commands.literal("reset")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> reply(ctx.getSource(),
                                        permReset(StringArgumentType.getString(ctx, "command"))))))
                .then(Commands.literal("info")
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .executes(ctx -> reply(ctx.getSource(),
                                        permInfo(StringArgumentType.getString(ctx, "command"))))))
                .then(Commands.literal("owner")
                        .then(Commands.argument("sub", StringArgumentType.word())
                                .then(Commands.argument("name", StringArgumentType.word())
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
        return reply(ctx.getSource(), MANAGER.createBulk(name, mode, count));
    }

    /** debug status：短概要（总数+当前），长列表交给 /season status 分页；配置路径放末尾避免被刷掉 */
    private static String debugStatus() {
        try {
            Season current = MANAGER.current();
            String cur = current != null
                    ? "#" + current.id + "「" + current.name + "」 " + current.state
                    : "无（使用 /season create）";
            return "周目总数: " + MANAGER.seasonCount() + " · 当前: " + cur
                    + " · 注入测试玩家: " + PlayerRegistry.getInstance().injectedCount()
                    + "\n（周目列表请用 /season status [页码] 分页查看）"
                    + "\n配置目录: " + MANAGER.configPath();
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
        return MANAGER.reset();
    }

    /** activity clear：需二次确认（首次置位提示，再次输入才真正清空活动日志） */
    private static String clearActivity() {
        if (!ConfirmGuard.confirm("activity.clear")) {
            return "⚠ 确认：清空 activity.log（活跃度历史将丢失，重新累计）。\n"
                    + "再次输入 /samsara debug activity clear（" + ConfirmGuard.ttlSeconds() + " 秒内）才会真正执行。";
        }
        return com.shenqianye.samsara.activity.ActivityManager.getInstance().clear();
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

    private static String permSet(CommandSourceStack src, String command, String levelStr) {
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
        return MANAGER.previewGroups(ctx.getSource().getServer(), players);
    }

    // ---- debug claim 命令实现 ----

    /** debug give：给予玩家圈地旗帜（f=前, b=后, l=左, r=右, a=全部） */
    private static String debugGiveBanner(CommandSourceStack src, String dir, int dist) {
        String patternId = switch (dir.toLowerCase()) {
            case "f" -> "stripe_top";     case "b" -> "stripe_bottom";
            case "l" -> "stripe_left";    case "r" -> "stripe_right";
            case "a" -> "border";
            default -> null;
        };
        if (patternId == null) {
            return "方向无效（f=前/b=后/l=左/r=右/a=全部）";
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

    private static String help() {
        return "周目管理 samsara 指令：\n"
                + "玩家命令：\n"
                + "  /samsara claim info [x] [z] —— 查看区块圈地信息\n"
                + "  /samsara claim boundary —— 切换边界持续显示\n"
                + "  /samsara season —— 周目业务（status/info/create/start/end/skip/respawn-groups）\n"
                + "  /samsara activity —— 活跃度（recent [天数]|stats|dead）\n"
                + "  /samsara mspt|tps —— 服务器性能（别名 /mspt /tps）\n"
                + "  /samsara version —— 模组版本号\n"
                + "管理员命令（/samsara debug ...）：\n"
                + "  debug info [on|off] —— 调试信息开关（不带参数=切换）\n"
                + "  debug claim give|list|rebuild|scan|remove|info|particle|delete|owner —— 圈地管理\n"
                + "    delete dryrun|backup|execute|report —— 删除未保留区块生命周期\n"
                + "    owner <x> <z> —— 查看区域归属（旗帜放置者）\n"
                + "  debug season|activity —— 周目/活跃度调试\n"
                + "  debug status|json|reload|reset|config|settings —— 状态与配置\n"
                + "  debug fakeplayers|groups —— 测试数据注入与分组预览\n"
                + "  debug rewind|diff|flow —— 倒流与差异控制\n"
                + "  debug perm —— 权限管理";
    }

    /** /samsara version：从 FabricLoader 取模组版本 + 显示名 + 简略配置 */
    private static String versionInfo() {
        String version = "unknown";
        String name = "samsara";
        try {
            var container = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer("samsara");
            if (container.isPresent()) {
                version = container.get().getMetadata().getVersion().getFriendlyString();
                name = container.get().getMetadata().getName();
            }
        } catch (Exception e) {
            // 取不到版本就用 unknown
        }
        return "周目管理（" + name + "） · 版本 " + version
                + " · MC 26.2 / Fabric —— 空间/时间/逆熵";
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
        src.sendSuccess(() -> ChatFormat.formatDebugComponent(message), false);
        return 1;
    }

    /** config 命令：message==null 表示未知配置项 */
    private static int replyOrErr(CommandContext<CommandSourceStack> ctx, String message) {
        if (message == null) {
            ctx.getSource().sendSuccess(() -> ChatFormat.formatDebugComponent(
                    "未知配置项（可用 /samsara debug config 查看全部）"), false);
            return 0;
        }
        return reply(ctx.getSource(), message);
    }
}