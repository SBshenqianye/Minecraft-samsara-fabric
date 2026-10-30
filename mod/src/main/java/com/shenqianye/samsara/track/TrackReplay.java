package com.shenqianye.samsara.track;

import com.mojang.authlib.GameProfile;
import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.rewind.DiffStore;
import com.shenqianye.samsara.season.SeasonManager;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 玩家轨迹回放（设计文档 §5.4）：服务端按光标时间在相邻样本间插值，驱动
 * {@link ReplayMannequin}（原版 26.3 Mannequin 子类，客户端原生渲染、带玩家皮肤）。
 * <p>
 * <b>多人同屏</b>：{@code Map<UUID, Replay>} 按"被回放的玩家"索引，支持同时回放多个玩家/多个目标；
 * 每个回放独立推进、独立结束、独立捕捉，所有在场玩家都能看到全部人偶。
 * <p>
 * <b>周目隔离</b>：回放按开始时刻反查所属周目（{@link SeasonManager#cycleIdAt(long)}），
 * 也可由命令显式指定；轨迹文件读 {@code track/<世界>/cycle<id>/<日期>/<uuid>.bin}。
 * <p>
 * <b>捕捉</b>：人偶第一次被玩家攻击/交互即停止回放并变成普通实体（见 {@link ReplayMannequin}）。
 * 捕捉时刻（轨迹时间轴）写入 {@link TrackCuts}，该玩家该周目之后的轨迹不再回放。
 */
public final class TrackReplay {
    private static final Map<UUID, Replay> ACTIVE = new LinkedHashMap<>();

    private TrackReplay() {
    }

    private static final class Replay {
        UUID uuid;
        String name;
        ReplayMannequin entity;
        List<TrackStore.Sample> samples;
        long startWallMs;
        long startTrackMs;
        long endTrackMs;
        double speed;
        int cycleId;
        String dimension;
        boolean captured;

        /** 当前轨迹时间（按墙钟 × 倍速推进，钳到区间内） */
        long cursorMs(long nowWallMs) {
            long t = startTrackMs + (long) ((nowWallMs - startWallMs) * speed);
            return Math.max(startTrackMs, Math.min(endTrackMs, t));
        }
    }

    /**
     * 开始回放。
     *
     * @param cycleSpec 周目（{@code 3} / {@code cycle3}）；null/空 = 按开始时刻反查
     * @return 给命令层的文本
     */
    public static synchronized String start(MinecraftServer server, UUID uuid, String name,
                                            String startSpec, double speed, String cycleSpec) {
        long startMs;
        try {
            startMs = parseTime(startSpec);
        } catch (Exception e) {
            return "开始时刻格式无效（支持 epoch 毫秒、2026-10-28T12:00、12:00、2026-10-28）";
        }
        if (speed <= 0 || speed > 64) {
            return "倍速须在 (0, 64] 内";
        }
        Integer explicitCycle = parseCycle(cycleSpec);
        if (cycleSpec != null && !cycleSpec.isBlank() && explicitCycle == null) {
            return "周目参数无效（用 3 或 cycle3；省略则按开始时刻反查）";
        }
        int cycleId = explicitCycle != null ? explicitCycle
                : SeasonManager.getInstance().cycleIdAt(startMs);
        TrackRecorder recorder = TrackRecorder.getInstance();
        List<UUID> aliases = com.shenqianye.samsara.identity.IdentityService.getInstance()
                .aliasesOf(uuid);
        TrackRecorder.LocatedFile located = recorder.fileForAny(aliases, startMs, cycleId);
        Path file = located == null ? null : located.file();
        if (file == null) {
            return "找不到 " + name + " 在周目 " + cycleId + " / "
                    + Instant.ofEpochMilli(startMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    + " 附近的轨迹文件（track/<世界>/cycle" + cycleId + "/<日期>/<uuid>.bin）";
        }
        List<TrackStore.Segment> segments;
        try {
            segments = TrackStore.read(file);
        } catch (IOException e) {
            return "轨迹文件读取失败: " + e.getMessage();
        }
        TrackStore.Segment seg = pickSegment(segments, startMs);
        if (seg == null || seg.samples.isEmpty()) {
            return "轨迹文件里没有可用样本";
        }
        ServerLevel level = findLevel(server, seg.dimension);
        if (level == null) {
            return "轨迹维度不存在: " + seg.dimension;
        }
        // capture cutoff：捕捉后该玩家该周目从捕捉时刻起的轨迹作废（磁盘记录保留）
        String worldKey = recorder.worldKey() == null ? "world" : recorder.worldKey();
        Long cutoff = recorder.cuts().cutoff(worldKey, cycleId, located.owner());
        List<TrackStore.Sample> samples = cutoff == null
                ? seg.samples
                : TrackStore.truncate(seg.samples, cutoff);
        if (samples.isEmpty()) {
            return name + " 在周目 " + cycleId + " 的轨迹已在 "
                    + Instant.ofEpochMilli(cutoff).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    + " 被捕捉，之后的部分不再回放（磁盘记录仍保留）";
        }
        long from = Math.max(startMs, samples.get(0).timeMs());
        long lastMs = samples.get(samples.size() - 1).timeMs();
        if (from > lastMs) {
            return cutoff != null
                    ? "开始时刻晚于该周目可回放区间（" + name + " 已于轨迹时间 "
                    + Instant.ofEpochMilli(cutoff).atZone(ZoneId.systemDefault()).toLocalDateTime()
                    + " 被捕捉）"
                    : "开始时刻晚于该段轨迹结束时间";
        }
        int idx = TrackStore.floorIndex(samples, from);
        TrackStore.Sample first = samples.get(idx);

        // 同一玩家重复回放：先清掉旧的
        Replay old = ACTIVE.remove(uuid);
        if (old != null) {
            discardEntity(old);
        }

        ReplayMannequin entity = new ReplayMannequin(level, uuid, cycleId, TrackReplay::onCaptured);
        entity.applyProfile(resolveProfile(server, uuid, name));
        entity.setCustomName(Component.literal(cycleId > 0 ? name + " · 周目 " + cycleId : name));
        entity.snapTo(first.x(), first.y(), first.z(), first.yaw(), first.pitch());
        entity.setYHeadRot(first.yaw());
        entity.setYBodyRot(first.yaw());
        entity.yHeadRotO = first.yaw();
        entity.yBodyRotO = first.yaw();
        if (!level.addFreshEntity(entity)) {
            return "人偶生成失败（区块未加载？）";
        }

        Replay r = new Replay();
        r.uuid = uuid;
        r.name = name;
        r.entity = entity;
        r.samples = samples;
        r.startWallMs = System.currentTimeMillis();
        r.startTrackMs = first.timeMs();
        r.endTrackMs = lastMs;
        r.speed = speed;
        r.cycleId = cycleId;
        r.dimension = seg.dimension;
        ACTIVE.put(uuid, r);

        StringBuilder sb = new StringBuilder("开始回放 ").append(name)
                .append("（周目 ").append(cycleId).append(" · ").append(seg.dimension).append(" · ")
                .append(Instant.ofEpochMilli(r.startTrackMs).atZone(ZoneId.systemDefault()).toLocalDateTime())
                .append(" 起 · ").append(speed).append("× · 样本 ").append(samples.size()).append(" 点");
        if (cutoff != null) {
            sb.append(" · 已按捕捉截断到 ")
                    .append(Instant.ofEpochMilli(cutoff).atZone(ZoneId.systemDefault()).toLocalDateTime());
        }
        sb.append("）\n当前同屏回放 ").append(ACTIVE.size())
                .append(" 个\n停止：/samsara debug track stop [玩家|all]");
        return sb.toString();
    }

    /** 停止全部回放（兼容旧命令入口） */
    public static synchronized String stop() {
        return stop(null);
    }

    /** 停止回放：玩家名 / all / 空（=全部） */
    public static synchronized String stop(String target) {
        if (ACTIVE.isEmpty()) {
            return "当前没有进行中的轨迹回放";
        }
        if (target == null || target.isBlank() || "all".equalsIgnoreCase(target)) {
            int n = ACTIVE.size();
            for (Replay r : new ArrayList<>(ACTIVE.values())) {
                discardEntity(r);
            }
            ACTIVE.clear();
            return "已停止全部回放（" + n + " 个）";
        }
        Replay found = null;
        for (Replay r : ACTIVE.values()) {
            if (r.name.equalsIgnoreCase(target) || r.uuid.toString().equalsIgnoreCase(target)) {
                found = r;
                break;
            }
        }
        if (found == null) {
            return "没有该玩家的进行中回放: " + target;
        }
        ACTIVE.remove(found.uuid);
        discardEntity(found);
        return "已停止回放 " + found.name + "（周目 " + found.cycleId + "）";
    }

    /** 所有进行中的回放（命令展示用） */
    public static synchronized String status() {
        if (ACTIVE.isEmpty()) {
            return "当前没有进行中的轨迹回放";
        }
        StringBuilder sb = new StringBuilder("进行中的轨迹回放（").append(ACTIVE.size()).append(" 个）：");
        long now = System.currentTimeMillis();
        for (Replay r : ACTIVE.values()) {
            sb.append("\n  ").append(r.name).append(" · 周目 ").append(r.cycleId)
                    .append(" · ").append(r.speed).append("×")
                    .append(" · ").append(r.dimension)
                    .append(" · 轨迹时间 ")
                    .append(Instant.ofEpochMilli(r.cursorMs(now)).atZone(ZoneId.systemDefault()).toLocalDateTime())
                    .append(" / 止于 ")
                    .append(Instant.ofEpochMilli(r.endTrackMs).atZone(ZoneId.systemDefault()).toLocalDateTime());
        }
        return sb.toString();
    }

    /** 该目标是否已有捕捉式回放进行中（只读查询；回看 F-92 互斥检查用） */
    public static synchronized boolean isReplaying(UUID uuid) {
        return ACTIVE.containsKey(uuid);
    }

    /** 每 tick 插值推进（由 Samsara 注册） */
    public static synchronized void tick(MinecraftServer server) {
        if (ACTIVE.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Replay r : new ArrayList<>(ACTIVE.values())) {
            if (r.captured) {
                continue;
            }
            if (r.entity == null || r.entity.isRemoved() || !r.entity.isAlive()) {
                finish(r);
                continue;
            }
            long trackTime = r.cursorMs(now);
            if (trackTime >= r.endTrackMs) {
                finish(r);
                continue;
            }
            TrackStore.Sample a = r.samples.get(TrackStore.floorIndex(r.samples, trackTime));
            TrackStore.Sample b = r.samples.get(Math.min(
                    TrackStore.floorIndex(r.samples, trackTime) + 1, r.samples.size() - 1));
            double f = 0;
            if (b.timeMs() > a.timeMs()) {
                f = (trackTime - a.timeMs()) / (double) (b.timeMs() - a.timeMs());
                f = Math.max(0, Math.min(1, f));
            }
            double x = a.x() + (b.x() - a.x()) * f;
            double y = a.y() + (b.y() - a.y()) * f;
            double z = a.z() + (b.z() - a.z()) * f;
            float yaw = lerpAngle(a.yaw(), b.yaw(), f);
            float pitch = (float) (a.pitch() + (b.pitch() - a.pitch()) * f);
            BlockPos pos = BlockPos.containing(x, y, z);
            if (!r.entity.level().isLoaded(pos)) {
                r.entity.level().getChunk(pos.getX() >> 4, pos.getZ() >> 4);
            }
            r.entity.snapTo(x, y, z, yaw, pitch);
            r.entity.setYHeadRot(yaw);
            r.entity.setYBodyRot(yaw);
            r.entity.yHeadRotO = yaw;
            r.entity.yBodyRotO = yaw;
            r.entity.setDeltaMovement(0, 0, 0);
        }
    }

    /** 服务器停止/重载时清理进行中的回放（已捕捉的人偶已脱离回放，会正常存档） */
    public static synchronized void shutdown() {
        for (Replay r : new ArrayList<>(ACTIVE.values())) {
            discardEntity(r);
        }
        ACTIVE.clear();
    }

    /**
     * 捕捉回调：停止该回放、取消伤害（由实体返回 false）、原地变普通实体、
     * 记录 capture cutoff、广播。只影响该玩家该周目，不碰方块逆熵。
     */
    private static synchronized void onCaptured(ReplayMannequin entity, ServerPlayer player) {
        Replay r = ACTIVE.get(entity.sourceUuid());
        if (r == null || r.entity != entity || r.captured) {
            return; // 已在捕捉流程中/不是当前回放实体
        }
        long now = System.currentTimeMillis();
        // 捕捉发生在“轨迹时间轴”上的哪个点（不是墙钟）：之后的轨迹作废
        long captureTrackMs = r.cursorMs(now);
        r.captured = true;
        ACTIVE.remove(r.uuid);
        entity.markCaptured();

        String worldKey = TrackRecorder.getInstance().worldKey();
        TrackRecorder.getInstance().cuts().capture(
                worldKey == null ? DiffStore.sanitizeWorldKey("world") : worldKey,
                r.cycleId, r.uuid, captureTrackMs);

        String msg = "玩家 " + player.getScoreboardName() + " 捕捉了 " + r.name + " 的人偶（周目 "
                + r.cycleId + "）· 回放已停止，该玩家该周目从轨迹时间 "
                + Instant.ofEpochMilli(captureTrackMs).atZone(ZoneId.systemDefault()).toLocalDateTime()
                + " 起的轨迹不再回放（磁盘记录保留）· 方块逆熵照常";
        Samsara.LOGGER.info("[周目管理 samsara] {}", msg);
        MinecraftServer server = player.level().getServer();
        if (server != null) {
            server.getPlayerList().broadcastSystemMessage(Component.literal("[周目管理] " + msg), false);
        }
    }

    /** 正常结束（轨迹播完/实体被非玩家杀死）：移除人偶 */
    private static void finish(Replay r) {
        if (r == null) {
            return;
        }
        ACTIVE.remove(r.uuid);
        discardEntity(r);
    }

    private static void discardEntity(Replay r) {
        if (r != null && r.entity != null && !r.entity.isRemoved()) {
            r.entity.discard();
        }
    }

    /** 解析开始时刻：epoch 毫秒 / ISO 本地时间 / HH:mm（今天）/ yyyy-MM-dd（当天 00:00） */
    public static long parseTime(String s) {
        String v = s.trim();
        if (v.matches("\\d{10,}")) {
            return Long.parseLong(v);
        }
        ZoneId zone = ZoneId.systemDefault();
        try {
            return LocalDateTime.parse(v).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalTime.parse(v).atDate(LocalDate.now()).atZone(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        try {
            return LocalDate.parse(v).atStartOfDay(zone).toInstant().toEpochMilli();
        } catch (DateTimeParseException ignored) {
        }
        throw new IllegalArgumentException(v);
    }

    /** 解析周目参数：{@code 3} / {@code cycle3}；空 = null（按开始时刻反查） */
    public static Integer parseCycle(String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        String s = spec.trim();
        if (s.regionMatches(true, 0, TrackLayout.CYCLE_PREFIX, 0, TrackLayout.CYCLE_PREFIX.length())) {
            s = s.substring(TrackLayout.CYCLE_PREFIX.length());
        }
        try {
            int id = Integer.parseInt(s);
            return id < 0 ? null : id;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static TrackStore.Segment pickSegment(List<TrackStore.Segment> segments, long t) {
        TrackStore.Segment best = null;
        for (TrackStore.Segment s : segments) {
            if (s.samples.isEmpty()) {
                continue;
            }
            if (s.startMs <= t && t <= s.endMs()) {
                return s;
            }
            if (s.startMs <= t && (best == null || s.startMs > best.startMs)) {
                best = s;
            }
        }
        if (best != null) {
            return best;
        }
        for (TrackStore.Segment s : segments) {
            if (!s.samples.isEmpty()) {
                return s;
            }
        }
        return null;
    }

    private static ServerLevel findLevel(MinecraftServer server, String dimension) {
        for (ResourceKey<Level> key : server.levelKeys()) {
            if (key.identifier().toString().equals(dimension)) {
                return server.getLevel(key);
            }
        }
        return null;
    }

    private static float lerpAngle(float a, float b, double f) {
        float delta = ((b - a + 540) % 360) - 180;
        return a + (float) (delta * f);
    }

    /**
     * 玩家皮肤：优先在线玩家的 GameProfile（含纹理）；否则用 usercache 的 uuid+name
     * （离线玩家没有皮肤纹理，但名字/人偶仍原生渲染）。不发起网络请求，避免卡主线程。
     */
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
            // usercache 不可用：退回只有 uuid/name 的 profile
        }
        return new GameProfile(uuid, name == null ? uuid.toString() : name);
    }
}
