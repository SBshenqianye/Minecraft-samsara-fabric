package com.shenqianye.samsara.track.view;

import com.mojang.authlib.GameProfile;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.rewind.DiffStore;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.track.ChatLogStore;
import com.shenqianye.samsara.track.TrackEventApplier;
import com.shenqianye.samsara.track.TrackEventCursor;
import com.shenqianye.samsara.track.TrackEventStore;
import com.shenqianye.samsara.track.TrackLayout;
import com.shenqianye.samsara.track.TrackRecorder;
import com.shenqianye.samsara.track.TrackReplay;
import com.shenqianye.samsara.track.FakeVehicle;
import com.shenqianye.samsara.track.TrackMannequin;
import net.minecraft.core.BlockPos;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 回看会话管理（设计 5.7 / F-79~F-93）。
 * <p>
 * 与捕捉式 {@link TrackReplay} 完全独立：本类不写 track/diff/cutoffs/trophies/season，
 * 不调用 sweep，不使用 forceload；只读轨迹文件，结束统一恢复观看者。
 */
public final class ViewManager {
    public static final int MAX_SESSIONS = 4;
    public static final int MAX_SAMPLES = 2_000_000;
    public static final long COOLDOWN_MS = 1_000L;
    /** 临时 ticket：不持久化（不写 level.dat），会话结束显式移除 */
    private static final TicketType VIEW_TICKET =
            new TicketType(TicketType.NO_TIMEOUT, TicketType.FLAG_LOADING);

    private static final ViewManager INSTANCE = new ViewManager();

