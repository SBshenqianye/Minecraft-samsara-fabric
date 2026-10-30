package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.output.OutputPreviewFragment;
import com.shenqianye.samsara.output.OutputRenderer;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonData;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
                        .executes(ctx -> infoReply(ctx.getSource())))
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
        SeasonManager manager = manager();
        SeasonData data = manager.data();
        final int pageSize = 8;
        List<OutputPreviewFragment> body = new ArrayList<>();
        if (data.seasons.isEmpty()) {
            body.add(OutputPreviewFragment.text("text",
                    "尚无周目，使用 /season create <名称> 创建", null));
        } else {
            int total = data.seasons.size();
            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            if (page > 0 && page > pages) {
                page = pages;
            }
            int from = page > 0 ? (page - 1) * pageSize : 0;
            int to = page > 0 ? Math.min(from + pageSize, total) : total;
            for (int i = from; i < to; i++) {
                if (i > from) {
                    body.add(OutputPreviewFragment.text("text", "\n", null));
                }
                body.addAll(OutputRenderer.renderRawBody("season.status.item",
                        seasonValues(data.seasons.get(i)), OutputRenderer.ButtonPolicy.all()));
            }
        }
        Map<String, Object> header = new LinkedHashMap<>();
        int total = data.seasons.size();
        int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
        if (page > 0 && page > pages) {
            page = pages;
        }
        header.put("pageLine", page > 0 && pages > 1
                ? "\n--- 第 " + page + "/" + pages + " 页，共 " + total + " 条 ---" : "");
        Season current = manager.current();
        header.put("currentLine", current == null ? ""
                : "\n当前：#" + current.id + "「" + current.name + "」 " + current.state);
        header.put("worldStackLine", data.worldStack.isEmpty() ? ""
                : "\n世界栈：" + String.join(" → ", data.worldStack));
        header.put("rewindLine", com.shenqianye.samsara.rewind.RewindEngine.getInstance().running()
                ? "\n时间倒流进行中（"
                + com.shenqianye.samsara.rewind.RewindEngine.getInstance().status() + "）" : "");
        List<OutputPreviewFragment> fragments = new ArrayList<>(
                OutputRenderer.renderPrefix("season.status.header", header,
                        OutputRenderer.ButtonPolicy.all()));
        boolean explicitPrefix = !fragments.isEmpty();
        fragments.addAll(body);
        fragments.addAll(OutputRenderer.renderRawBody("season.status.header", header,
                OutputRenderer.ButtonPolicy.all()));
        ChatUi.send(src, OutputRenderer.toComponent(
                OutputRenderer.decorate(fragments, explicitPrefix)));
        return 1;
    }

    private static Map<String, Object> seasonValues(Season season) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", season.id);
        values.put("name", season.name);
        values.put("state", season.state);
        values.put("mode", season.mode);
        values.put("startSuffix", season.startTime == null ? ""
                : " · 始于 " + fmt(season.startTime));
        values.put("endSuffix", season.endTime == null ? ""
                : " · 止于 " + fmt(season.endTime));
        values.put("groupSuffix", season.groupSpawns == null || season.groupSpawns.isEmpty() ? ""
                : " · 分组 " + season.groupSpawns.size() + " 组");
        return values;
    }

    private static String fmt(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    private static int replyWithRefresh(CommandSourceStack src, String message,
                                        String refreshCommand, String hover) {
        MutableComponent refresh = ChatUi.run("刷新", refreshCommand, hover);
        ChatUi.send(src, ChatFormat.formatComponent(message),
                ChatUi.line(ChatUi.text(ChatFormat.getContinuePrefix(), ChatFormatting.GRAY), refresh));
        return 1;
    }

    private static int infoReply(CommandSourceStack src) {
        MinecraftServer server = src.getServer();
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world == null) {
            return reply(src, infoText(src));
        }
        BlockPos spawn = world.getRespawnData().pos();
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("seed", world.getSeed());
        values.put("spawnX", spawn.getX());
        values.put("spawnY", spawn.getY());
        values.put("spawnZ", spawn.getZ());
        MutableComponent refresh = ChatUi.run("刷新", "/samsara season info",
                "重新读取周目与世界信息");
        ChatUi.send(src, OutputRenderer.render("season.info", values, src, null),
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
        src.sendSuccess(() -> OutputRenderer.renderLegacy("season.reply", message, src), false);
        return 1;
    }
}
