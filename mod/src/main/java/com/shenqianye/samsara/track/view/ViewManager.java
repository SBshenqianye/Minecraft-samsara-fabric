package com.shenqianye.samsara.track.view;

import com.mojang.authlib.GameProfile;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.rewind.DiffStore;
import com.shenqianye.samsara.season.SeasonManager;
import com.shenqianye.samsara.track.TrackLayout;
import com.shenqianye.samsara.track.TrackRecorder;
import com.shenqianye.samsara.track.TrackReplay;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.io.IOException;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
        final CompletableFuture<ViewTrackReader.LoadResult> future;
        final ViewSession old;
        final Long cutoff;

        Pending(ViewSession session, CompletableFuture<ViewTrackReader.LoadResult> future,
                ViewSession old, Long cutoff) {
            this.session = session;
            this.future = future;
            this.old = old;
            this.cutoff = cutoff;
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

        ServerPlayer onlineTarget = server.getPlayerList().getPlayerByName(targetName);
        UUID targetUuid = onlineTarget != null ? onlineTarget.getUUID() : resolvePlayerId(server, targetName);
        if (targetUuid == null) {
            return "找不到玩家: " + targetName + "（可用 /samsara debug fakeplayers 注入测试名单）";
        }
        String canonical = onlineTarget != null ? onlineTarget.getScoreboardName() : displayName(server, targetUuid, targetName);
        boolean other = !targetUuid.equals(viewer.getUUID());
        if (other && !allowOthers) {
            return "回看他人记录需要管理员权限";
        }

        long dayEnd = ViewTime.dayOf(startMs).plusDays(1)
                .atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli() - 1;
        ViewOptions options;
        try {
            options = ViewOptions.parse(optionText, startMs, dayEnd);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        if (TrackReplay.isReplaying(targetUuid)) {
            return "目标 " + canonical + " 已有捕捉式回放在进行中，请先 /samsara debug track stop " + canonical
                    + "（回看与捕捉式回放不并行，F-92）";
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
        Path worldRoot = recorder.worldRoot();
        if (worldRoot == null) {
            return "世界轨迹目录尚未初始化，请等服务器启动完成后再试";
        }
        String worldKey = recorder.worldKey() == null ? DiffStore.sanitizeWorldKey("world") : recorder.worldKey();
        recorder.cuts().load();
        Long cutoff = recorder.cuts().cutoff(worldKey, cycleId, targetUuid);
        String summary = "cycle" + cycleId + " · " + ViewTime.format(startMs) + " → "
                + ViewTime.format(options.endMs());

        ViewSession old = sessions.get(viewer.getUUID());
        if (pending.containsKey(viewer.getUUID())) {
            return "上一回看请求仍在加载，请稍候";
        }
        int active = sessions.size() + pending.size();
        if (old == null && active >= MAX_SESSIONS) {
            return "回看并发已达上限（" + MAX_SESSIONS + " 个），请等其他人结束";
        }

        ViewSession session = new ViewSession(viewer.getUUID(), viewer.getScoreboardName(),
                canonical, targetUuid, options, cycleId, startMs, options.endMs(), summary);
        session.liveCycleAtStart = recorder.cycleIdNow();
        lastStart.put(viewer.getUUID(), now);

        final long start = startMs;
        final long end = options.endMs();
        final UUID target = targetUuid;
        final int cycle = cycleId;
        final Long cut = cutoff;
        CompletableFuture<ViewTrackReader.LoadResult> future = CompletableFuture.supplyAsync(() -> {
            try {
                return ViewTrackReader.load(worldRoot, cycle, target, start, end, cut);
            } catch (IOException e) {
                throw new CompletionException(e);
            }
        }, loader);
        pending.put(viewer.getUUID(), new Pending(session, future, old, cutoff));

        Samsara.LOGGER.info("[回看] {} 请求回看 {}（周目 {} · {} → {} · {}x）",
                viewer.getScoreboardName(), canonical, cycleId,
                ViewTime.format(startMs), ViewTime.format(options.endMs()), options.speed());
        if (other) {
            ViewAudit.append(viewer.getScoreboardName(), canonical, cycleId, startMs,
                    options.endMs(), options.speed(), "REQUEST");
        }
        return "正在加载 " + canonical + " 的轨迹（周目 " + cycleId + " · "
                + ViewTime.format(startMs) + " → " + ViewTime.format(options.endMs())
                + " · " + options.speed() + "×）…";
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
            if (s.state == ViewSession.State.PLAYING) {
                long trackTime = s.trackTime(now);
                if (trackTime >= s.endTrackMs) {
                    restore(s, "播放完毕");
                    continue;
                }
                if (!updateMannequin(server, s, trackTime)) {
                    restore(s, "人偶所在维度不可用");
                    continue;
                }
                if (s.options.follow()) {
                    follow(s, player, trackTime);
                }
                if (s.display != null) {
                    s.display.update("[回看] " + s.targetName + " · "
                                    + Math.round(s.progress(now) * 100) + "% · "
                                    + String.format("%.1f", s.options.speed()) + "×",
                            s.progress(now));
                }
            } else if (s.state == ViewSession.State.PAUSED && s.display != null) {
                s.display.update("[回看] " + s.targetName + " · 已暂停 · "
                        + Math.round(s.progress(now) * 100) + "%", s.progress(now));
            }
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
            ViewTrackReader.LoadResult result;
            try {
                result = p.future.join();
            } catch (CompletionException ex) {
                fail(server, p, "加载失败: " + rootMessage(ex));
                continue;
            } catch (Exception ex) {
                fail(server, p, "加载失败: " + ex.getMessage());
                continue;
            }
            if (result.totalSamples() > MAX_SAMPLES) {
                fail(server, p, "样本 " + result.totalSamples() + " 超过 viewMaxSamples="
                        + MAX_SAMPLES + "；请用 --to 或 --duration 缩小窗口");
                continue;
            }
            ViewCursor cursor;
            try {
                cursor = ViewCursor.build(result.segments(), p.session.windowFrom, p.session.windowTo,
                        result.truncated(), result.cutoffApplied());
            } catch (IllegalArgumentException ex) {
                fail(server, p, ex.getMessage());
                continue;
            }
            p.session.cursor = cursor;
            p.session.truncated = result.truncated();
            p.session.cutoffApplied = result.cutoffApplied();
            p.session.sourceSummary = summarize(result.files(), p.session.cycleId)
                    + " · " + ViewTime.format(cursor.startMs()) + " → " + ViewTime.format(cursor.endMs());
            apply(server, p);
        }
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
        if (TrackReplay.isReplaying(s.targetUuid)) {
            fail(server, p, "目标已有捕捉式回放在进行中（F-92）");
            return;
        }
        if (TrackRecorder.getInstance().cycleIdNow() != s.liveCycleAtStart) {
            fail(server, p, "加载期间周目已切换");
            return;
        }

        ViewCursor.Point first = s.cursor.pointAt(s.cursor.startMs());
        ServerLevel level = findLevel(server, first.dimension());
        if (level == null) {
            fail(server, p, "轨迹维度不存在: " + first.dimension());
            return;
        }

        // 1) 人偶与临时 ticket（失败时观看者仍未被修改）
        moveTicket(s, level, BlockPos.containing(first.x(), first.y(), first.z()));
        ViewMannequin entity = new ViewMannequin(level, s.targetUuid, s.targetName,
                s.options.interact(), s.options.glow(), s.options.nameTag());
        GameProfile profile = resolveProfile(server, s.targetUuid, s.targetName);
        s.profile = profile;
        entity.applyProfile(profile);
        entity.snapTo(first.x(), first.y(), first.z(), first.yaw(), first.pitch());
        entity.setYHeadRot(first.yaw());
        entity.setYBodyRot(first.yaw());
        entity.yHeadRotO = first.yaw();
        entity.yBodyRotO = first.yaw();
        entity.applyTrackFlags(first.flags());
        entity.walkAnimation.stop();
        s.prevMannequinX = first.x();
        s.prevMannequinY = first.y();
        s.prevMannequinZ = first.z();
        s.hasPrevMannequin = true;
        if (!level.addFreshEntity(entity)) {
            entity.discard();
            releaseTickets(s);
            fail(server, p, "人偶生成失败（区块未加载）");
            return;
        }

        // 2) 旧会话先结束（同观看者替换，不叠加实体）
        if (p.old != null) {
            restore(p.old, "被新的回看替换");
        }

        try {
            s.snapshot = ViewSnapshot.capture(viewer);
            s.playerRef = viewer;
            s.vehicleId = viewer.getVehicle() == null ? null : viewer.getVehicle().getUUID();
            s.mannequin = entity;
            s.display = new ViewDisplay(viewer, s.targetName, s.cycleId);
            if (s.options.spectator()) {
                viewer.setGameMode(net.minecraft.world.level.GameType.SPECTATOR);
            }
            s.lastWriteDim = viewer.level().dimension().identifier().toString();
            s.lastWriteX = viewer.getX();
            s.lastWriteY = viewer.getY();
            s.lastWriteZ = viewer.getZ();
            s.startWallMs = System.currentTimeMillis();
            s.startTrackMs = s.cursor.startMs();
            s.endTrackMs = s.cursor.endMs();
            s.state = ViewSession.State.PLAYING;
            sessions.put(s.viewer, s);
            if (s.options.follow()) {
                follow(s, viewer, s.startTrackMs);
            }
            updateMannequin(server, s, s.startTrackMs);
            s.display.update("[回看] " + s.targetName + " · 0%", 0f);

            String msg = "开始回看 " + s.targetName + "（周目 " + s.cycleId + " · "
                    + s.sourceSummary + " · " + s.options.speed() + "× · 样本 "
                    + s.cursor.sampleCount() + " 点"
                    + (s.options.follow() ? " · 跟随" : "")
                    + (s.options.spectator() ? " · 旁观" : "")
                    + (s.options.interact() ? " · 可交互(只读)" : "") + "）"
                    + (s.cutoffApplied ? " · 已按捕捉截断" : "")
                    + (s.truncated ? " · 文件尾部已截断" : "")
                    + "\n停止：/samsara track view stop";
            Samsara.LOGGER.info("[回看] 开始: viewer={} target={} cycle={} window={} → {} speed={} samples={} cutoff={} truncated={}",
                    s.viewerName, s.targetName, s.cycleId, ViewTime.format(s.startTrackMs),
                    ViewTime.format(s.endTrackMs), s.options.speed(), s.cursor.sampleCount(),
                    s.cutoffApplied, s.truncated);
            viewer.sendSystemMessage(Component.literal("[回看] " + msg));
            if (!s.targetUuid.equals(s.viewer)) {
                ViewAudit.append(s.viewerName, s.targetName, s.cycleId, s.windowFrom,
                        s.windowTo, s.options.speed(), "STARTED");
            }
        } catch (Exception e) {
            Samsara.LOGGER.warn("[回看] 应用会话失败，回滚观看者状态: {}", e.getMessage());
            s.mannequin = entity;
            restore(s, "启动异常: " + e.getMessage());
        }
    }

    private void fail(MinecraftServer server, Pending p, String reason) {
        ViewSession s = p.session;
        s.state = ViewSession.State.FAILED;
        Samsara.LOGGER.warn("[回看] 失败: viewer={} target={} cycle={} window={} → {} speed={} reason={}",
                s.viewerName, s.targetName, s.cycleId, ViewTime.format(s.windowFrom),
                ViewTime.format(s.windowTo), s.options.speed(), reason);
        ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
        if (viewer != null) {
            viewer.sendSystemMessage(Component.literal("[回看] 无法开始：" + reason
                    + "（可用时段：/samsara track list " + s.targetName + "）"));
        }
        if (!s.targetUuid.equals(s.viewer)) {
            ViewAudit.append(s.viewerName, s.targetName, s.cycleId, s.windowFrom,
                    s.windowTo, s.options.speed(), "FAILED: " + reason);
        }
    }

    // ---- 人偶推进 ----

    private boolean updateMannequin(MinecraftServer server, ViewSession s, long trackTime) {
        ViewCursor.Point point = s.cursor.pointAt(trackTime);
        ServerLevel level = findLevel(server, point.dimension());
        if (level == null) {
            return false;
        }
        moveTicket(s, level, BlockPos.containing(point.x(), point.y(), point.z()));
        ViewMannequin m = s.mannequin;
        if (m == null || m.isRemoved()) {
            m = new ViewMannequin(level, s.targetUuid, s.targetName,
                    s.options.interact(), s.options.glow(), s.options.nameTag());
            m.applyProfile(s.profile);
            if (!level.addFreshEntity(m)) {
                return false;
            }
            s.mannequin = m;
        } else if (m.level() != level) {
            m.discard();
            m = new ViewMannequin(level, s.targetUuid, s.targetName,
                    s.options.interact(), s.options.glow(), s.options.nameTag());
            m.applyProfile(s.profile);
            if (!level.addFreshEntity(m)) {
                return false;
            }
            s.mannequin = m;
        }
        m.snapTo(point.x(), point.y(), point.z(), point.yaw(), point.pitch());
        m.setYHeadRot(point.yaw());
        m.setYBodyRot(point.yaw());
        m.yHeadRotO = point.yaw();
        m.yBodyRotO = point.yaw();
        m.setDeltaMovement(Vec3.ZERO);
        m.applyTrackFlags(point.flags());
        if (s.hasPrevMannequin) {
            double dx = point.x() - s.prevMannequinX;
            double dz = point.z() - s.prevMannequinZ;
            float walk = (float) Math.min(1.5, Math.hypot(dx, dz) * 4.0);
            if (walk > 0.02f) {
                m.walkAnimation.setSpeed(walk);
            } else {
                m.walkAnimation.stop();
            }
        }
        s.prevMannequinX = point.x();
        s.prevMannequinY = point.y();
        s.prevMannequinZ = point.z();
        s.hasPrevMannequin = true;
        return true;
    }

    /** 跟随观察点：人偶身后 4.5 格、上方 2 格，并面向人偶（不是贴在人偶身体里）。 */
    private static final double FOLLOW_DISTANCE = 4.5;
    private static final double FOLLOW_HEIGHT = 2.0;

    private void follow(ViewSession s, ServerPlayer player, long trackTime) {
        ViewCursor.Point point = s.cursor.pointAt(trackTime);
        ServerLevel level = findLevel(player.level().getServer(), point.dimension());
        if (level == null) {
            return;
        }
        double yawRad = Math.toRadians(point.yaw());
        double vx = point.x() + Math.sin(yawRad) * FOLLOW_DISTANCE;
        double vy = point.y() + FOLLOW_HEIGHT;
        double vz = point.z() - Math.cos(yawRad) * FOLLOW_DISTANCE;
        double dx = point.x() - vx;
        double dz = point.z() - vz;
        double dy = (point.y() + 1.2) - (vy + 1.62);
        float lookYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float lookPitch = (float) -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        player.teleportTo(level, vx, vy, vz, java.util.Set.of(), lookYaw, lookPitch, false);
        player.setDeltaMovement(Vec3.ZERO);
        player.fallDistance = 0;
        s.lastWriteDim = point.dimension();
        s.lastWriteX = vx;
        s.lastWriteY = vy;
        s.lastWriteZ = vz;
        s.lastWriteAtMs = System.currentTimeMillis();
    }

    // ---- 临时 chunk ticket ----

    private void moveTicket(ViewSession s, ServerLevel level, BlockPos pos) {
        ChunkPos chunk = ChunkPos.containing(pos);
        long key = chunk.pack();
        if (s.ticketLevel != level) {
            releaseTickets(s);
            s.ticketLevel = level;
        }
        if (s.tickets.contains(key)) {
            return;
        }
        if (!level.getChunkSource().hasChunk(chunk.x(), chunk.z())) {
            level.getChunk(chunk.x(), chunk.z()); // 临时 ticket + 同步读取，不使用 setChunkForced
        }
        for (Iterator<Long> it = s.tickets.iterator(); it.hasNext(); ) {
            long old = it.next();
            if (old != key) {
                level.getChunkSource().removeTicketWithRadius(VIEW_TICKET, ChunkPos.unpack(old), 1);
                it.remove();
            }
        }
        if (s.tickets.add(key)) {
            level.getChunkSource().addTicketWithRadius(VIEW_TICKET, chunk, 1);
        }
    }

    private void releaseTickets(ViewSession s) {
        ServerLevel level = s.ticketLevel;
        if (level == null && s.mannequin != null && s.mannequin.level() instanceof ServerLevel sl) {
            level = sl;
        }
        if (level != null) {
            for (long key : new ArrayList<>(s.tickets)) {
                level.getChunkSource().removeTicketWithRadius(VIEW_TICKET, ChunkPos.unpack(key), 1);
            }
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
            return "已停止回看 " + s.targetName;
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

        if (s.mannequin != null) {
            s.mannequin.discard();
            s.mannequin = null;
        }
        releaseTickets(s);
        if (s.display != null) {
            s.display.release();
            s.display = null;
        }

        MinecraftServer server = currentServer;
        ServerPlayer viewer = s.playerRef;
        if (viewer == null && server != null) {
            viewer = server.getPlayerList().getPlayer(s.viewer);
        }
        boolean skippedPosition = false;
        boolean skippedMode = false;
        if (viewer != null && s.snapshot != null) {
            try {
                ViewRestore.Gate gate = new ViewRestore.Gate(
                        s.lastWriteDim, s.lastWriteX, s.lastWriteY, s.lastWriteZ,
                        s.options.spectator() ? "SPECTATOR" : null,
                        ViewRestore.MAX_DRIFT, !viewer.isDeadOrDying());
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
                    + (s.targetName == null ? "" : "（" + s.targetName + "）")));
        }

        sessions.remove(s.viewer, s);
        s.state = ViewSession.State.IDLE;
        Samsara.LOGGER.info("[回看] 结束: viewer={} target={} cycle={} reason={} skippedPosition={} skippedMode={}",
                s.viewerName, s.targetName, s.cycleId, reason, skippedPosition, skippedMode);
        if (server != null && !s.targetUuid.equals(s.viewer)) {
            ViewAudit.append(s.viewerName, s.targetName, s.cycleId, s.windowFrom,
                    s.windowTo, s.options.speed(), "STOPPED: " + reason);
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
        return "已暂停回看 " + s.targetName + "（" + Math.round(s.progress(System.currentTimeMillis()) * 100) + "%）";
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
        return "已继续回看 " + s.targetName;
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
        StringBuilder sb = new StringBuilder("回看中 · 目标 ").append(s.targetName)
                .append(" · 周目 ").append(s.cycleId)
                .append(" · ").append(s.options.speed()).append("×")
                .append(" · ").append(s.state);
        sb.append("\n来源: ").append(s.sourceSummary == null ? s.cycleId + " / 加载中" : s.sourceSummary);
        sb.append("\n进度: ").append(Math.round(s.progress(now) * 100)).append("%")
                .append(" · 轨迹时间 ").append(ViewTime.format(s.trackTime(now)))
                .append(" · 剩余 ").append(com.shenqianye.samsara.season.DurationUtil.formatMs(s.remainingMs(now)));
        sb.append("\n参数: follow=").append(s.options.follow() ? "on" : "off")
                .append(" · spectator=").append(s.options.spectator() ? "on" : "off")
                .append(" · interact=").append(s.options.interact() ? "on" : "off");
        if (s.cutoffApplied) {
            sb.append("\n⚠ 该段被捕捉截断（截断之后不播）");
        }
        if (s.truncated) {
            sb.append("\n⚠ 文件尾部不完整，已播到最后一个完整样本");
        }
        return sb.toString();
    }

    public synchronized String list(MinecraftServer server, String targetName, int page) {
        UUID uuid = resolvePlayerId(server, targetName);
        if (uuid == null) {
            return "找不到玩家: " + targetName;
        }
        String display = displayName(server, uuid, targetName);
        TrackRecorder recorder = TrackRecorder.getInstance();
        Path root = recorder.worldRoot();
        if (root == null) {
            return "世界轨迹目录尚未初始化";
        }
        String worldKey = recorder.worldKey() == null ? DiffStore.sanitizeWorldKey("world") : recorder.worldKey();
        recorder.cuts().load();
        final UUID playerId = uuid;
        List<ViewTrackReader.Available> all = ViewTrackReader.available(root, playerId,
                cycle -> recorder.cuts().cutoff(worldKey, cycle, playerId));
        if (all.isEmpty()) {
            return display + " 没有可回看的轨迹（可用时段：无）";
        }
        final int pageSize = 8;
        int pages = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
        int p = Math.max(1, Math.min(page, pages));
        int from = (p - 1) * pageSize;
        int to = Math.min(from + pageSize, all.size());
        StringBuilder sb = new StringBuilder(display).append(" 可回看时段（")
                .append(all.size()).append(" 个文件 · 第 ").append(p).append("/").append(pages).append(" 页）：");
        for (int i = from; i < to; i++) {
            ViewTrackReader.Available a = all.get(i);
            sb.append("\n  cycle").append(a.cycleId()).append(" / ").append(a.day()).append(" · ");
            if (a.error() != null) {
                sb.append("损坏: ").append(a.error());
                continue;
            }
            sb.append(ViewTime.format(a.firstMs())).append(" → ").append(ViewTime.format(a.lastMs()))
                    .append(" · ").append(a.samples()).append(" 点");
            if (a.cutoffApplied()) {
                sb.append(" · 被截断");
            }
            if (a.truncated()) {
                sb.append(" · 尾部不完整");
            }
        }
        sb.append("\n开始回看：/samsara track view <玩家> <开始时间> [--cycle cycleN] [--duration 30m]");
        return sb.toString();
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
        cleanupOrphans(server);
        currentServer = null;
    }

    /** 清理崩溃/异常残留的回看人偶（它们本就不写存档，这里兜底）。 */
    public synchronized void cleanupOrphans(MinecraftServer server) {
        if (server == null) {
            return;
        }
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
            if (key.identifier().toString().equals(dimension)) {
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