    private final Map<UUID, ViewSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> lastStart = new HashMap<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "samsara-track-view-loader");
        t.setDaemon(true);
        return t;
    });

    private ViewManager() {
    }

    public static ViewManager getInstance() {
        return INSTANCE;
    }

    private static final class Pending {
        final ViewSession session;
        final CompletableFuture<GroupLoad> future;
        final ViewSession old;

        Pending(ViewSession session, CompletableFuture<GroupLoad> future, ViewSession old) {
            this.session = session;
            this.future = future;
            this.old = old;
        }
    }

    private record TargetSpec(UUID uuid, String name, List<UUID> aliases) {
    }

    private record TargetLoad(TargetSpec spec, ViewTrackReader.LoadResult result,
                              List<ChatLogStore.Entry> chats, String error) {
    }

    private record GroupLoad(List<TargetLoad> targets) {
        int totalSamples() {
            int total = 0;
            for (TargetLoad load : targets) {
                if (load.result() != null) {
                    total += load.result().totalSamples();
                }
            }
            return total;
        }
    }

    // ---- 启动 ----

    /**
     * 发起回看。同步阶段只做校验/权限/互斥，不修改观看者状态；
     * 轨迹解析异步完成，失败只报错。
     *
     * @param allowOthers 看他人是否被允许（管理员/OWNER）
     */
    public synchronized String start(MinecraftServer server, ServerPlayer viewer,
                                     String targetName, String startSpec, String optionText,
                                     boolean allowOthers) {
        return start(server, viewer, List.of(targetName), false, startSpec, optionText, allowOthers);
    }

    /**
     * 群组时段回放（F-97）：
     * {@code targets} 为空且 {@code allTargets=true} 表示该时段全体；
     * 否则按列表解析（可含逗号分隔多项，由命令层拆好）。
     */
    public synchronized String start(MinecraftServer server, ServerPlayer viewer,
                                     List<String> targetSpecs, boolean allTargets,
                                     String startSpec, String optionText,
                                     boolean allowOthers) {
        long now = System.currentTimeMillis();
        long last = lastStart.getOrDefault(viewer.getUUID(), 0L);
        if (now - last < COOLDOWN_MS) {
            return "操作过快：每人 1 次/秒，请稍后再试";
        }
        long startMs;
        try {
            startMs = ViewTime.parseAbsolute(startSpec);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }

        long dayEnd = ViewTime.dayOf(startMs).plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
        ViewOptions options;
        try {
            options = ViewOptions.parse(optionText, startMs, dayEnd);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        List<String> specs = options.players().isEmpty()
                ? targetSpecs : options.players();
        if (specs == null || (specs.isEmpty() && !allTargets)) {
            return "缺少回看目标（也可只给开始时间，表示该时段全体）";
        }

        int cycleId;
        try {
            cycleId = options.cycle() != null ? options.cycle()
                    : SeasonManager.getInstance().cycleIdAt(startMs);
            if (options.cycle() == null) {
                int endCycle = SeasonManager.getInstance().cycleIdAt(Math.max(startMs, options.endMs() - 1));
                if (endCycle != cycleId) {
                    return "时间窗口跨周目（周目 " + cycleId + " → " + endCycle
                            + "）；请缩小窗口或显式指定 --cycle <cycleN>。候选: "
                            + candidateCycles();
                }
            }
        } catch (Exception e) {
            return "周目解析失败: " + e.getMessage();
        }

        TrackRecorder recorder = TrackRecorder.getInstance();
        List<Path> roots = recorder.trackRoots();
        if (roots.isEmpty()) {
            return "世界轨迹目录尚未初始化，请等服务器启动完成后再试";
        }

        List<TargetSpec> targets;
        try {
            targets = allTargets && options.players().isEmpty()
                    ? discoverTargets(server, roots, cycleId, startMs, options.endMs())
                    : resolveTargets(server, specs);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (targets.isEmpty()) {
            return allTargets && options.players().isEmpty()
                    ? "该时段没有有轨迹的玩家（周目 " + cycleId + " · "
                    + ViewTime.format(startMs) + " → " + ViewTime.format(options.endMs()) + "）"
                    : "找不到玩家: " + String.join(",", specs);
        }
        int maxTargets = Math.max(1, CommandConfig.getInstance().viewGroupMaxTargets());
        if (targets.size() > maxTargets) {
            return "目标 " + targets.size() + " 个，超过 view.groupMaxTargets=" + maxTargets
                    + "；请缩小目标列表或提高配置";
        }

        boolean allMode = allTargets && options.players().isEmpty();
        boolean other = targets.stream().anyMatch(t -> !t.uuid().equals(viewer.getUUID()));
        if (allMode && !allowOthers) {
            return "回放该时段全体玩家需要管理员权限";
        }
        if (other && !allowOthers) {
            return "回看他人记录需要管理员权限";
        }
        for (TargetSpec target : targets) {
            boolean replaying = TrackReplay.isReplaying(target.uuid());
            for (UUID alias : target.aliases()) {
                replaying |= TrackReplay.isReplaying(alias);
            }
            if (replaying) {
                return "目标 " + target.name() + " 已有捕捉式回放在进行中，请先 /samsara debug track stop "
                        + target.name() + "（回看与捕捉式回放不并行，F-92）";
            }
        }

        String canonical = targets.size() == 1 ? targets.get(0).name()
                : (targets.size() <= 4
                ? String.join(", ", targets.stream().map(TargetSpec::name).toList())
                : targets.size() + " 个目标");
        String summary = "cycle" + cycleId + " · " + ViewTime.format(startMs) + " → "
                + ViewTime.format(options.endMs())
                + (targets.size() > 1 ? " · " + targets.size() + " 个目标" : "");

        ViewSession old = sessions.get(viewer.getUUID());
        if (pending.containsKey(viewer.getUUID())) {
            return "上一回看请求仍在加载，请稍候";
        }
        int active = sessions.size() + pending.size();
        if (old == null && active >= MAX_SESSIONS) {
            return "回看并发已达上限（" + MAX_SESSIONS + " 个），请等其他人结束";
        }

        ViewSession session = new ViewSession(viewer.getUUID(), viewer.getScoreboardName(),
                canonical, targets.get(0).uuid(), options, cycleId, startMs, options.endMs(), summary);
        session.displayName = canonical;
        session.group = targets.size() > 1;
        session.liveCycleAtStart = recorder.cycleIdNow();
        UUID followTarget = null;
        boolean followActive;
        if (options.followTarget() != null) {
            TargetSpec follow = findTarget(targets, options.followTarget());
            if (follow == null) {
                return "--follow 指定的目标不在本次回放中: " + options.followTarget();
            }
            followTarget = follow.uuid();
            followActive = true;
        } else if (session.group) {
            // 多目标默认不跟随；--follow 单独出现也视为 off（F-98）
            followActive = options.followValueExplicit() && options.follow();
        } else {
            followActive = options.follow();
        }
        session.followTarget = followActive ? (followTarget != null ? followTarget : targets.get(0).uuid()) : null;
        session.followActive = followActive;
        for (TargetSpec spec : targets) {
            ViewTarget viewTarget = new ViewTarget(spec.uuid(), spec.name(), spec.aliases(), cycleId);
            viewTarget.session = session;
            session.targets.add(viewTarget);
        }
        lastStart.put(viewer.getUUID(), now);

        final long start = startMs;
        final long end = options.endMs();
        final int cycle = cycleId;
        final List<TargetSpec> loadTargets = List.copyOf(targets);
        final String all = allMode ? "all" : String.join(",", targets.stream().map(TargetSpec::name).toList());

        CompletableFuture<GroupLoad> future = CompletableFuture.supplyAsync(() -> {
            List<TargetLoad> results = new ArrayList<>();
            for (TargetSpec spec : loadTargets) {
                try {
                    ViewTrackReader.LoadResult result = ViewTrackReader.load(roots, cycle,
                            spec.aliases(), start, end,
                            (root, owner, ignored) -> recorder.cutsForTrackRoot(root)
                                    .cutoff(recorder.worldKeyOfTrackRoot(root), cycle, owner));
                    List<ChatLogStore.Entry> chats = ChatLogStore.readRange(cycle, start, end,
                            spec.aliases());
                    results.add(new TargetLoad(spec, result, chats, null));
                } catch (IOException e) {
                    results.add(new TargetLoad(spec, null, List.of(), e.getMessage()));
                } catch (RuntimeException e) {
                    results.add(new TargetLoad(spec, null, List.of(),
                            e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
                }
            }
            return new GroupLoad(List.copyOf(results));
        }, loader);
        pending.put(viewer.getUUID(), new Pending(session, future, old));

        Samsara.LOGGER.info("[回看] {} 请求回看 {}（周目 {} · {} → {} · {}x · {} 个目标）",
                viewer.getScoreboardName(), canonical, cycleId,
                ViewTime.format(startMs), ViewTime.format(options.endMs()), options.speed(), targets.size());
        if (other) {
            ViewAudit.append(viewer.getScoreboardName(), all, cycleId, startMs,
                    options.endMs(), options.speed(), "REQUEST");
        }
        String groupHint = targets.size() > 1 && !followActive
                ? " · 多目标默认不跟随，可用 --follow <玩家> 指定" : "";
        return "正在加载 " + canonical + " 的轨迹（周目 " + cycleId + " · "
                + ViewTime.format(startMs) + " → " + ViewTime.format(options.endMs())
                + " · " + options.speed() + "×" + groupHint + "）…";
    }

    /** 命令层解析目标名/UUID → 身份 UUID + 展示名。 */
    public record TargetRef(UUID uuid, String name) {
    }

    public static TargetRef resolveTargetRef(MinecraftServer server, String spec) {
        TargetSpec target = resolveTarget(server, spec == null ? "" : spec.trim());
        return target == null ? null : new TargetRef(target.uuid(), target.name());
    }

    /** 身份组别名（命令层收藏/统计用）。 */
    public static List<UUID> aliasesOf(UUID uuid) {
        List<UUID> aliases = new ArrayList<>(com.shenqianye.samsara.identity.IdentityService
                .getInstance().aliasesOf(uuid));
        UUID canonical = com.shenqianye.samsara.identity.IdentityService.getInstance()
                .identityOf(uuid);
        if (canonical != null && !aliases.contains(canonical)) {
            aliases.add(canonical);
        }
        return aliases;
    }

    /** 从收藏片段开始回看（F-103）。 */
    public synchronized String startFromClip(MinecraftServer server, ServerPlayer viewer,
                                             String clipId, boolean allowOthers) {
        TrackRecorder recorder = TrackRecorder.getInstance();
        List<Path> roots = recorder.trackRoots();
        if (roots.isEmpty()) {
            return "世界轨迹目录尚未初始化，请等服务器启动完成后再试";
        }
        com.shenqianye.samsara.track.TrackClips.Entry entry = null;
        for (Path root : roots) {
            entry = recorder.clipsForTrackRoot(root).find(clipId);
            if (entry != null) {
                break;
            }
        }
        if (entry == null) {
            return "找不到收藏片段: " + clipId + "（用 /samsara track clip list 查看）";
        }
        if (!entry.createdBy.equalsIgnoreCase(viewer.getScoreboardName()) && !allowOthers) {
            return "使用他人的收藏片段需要管理员权限";
        }
        String options = "--to " + entry.toMs + " --cycle cycle" + entry.cycle;
        return start(server, viewer, List.of(entry.uuid), false,
                String.valueOf(entry.fromMs), options, allowOthers);
    }

    /** 解析逗号列表已经由命令层拆好的目标；重复目标去重。 */
    private static List<TargetSpec> resolveTargets(MinecraftServer server, List<String> specs) {
        Map<UUID, TargetSpec> resolved = new LinkedHashMap<>();
        for (String raw : specs) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            TargetSpec spec = resolveTarget(server, raw.trim());
            if (spec == null) {
                throw new IllegalArgumentException("找不到玩家: " + raw
                        + "（可用 /samsara debug fakeplayers 注入测试名单）");
            }
            resolved.putIfAbsent(spec.uuid(), spec);
        }
        return new ArrayList<>(resolved.values());
    }

    private static TargetSpec resolveTarget(MinecraftServer server, String spec) {
        ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(spec);
        UUID targetUuid = onlineTarget != null ? onlineTarget.getUUID() : resolvePlayerId(server, spec);
        if (targetUuid == null) {
            return null;
        }
        UUID canonical = com.shenqianye.samsara.identity.IdentityService.getInstance()
                .identityOf(targetUuid);
        if (canonical == null) {
            canonical = targetUuid;
        }
        String name = onlineTarget != null ? onlineTarget.getScoreboardName()
                : displayName(server, targetUuid, spec);
        List<UUID> aliases = new ArrayList<>(com.shenqianye.samsara.identity.IdentityService
                .getInstance().aliasesOf(targetUuid));
        if (!aliases.contains(canonical)) {
            aliases.add(canonical);
        }
        return new TargetSpec(canonical, name, List.copyOf(aliases));
    }

    /** 该时段有轨迹的全体玩家（按身份组合并多个账号入口）。 */
    private List<TargetSpec> discoverTargets(MinecraftServer server, List<Path> roots,
                                             int cycleId, long from, long to) {
        List<ViewTrackReader.AvailablePlayer> found =
                ViewTrackReader.availablePlayers(roots, cycleId, from, to);
        Map<UUID, LinkedHashSet<UUID>> groups = new LinkedHashMap<>();
        for (ViewTrackReader.AvailablePlayer player : found) {
            UUID canonical = com.shenqianye.samsara.identity.IdentityService.getInstance()
                    .identityOf(player.uuid());
            if (canonical == null) {
                canonical = player.uuid();
            }
            groups.computeIfAbsent(canonical, id -> new LinkedHashSet<>()).add(player.uuid());
        }
        List<TargetSpec> out = new ArrayList<>();
        for (Map.Entry<UUID, LinkedHashSet<UUID>> entry : groups.entrySet()) {
            UUID canonical = entry.getKey();
            UUID displayId = entry.getValue().iterator().next();
            String name = displayName(server, displayId, canonical.toString());
            List<UUID> aliases = new ArrayList<>(entry.getValue());
            aliases.addAll(com.shenqianye.samsara.identity.IdentityService.getInstance()
                    .aliasesOf(canonical));
            if (!aliases.contains(canonical)) {
                aliases.add(canonical);
            }
            out.add(new TargetSpec(canonical, name, List.copyOf(new LinkedHashSet<>(aliases))));
        }
        return out;
    }

    /** 按 UUID / 名字在目标列表里查找（--follow）。 */
    private static TargetSpec findTarget(List<TargetSpec> targets, String spec) {
        for (TargetSpec target : targets) {
            if (target.uuid().toString().equalsIgnoreCase(spec)
                    || target.name().equalsIgnoreCase(spec)) {
                return target;
            }
        }
        return null;
    }

    // ---- 每 tick ----

    public synchronized void tick(MinecraftServer server) {
        drainPending(server);
        if (sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (ViewSession s : new ArrayList<>(sessions.values())) {
            ServerPlayer player = server.getPlayerList().getPlayer(s.viewer);
            if (player == null || player.hasDisconnected() || player.isRemoved()) {
                restore(s, "观看者离线");
                continue;
            }
            if (player.isDeadOrDying()) {
                restore(s, "观看者死亡");
                continue;
            }
            if (TrackRecorder.getInstance().cycleIdNow() != s.liveCycleAtStart) {
                restore(s, "周目切换");
                continue;
            }
            String dim = player.level().dimension().identifier().toString();
            if (s.lastWriteDim != null && !s.lastWriteDim.equals(dim)) {
                restore(s, "观看者换维度/被外部传送");
                continue;
            }
            if (s.scrubMode) {
                handleScrub(server, s, player, now);
            }
            if (s.state == ViewSession.State.PLAYING) {
                long trackTime = s.trackTime(now);
                if (s.direction() > 0 && trackTime >= s.endTrackMs) {
                    if (!s.options.loop()) {
                        restore(s, "播放完毕");
                        continue;
                    }
                    trackTime = loopTo(s, s.timelineStartMs, now);
                } else if (s.direction() < 0 && trackTime <= s.timelineStartMs) {
                    if (!s.options.loop()) {
                        restore(s, "倒放到起点");
                        continue;
                    }
                    trackTime = loopTo(s, s.endTrackMs, now);
                }
                List<ViewTarget> visible = new ArrayList<>();
                for (ViewTarget target : s.targets) {
                    if (updateTarget(server, s, target, trackTime, now)) {
                        visible.add(target);
                    }
                }
                s.mannequin = s.primary == null ? null : s.primary.mannequin;
                if (s.followActive && !s.scrubbingThisTick) {
                    follow(s, player, trackTime);
                }
                if (s.aimEnabled) {
                    applyAim(s, player, trackTime, visible);
                }
                simulateChat(s, player, trackTime);
                if (s.display != null) {
                    s.display.update("[回看] " + s.displayName
                                    + (s.group ? "（" + s.targets.size() + " 目标）" : "")
                                    + " · " + Math.round(s.progress(now) * 100) + "% · "
                                    + String.format("%.1f", s.speed()) + "×"
                                    + (s.aimEnabled ? " · 自瞄" : "")
                                    + (s.group ? " · 可见 " + visible.size() : ""),
                            s.progress(now));
                }
            } else if (s.state == ViewSession.State.PAUSED && s.display != null) {
                s.display.update("[回看] " + s.displayName + " · 已暂停 · "
                        + Math.round(s.progress(now) * 100) + "%", s.progress(now));
            }
        }
    }

    /**
     * F-111：正放跨过发言时刻时向观看者输出“名字: 内容”；跳转/倒放不补发。
     * 每个目标按自己的聊天游标独立触发。
     */
    private static void simulateChat(ViewSession s, ServerPlayer viewer, long trackTime) {
        if (s.direction() < 0) {
            for (ViewTarget target : s.targets) {
                target.lastChatTrackMs = trackTime;
            }
            return;
        }
        for (ViewTarget target : s.targets) {
            long last = target.lastChatTrackMs;
            if (trackTime <= last) {
                continue;
            }
            for (ChatLogStore.Entry entry : target.chats) {
                if (target.cursor != null
                        && (entry.timeMs() < target.cursor.startMs()
                        || entry.timeMs() > target.cursor.endMs())) {
                    continue;
                }
                if (entry.timeMs() > last && entry.timeMs() <= trackTime) {
                    String line = "[回看] " + entry.name() + ": " + entry.message();
                    viewer.sendSystemMessage(Component.literal(line));
                    if (CommandConfig.getInstance().viewChatToConsole()) {
                        Samsara.LOGGER.info(line);
                    }
                }
            }
            target.lastChatTrackMs = trackTime;
        }
    }

    private void drainPending(MinecraftServer server) {
        for (Iterator<Map.Entry<UUID, Pending>> it = pending.entrySet().iterator(); it.hasNext(); ) {
            Map.Entry<UUID, Pending> e = it.next();
            Pending p = e.getValue();
            if (!p.future.isDone()) {
                continue;
            }
            it.remove();
            GroupLoad result;
            try {
                result = p.future.join();
            } catch (CompletionException ex) {
                fail(server, p, "加载失败: " + rootMessage(ex));
                continue;
            } catch (Exception ex) {
                fail(server, p, "加载失败: " + ex.getMessage());
                continue;
            }
            int maxSamples = Math.max(10_000,
                    CommandConfig.getInstance().viewGroupMaxSamples());
            if (result.totalSamples() > maxSamples) {
                fail(server, p, "样本 " + result.totalSamples() + " 超过 viewGroupMaxSamples="
                        + maxSamples + "；请用 --to 或 --duration 缩小窗口");
                continue;
            }
            List<ViewTarget> loaded = new ArrayList<>();
            List<String> skipped = new ArrayList<>();
            for (TargetLoad load : result.targets()) {
                ViewTarget target = findTarget(p.session, load.spec().uuid());
                if (target == null) {
                    continue;
                }
                if (load.result() == null) {
                    target.status = "加载失败: " + load.error();
                    skipped.add(target.name + "（" + load.error() + "）");
                    continue;
                }
                ViewCursor cursor;
                try {
                    cursor = ViewCursor.build(load.result().segments(),
                            p.session.windowFrom, p.session.windowTo,
                            load.result().truncated(), load.result().cutoffApplied());
                } catch (IllegalArgumentException ex) {
                    target.status = ex.getMessage();
                    skipped.add(target.name + "（" + ex.getMessage() + "）");
                    continue;
                }
                target.cursor = cursor;
                target.truncated = load.result().truncated();
                target.cutoffApplied = load.result().cutoffApplied();
                target.events = new TrackEventCursor(load.result().events(), server.registryAccess());
                target.eventsTruncated = load.result().eventsTruncated();
                target.legacyEquipment = !target.events.hasEquipment();
                target.eventsJumpPending = true;
                target.chats = load.chats() == null ? List.of() : List.copyOf(load.chats());
                loaded.add(target);
            }
            if (loaded.isEmpty()) {
                fail(server, p, skipped.isEmpty() ? "所选时间窗口内没有可用轨迹样本"
                        : "没有可用目标：" + String.join("；", skipped));
                continue;
            }
            if (!p.session.group && !skipped.isEmpty()) {
                fail(server, p, skipped.get(0));
                continue;
            }
            p.session.targets.removeIf(target -> !loaded.contains(target));
            p.session.primary = loaded.get(0);
            p.session.cursor = p.session.primary.cursor;
            p.session.events = p.session.primary.events;
            p.session.eventsTruncated = p.session.primary.eventsTruncated;
            p.session.legacyEquipment = p.session.primary.legacyEquipment;
            p.session.truncated = p.session.primary.truncated;
            p.session.cutoffApplied = p.session.primary.cutoffApplied;
            p.session.timelineStartMs = loaded.stream()
                    .mapToLong(target -> target.cursor.startMs()).min().orElse(0L);
            p.session.endTrackMs = loaded.stream()
                    .mapToLong(target -> target.cursor.endMs()).max().orElse(0L);
            p.session.displayName = loaded.size() == 1 ? loaded.get(0).name
                    : (loaded.size() <= 4
                    ? String.join(", ", loaded.stream().map(target -> target.name).toList())
                    : loaded.size() + " 个目标");
            p.session.group = loaded.size() > 1;
            if (!p.session.group && !p.session.options.followExplicit()) {
                p.session.followActive = p.session.options.follow();
                p.session.followTarget = p.session.followActive ? loaded.get(0).uuid : null;
            } else if (p.session.followActive
                    && findTarget(p.session, p.session.followTarget) == null) {
                p.session.followTarget = loaded.get(0).uuid;
            }
            p.session.chatCount = loaded.stream().mapToInt(target -> target.chats.size()).sum();
            p.session.sourceSummary = summarizeTargets(loaded, p.session.cycleId)
                    + " · " + ViewTime.format(p.session.timelineStartMs) + " → "
                    + ViewTime.format(p.session.endTrackMs);
            if (!skipped.isEmpty()) {
                Samsara.LOGGER.info("[回看] {}/{} 个目标在窗口内无样本，已跳过: {}",
                        skipped.size(), p.session.targets.size() + skipped.size(),
                        String.join("；", skipped));
            }
            apply(server, p);
        }
    }

    private static ViewTarget findTarget(ViewSession session, UUID uuid) {
        for (ViewTarget target : session.targets) {
            if (target.uuid.equals(uuid)) {
                return target;
            }
        }
        return null;
    }

    private static String followName(ViewSession session) {
        ViewTarget target = findTarget(session, session.followTarget);
        return target == null ? session.followTarget.toString() : target.name;
    }

    private static String summarizeTargets(List<ViewTarget> targets, int cycleId) {
        int files = 0;
        for (ViewTarget target : targets) {
            files += target.cursor.segmentCount();
        }
        return "cycle" + cycleId + " / " + targets.size() + " 个目标 / " + files + " 段";
    }

    private void apply(MinecraftServer server, Pending p) {
        ViewSession s = p.session;
        ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
        if (viewer == null) {
            Samsara.LOGGER.info("[回看] {} 加载完成前已离线，取消", s.viewerName);
            return;
        }
        ViewSession current = sessions.get(s.viewer);
        if (current != p.old) {
            Samsara.LOGGER.info("[回看] {} 的旧会话已被停止，取消本次加载", s.viewerName);
            return;
        }
        if (s.primary == null || s.primary.cursor == null) {
            fail(server, p, "没有可用目标");
            return;
        }
        if (TrackReplay.isReplaying(s.primary.uuid)) {
            fail(server, p, "目标已有捕捉式回放在进行中（F-92）");
            return;
        }
        if (TrackRecorder.getInstance().cycleIdNow() != s.liveCycleAtStart) {
            fail(server, p, "加载期间周目已切换");
            return;
        }

        String dimension = s.options.dimension();
        if (dimension == null || dimension.isBlank()) {
            for (ViewTarget target : s.targets) {
                if (target.cursor == null) {
                    continue;
                }
                long t = Math.max(s.timelineStartMs, target.cursor.startMs());
                dimension = target.cursor.pointAt(t).dimension();
                break;
            }
        }
        if (dimension == null || dimension.isBlank()) {
            fail(server, p, "无法确定回放维度");
            return;
        }
        ServerLevel level = findLevel(server, dimension);
        if (level == null) {
            fail(server, p, "轨迹维度不存在: " + dimension);
            return;
        }
        if (!dimension.startsWith("minecraft:")) {
            // 兼容 --dim world 之类的简写：findLevel 已解析成功即可
            dimension = level.dimension().identifier().toString();
        }
        s.activeDimension = dimension;

        // 旧会话先结束（同观看者替换，不叠加实体）
        if (p.old != null) {
            restore(p.old, "被新的回看替换");
        }

        try {
            s.snapshot = ViewSnapshot.capture(viewer);
            s.playerRef = viewer;
            s.vehicleId = viewer.getVehicle() == null ? null : viewer.getVehicle().getUUID();
            s.display = new ViewDisplay(viewer, s.displayName, s.cycleId);
            // 回看期间始终旁观：无碰撞、无伤害，可自由飞行
            viewer.setGameMode(GameType.SPECTATOR);
            // 观察者本身不是被录制对象：关闭当前段，结束回看后再重新开段
            TrackRecorder.getInstance().pausePlayer(viewer.getUUID());
            // 回看保护：安全跟随 + ALLOW_DAMAGE 取消伤害（见 Samsara 事件注册）
            s.lastWriteDim = viewer.level().dimension().identifier().toString();
            s.lastWriteX = viewer.getX();
            s.lastWriteY = viewer.getY();
            s.lastWriteZ = viewer.getZ();
            long now = System.currentTimeMillis();
            s.startWallMs = now;
            s.startTrackMs = s.timelineStartMs;
            s.state = ViewSession.State.PLAYING;
            CommandConfig cfg = CommandConfig.getInstance();
            s.scrubMode = s.options.scrub() != null ? s.options.scrub() : cfg.viewScrubEnabled();
            s.scrubAnchorDim = null;
            s.scrubbingThisTick = false;
            s.aimEnabled = s.options.aim() != null ? s.options.aim() : cfg.viewAimEnabled();
            s.aimDeadzone = s.options.aimDeadzone();
            s.aimEdge = s.options.aimEdge();
            s.aimSpeed = s.options.aimSpeed();
            s.aimPitch = s.options.aimPitch();
            s.aimSnap = cfg.viewAimSnap();
            s.aimHz = cfg.viewAimHz();
            s.aimSnapAccumSec = 1.0;
            s.aimTracker = new ViewAim.Tracker(
                    new ViewAim.Params(s.aimDeadzone, s.aimEdge, s.aimSpeed, s.aimPitch));
            s.lastAimAtMs = now;
            s.equipmentDrop = s.options.equipmentDrop() != null
                    ? s.options.equipmentDrop() : cfg.viewAllowEquipmentDrop();
            for (ViewTarget target : s.targets) {
                target.lastChatTrackMs = Math.max(s.startTrackMs,
                        target.cursor == null ? s.startTrackMs : target.cursor.startMs());
                target.eventsJumpPending = true;
                target.profile = resolveProfile(server, target.uuid, target.name);
                updateTarget(server, s, target, s.startTrackMs, now);
            }
            s.primary = s.targets.get(0);
            s.cursor = s.primary.cursor;
            s.mannequin = s.primary.mannequin;
            s.events = s.primary.events;
            s.eventsTruncated = s.primary.eventsTruncated;
            s.legacyEquipment = s.primary.legacyEquipment;
            s.truncated = s.primary.truncated;
            s.cutoffApplied = s.primary.cutoffApplied;
            sessions.put(s.viewer, s);
            if (s.followActive) {
                follow(s, viewer, s.startTrackMs);
            } else if (s.group) {
                snapToScene(s, viewer, s.startTrackMs);
            }
            s.display.update("[回看] " + s.displayName + " · 0%", 0f);

            String msg = "开始回看 " + s.displayName + "（周目 " + s.cycleId + " · "
                    + s.sourceSummary + " · " + s.speed() + "× · 样本 "
                    + sampleCount(s) + " 点"
                    + (s.group ? " · 群组 " + s.targets.size() + " 个目标" : "")
                    + (s.followActive ? " · 跟随" : "")
                    + (s.options.spectator() ? " · 旁观" : "")
                    + (s.aimEnabled ? " · 自瞄" : "")
                    + (s.options.loop() ? " · 循环" : "") + "）"
                    + (s.chatCount > 0 ? " · 聊天 " + s.chatCount + " 条" : "")
                    + (s.cutoffApplied ? " · 已按捕捉截断" : "")
                    + (s.truncated ? " · 文件尾部已截断" : "")
                    + (s.legacyEquipment ? " · 该段录制于装备事件之前（空手）" : "")
                    + (s.eventsTruncated ? " · 事件尾部已截断" : "")
                    + (s.group && !s.followActive ? "\n多目标默认不跟随，可用 --follow <玩家> 指定；"
                    + "对齐：/samsara track view align" : "")
                    + "\n停止：/samsara track view stop";
            Samsara.LOGGER.info("[回看] 开始: viewer={} targets={} cycle={} window={} → {} speed={} samples={}",
                    s.viewerName, s.displayName, s.cycleId, ViewTime.format(s.startTrackMs),
                    ViewTime.format(s.endTrackMs), s.speed(), sampleCount(s));
            viewer.sendSystemMessage(Component.literal("[回看] " + msg));
            // 控制条只在会话开始时发一次（不每 tick 刷屏）
            sendControls(s, viewer);
            if (!s.targetUuid.equals(s.viewer)) {
                ViewAudit.append(s.viewerName, s.displayName, s.cycleId, s.windowFrom,
                        s.windowTo, s.speed(), "STARTED"
                                + (s.chatCount > 0 ? " (chat=" + s.chatCount + ")" : ""));
            }
        } catch (Exception e) {
            Samsara.LOGGER.warn("[回看] 应用会话失败，回滚观看者状态: {}", e.getMessage());
            restore(s, "启动异常: " + e.getMessage());
        }
    }

    /** 循环回看：把时间轴跳到另一端，重置聊天/事件游标并返回新的轨迹时间。 */
    private long loopTo(ViewSession s, long targetTrackMs, long now) {
        s.loopCount++;
        s.seekTo(targetTrackMs);
        for (ViewTarget target : s.targets) {
            target.eventsJumpPending = true;
        }
        s.resetChatCursors(targetTrackMs);
        long trackTime = s.trackTime(now);
        if (s.loopCount <= 1 || s.loopCount % 10 == 0) {
            Samsara.LOGGER.info("[回看] 循环 {} 次: viewer={} target={} → {}",
                    s.loopCount, s.viewerName, s.displayName, ViewTime.format(trackTime));
        }
        return trackTime;
    }

    private static int sampleCount(ViewSession s) {
        int total = 0;
        for (ViewTarget target : s.targets) {
            if (target.cursor != null) {
                total += target.cursor.sampleCount();
            }
        }
        return total;
    }

    private void fail(MinecraftServer server, Pending p, String reason) {
        ViewSession s = p.session;
        s.state = ViewSession.State.FAILED;
        Samsara.LOGGER.warn("[回看] 失败: viewer={} target={} cycle={} window={} → {} speed={} reason={}",
                s.viewerName, s.targetName, s.cycleId, ViewTime.format(s.windowFrom),
                ViewTime.format(s.windowTo), s.speed(), reason);
        ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
        if (viewer != null) {
            String listHint = s.group ? "" : "（可用时段：/samsara track list " + s.targetName + "）";
            viewer.sendSystemMessage(Component.literal("[回看] 无法开始：" + reason + listHint));
        }
        if (!s.targetUuid.equals(s.viewer)) {
            ViewAudit.append(s.viewerName, s.targetName, s.cycleId, s.windowFrom,
                    s.windowTo, s.speed(), "FAILED: " + reason);
        }
        CameraService.getInstance().onViewFailed(server, s.viewer);
    }

    // ---- 人偶推进 ----

    /**
     * 推进一个目标：可见性、共享人偶、ticket、位置/朝向、事件与载具。
     *
     * @return 该目标本 tick 是否可见
     */
    private boolean updateTarget(MinecraftServer server, ViewSession s, ViewTarget target,
                                 long trackTime, long now) {
        if (target.cursor == null) {
            target.visible = false;
            return false;
        }
        if (trackTime < target.cursor.startMs() || trackTime > target.cursor.endMs()) {
            hideTarget(server, s, target, "已离开该目标的轨迹区间");
            return false;
        }
        if (s.group && !target.cursor.covers(trackTime)) {
            hideTarget(server, s, target, "跨段空隙，暂时隐藏");
            return false;
        }
        ViewCursor.Point point = target.cursor.pointAt(trackTime);
        if (s.activeDimension != null && !point.dimension().equals(s.activeDimension)) {
            hideTarget(server, s, target, "其他维度，已跳过");
            return false;
        }
        ServerLevel level = findLevel(server, point.dimension());
        if (level == null) {
            hideTarget(server, s, target, "维度不可用: " + point.dimension());
            return false;
        }
        long window = Math.max(0, CommandConfig.getInstance().viewDedupeWindowMs());

        // 已是借用者：宿主仍有效且时间相近则继续借用，否则分裂出自己的实体。
        if (target.hasMannequin() && !target.ownsMannequin) {
            SharedViewMannequin.Host host = target.sharedHost;
            boolean valid = host != null && host.entity == target.mannequin
                    && !host.entity.isRemoved() && host.session != s && !host.session.closed()
                    && Math.abs(host.session.trackTime(now) - trackTime) <= window;
            if (!valid) {
                SharedViewMannequin.unborrow(target);
                target.mannequin = null;
            } else {
                target.visible = true;
                target.status = "共享人偶（与 " + host.session.viewerName + " 对齐）";
                target.teleported = false;
                return true;
            }
        }

        if (!target.hasOwnMannequin()) {
            if (target.mannequin != null && target.ownsMannequin) {
                SharedViewMannequin.release(target);
                target.mannequin = null;
            }
            SharedViewMannequin.Host host = SharedViewMannequin.find(
                    s, target, point.dimension(), trackTime, now, window);
            if (host != null) {
                target.mannequin = host.entity;
                SharedViewMannequin.borrow(target, host);
                target.visible = true;
                target.status = "共享人偶（与 " + host.session.viewerName + " 对齐）";
                target.teleported = false;
                return true;
            }
        }

        ViewMannequin m = target.mannequin;
        if (m == null || m.isRemoved() || !target.ownsMannequin || m.level() != level) {
            if (m != null && target.ownsMannequin) {
                releaseCamera(server, s, m);
                boolean keep = SharedViewMannequin.release(target);
                if (!keep) {
                    m.discard();
                }
            }
            m = createMannequin(server, s, target, level, point);
            if (m == null) {
                target.mannequin = null;
                target.ownsMannequin = false;
                target.visible = false;
                target.status = "人偶生成失败（区块未加载）";
                return false;
            }
            target.mannequin = m;
            target.ownsMannequin = true;
            target.hasPrev = false;
            SharedViewMannequin.register(s, target, point.dimension());
        } else {
            SharedViewMannequin.updateKey(s, target, point.dimension());
        }

        moveTicket(s, target, level, BlockPos.containing(point.x(), point.y(), point.z()));
        m.snapTo(point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        m.setYHeadRot(point.yaw());
        m.setYBodyRot(point.yaw());
        m.yHeadRotO = point.yaw();
        m.yBodyRotO = point.yaw();
        m.setDeltaMovement(Vec3.ZERO);
        applyTrackEvents(target, m, trackTime);
        m.applyTrackFlags(point.flags());
        updateFakeVehicle(target, m);
        if (target.hasPrev) {
            double jx = point.x() - target.prevX;
            double jy = point.y() - target.prevY;
            double jz = point.z() - target.prevZ;
            target.teleported = jx * jx + jy * jy + jz * jz > 16.0 * 16.0;
            double dx = point.x() - target.prevX;
            double dz = point.z() - target.prevZ;
            float walk = (float) Math.min(1.5, Math.hypot(dx, dz) * 4.0);
            if (walk > 0.02f) {
                m.walkAnimation.setSpeed(walk);
            } else {
                m.walkAnimation.stop();
            }
        } else {
            target.teleported = true;
        }
        target.prevX = point.x();
        target.prevY = point.y();
        target.prevZ = point.z();
        target.hasPrev = true;
        target.visible = true;
        target.status = "可见";
        return true;
    }

    /** 刷新所有目标；返回是否至少有一个可见。 */
    private boolean refreshTargets(MinecraftServer server, ViewSession s, long trackTime, long now) {
        boolean any = false;
        for (ViewTarget target : s.targets) {
            any |= updateTarget(server, s, target, trackTime, now);
        }
        s.mannequin = s.primary == null ? null : s.primary.mannequin;
        return any;
    }

    private ViewMannequin createMannequin(MinecraftServer server, ViewSession s,
                                          ViewTarget target, ServerLevel level,
                                          ViewCursor.Point point) {
        ViewMannequin m = new ViewMannequin(level, target.uuid, target.name,
                s.options.glow(), s.options.nameTag());
        if (target.profile == null) {
            target.profile = resolveProfile(server, target.uuid, target.name);
        }
        m.applyProfile(target.profile);
        m.snapTo(point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        m.setYHeadRot(point.yaw());
        m.setYBodyRot(point.yaw());
        m.yHeadRotO = point.yaw();
        m.yBodyRotO = point.yaw();
        m.walkAnimation.stop();
        if (!level.addFreshEntity(m)) {
            return null;
        }
        // 加入世界后再应用：受伤闪烁需要广播给已追踪该实体的玩家
        m.applyTrackFlags(point.flags());
        m.setEquipmentDropAllowed(s.equipmentDrop);
        return m;
    }

    /** 目标离开区间/换维度/跨段空隙：隐藏而不是冻结在最后位置。 */
    private void hideTarget(MinecraftServer server, ViewSession s, ViewTarget target, String reason) {
        if (target.mannequin != null) {
            if (target.ownsMannequin) {
                releaseCamera(server, s, target.mannequin);
                boolean keep = SharedViewMannequin.release(target);
                if (!keep) {
                    target.mannequin.discard();
                }
            } else {
                SharedViewMannequin.unborrow(target);
            }
        }
        target.mannequin = null;
        target.ownsMannequin = false;
        target.visible = false;
        target.hasPrev = false;
        target.status = reason;
        releaseTickets(target);
    }

    /**
     * 客户端假载具：按目标当前 VEHICLE 类型向附近观看者发假实体包。
     * 未知类型不创建实体，人偶回退蹲姿。
     */
    private static void updateFakeVehicle(ViewTarget target, TrackMannequin mannequin) {
        if (mannequin.vehicleType().isBlank()) {
            if (target.vehicle != null) {
                target.vehicle.setType("");
                target.vehicle = null;
            }
            return;
        }
        if (target.vehicle == null) {
            target.vehicle = new FakeVehicle();
        }
        target.vehicle.setType(mannequin.vehicleType());
        if (mannequin.level() instanceof ServerLevel level) {
            target.vehicle.tick(level, mannequin);
        }
    }

    /**
     * 事件应用：正放跨越 SWING/USE；跳转、倒放、拖动只按快照重建并清空动作。
     */
    private static void applyTrackEvents(ViewTarget target, ViewMannequin mannequin, long trackTime) {
        if (target.events == null || target.events.isEmpty() || mannequin == null) {
            return;
        }
        boolean jump = target.eventsJumpPending || trackTime < target.lastEventTrackMs;
        if (jump) {
            TrackEventApplier.resetTo(mannequin, target.events, trackTime);
        } else if (trackTime > target.lastEventTrackMs) {
            TrackEventApplier.advance(mannequin, target.events, target.lastEventTrackMs, trackTime);
        }
        target.lastEventTrackMs = trackTime;
        target.eventsJumpPending = false;
    }

    /** 跟随观察点：人偶身后 4.5 格、上方 2 格，并面向人偶（不是贴在人偶身体里）。 */
    private static final double FOLLOW_DISTANCE = 4.5;
    private static final double FOLLOW_HEIGHT = 2.0;

    /**
     * 自由活动 + 自动跟随：
     * 观看者始终旁观，可在 {@code view.freeRoamRadius} 范围内自由飞行；
     * 超出范围、人偶瞬移或换维度时才重新贴近。
     */
    private void follow(ViewSession s, ServerPlayer player, long trackTime) {
        ViewTarget target = followTargetOf(s, null);
        if (target == null || !target.visible || target.cursor == null) {
            return;
        }
        ViewCursor.Point point = target.cursor.pointAt(trackTime);
        ServerLevel level = findLevel(player.level().getServer(), point.dimension());
        if (level == null) {
            return;
        }
        int radius = Math.max(8, CommandConfig.getInstance().viewFreeRoamRadius());
        boolean dimensionChanged = !point.dimension().equals(s.lastFollowDim);
        boolean tooFar = player.distanceToSqr(point.x(), point.y(), point.z())
                > (double) radius * radius;
        if (s.followInitialized && !dimensionChanged && !target.teleported && !tooFar) {
            return; // 自由活动范围内：不打断观看者
        }
        snapToObservation(s, player, level, point);
        target.teleported = false;
    }

    /**
     * 一键对齐（F-96）：单目标贴到理想观察点；多目标贴到场景中心；
     * 有 --follow 时按该目标。不改变 follow 开关。
     */
    private void align(ViewSession s, ServerPlayer player, long trackTime) {
        ViewTarget target = followTargetOf(s, null);
        if (target != null && target.cursor != null) {
            ViewCursor.Point point = target.cursor.pointAt(trackTime);
            ServerLevel level = findLevel(player.level().getServer(), point.dimension());
            if (level != null) {
                snapToObservation(s, player, level, point);
                target.teleported = false;
                return;
            }
        }
        if (s.group) {
            snapToScene(s, player, trackTime);
        }
    }

    /** 有 --follow 目标优先；否则单目标取 primary，多目标返回 null（走场景中心）。 */
    private static ViewTarget followTargetOf(ViewSession s, List<ViewTarget> visible) {
        if (s.followTarget != null) {
            for (ViewTarget target : s.targets) {
                if (target.uuid.equals(s.followTarget)
                        && (visible == null || visible.contains(target))) {
                    return target;
                }
            }
            return null;
        }
        if (!s.group) {
            return s.primary;
        }
        return null;
    }

    /** 多目标一键对齐：场景中心上方/后方，面向质心。 */
    private void snapToScene(ViewSession s, ServerPlayer player, long trackTime) {
        double cx = 0;
        double cy = 0;
        double cz = 0;
        int count = 0;
        for (ViewTarget target : s.targets) {
            if (!target.visible || target.cursor == null) {
                continue;
            }
            ViewCursor.Point point = target.cursor.pointAt(trackTime);
            if (s.activeDimension != null && !point.dimension().equals(s.activeDimension)) {
                continue;
            }
            cx += point.x();
            cy += point.y();
            cz += point.z();
            count++;
        }
        if (count == 0) {
            if (s.primary != null && s.primary.cursor != null) {
                ViewCursor.Point point = s.primary.cursor.pointAt(trackTime);
                ServerLevel level = findLevel(player.level().getServer(), point.dimension());
                if (level != null) {
                    snapToObservation(s, player, level, point);
                }
            }
            return;
        }
        cx /= count;
        cy /= count;
        cz /= count;
        ServerLevel level = findLevel(player.level().getServer(), s.activeDimension);
        if (level == null) {
            return;
        }
        double vx = cx;
        double vy = cy + 10.0;
        double vz = cz + 12.0;
        double dx = cx - vx;
        double dz = cz - vz;
        double dy = (cy + 1.2) - (vy + 1.62);
        float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleportTo(level, vx, vy, vz, Set.of(), lookYaw, lookPitch, false);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        s.followInitialized = true;
        s.mannequinTeleported = false;
        s.lastFollowDim = s.activeDimension;
        s.lastWriteDim = s.activeDimension;
        s.lastWriteX = vx;
        s.lastWriteY = vy;
        s.lastWriteZ = vz;
        s.lastWriteAtMs = System.currentTimeMillis();
        s.lastAimAtMs = System.currentTimeMillis();
        if (s.aimTracker != null) {
            s.aimTracker.reset();
        }
    }

    private void snapToObservation(ViewSession s, ServerPlayer player,
                                   ServerLevel level, ViewCursor.Point point) {
        double yawRad = Math.toRadians(point.yaw());
        double vx = point.x() + Math.sin(yawRad) * FOLLOW_DISTANCE;
        double vy = point.y() + FOLLOW_HEIGHT;
        double vz = point.z() - Math.cos(yawRad) * FOLLOW_DISTANCE;
        double dx = point.x() - vx;
        double dz = point.z() - vz;
        double dy = (point.y() + 1.2) - (vy + 1.62);
        float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleportTo(level, vx, vy, vz, Set.of(), lookYaw, lookPitch, false);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        s.followInitialized = true;
        s.mannequinTeleported = false;
        s.lastFollowDim = point.dimension();
        s.lastWriteDim = point.dimension();
        s.lastWriteX = vx;
        s.lastWriteY = vy;
        s.lastWriteZ = vz;
        s.lastWriteAtMs = System.currentTimeMillis();
        s.lastAimAtMs = System.currentTimeMillis();
        if (s.aimTracker != null) {
            s.aimTracker.reset();
        }
    }

    /**
     * 自动瞄准（F-100）：只发旋转增量，不改位置/速度。
     * 单目标瞄该目标；群组瞄 --follow 目标，否则瞄视线中心最近且在 edge 内的人偶。
     */
    private void applyAim(ViewSession s, ServerPlayer player, long trackTime,
                          List<ViewTarget> visible) {
        if (player.getCamera() != player) {
            return;
        }
        ViewTarget chosen = aimTargetOf(s, player, trackTime, visible);
        if (chosen == null || chosen.cursor == null) {
            return;
        }
        ViewCursor.Point point = chosen.cursor.pointAt(trackTime);
        if (!point.dimension().equals(player.level().dimension().identifier().toString())) {
            return;
        }
        double targetX = point.x();
        double targetY = point.y() + 1.5;
        double targetZ = point.z();
        if (player.distanceToSqr(targetX, targetY, targetZ) > 128.0 * 128.0) {
            return;
        }
        long now = System.currentTimeMillis();
        double dt = Math.min(0.25, (now - s.lastAimAtMs) / 1000.0);
        s.lastAimAtMs = now;
        if (dt <= 0) {
            return;
        }
        ViewAim.Delta delta;
        try {
            if (s.aimSnap) {
                // 2~5Hz 快速对准：只在节流窗口到点时一次性转到位，避免缓慢拖拽头晕。
                s.aimSnapAccumSec += dt;
                double interval = 1.0 / Math.max(2, s.aimHz);
                if (s.aimSnapAccumSec < interval) {
                    return;
                }
                s.aimSnapAccumSec = 0.0;
                delta = ViewAim.snap(player.getYRot(), player.getXRot(),
                        player.getX(), player.getEyeY(), player.getZ(),
                        targetX, targetY, targetZ, s.aimPitch);
            } else {
                if (s.aimTracker == null) {
                    s.aimTracker = new ViewAim.Tracker(
                            new ViewAim.Params(s.aimDeadzone, s.aimEdge, s.aimSpeed, s.aimPitch));
                }
                delta = s.aimTracker.step(player.getYRot(), player.getXRot(),
                        player.getX(), player.getEyeY(), player.getZ(),
                        targetX, targetY, targetZ, dt);
            }
        } catch (IllegalArgumentException e) {
            s.aimEnabled = false;
            return;
        }
        if (delta.isZero()) {
            return;
        }
        float pitch = player.getXRot() + delta.pitch();
        if (pitch > 90f) {
            delta = new ViewAim.Delta(delta.yaw(), 90f - player.getXRot());
        } else if (pitch < -90f) {
            delta = new ViewAim.Delta(delta.yaw(), -90f - player.getXRot());
        }
        PositionMoveRotation change = new PositionMoveRotation(Vec3.ZERO, Vec3.ZERO,
                delta.yaw(), delta.pitch());
        player.connection.teleport(change, Set.of(
                Relative.X, Relative.Y, Relative.Z,
                Relative.DELTA_X, Relative.DELTA_Y, Relative.DELTA_Z,
                Relative.Y_ROT, Relative.X_ROT));
    }

    private static ViewTarget aimTargetOf(ViewSession s, ServerPlayer player, long trackTime,
                                          List<ViewTarget> visible) {
        ViewTarget follow = followTargetOf(s, visible);
        if (follow != null) {
            return follow;
        }
        ViewTarget best = null;
        double bestAngle = Double.MAX_VALUE;
        for (ViewTarget target : visible) {
            if (!target.visible || target.cursor == null) {
                continue;
            }
            ViewCursor.Point point = target.cursor.pointAt(trackTime);
            if (!point.dimension().equals(player.level().dimension().identifier().toString())) {
                continue;
            }
            double angle = viewAngleTo(player, point.x(), point.y() + 1.5, point.z());
            if (angle > s.aimEdge) {
                continue;
            }
            if (angle < bestAngle) {
                bestAngle = angle;
                best = target;
            }
        }
        return best;
    }

    /** 粗略视角角误差（度），用于在群组里挑选离视线中心最近的人偶。 */
    private static double viewAngleTo(ServerPlayer player, double x, double y, double z) {
        double dx = x - player.getX();
        double dy = y - player.getEyeY();
        double dz = z - player.getZ();
        double horizontal = Math.hypot(dx, dz);
        double targetYaw = Math.toDegrees(Math.atan2(-dx, dz));
        double targetPitch = -Math.toDegrees(Math.atan2(dy, horizontal));
        double dyaw = ((targetYaw - player.getYRot() + 540) % 360) - 180;
        double dpitch = targetPitch - player.getXRot();
        return Math.hypot(dyaw, dpitch);
    }

    /** 长按方向键多久后自动加速到 4×。 */
    public static final long SCRUB_FAST_AFTER_MS = 600L;

    /**
     * 拖动模式（仅服务端）：按住 Ctrl（疾跑键，旁观模式不参与移动）进入，松开即退出。
     * 拖动中 W 前进 / S 后退 / Shift 加速 / 空格暂停或继续；A/D 不参与拖动。
     * 分针只在拖动模式下生效，平时空格/Shift 仍是飞行上下与潜行。
     * <p>
     * 只有按住 Ctrl 时才锚定观看者；松开后恢复自由飞行/自动跟随，不长期锁死镜头。
     */
    private void handleScrub(MinecraftServer server, ViewSession s, ServerPlayer player, long now) {
        s.scrubbingThisTick = false;
        if (s.cursor == null) {
            s.scrubAnchorDim = null;
            return;
        }
        var input = player.getLastClientInput();
        if (input == null) {
            s.scrubAnchorDim = null;
            return;
        }
        boolean modeHeld = input.sprint();
        boolean jump = input.jump();
        if (!modeHeld) {
            s.lastScrubJump = jump;
            s.scrubAnchorDim = null;
            s.scrubHoldSinceMs = 0L;
            return;
        }
        boolean back = input.backward();
        boolean forward = input.forward();
        boolean pauseEdge = jump && !s.lastScrubJump;
        s.lastScrubJump = jump;

        s.scrubbingThisTick = true;
        if (s.scrubAnchorDim == null) {
            anchorScrub(s, player);
        }
        // 拖动期间锚定位置，只保留视角旋转；避免输入同时把镜头飞走
        if (player.distanceToSqr(s.scrubAnchorX, s.scrubAnchorY, s.scrubAnchorZ) > 1.0E-6) {
            player.teleportTo(player.level(), s.scrubAnchorX, s.scrubAnchorY, s.scrubAnchorZ,
                    Set.of(), player.getYRot(), player.getXRot(), false);
            player.setDeltaMovement(Vec3.ZERO);
            player.fallDistance = 0;
            s.lastWriteDim = player.level().dimension().identifier().toString();
            s.lastWriteX = s.scrubAnchorX;
            s.lastWriteY = s.scrubAnchorY;
            s.lastWriteZ = s.scrubAnchorZ;
            s.lastWriteAtMs = now;
        }
        if (pauseEdge) {
            if (s.state == ViewSession.State.PLAYING) {
                s.pause();
                player.sendOverlayMessage(Component.literal("[回看] 已暂停"));
            } else if (s.state == ViewSession.State.PAUSED) {
                s.resume();
                player.sendOverlayMessage(Component.literal("[回看] 已继续"));
            }
        }

        boolean moving = back != forward;
        if (!moving) {
            s.scrubHoldSinceMs = 0L;
            s.lastScrubAtMs = now;
            return;
        }
        if (s.scrubHoldSinceMs == 0L) {
            s.scrubHoldSinceMs = now;
            s.lastScrubAtMs = now;
        }
        boolean fast = input.shift() || now - s.scrubHoldSinceMs >= SCRUB_FAST_AFTER_MS;
        double dt = Math.min(0.25, (now - s.lastScrubAtMs) / 1000.0);
        s.lastScrubAtMs = now;
        long current = s.trackTime(now);
        long target = ViewScrub.target(current, s.timelineStartMs, s.endTrackMs,
                back, forward, fast, dt);
        if (target != current) {
            s.seekTo(target);
            for (ViewTarget viewTarget : s.targets) {
                viewTarget.eventsJumpPending = true;
            }
            s.resetChatCursors(target);
            if (!refreshTargets(server, s, target, now)) {
                restore(s, "拖动到不可用维度");
                return;
            }
            if (s.display != null) {
                float progress = s.progress(now);
                s.display.update("[回看] " + s.displayName + " · "
                                + Math.round(progress * 100) + "% · 拖动"
                                + (fast ? " 4×" : "")
                                + (s.state == ViewSession.State.PAUSED ? "（暂停）" : ""),
                        progress);
            }
        }
    }

    private void anchorScrub(ViewSession s, ServerPlayer player) {
        s.scrubAnchorDim = player.level().dimension().identifier().toString();
        s.scrubAnchorX = player.getX();
        s.scrubAnchorY = player.getY();
        s.scrubAnchorZ = player.getZ();
        s.lastScrubAtMs = System.currentTimeMillis();
        s.scrubHoldSinceMs = 0L;
        var input = player.getLastClientInput();
        s.lastScrubJump = input != null && input.jump();
    }

    /** 人偶被移除/替换前，如果相机正接管在它身上，先归还观看者。 */
    private static void releaseCamera(MinecraftServer server, ViewSession s, Entity mannequin) {
        if (server == null || mannequin == null) {
            return;
        }
        ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
        if (viewer != null && viewer.getCamera() == mannequin) {
            viewer.setCamera(viewer);
        }
    }

    private static boolean protectViewer() {
        return CommandConfig.getInstance().viewProtectViewer();
    }

    /** 该玩家是否处于受保护的回看会话中（服务端伤害取消事件使用）。 */
    public boolean protectsViewer(UUID viewer) {
        ViewSession s = sessions.get(viewer);
        return s != null && !s.restored
                && (s.state == ViewSession.State.PLAYING || s.state == ViewSession.State.PAUSED)
                && protectViewer();
    }

    /** 聊天栏（左下角）可点击控制条：倍速 / 暂停 / 继续 / 倒放 / 停止。 */
    private static void sendControls(ViewSession s, ServerPlayer viewer) {
        MutableComponent line1 = ChatUi.line(
                ChatUi.text("[回看控制] 速度：", ChatFormatting.GRAY),
                ChatUi.run("0.25x", "/samsara track view speed 0.25", "点击设置 0.25 倍速"),
                ChatUi.run("0.5x", "/samsara track view speed 0.5", "点击设置 0.5 倍速"),
                ChatUi.run("1x", "/samsara track view speed 1", "点击设置 1 倍速"),
                ChatUi.run("2x", "/samsara track view speed 2", "点击设置 2 倍速"),
                ChatUi.run("4x", "/samsara track view speed 4", "点击设置 4 倍速"),
                ChatUi.run("8x", "/samsara track view speed 8", "点击设置 8 倍速"));
        MutableComponent line2 = ChatUi.line(
                ChatUi.text("[回看控制]", ChatFormatting.GRAY),
                ChatUi.run("暂停", "/samsara track view pause", "暂停回看"),
                ChatUi.run("继续", "/samsara track view resume", "继续回看"),
                ChatUi.run("倒放/正放", "/samsara track view reverse", "切换正放与倒放"),
                ChatUi.run("对齐", "/samsara track view align", "一键回到人偶观察点"),
                ChatUi.run("自瞄", "/samsara track view aim", "即时开关自动瞄准"),
                ChatUi.text("按住 Ctrl + W/S 拖动", ChatFormatting.DARK_GRAY),
                ChatUi.run("停止", "/samsara track view stop", "停止回看并恢复状态"));
        MutableComponent line3 = ChatUi.line(
                ChatUi.text("[进度]", ChatFormatting.GRAY),
                ChatUi.run("-30s", "/samsara track view seek -30s", "后退 30 秒"),
                ChatUi.run("-10s", "/samsara track view seek -10s", "后退 10 秒"),
                ChatUi.run("+10s", "/samsara track view seek +10s", "前进 10 秒"),
                ChatUi.run("+30s", "/samsara track view seek +30s", "前进 30 秒"),
                ChatUi.run("25%", "/samsara track view seek 25%", "跳到 25%"),
                ChatUi.run("50%", "/samsara track view seek 50%", "跳到 50%"),
                ChatUi.run("75%", "/samsara track view seek 75%", "跳到 75%"));
        ChatUi.send(viewer, line1, line2, line3);
    }

    /** 候选水平偏移：先正后方，再左右/前方，最后 2 格环；都尽量贴近理想观察点。 */
    private static final int[][] SAFE_HORIZONTAL = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 2}, {2, -2}, {-2, 2}, {-2, -2}
    };

    /** 在理想观察点附近找安全落脚点；返回 {x,y,z}，找不到返回 null。 */
    private static double[] findSafeSpot(ServerLevel level, double x, double y, double z) {
        // 先在同一高度附近找，再逐步向上/向下扩展到 4 格
        for (int dy = 0; dy <= 4; dy++) {
            for (int[] o : SAFE_HORIZONTAL) {
                double cx = x + o[0];
                double cy = y + dy;
                double cz = z + o[1];
                if (isSafeStand(level, cx, cy, cz)) {
                    return new double[]{cx, cy, cz};
                }
            }
        }
        for (int dy = -1; dy >= -3; dy--) {
            for (int[] o : SAFE_HORIZONTAL) {
                double cx = x + o[0];
                double cy = y + dy;
                double cz = z + o[1];
                if (isSafeStand(level, cx, cy, cz)) {
                    return new double[]{cx, cy, cz};
                }
            }
        }
        // 地洞/低矮空间：尝试向上找洞口（最多 12 格）
        for (int dy = 5; dy <= 12; dy++) {
            if (isSafeStand(level, x, y + dy, z)) {
                return new double[]{x, y + dy, z};
            }
        }
        return null;
    }

    /** 脚/头两格可站、无流体/危险方块，且下方有实心地面。 */
    private static boolean isSafeStand(ServerLevel level, double x, double y, double z) {
        BlockPos feet = BlockPos.containing(x, y, z);
        BlockPos head = feet.above();
        if (!isPassable(level, feet) || !isPassable(level, head)) {
            return false;
        }
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (ground.getCollisionShape(level, below).isEmpty()) {
            return false;
        }
        return !isHazard(ground);
    }

    private static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        return !isHazard(state);
    }

    private static boolean isHazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_PORTAL);
    }

    // ---- 临时 chunk ticket ----

    private void moveTicket(ViewSession s, ViewTarget target, ServerLevel level, BlockPos pos) {
        ChunkPos chunk = ChunkPos.containing(pos);
        long key = chunk.pack();
        if (target.ticketLevel != level) {
            releaseTickets(target);
            target.ticketLevel = level;
        }
        if (target.tickets.contains(key)) {
            return;
        }
        if (!level.getChunkSource().hasChunk(chunk.x(), chunk.z())) {
            level.getChunk(chunk.x(), chunk.z()); // 临时 ticket + 同步读取，不使用 setChunkForced
        }
        for (Iterator<Long> it = target.tickets.iterator(); it.hasNext(); ) {
            long old = it.next();
            if (old != key) {
                level.getChunkSource().removeTicketWithRadius(VIEW_TICKET, ChunkPos.unpack(old), 1);
                it.remove();
            }
        }
        if (target.tickets.add(key)) {
            level.getChunkSource().addTicketWithRadius(VIEW_TICKET, chunk, 1);
        }
    }

    private void releaseTickets(ViewTarget target) {
        ServerLevel level = target.ticketLevel;
        if (level == null && target.mannequin != null && target.mannequin.level() instanceof ServerLevel sl) {
            level = sl;
        }
        if (level != null) {
            for (long key : new ArrayList<>(target.tickets)) {
                level.getChunkSource().removeTicketWithRadius(VIEW_TICKET, ChunkPos.unpack(key), 1);
            }
        }
        target.tickets.clear();
        target.ticketLevel = null;
    }

    private void releaseAllTickets(ViewSession s) {
        for (ViewTarget target : s.targets) {
            releaseTickets(target);
        }
        s.tickets.clear();
        s.ticketLevel = null;
    }

    // ---- 停止/恢复 ----

    /** 停止当前命令源的会话；target = 观看者名 / all。 */
    public synchronized String stop(MinecraftServer server, ServerPlayer actor, String target, boolean allowOthers) {
        if (target == null || target.isBlank()) {
            if (actor == null) {
                return stopAll(server);
            }
            ViewSession s = sessions.get(actor.getUUID());
            if (s == null && pending.containsKey(actor.getUUID())) {
                pending.remove(actor.getUUID());
                return "已取消正在加载的回看";
            }
            if (s == null) {
                return "你当前没有进行中的回看";
            }
            restore(s, "手动停止");
            return "已停止回看 " + s.displayName;
        }
        if ("all".equalsIgnoreCase(target)) {
            if (actor != null && !allowOthers) {
                return "停止全部回看需要管理员权限";
            }
            return stopAll(server);
        }
        ViewSession found = null;
        for (ViewSession s : sessions.values()) {
            if (s.viewerName.equalsIgnoreCase(target) || s.viewer.toString().equalsIgnoreCase(target)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            UUID id = resolvePlayerId(server, target);
            if (id != null) {
                Pending p = pending.remove(id);
                if (p != null) {
                    return "已取消 " + target + " 正在加载的回看";
                }
                found = sessions.get(id);
            }
        }
        if (found == null) {
            return "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(found.viewer) && !allowOthers) {
            return "停止他人回看需要管理员权限";
        }
        String name = found.targetName;
        restore(found, "手动停止");
        return "已停止 " + found.viewerName + " 对 " + name + " 的回看";
    }

    private String stopAll(MinecraftServer server) {
        int n = sessions.size() + pending.size();
        if (n == 0) {
            return "当前没有进行中的回看";
        }
        pending.clear();
        for (ViewSession s : new ArrayList<>(sessions.values())) {
            restore(s, "手动停止（all）");
        }
        return "已停止全部回看（" + n + " 个）";
    }

    /**
     * 统一恢复入口：幂等；所有退出路径（播完/stop/退出/死亡/换维度/周目切换/
     * 停服/异常）都走这里。
     */
    public synchronized void restore(ViewSession s, String reason) {
        if (s == null || s.restored) {
            return;
        }
        s.restored = true;
        s.state = ViewSession.State.RESTORING;
        s.stopReason = reason == null ? "" : reason;

        MinecraftServer server = currentServer;
        ServerPlayer viewer = s.playerRef;
        if (viewer == null && server != null) {
            viewer = server.getPlayerList().getPlayer(s.viewer);
        }
        // 相机接管在人偶身上时先归还，再移除实体，避免黑屏/相机悬空
        for (ViewTarget target : s.targets) {
            if (target.vehicle != null) {
                target.vehicle.remove();
                target.vehicle = null;
            }
            releaseCamera(server, s, target.mannequin);
            if (target.mannequin != null && target.ownsMannequin) {
                boolean keep = SharedViewMannequin.release(target);
                if (!keep) {
                    target.mannequin.discard();
                }
            } else if (target.sharedHost != null) {
                SharedViewMannequin.unborrow(target);
            }
            target.mannequin = null;
            target.ownsMannequin = false;
            releaseTickets(target);
        }
        if (s.vehicle != null) {
            s.vehicle.remove();
            s.vehicle = null;
        }
        if (s.mannequin != null && s.targets.isEmpty()) {
            s.mannequin.discard();
        }
        s.mannequin = null;
        releaseAllTickets(s);
        if (s.display != null) {
            s.display.release();
            s.display = null;
        }
        boolean skippedPosition = false;
        boolean skippedMode = false;
        if (viewer != null && s.snapshot != null) {
            try {
                ViewRestore.Gate gate = new ViewRestore.Gate(
                        s.lastWriteDim, s.lastWriteX, s.lastWriteY, s.lastWriteZ,
                        "SPECTATOR",
                        Math.max(ViewRestore.MAX_DRIFT,
                                CommandConfig.getInstance().viewFreeRoamRadius() + 8),
                        !viewer.isDeadOrDying());
                ViewRestore.Result r = ViewRestore.restore(s.snapshot.data(),
                        ViewRestore.sink(viewer), gate);
                skippedPosition = r.skippedPosition();
                skippedMode = r.skippedMode();
            } catch (Exception e) {
                Samsara.LOGGER.warn("[回看] 恢复观看者状态失败（已尽力清理实体/展示物）: {}", e.getMessage());
            }
            restoreVehicle(server, viewer, s);
            if (skippedPosition) {
                viewer.sendSystemMessage(Component.literal(
                        "[回看] 检测到外部传送/位移，已跳过位置恢复（保留你当前所在位置）"));
            }
            if (skippedMode) {
                viewer.sendSystemMessage(Component.literal(
                        "[回看] 检测到游戏模式已被外部修改，未覆盖当前模式"));
            }
            viewer.sendSystemMessage(Component.literal("[回看] 已结束：" + reason
                    + (s.displayName == null ? "" : "（" + s.displayName + "）")));
        }

        sessions.remove(s.viewer, s);
        s.state = ViewSession.State.IDLE;
        // 恢复录制：只有回到非旁观且不再观看任何会话时才重新开段
        if (viewer != null && !viewer.isSpectator() && !sessions.containsKey(viewer.getUUID())) {
            TrackRecorder.getInstance().resumePlayer(viewer);
        }
        Samsara.LOGGER.info("[回看] 结束: viewer={} target={} cycle={} reason={} skippedPosition={} skippedMode={}",
                s.viewerName, s.displayName, s.cycleId, reason, skippedPosition, skippedMode);
        if (server != null && !s.targetUuid.equals(s.viewer)) {
            ViewAudit.append(s.viewerName, s.displayName, s.cycleId, s.windowFrom,
                    s.windowTo, s.speed(), "STOPPED: " + reason);
        }
    }

    // ---- 状态/列表 ----

    public synchronized String pause(MinecraftServer server, ServerPlayer actor, String target, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "暂停他人回看需要管理员权限";
        }
        if (s.state == ViewSession.State.PAUSED) {
            return "回看已经暂停";
        }
        if (s.state != ViewSession.State.PLAYING) {
            return "当前状态不能暂停: " + s.state;
        }
        s.pause();
        return "已暂停回看 " + s.displayName + "（" + Math.round(s.progress(System.currentTimeMillis()) * 100) + "%）";
    }

    public synchronized String resume(MinecraftServer server, ServerPlayer actor, String target, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "恢复他人回看需要管理员权限";
        }
        if (s.state != ViewSession.State.PAUSED) {
            return "当前状态不是暂停: " + s.state;
        }
        s.resume();
        return "已继续回看 " + s.displayName;
    }

    /** 点击控制面板：调整倍速 */
    public synchronized String setSpeed(MinecraftServer server, ServerPlayer actor, String target,
                                        double speed, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "调整他人回看需要管理员权限";
        }
        if (s.state != ViewSession.State.PLAYING && s.state != ViewSession.State.PAUSED) {
            return "当前状态不能调整倍速: " + s.state;
        }
        s.setSpeed(speed);
        return "回看速度已设为 " + String.format("%.2f", speed).replaceAll("0+$", "").replaceAll("\\.$", "") + "×";
    }

    /** 一键对齐（F-96）：单目标贴到理想观察点；不改变 follow 开关。 */
    public synchronized String align(MinecraftServer server, ServerPlayer actor, String target,
                                     boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "对齐他人回看需要管理员权限";
        }
        if (s.state != ViewSession.State.PLAYING && s.state != ViewSession.State.PAUSED) {
            return "当前状态不能对齐: " + s.state;
        }
        ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
        if (viewer == null) {
            return "观看者不在线";
        }
        if (viewer.getCamera() != viewer) {
            viewer.setCamera(viewer);
        }
        align(s, viewer, s.trackTime(System.currentTimeMillis()));
        return "已对齐到 " + (s.group ? "场景中心" : s.displayName) + " 的观察点";
    }

    /** 进度跳转（F-101）：绝对时间 / 相对时间 / 百分比，钳到游标窗口。 */
    public synchronized String seek(MinecraftServer server, ServerPlayer actor, String target,
                                    String spec, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "跳转他人回看需要管理员权限";
        }
        if (s.state != ViewSession.State.PLAYING && s.state != ViewSession.State.PAUSED) {
            return "当前状态不能跳转: " + s.state;
        }
        if (s.cursor == null) {
            return "回看仍在加载，请稍候";
        }
        long now = System.currentTimeMillis();
        long current = s.trackTime(now);
        long targetMs;
        try {
            targetMs = ViewSeek.target(spec, current, s.timelineStartMs, s.endTrackMs);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        s.seekTo(targetMs);
        for (ViewTarget viewTarget : s.targets) {
            viewTarget.eventsJumpPending = true;
        }
        s.resetChatCursors(targetMs);
        if (s.aimTracker != null) {
            s.aimTracker.reset();
        }
        if (!refreshTargets(server, s, targetMs, now)) {
            return "跳转后没有可见目标（可能全部在其他维度/区间外）";
        }
        if (s.followActive) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
            if (viewer != null) {
                follow(s, viewer, targetMs);
            }
        }
        float progress = s.progress(System.currentTimeMillis());
        if (s.display != null) {
            s.display.update("[回看] " + s.displayName + " · "
                    + Math.round(progress * 100) + "% · "
                    + String.format("%.1f", s.speed()) + "×"
                    + (s.aimEnabled ? " · 自瞄" : ""), progress);
        }
        return "已跳转到 " + ViewTime.format(targetMs)
                + "（" + Math.round(progress * 100) + "%）";
    }

    /** 自动瞄准开关（F-100）：on/off；null = 切换。 */
    public synchronized String setAim(MinecraftServer server, ServerPlayer actor, String target,
                                      Boolean on, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "控制他人回看需要管理员权限";
        }
        if (s.state != ViewSession.State.PLAYING && s.state != ViewSession.State.PAUSED) {
            return "当前状态不能切换自瞄: " + s.state;
        }
        s.aimEnabled = on == null ? !s.aimEnabled : on;
        s.lastAimAtMs = System.currentTimeMillis();
        s.aimSnapAccumSec = 1.0;
        if (s.aimTracker != null) {
            s.aimTracker.reset();
        }
        return "自动瞄准已" + (s.aimEnabled ? "开启" : "关闭");
    }

    /** F-107：本次回看的装备掉落开关（只影响捕捉后普通实体的死亡掉落）。 */
    public synchronized String setEquipmentDrop(MinecraftServer server, ServerPlayer actor,
                                                String target, Boolean on, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "控制他人回看需要管理员权限";
        }
        // 回看人偶没有死亡掉落路径；保留命令仅为兼容旧脚本，不再改变任何状态
        return "回看人偶永不掉落；view drop 仅为旧命令兼容，不产生掉落效果"
                + "（捕捉式回放的装备掉落由 view.allowEquipmentDrop 在回放前决定）";
    }

    /**
     * F-108：只读、分页查看录制的完整背包快照。
     * 不贴到实体库存、不生成 ItemEntity/容器，因此不会产生可拾取物品。
     */
    public synchronized String inspect(MinecraftServer server, ServerPlayer actor,
                                       String target, int page, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "查看他人回看背包需要管理员权限";
        }
        return inspectSession(s, page).text();
    }

    /**
     * 右键回看人偶：打开背包第 1 页；1.5 秒内再次右键翻下一页，末页后回到第 1 页。
     * 仍复用只读快照，不生成任何可拾取物品。
     */
    public synchronized void inspectByRightClick(ServerPlayer viewer, ViewMannequin mannequin) {
        if (viewer == null || mannequin == null) {
            return;
        }
        ViewSession s = sessions.get(viewer.getUUID());
        if (s == null || s.closed()) {
            return;
        }
        ViewTarget target = null;
        for (ViewTarget candidate : s.targets) {
            if (candidate.mannequin == mannequin) {
                target = candidate;
                break;
            }
        }
        if (target == null) {
            return;
        }
        if (viewer.distanceToSqr(mannequin) > 64.0 * 64.0) {
            return;
        }
        long now = System.currentTimeMillis();
        // 一次右键可能同时发主手/副手两个交互包：200ms 内只认一次
        if (now - s.lastInspectAtMs < 200L) {
            return;
        }
        int next = now - s.lastInspectAtMs <= 1_500L ? s.inspectPage + 1 : 1;
        InspectResult result = inspectSession(s, target, next);
        if (result.page() > 0) {
            s.inspectPage = result.page();
            s.lastInspectAtMs = now;
        }
        String text = result.text();
        if (result.pages() > 1) {
            text += "\n右键人偶：翻到下一页";
        }
        Samsara.LOGGER.info("[回看] 右键查看背包: viewer={} target={} page={}/{}",
                s.viewerName, target.name, result.page(), result.pages());
        viewer.sendSystemMessage(Component.literal(text));
    }

    private InspectResult inspectSession(ViewSession s, int page) {
        return inspectSession(s, s.primary, page);
    }

    private InspectResult inspectSession(ViewSession s, ViewTarget target, int page) {
        if (s.cursor == null || target == null || target.cursor == null || target.events == null) {
            return new InspectResult("回看仍在加载或该段没有事件数据", 0, 0);
        }
        try {
            long time = s.trackTime(System.currentTimeMillis());
            TrackEventStore.InventorySnapshot snapshot;
            try {
                snapshot = target.events.inventoryAt(time);
            } catch (RuntimeException e) {
                Samsara.LOGGER.warn("[回看] 背包快照读取失败 viewer={} target={}: {}",
                        s.viewerName, target.name, e.getMessage());
                return new InspectResult("背包快照读取失败（事件可能仍在写入）：" + e.getMessage(), 0, 0);
            }
            if (snapshot == null) {
                return new InspectResult("该时刻没有完整背包快照（track.recordInventory=false，或该段录制于开启之前）；"
                        + "已记录的可见装备仍显示在人偶身上", 0, 0);
            }
            List<TrackEventStore.InventoryItem> items = new ArrayList<>(snapshot.items());
            items.sort(java.util.Comparator.comparingInt(TrackEventStore.InventoryItem::slot));
            int pageSize = 10;
            int pages = Math.max(1, (items.size() + pageSize - 1) / pageSize);
            int current = Math.max(1, Math.min(pages, page));
            StringBuilder sb = new StringBuilder("[回看] ").append(target.name)
                    .append(" 的只读背包快照（").append(ViewTime.format(time)).append("）")
                    .append(" · 第 ").append(current).append("/").append(pages).append(" 页")
                    .append(" · 共 ").append(items.size()).append(" 件（只读，不会生成物品）");
            int from = (current - 1) * pageSize;
            int to = Math.min(items.size(), from + pageSize);
            for (int i = from; i < to; i++) {
                TrackEventStore.InventoryItem item = items.get(i);
                sb.append("\n  槽 ").append(item.slot()).append(": ")
                        .append(item.stack().getItemName().getString())
                        .append(" ×").append(item.stack().getCount());
            }
            if (items.isEmpty()) {
                sb.append("\n  （空背包）");
            }
            if (current < pages) {
                sb.append("\n下一页：/samsara track view inspect ")
                        .append(s.viewerName).append(" ").append(current + 1);
            }
            return new InspectResult(sb.toString(), current, pages);
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[回看] 背包快照读取失败 viewer={} target={}: {}",
                    s.viewerName, target.name, e.toString(), e);
            return new InspectResult("背包快照读取失败（事件可能仍在写入）：" + e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : " " + e.getMessage()), 0, 0);
        }
    }

    public record InspectResult(String text, int page, int pages) {
    }

    /** 拖动模式开关（F-102）：Shift/Ctrl/空格；滚轮不可用。 */
    public synchronized String setScrub(MinecraftServer server, ServerPlayer actor, String target,
                                        Boolean on, boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "控制他人回看需要管理员权限";
        }
        if (s.state != ViewSession.State.PLAYING && s.state != ViewSession.State.PAUSED) {
            return "当前状态不能开启拖动: " + s.state;
        }
        boolean enable = on == null ? !s.scrubMode : on;
        if (enable && !s.scrubMode) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
            if (viewer == null) {
                return "观看者不在线";
            }
            s.scrubMode = true;
            s.scrubAnchorDim = null;
            viewer.sendOverlayMessage(Component.literal(
                    "[拖动] 按住 Ctrl 进入 · W/S 前后 · Shift 加速 · 空格暂停"));
            return "拖动模式已开启：按住 Ctrl + W/S 前后拖动 / Shift 加速 / 空格暂停；松开 Ctrl 即退出";
        }
        if (!enable && s.scrubMode) {
            s.scrubMode = false;
            s.scrubAnchorDim = null;
            s.scrubbingThisTick = false;
            s.scrubHoldSinceMs = 0L;
            ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
            if (viewer != null) {
                viewer.sendOverlayMessage(Component.literal("[拖动] 已关闭"));
            }
            return "拖动模式已关闭";
        }
        return enable ? "拖动模式已经是开启状态" : "拖动模式已经是关闭状态";
    }

    /** 命令层判断某名字是否是正在观看的回看者（用于 seek 的“玩家 + 参数”拆词）。 */
    public synchronized boolean hasViewerSession(MinecraftServer server, String name) {
        return name != null && !name.isBlank() && findSession(server, null, name) != null;
    }

    /** 录制端用：该 UUID 是否正在回看（不解析名字，避免每 tick 查玩家表）。 */
    public synchronized boolean isViewing(UUID viewer) {
        return viewer != null && sessions.containsKey(viewer);
    }

    /** camera 固定焦点：人偶离开焦点范围时隐藏，回来时恢复；只影响本会话的客户端实体。 */
    public synchronized void setMannequinVisible(UUID viewer, boolean visible) {
        ViewSession s = viewer == null ? null : sessions.get(viewer);
        if (s == null) {
            return;
        }
        for (ViewTarget target : s.targets) {
            ViewMannequin mannequin = target.mannequin;
            if (mannequin != null && !mannequin.isRemoved()) {
                mannequin.setInvisible(!visible);
            }
        }
        if (s.mannequin != null && !s.mannequin.isRemoved()) {
            s.mannequin.setInvisible(!visible);
        }
    }

    /** 点击控制面板：切换正放/倒放 */
    public synchronized String toggleReverse(MinecraftServer server, ServerPlayer actor, String target,
                                             boolean allowOthers) {
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "控制他人回看需要管理员权限";
        }
        if (s.state != ViewSession.State.PLAYING && s.state != ViewSession.State.PAUSED) {
            return "当前状态不能倒放: " + s.state;
        }
        s.toggleDirection();
        for (ViewTarget viewTarget : s.targets) {
            viewTarget.eventsJumpPending = true;
        }
        s.resetChatCursors(s.trackTime(System.currentTimeMillis()));
        return s.direction() < 0 ? "已切换为倒放（已清空动作状态）" : "已切换为正放（按快照重建动作状态）";
    }

    private ViewSession findSession(MinecraftServer server, ServerPlayer actor, String target) {
        if (target == null || target.isBlank()) {
            return actor == null ? null : sessions.get(actor.getUUID());
        }
        UUID id = resolvePlayerId(server, target);
        ViewSession s = id == null ? null : sessions.get(id);
        if (s == null) {
            for (ViewSession candidate : sessions.values()) {
                if (candidate.viewerName.equalsIgnoreCase(target)) {
                    return candidate;
                }
            }
        }
        return s;
    }

    public synchronized String status(MinecraftServer server, ServerPlayer actor, String target) {
        ViewSession s = null;
        if (target == null || target.isBlank()) {
            if (actor != null) {
                s = sessions.get(actor.getUUID());
            }
        } else {
            UUID id = resolvePlayerId(server, target);
            if (id != null) {
                s = sessions.get(id);
            }
            if (s == null) {
                for (ViewSession candidate : sessions.values()) {
                    if (candidate.viewerName.equalsIgnoreCase(target)) {
                        s = candidate;
                        break;
                    }
                }
            }
        }
        if (s == null) {
            if (actor != null && pending.containsKey(actor.getUUID())) {
                ViewSession p = pending.get(actor.getUUID()).session;
                return "回看加载中 · 目标 " + p.targetName + " · 周目 " + p.cycleId
                        + " · " + p.options.speed() + "×";
            }
            return target == null || target.isBlank() ? "你当前没有进行中的回看"
                    : "该玩家没有进行中的回看: " + target;
        }
        long now = System.currentTimeMillis();
        StringBuilder sb = new StringBuilder("回看中 · 目标 ").append(s.displayName)
                .append(s.group ? "（" + s.targets.size() + " 个）" : "")
                .append(" · 周目 ").append(s.cycleId)
                .append(" · ").append(s.speed()).append("×")
                .append(s.direction() < 0 ? " 倒放" : " 正放")
                .append(" · ").append(s.state);
        sb.append("\n来源: ").append(s.sourceSummary == null ? s.cycleId + " / 加载中" : s.sourceSummary);
        sb.append("\n进度: ").append(Math.round(s.progress(now) * 100)).append("%")
                .append(" · 轨迹时间 ").append(ViewTime.format(s.trackTime(now)))
                .append(s.direction() < 0 ? " · 距起点 " : " · 剩余 ")
                .append(com.shenqianye.samsara.season.DurationUtil.formatMs(s.remainingMs(now)));
        sb.append("\n参数: follow=").append(s.followActive ? "on" : "off")
                .append(s.followTarget != null && s.followActive ? "（" + followName(s) + "）" : "")
                .append(" · spectator=on")
                .append(" · aim=").append(s.aimEnabled
                        ? String.format("on（%.0f°/%.0f° · %.0f°/s）",
                        s.aimDeadzone, s.aimEdge, s.aimSpeed)
                        : "off")
                .append(" · 拖动=").append(s.scrubMode ? "on" : "off")
                .append(" · 聊天=").append(s.chatCount);
        if (s.group || s.targets.stream().anyMatch(t -> t.sharedHost != null)) {
            sb.append("\n目标:");
            for (ViewTarget viewTarget : s.targets) {
                sb.append("\n  ▸ ").append(viewTarget.name)
                        .append(" · ").append(viewTarget.visible ? "可见" : "隐藏")
                        .append(viewTarget.status == null || viewTarget.status.isBlank()
                                ? "" : "（" + viewTarget.status + "）");
            }
        }
        if (s.cutoffApplied) {
            sb.append("\n⚠ 该段被捕捉截断（截断之后不播）");
        }
        if (s.truncated) {
            sb.append("\n⚠ 文件尾部不完整，已播到最后一个完整样本");
        }
        if (s.legacyEquipment) {
            sb.append("\n⚠ 该段录制于装备事件之前（空手）");
        } else if (s.events != null) {
            sb.append("\n").append(s.events.describeAt(s.trackTime(now)));
        }
        sb.append("\n掉落: 回看人偶永不掉落（无掉落开关）");
        if (s.eventsTruncated) {
            sb.append("\n⚠ 事件文件尾部不完整，已读到最后一个完整事件");
        }
        return sb.toString();
    }

    /**
     * 重新发送聊天栏控制条（按钮）。
     *
     * <p>控制条只在会话开始时发送一次；聊天记录被刷掉后玩家就找不回按钮了。
     * 该入口只重发控制条，不改变会话状态，也不重置进度。
     *
     * <p>{@code target} 是<b>接收者（观看者）</b>，不是被回放的监控目标。
     * 省略时：玩家发给自己，控制台默认广播（控制台没有聊天栏可显示）。
     */
    public synchronized String showControls(MinecraftServer server, ServerPlayer actor,
                                            String target, boolean allowOthers) {
        if (target == null || target.isBlank()) {
            if (actor == null) {
                // 控制台无法显示按钮，默认广播给所有观看者
                return broadcastControls(server, null, true);
            }
            ViewSession own = sessions.get(actor.getUUID());
            if (own == null || own.closed()) {
                return "你当前没有进行中的回看";
            }
            sendControls(own, actor);
            return "已重新发送回看控制条（接收者：你自己）";
        }
        ViewSession s = findSession(server, actor, target);
        if (s == null) {
            return "没有该接收者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(s.viewer) && !allowOthers) {
            return "重发他人回看控制条需要管理员权限；广播请用 /samsara track view ui broadcast";
        }
        ServerPlayer viewer = server == null ? null : server.getPlayerList().getPlayer(s.viewer);
        if (viewer == null) {
            return "接收者不在线: " + s.viewerName;
        }
        sendControls(s, viewer);
        return "已向接收者 " + s.viewerName + " 重新发送回看控制条";
    }

    /**
     * 把控制条广播给所有当前观看者。
     *
     * <p>只有管理员（或控制台）可以广播；普通玩家请使用 {@link #showControls} 发给自己。
     */
    public synchronized String broadcastControls(MinecraftServer server, ServerPlayer actor,
                                                 boolean allowOthers) {
        if (actor != null && !allowOthers) {
            return "广播回看控制条需要管理员权限";
        }
        int sent = 0;
        for (ViewSession s : sessions.values()) {
            if (s.closed()) {
                continue;
            }
            ServerPlayer viewer = server == null ? null : server.getPlayerList().getPlayer(s.viewer);
            if (viewer != null) {
                sendControls(s, viewer);
                sent++;
            }
        }
        return sent == 0
                ? "当前没有进行中的回看"
                : "已向 " + sent + " 个观看者广播回看控制条";
    }

    /**
     * 可回看时段列表（可点击）：每个时段用 {@link ClickEvent.SuggestCommand} 填入回看命令，
     * 避免玩家手打长串时间戳/参数。
     */
    public synchronized Component list(MinecraftServer server, String targetName, int page) {
        UUID uuid = resolvePlayerId(server, targetName);
        if (uuid == null) {
            return Component.literal("[周目管理] ▸ 找不到玩家: " + targetName);
        }
        String display = displayName(server, uuid, targetName);
        TrackRecorder recorder = TrackRecorder.getInstance();
        List<Path> roots = recorder.trackRoots();
        if (roots.isEmpty()) {
            return Component.literal("[周目管理] ▸ 世界轨迹目录尚未初始化");
        }
        String worldKey = recorder.worldKey() == null ? DiffStore.sanitizeWorldKey("world") : recorder.worldKey();
        List<UUID> ids = com.shenqianye.samsara.identity.IdentityService.getInstance()
                .aliasesOf(uuid);
        List<ViewTrackReader.Available> all = ViewTrackReader.available(roots, ids,
                (root, owner, cycle) -> recorder.cutsForTrackRoot(root)
                        .cutoff(recorder.worldKeyOfTrackRoot(root), cycle, owner));
        if (all.isEmpty()) {
            return Component.literal("[周目管理] ▸ " + display + " 没有可回看的轨迹（可用时段：无）");
        }
        final int pageSize = 8;
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
        int p = Math.max(1, Math.min(page, pages));
        int from = (p - 1) * pageSize;
        int to = Math.min(from + pageSize, all.size());

        MutableComponent out = Component.literal("[周目管理] ▸ " + display + " 可回看时段（")
                .append(String.valueOf(all.size())).append(" 个文件 · 第 ")
                .append(String.valueOf(p)).append("/").append(String.valueOf(pages)).append(" 页）：");
        for (int i = from; i < to; i++) {
            ViewTrackReader.Available a = all.get(i);
            out.append("\n");
            if (a.error() != null) {
                out.append(Component.literal("  ▸ cycle" + a.cycleId() + " / " + a.day()
                        + " · 损坏: " + a.error()).withStyle(ChatFormatting.RED));
                continue;
            }
            String fullStart = ViewTime.format(a.firstMs());
            String command = "/samsara track view " + display + " "
                    + fullStart.replace(' ', 'T')
                    + " --cycle cycle" + a.cycleId() + " --duration 30m";
            String flags = (a.cutoffApplied() ? " · 被截断" : "")
                    + (a.truncated() ? " · 尾部不完整" : "");
            String label = "  ▸ [cycle" + a.cycleId() + " / " + a.day() + "] "
                    + shortTime(a.firstMs()) + " → " + shortTime(a.lastMs())
                    + " · " + a.samples() + " 点" + flags;
            out.append(ChatUi.suggestText(label, command,
                    "点击填入回看命令（不会直接执行）：\n" + command));
        }
        out.append("\n").append(ChatUi.text("  ▸ 点击任意条目自动填入回看命令（不会直接执行）",
                ChatFormatting.GRAY));
        if (pages > 1) {
            out.append("\n  ");
            if (p > 1) {
                out.append(ChatUi.suggest("上一页", "/samsara track list " + display + " " + (p - 1),
                        "点击填入上一页命令"));
            }
            if (p < pages) {
                if (p > 1) out.append(" ");
                out.append(ChatUi.suggest("下一页", "/samsara track list " + display + " " + (p + 1),
                        "点击填入下一页命令"));
            }
        }
        return out;
    }

    /** 列表里只显示 HH:mm:ss，日期已在条目开头。 */
    private static String shortTime(long epochMs) {
        String text = ViewTime.format(epochMs);
        int space = text.indexOf(' ');
        if (space < 0) {
            return text;
        }
        String time = text.substring(space + 1);
        return time.length() > 8 ? time.substring(0, 8) : time;
    }

    // ---- 生命周期 ----

    private volatile MinecraftServer currentServer;

    public synchronized void onServerStarted(MinecraftServer server) {
        currentServer = server;
        cleanupOrphans(server);
    }

    /** 观看者退出：先恢复，再让服务器保存 playerdata（F-84/F-85）。 */
    public synchronized void onDisconnect(UUID viewer) {
        pending.remove(viewer);
        ViewSession s = sessions.get(viewer);
        if (s != null) {
            restore(s, "观看者退出");
        }
    }

    /** SERVER_STOPPING/SERVER_STOPPED：先恢复观看者，再让服务器保存。 */
    public synchronized void shutdown(MinecraftServer server) {
        currentServer = server;
        pending.clear();
        for (ViewSession s : new ArrayList<>(sessions.values())) {
            restore(s, "服务器停止");
        }
        sessions.clear();
        SharedViewMannequin.clear();
        cleanupOrphans(server);
        currentServer = null;
    }

    /** 清理崩溃/异常残留的回看人偶（它们本就不写存档，这里兜底）。 */
    public synchronized void cleanupOrphans(MinecraftServer server) {
        if (server == null) {
            return;
        }
        SharedViewMannequin.clear();
        for (ServerLevel level : server.getAllLevels()) {
            List<ViewMannequin> leftovers = new ArrayList<>();
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ViewMannequin mannequin) {
                    leftovers.add(mannequin);
                }
            }
            for (ViewMannequin mannequin : leftovers) {
                mannequin.discard();
            }
        }
    }

    // ---- 工具 ----

    private static String summarize(List<Path> files, int cycleId) {
        if (files.isEmpty()) {
            return "cycle" + cycleId + " / 无文件";
        }
        String firstDay = files.get(0).getParent() == null ? "?"
                : files.get(0).getParent().getFileName().toString();
        String lastDay = files.get(files.size() - 1).getParent() == null ? "?"
                : files.get(files.size() - 1).getParent().getFileName().toString();
        String range = firstDay.equals(lastDay) ? firstDay : firstDay + ".." + lastDay;
        String fileName = files.get(0).getFileName().toString();
        return "cycle" + cycleId + " / " + range + " / " + fileName + "（" + files.size() + " 个文件）";
    }

    private static String candidateCycles() {
        List<Integer> ids = TrackRecorder.getInstance().cycleIds();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < Math.min(ids.size(), 8); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append("cycle").append(ids.get(i));
        }
        return sb.length() == 0 ? "无" : sb.toString();
    }

    /** 目标解析：直接 UUID / 在线名 / 注册名单 / usercache。 */
    private static UUID resolvePlayerId(MinecraftServer server, String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(spec.trim());
        } catch (IllegalArgumentException ignored) {
        }
        ServerPlayer online = server.getPlayerList().getPlayerByName(spec);
        if (online != null) {
            return online.getUUID();
        }
        UUID id = TrackRecorder.resolveUuid(server, spec);
        if (id != null) {
            return id;
        }
        try {
            return server.services().nameToIdCache().get(spec).map(n -> n.id()).orElse(null);
        } catch (Exception e) {
            return null;
        }
    }

    private static String displayName(MinecraftServer server, UUID uuid, String fallback) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getScoreboardName();
        }
        try {
            var found = server.services().nameToIdCache().get(uuid);
            if (found.isPresent()) {
                return found.get().name();
            }
        } catch (Exception ignored) {
        }
        return fallback;
    }

    private static String rootMessage(Throwable t) {
        Throwable c = t;
        while (c.getCause() != null && c.getCause() != c) {
            c = c.getCause();
        }
        return c.getMessage() == null ? c.getClass().getSimpleName() : c.getMessage();
    }

    private void restoreVehicle(MinecraftServer server, ServerPlayer viewer, ViewSession s) {
        if (server == null || s.vehicleId == null || viewer.isDeadOrDying()) {
            return;
        }
        Entity current = viewer.getVehicle();
        if (current != null && current.getUUID().equals(s.vehicleId)) {
            return;
        }
        Entity vehicle = findEntity(server, s.vehicleId);
        if (vehicle != null && !vehicle.isRemoved()) {
            if (current != null) {
                viewer.stopRiding();
            }
            viewer.startRiding(vehicle, true, false);
        }
    }

    private static Entity findEntity(MinecraftServer server, java.util.UUID id) {
        for (ServerLevel level : server.getAllLevels()) {
            for (Entity entity : level.getAllEntities()) {
                if (entity.getUUID().equals(id)) {
                    return entity;
                }
            }
        }
        return null;
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        if (server == null) {
            return null;
        }
        for (var key : server.levelKeys()) {
            String id = key.identifier().toString();
            if (id.equals(dimension) || key.identifier().getPath().equals(dimension)
                    || id.equals("minecraft:" + dimension)) {
                return server.getLevel(key);
            }
        }
        return null;
    }

    /** 现有 profile 解析链：在线 → usercache → 仅 uuid/name。 */
    private static GameProfile resolveProfile(MinecraftServer server, UUID uuid, String name) {
        ServerPlayer online = server.getPlayerList().getPlayer(uuid);
        if (online != null) {
            return online.getGameProfile();
        }
        try {
            var found = server.services().nameToIdCache().get(uuid);
            if (found.isPresent()) {
                return new GameProfile(found.get().id(), found.get().name());
            }
        } catch (Exception ignored) {
        }
        return new GameProfile(uuid, name == null ? uuid.toString() : name);
    }
}
