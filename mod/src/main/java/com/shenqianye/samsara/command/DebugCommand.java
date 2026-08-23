package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.rewind.RewindEngine;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.whitelist.WhitelistStore;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.coordinates.Vec2Argument;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.phys.Vec2;

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
 * /samsara debug whitelist json     —— 打印 whitelist.json 内容
 * /samsara debug whitelist reload   —— 重新加载 whitelist.json
 * </pre>
 */
public class DebugCommand {
    private static final SeasonManager MANAGER = SeasonManager.getInstance();
    /** reset 二次确认标记：首次置位提示，再次执行才真正清空 */
    private static boolean resetArmed = false;
    /** activity clear 二次确认标记：首次置位提示，再次执行才真正清空 activity.log */
    private static boolean activityClearArmed = false;

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
        dispatcher.register(Commands.literal("samsara")
                .executes(ctx -> reply(ctx.getSource(), help()))
                .then(Commands.literal("version")
                        .executes(ctx -> reply(ctx.getSource(), versionInfo())))
                .then(SeasonCommand.seasonNode())
                .then(ActivityCommand.activityNode())
                .then(ClaimCommand.claimNode())
                .then(Commands.literal("debug")
                        .requires(src -> isOwner(src))
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
                                                RewindEngine.getInstance().purgeNow(ctx.getSource().getServer()))))
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
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                ClaimCommand.infoAt(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))
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
                                                                debugSetGlobalDisplay(StringArgumentType.getString(ctx, "on_off"))))))))
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
                                .then(Commands.literal("simulate")
                                        .then(Commands.argument("daysAgo", IntegerArgumentType.integer(0, 5000))
                                                .executes(ctx -> reply(ctx.getSource(),
                                                        com.shenqianye.samsara.activity.ActivityManager.getInstance().simulateText(
                                                                IntegerArgumentType.getInteger(ctx, "daysAgo"),
                                                                com.shenqianye.samsara.activity.ActivityManager.getInstance().active30Count())))
                                                .then(Commands.argument("active30", IntegerArgumentType.integer(0, 100000))
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                com.shenqianye.samsara.activity.ActivityManager.getInstance().simulateText(
                                                                        IntegerArgumentType.getInteger(ctx, "daysAgo"),
                                                                        IntegerArgumentType.getInteger(ctx, "active30")))))))
                                .then(Commands.literal("clear")
                                        .executes(ctx -> reply(ctx.getSource(), clearActivity()))))
                        .then(Commands.literal("delete")
                                .then(Commands.literal("dryrun")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.claim.SafeDeletion.dryRun(
                                                        ctx.getSource().getServer().getLevel(
                                                                net.minecraft.world.level.Level.OVERWORLD)))))
                                .then(Commands.literal("backup")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.claim.SafeDeletion.backup(
                                                        ctx.getSource().getServer().getLevel(
                                                                net.minecraft.world.level.Level.OVERWORLD)))))
                                .then(Commands.literal("execute")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.claim.SafeDeletion.execute(
                                                        ctx.getSource().getServer().getLevel(
                                                                net.minecraft.world.level.Level.OVERWORLD)))))
                                .then(Commands.literal("report")
                                        .executes(ctx -> reply(ctx.getSource(),
                                                com.shenqianye.samsara.claim.SafeDeletion.report(
                                                        ctx.getSource().getServer().getLevel(
                                                                net.minecraft.world.level.Level.OVERWORLD))))))
                        .then(Commands.literal("whitelist")
                                .then(Commands.literal("json")
                                        .executes(ctx -> reply(ctx.getSource(), WhitelistStore.getInstance().rawJson()
                                                + "\n\n↑ whitelist.json 位置: " + WhitelistStore.getInstance().configPath())))
                                .then(Commands.literal("reload")
                                        .executes(ctx -> reply(ctx.getSource(), WhitelistStore.getInstance().reload()))))
                        .then(permNode())));
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

    /** claim add/addc：从 Vec2 参数解析坐标（支持 ~ 相对偏移 / 数字），chunkMode=true 时按区块坐标×16 */
    private static int claimAddFromVec2(CommandContext<CommandSourceStack> ctx, String nameArg,
                                        String p1Arg, String p2Arg, boolean chunkMode) {
        String name = StringArgumentType.getString(ctx, nameArg);
        Vec2 p1 = Vec2Argument.getVec2(ctx, p1Arg);
        Vec2 p2 = Vec2Argument.getVec2(ctx, p2Arg);
        int x1 = (int) Math.round(p1.x);
        int z1 = (int) Math.round(p1.y);
        int x2 = (int) Math.round(p2.x);
        int z2 = (int) Math.round(p2.y);
        if (chunkMode) {
            x1 *= 16;
            z1 *= 16;
            x2 *= 16;
            z2 *= 16;
        }
        return reply(ctx.getSource(), WhitelistStore.getInstance().claimAdd(name, x1, z1, x2, z2));
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
        if (!resetArmed) {
            resetArmed = true;
            return "⚠ 危险操作确认：清空全部周目数据。\n再次输入 /samsara debug reset 才会真正执行。";
        }
        resetArmed = false;
        return MANAGER.reset();
    }

    /** activity clear：需二次确认（首次置位提示，再次输入才真正清空活动日志） */
    private static String clearActivity() {
        if (!activityClearArmed) {
            activityClearArmed = true;
            return "⚠ 确认：清空 activity.log（活跃度历史将丢失，重新累计）。\n再次输入 /samsara debug activity clear 才会真正执行。";
        }
        activityClearArmed = false;
        return com.shenqianye.samsara.activity.ActivityManager.getInstance().clear();
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

    /** debug give：给予玩家圈地旗帜 */
    private static String debugGiveBanner(CommandSourceStack src, String dir, int dist) {
        String patternId = switch (dir.toLowerCase()) {
            case "n"  -> "stripe_top"; case "s"  -> "stripe_bottom";
            case "w"  -> "stripe_left"; case "e"  -> "stripe_right";
            case "nw" -> "square_top_left"; case "sw" -> "square_bottom_left";
            case "ne" -> "square_top_right"; case "se" -> "square_bottom_right";
            case "all"-> "border";
            default -> null;
        };
        if (patternId == null) {
            return "方向无效（n/ne/e/se/s/sw/w/nw/all）";
        }
        String dirName = switch (dir.toLowerCase()) {
            case "n" -> "北"; case "s" -> "南"; case "w" -> "西"; case "e" -> "东";
            case "nw" -> "西北"; case "sw" -> "西南"; case "ne" -> "东北"; case "se" -> "东南";
            case "all" -> "全方向"; default -> dir;
        };
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

        return "已给予圈地旗：" + dirName + " " + dist + " 区块";
    }

    /** debug list：列出所有已注册的圈地旗帜 */
    private static String debugListNodes() {
        java.util.Map<com.shenqianye.samsara.geometry.ChunkCoord, com.shenqianye.samsara.claim.ClaimNode> nodes =
                com.shenqianye.samsara.claim.AreaService.getInstance().getNodes();
        if (nodes.isEmpty()) {
            return "当前无圈地旗帜";
        }
        java.util.Set<com.shenqianye.samsara.geometry.ChunkCoord> wl =
                com.shenqianye.samsara.claim.AreaService.getInstance().getWhitelist();
        StringBuilder sb = new StringBuilder("圈地旗帜（" + nodes.size() + " 面）· 保护区块 " + wl.size() + " 个：\n");
        for (var e : nodes.entrySet()) {
            com.shenqianye.samsara.geometry.ChunkCoord loc = e.getKey();
            com.shenqianye.samsara.claim.ClaimNode n = e.getValue();
            sb.append("  ").append(loc).append(" 朝向").append(n.getFacing())
                    .append(" 图案").append(n.getPatterns().size()).append(" 个\n");
        }
        sb.append("数据文件: ").append(com.shenqianye.samsara.claim.AreaService.getInstance().configPath());
        return sb.toString().trim();
    }

    /** debug rebuild：强制重算白名单 */
    private static String debugRebuild() {
        com.shenqianye.samsara.claim.AreaService.getInstance().recompute();
        java.util.Set<com.shenqianye.samsara.geometry.ChunkCoord> wl =
                com.shenqianye.samsara.claim.AreaService.getInstance().getWhitelist();
        return "白名单已重算：" + com.shenqianye.samsara.claim.AreaService.getInstance().getNodes().size()
                + " 面旗帜 → " + wl.size() + " 个保护区块";
    }

    /** debug scan：扫描世界中已放置的圈地旗帜 */
    private static String debugScan(CommandSourceStack src) {
        var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (level == null) return "主世界不可用";
        return com.shenqianye.samsara.command.ClaimCommand.scanLevel(level);
    }

    /** debug remove：移除指定位置的圈地旗帜 */
    private static String debugRemoveNode(int x, int z) {
        return com.shenqianye.samsara.claim.AreaService.getInstance().removeNode(
                new com.shenqianye.samsara.geometry.ChunkCoord(x, z));
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
                + "  /samsara version —— 模组版本号\n"
                + "管理员命令（/samsara debug ...）：\n"
                + "  debug claim give|list|rebuild|scan|remove|info|particle —— 圈地管理\n"
                + "  debug season|activity —— 周目/活跃度调试\n"
                + "  debug status|json|reload|reset|config|settings —— 状态与配置\n"
                + "  debug fakeplayers|groups —— 测试数据注入与分组预览\n"
                + "  debug rewind|diff|flow —— 倒流与差异控制\n"
                + "  debug delete|whitelist|perm —— 安全删除/白名单/权限管理";
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