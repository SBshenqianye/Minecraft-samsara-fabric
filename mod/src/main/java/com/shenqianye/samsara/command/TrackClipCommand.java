package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.output.OutputRenderer;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.season.DurationUtil;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.track.TrackClips;
import com.shenqianye.samsara.track.TrackRecorder;
import com.shenqianye.samsara.track.TrackReplay;
import com.shenqianye.samsara.track.view.ViewManager;
import com.shenqianye.samsara.track.view.ViewTime;
import com.shenqianye.samsara.track.view.ViewTrackReader;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 片段收藏（F-103 / F-104）。
 *
 * <pre>
 * /samsara track clip add &lt;目标&gt; &lt;开始时间&gt; [--to &lt;时间&gt;|--duration &lt;30s&gt;] [--label &lt;名称&gt;] [--cycle cycleN]
 * /samsara track clip list [目标] [页]
 * /samsara track clip info &lt;id&gt;
 * /samsara track clip remove &lt;id&gt;
 * /samsara track view clip &lt;id&gt;
 * </pre>
 *
 * 收藏只影响保留扫描时 {@code .bin} 的降采样密度，不改变时间戳、不读写 {@code diff/}。
 */
public final class TrackClipCommand {
    private TrackClipCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> clipNode() {
        return Commands.literal("clip")
                .requires(PermissionRequirements.path("track clip"))
                .then(Commands.literal("add")
                        .requires(PermissionRequirements.path("track clip add"))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.viewGreedyProvider())
                                .executes(TrackClipCommand::add)))
                .then(Commands.literal("list")
                        .requires(PermissionRequirements.path("track clip list"))
                        .executes(ctx -> list(ctx, "", 1))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.viewGreedyProvider())
                                .executes(TrackClipCommand::listInput)))
                .then(Commands.literal("info")
                        .requires(PermissionRequirements.path("track clip info"))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(SuggestionProviders.clipIdProvider())
                                .executes(TrackClipCommand::info)))
                .then(Commands.literal("remove")
                        .requires(PermissionRequirements.path("track clip remove"))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(SuggestionProviders.clipIdProvider())
                                .executes(TrackClipCommand::remove)));
    }

    private static int add(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer viewer;
        try {
            viewer = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return reply(ctx.getSource(), "收藏片段需要以玩家身份执行");
        }
        String raw = StringArgumentType.getString(ctx, "input").trim();
        String[] tokens = raw.isEmpty() ? new String[0] : raw.split("\\s+");
        if (tokens.length < 2) {
            return reply(ctx.getSource(), "用法：track clip add <目标> <开始时间> "
                    + "[--to <时间>|--duration <30s>] [--label <名称>]");
        }
        String targetSpec = tokens[0];
        String startSpec = tokens[1];
        ClipOptions options;
        try {
            options = parseOptions(tokens, 2, startSpec);
        } catch (IllegalArgumentException e) {
            return reply(ctx.getSource(), e.getMessage());
        }
        if (options.endMs == null) {
            return reply(ctx.getSource(), "收藏片段需要 --to <结束时间> 或 --duration <时长>");
        }
        long startMs;
        try {
            startMs = ViewTime.parseAbsolute(startSpec);
        } catch (IllegalArgumentException e) {
            return reply(ctx.getSource(), e.getMessage());
        }
        long endMs = options.endMs;
        if (endMs <= startMs) {
            return reply(ctx.getSource(), "结束时间必须晚于开始时间");
        }
        long limit = CommandConfig.getInstance().trackClipMaxDurationMs();
        if (endMs - startMs > limit) {
            return reply(ctx.getSource(), "片段过长：" + DurationUtil.formatMs(endMs - startMs)
                    + "，上限 " + DurationUtil.formatMs(limit));
        }
        int cycle;
        try {
            cycle = options.cycle != null ? options.cycle : SeasonManager.getInstance().cycleIdAt(startMs);
            if (options.cycle == null) {
                int endCycle = SeasonManager.getInstance().cycleIdAt(Math.max(startMs, endMs - 1));
                if (endCycle != cycle) {
                    return reply(ctx.getSource(), "片段跨周目（" + cycle + " → " + endCycle
                            + "）；请缩小范围或显式指定 --cycle");
                }
            }
        } catch (Exception e) {
            return reply(ctx.getSource(), "周目解析失败: " + e.getMessage());
        }
        ViewManager.TargetRef target = ViewManager.resolveTargetRef(
                ctx.getSource().getServer(), targetSpec);
        if (target == null) {
            return reply(ctx.getSource(), "找不到玩家: " + targetSpec);
        }
        if (!target.uuid().equals(viewer.getUUID()) && !isAdmin(ctx.getSource())) {
            return reply(ctx.getSource(), "收藏他人轨迹需要管理员权限");
        }
        TrackRecorder recorder = TrackRecorder.getInstance();
        List<Path> roots = recorder.trackRoots();
        if (roots.isEmpty()) {
            return reply(ctx.getSource(), "世界轨迹目录尚未初始化");
        }
        List<java.util.UUID> aliases = ViewManager.aliasesOf(target.uuid());
        Path chosenRoot = null;
        for (Path root : roots) {
            try {
                ViewTrackReader.LoadResult result = ViewTrackReader.load(List.of(root), cycle, aliases,
                        startMs, endMs,
                        (trackRoot, owner, ignored) -> recorder.cutsForTrackRoot(trackRoot)
                                .cutoff(recorder.worldKeyOfTrackRoot(trackRoot), cycle, owner));
                if (result.totalSamples() > 0) {
                    chosenRoot = root;
                    break;
                }
            } catch (Exception ignored) {
                // 尝试下一个栈层
            }
        }
        if (chosenRoot == null) {
            return reply(ctx.getSource(), "该时间窗口没有 " + target.name() + " 的轨迹样本");
        }
        TrackClips store = recorder.clipsForTrackRoot(chosenRoot);
        int max = CommandConfig.getInstance().trackClipMaxPerPlayer();
        if (store.countByCreator(viewer.getScoreboardName()) >= max) {
            return reply(ctx.getSource(), "收藏数量已达上限（每人 " + max + " 段）；"
                    + "请先删除旧片段：/samsara track clip list");
        }
        String world = recorder.worldKeyOfTrackRoot(chosenRoot);
        String id = store.newId();
        TrackClips.Entry entry = new TrackClips.Entry(id, world, cycle, target.uuid(),
                startMs, endMs, options.label, viewer.getScoreboardName(),
                System.currentTimeMillis());
        store.add(entry);
        return reply(ctx.getSource(), "已收藏片段 " + id + " · " + target.name() + " · "
                + ViewTime.format(startMs) + " → " + ViewTime.format(endMs)
                + " · " + DurationUtil.formatMs(endMs - startMs)
                + (options.label.isBlank() ? "" : " · " + options.label)
                + "\n回看：/samsara track view clip " + id
                + "（收藏区间在保留扫描中保持原采样密度）");
    }

    private static int listInput(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "input").trim();
        String target = null;
        int page = 1;
        for (String token : raw.isEmpty() ? new String[0] : raw.split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.matches("\\d+")) {
                page = Integer.parseInt(token);
            } else {
                target = token;
            }
        }
        return list(ctx, target, page);
    }

    private static int list(CommandContext<CommandSourceStack> ctx, String targetSpec, int page) {
        ServerPlayer actor = ctx.getSource().getPlayer();
        String spec = targetSpec;
        if (spec == null || spec.isBlank()) {
            if (actor == null) {
                return reply(ctx.getSource(), "控制台请指定玩家：/samsara track clip list <玩家>");
            }
            spec = actor.getName().getString();
        }
        ViewManager.TargetRef target = ViewManager.resolveTargetRef(
                ctx.getSource().getServer(), spec);
        if (target == null) {
            return reply(ctx.getSource(), "找不到玩家: " + spec);
        }
        if ((actor == null || !target.uuid().equals(actor.getUUID())) && !isAdmin(ctx.getSource())) {
            return reply(ctx.getSource(), "查看他人收藏需要管理员权限");
        }
        TrackRecorder recorder = TrackRecorder.getInstance();
        List<ClipRef> clips = new ArrayList<>();
        for (Path root : recorder.trackRoots()) {
            TrackClips store = recorder.clipsForTrackRoot(root);
            for (TrackClips.Entry entry : store.all()) {
                if (entry.owner() != null && entry.owner().equals(target.uuid())) {
                    clips.add(new ClipRef(root, store, entry));
                }
            }
        }
        clips.sort((a, b) -> Long.compare(b.entry.fromMs, a.entry.fromMs));
        if (clips.isEmpty()) {
            return reply(ctx.getSource(), target.name() + " 没有收藏片段（可用 track clip add 创建）");
        }
        final int pageSize = 6;
        int pages = Math.max(1, (clips.size() + pageSize - 1) / pageSize);
        int current = Math.max(1, Math.min(page, pages));
        MutableComponent out = Component.literal("[片段] " + target.name() + " 的收藏（"
                + clips.size() + " 段 · 第 " + current + "/" + pages + " 页）：");
        int from = (current - 1) * pageSize;
        int to = Math.min(clips.size(), from + pageSize);
        for (int i = from; i < to; i++) {
            ClipRef ref = clips.get(i);
            TrackClips.Entry entry = ref.entry;
            Long cutoff = recorder.cutsForTrackRoot(ref.root)
                    .cutoff(recorder.worldKeyOfTrackRoot(ref.root), entry.cycle, entry.owner());
            boolean cut = cutoff != null && cutoff < entry.toMs;
            String label = "  ▸ [" + entry.id + "] cycle" + entry.cycle + " · "
                    + shortTime(entry.fromMs) + " → " + shortTime(entry.toMs)
                    + " · " + DurationUtil.formatMs(entry.durationMs())
                    + (entry.label == null || entry.label.isBlank() ? "" : " · " + entry.label)
                    + " · by " + entry.createdBy
                    + (cut ? " · 被捕捉截断" : "");
            out.append("\n").append(ChatUi.suggestText(label,
                    "/samsara track view clip " + entry.id,
                    "点击填入回看命令（不会直接执行）"));
        }
        if (pages > 1) {
            out.append("\n  ");
            if (current > 1) {
                out.append(ChatUi.suggest("上一页",
                        "/samsara track clip list " + target.name() + " " + (current - 1),
                        "上一页"));
            }
            if (current < pages) {
                if (current > 1) {
                    out.append(" ");
                }
                out.append(ChatUi.suggest("下一页",
                        "/samsara track clip list " + target.name() + " " + (current + 1),
                        "下一页"));
            }
        }
        ctx.getSource().sendSuccess(() -> out, false);
        return 1;
    }

    private static int info(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        ClipRef ref = findClip(id);
        if (ref == null) {
            return reply(ctx.getSource(), "找不到收藏片段: " + id);
        }
        ServerPlayer actor = ctx.getSource().getPlayer();
        if ((actor == null || !ref.entry.createdBy.equalsIgnoreCase(actor.getName().getString()))
                && !isAdmin(ctx.getSource())) {
            return reply(ctx.getSource(), "查看他人收藏需要管理员权限");
        }
        TrackRecorder recorder = TrackRecorder.getInstance();
        Long cutoff = recorder.cutsForTrackRoot(ref.root)
                .cutoff(recorder.worldKeyOfTrackRoot(ref.root), ref.entry.cycle, ref.entry.owner());
        boolean cut = cutoff != null && cutoff < ref.entry.toMs;
        ViewManager.TargetRef targetRef = ViewManager.resolveTargetRef(
                ctx.getSource().getServer(), ref.entry.uuid);
        String targetName = targetRef == null ? ref.entry.uuid : targetRef.name();
        String text = "[片段] " + ref.entry.id
                + "\n  目标: " + targetName
                + "\n  周目: " + ref.entry.cycle + " · 世界: " + ref.entry.world
                + "\n  区间: " + ViewTime.format(ref.entry.fromMs) + " → "
                + ViewTime.format(ref.entry.toMs)
                + "（" + DurationUtil.formatMs(ref.entry.durationMs()) + "）"
                + "\n  标签: " + (ref.entry.label == null || ref.entry.label.isBlank()
                ? "（无）" : ref.entry.label)
                + "\n  创建: " + ref.entry.createdBy + " · "
                + ViewTime.format(ref.entry.createdAt)
                + (cut ? "\n  ⚠ 收藏段被捕捉截断：回看只播到 " + ViewTime.format(cutoff) : "")
                + "\n回看：/samsara track view clip " + ref.entry.id
                + "\n删除：/samsara track clip remove " + ref.entry.id;
        return reply(ctx.getSource(), text);
    }

    private static int remove(CommandContext<CommandSourceStack> ctx) {
        String id = StringArgumentType.getString(ctx, "id");
        ClipRef ref = findClip(id);
        if (ref == null) {
            return reply(ctx.getSource(), "找不到收藏片段: " + id);
        }
        ServerPlayer actor = ctx.getSource().getPlayer();
        if ((actor == null || !ref.entry.createdBy.equalsIgnoreCase(actor.getName().getString()))
                && !isAdmin(ctx.getSource())) {
            return reply(ctx.getSource(), "删除他人收藏需要管理员权限");
        }
        ref.store.remove(ref.entry.id);
        return reply(ctx.getSource(), "已删除收藏片段 " + ref.entry.id
                + (ref.entry.label == null || ref.entry.label.isBlank() ? "" : "（" + ref.entry.label + "）"));
    }

    /** 记录一次解析后的 add 选项。 */
    private static final class ClipOptions {
        Long endMs;
        String label = "";
        Integer cycle;
    }

    private static ClipOptions parseOptions(String[] tokens, int startIndex, String startSpec)
            throws IllegalArgumentException {
        ClipOptions out = new ClipOptions();
        long startMs = ViewTime.parseAbsolute(startSpec);
        for (int i = startIndex; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isBlank()) {
                continue;
            }
            String key;
            String inline = null;
            int eq = token.indexOf('=');
            if (eq >= 0) {
                key = token.substring(0, eq).toLowerCase();
                inline = token.substring(eq + 1);
            } else {
                key = token.toLowerCase();
            }
            switch (key) {
                case "--to" -> {
                    if (out.endMs != null) {
                        throw new IllegalArgumentException("--to 与 --duration 不能同时使用");
                    }
                    String value = inline != null ? inline : need(tokens, ++i, "--to");
                    out.endMs = ViewTime.parseEnd(value, startMs);
                }
                case "--duration" -> {
                    String value = inline != null ? inline : need(tokens, ++i, "--duration");
                    long duration = DurationUtil.parse(value);
                    if (duration <= 0) {
                        throw new IllegalArgumentException("--duration 无效: " + value);
                    }
                    if (out.endMs != null) {
                        throw new IllegalArgumentException("--to 与 --duration 不能同时使用");
                    }
                    out.endMs = startMs + duration;
                }
                case "--label" -> {
                    StringBuilder label = new StringBuilder();
                    if (inline != null) {
                        label.append(inline);
                    } else {
                        while (i + 1 < tokens.length && !tokens[i + 1].startsWith("--")) {
                            if (!label.isEmpty()) {
                                label.append(' ');
                            }
                            label.append(tokens[++i]);
                        }
                    }
                    out.label = label.toString();
                }
                case "--cycle" -> {
                    String value = inline != null ? inline : need(tokens, ++i, "--cycle");
                    Integer cycle = TrackReplay.parseCycle(value);
                    if (cycle == null) {
                        throw new IllegalArgumentException("--cycle 无效: " + value);
                    }
                    out.cycle = cycle;
                }
                default -> throw new IllegalArgumentException("未知参数 " + token
                        + "。支持：--to、--duration、--label、--cycle");
            }
        }
        return out;
    }

    private static String need(String[] tokens, int index, String key) {
        if (index >= tokens.length || tokens[index].startsWith("--")) {
            throw new IllegalArgumentException(key + " 缺少值");
        }
        return tokens[index];
    }

    private record ClipRef(Path root, TrackClips store, TrackClips.Entry entry) {
    }

    private static ClipRef findClip(String id) {
        TrackRecorder recorder = TrackRecorder.getInstance();
        for (Path root : recorder.trackRoots()) {
            TrackClips store = recorder.clipsForTrackRoot(root);
            TrackClips.Entry entry = store.find(id);
            if (entry != null) {
                return new ClipRef(root, store, entry);
            }
        }
        return null;
    }

    private static String shortTime(long epochMs) {
        String text = ViewTime.format(epochMs);
        int space = text.indexOf(' ');
        return space < 0 ? text : text.substring(space + 1);
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
                || src.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> OutputRenderer.renderLegacy("track.reply", message, src), false);
        return 1;
    }
}
