package com.shenqianye.samsara.rewind;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.track.view.ViewRestore;
import com.shenqianye.samsara.track.view.ViewSnapshot;
import com.shenqianye.samsara.track.view.ViewTime;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Diff 单区块 / 全局只读回看（F-166）。
 * <p>
 * <b>零写入</b>：不调用 {@code ServerLevel.setBlock}、不写 chunk、不写 diff；
 * 只向观看者发送 {@link ClientboundBlockUpdatePacket} 假方块状态。退出/断线/停服时
 * 把每条原始状态重新发回观看者，并恢复玩家位置/视角/游戏模式。
 * <p>
 * 与保存型 {@link ChunkRestoreService} 完全分离：命令、服务、权限都不同。
 */
public final class DiffViewService {
    public static final int MAX_SESSIONS = 4;
    public static final int DEFAULT_RADIUS = 8;
    public static final int MAX_PACKETS_PER_TICK = 1000;
    /** 客户端收到区块数据前，方块更新可能被忽略；在这些 tick 强制重发。 */
    private static final int[] RESEND_TICKS = {5, 10, 20, 30, 45};

    private static final DiffViewService INSTANCE = new DiffViewService();

    private final Map<UUID, Session> sessions = new LinkedHashMap<>();

    private DiffViewService() {
    }

    public static DiffViewService getInstance() {
        return INSTANCE;
    }

    private static final class BlockView {
        final BlockPos pos;
        final BlockState original;
        final int originalId;
        final List<DiffStore.Edit> edits;
        final ClientboundBlockEntityDataPacket bePacket;
        int index;
        int lastSentId;

        BlockView(BlockPos pos, BlockState original, List<DiffStore.Edit> edits,
                  ClientboundBlockEntityDataPacket bePacket, long fromMs) {
            this.pos = pos;
            this.original = original;
            this.originalId = Block.getId(original);
            this.edits = edits;
            this.bePacket = bePacket;
            this.index = ChunkDiffLogic.indexAfter(edits, fromMs);
            this.lastSentId = originalId;
        }
    }

    private static final class Session {
        UUID viewer;
        String viewerName;
        ServerLevel level;
        boolean single;
        int cx;
        int cz;
        int radius;
        long fromMs;
        long toMs;
        double speed;
        long startWallMs;
        long startedTick;
        long lastHudTick;
        int sentTotal;
        int processedChunks;
        ViewSnapshot snapshot;
        ServerBossEvent bar;
        final Map<BlockPos, BlockView> blocks = new LinkedHashMap<>();
        final List<BlockView> order = new ArrayList<>();
        final Set<Long> chunks = new HashSet<>();
        int scanIndex;
        String lastWriteDim;
        double lastWriteX;
        double lastWriteY;
        double lastWriteZ;
        boolean restored;

        long cursor(long now) {
            long t = fromMs + (long) ((now - startWallMs) * speed);
            return Math.max(fromMs, Math.min(toMs, t));
        }
    }

    // ---- 启动 ----

    public synchronized String startChunk(MinecraftServer server, ServerPlayer viewer,
                                          String stackId, String dimension,
                                          int cx, int cz, String fromSpec, String toSpec,
                                          double speed) {
        if (viewer == null) {
            return "只有玩家可以进入回看";
        }
        String guard = guardStart(viewer);
        if (guard != null) {
            return guard;
        }
        String dimSpec = dimension == null || dimension.isBlank()
                ? viewer.level().dimension().identifier().toString() : dimension;
        String layerId = stackId;
        if (layerId == null || layerId.isBlank()) {
            layerId = com.shenqianye.samsara.season.StackPaths.layerIdOf(
                    viewer.level().dimension());
        }
        ChunkDiffTarget target = ChunkDiffTarget.resolve(server, layerId, dimSpec);
        if (target == null) {
            return "找不到目标栈层/维度（stack=" + layerId + " dim=" + dimSpec + "）";
        }
        boolean loaded = target.loaded(cx, cz);
        if (!loaded && !target.existsOnDisk(cx, cz)) {
            return "区块 (" + cx + "," + cz + ") 不在磁盘且未加载；回看不会生成新区块";
        }
        List<DiffStore.Edit> edits;
        try {
            if (!loaded) {
                target.level().getChunk(cx, cz); // 只读加载已有区块
            }
            edits = target.readEdits(cx, cz, true);
        } catch (RuntimeException e) {
            return "读取区块失败: " + e.getMessage();
        }
        if (edits.isEmpty()) {
            return "区块 (" + cx + "," + cz + ") 没有 diff 记录，无法回看";
        }
        long[] window = window(edits, fromSpec, toSpec);
        if (window == null) {
            return "时间格式无效（支持 epoch 毫秒、yyyy-MM-dd、HH:mm、yyyy-MM-ddTHH:mm）";
        }
        Session s = new Session();
        s.viewer = viewer.getUUID();
        s.viewerName = viewer.getScoreboardName();
        s.level = target.level();
        s.single = true;
        s.cx = cx;
        s.cz = cz;
        s.radius = 0;
        s.fromMs = window[0];
        s.toMs = window[1];
        s.speed = clampSpeed(speed);
        if (!buildChunk(s, target, cx, cz, true)) {
            return "区块 (" + cx + "," + cz + ") 已加载但没有可回看的方块记录";
        }
        return begin(server, viewer, s, "单区块 " + cx + "," + cz
                + "（磁盘有区块时会临时加载）");
    }

    public synchronized String startGlobal(MinecraftServer server, ServerPlayer viewer,
                                           String dimension, String fromSpec, String toSpec,
                                           double speed, int radius) {
        if (viewer == null) {
            return "只有玩家可以进入回看";
        }
        String guard = guardStart(viewer);
        if (guard != null) {
            return guard;
        }
        ServerLevel level = viewer.level();
        String dimSpec = dimension == null || dimension.isBlank()
                ? level.dimension().identifier().toString() : dimension;
        ChunkDiffTarget target = ChunkDiffTarget.resolve(server,
                com.shenqianye.samsara.season.StackPaths.layerIdOf(level.dimension()), dimSpec);
        if (target == null) {
            return "当前维度不属于可回看的世界栈维度: " + dimSpec;
        }
        long now = System.currentTimeMillis();
        long from;
        long to;
        try {
            from = fromSpec == null || fromSpec.isBlank()
                    ? now - 5 * 60_000L : ViewTime.parseAbsolute(fromSpec);
            to = toSpec == null || toSpec.isBlank()
                    ? now : ViewTime.parseEnd(toSpec, from);
        } catch (IllegalArgumentException e) {
            return e.getMessage();
        }
        Session s = new Session();
        s.viewer = viewer.getUUID();
        s.viewerName = viewer.getScoreboardName();
        s.level = target.level();
        s.single = false;
        s.radius = Math.max(1, Math.min(32, radius));
        s.fromMs = from;
        s.toMs = Math.max(from + 1, to);
        s.speed = clampSpeed(speed);
        return begin(server, viewer, s, "全局 · 半径 " + s.radius
                + " 区块（仅当前已加载区块；未加载区块不显示）");
    }

    private String guardStart(ServerPlayer viewer) {
        if (sessions.containsKey(viewer.getUUID())) {
            return "你已有进行中的 diff 回看，请先 /samsara diff view stop";
        }
        if (sessions.size() >= MAX_SESSIONS) {
            return "回看并发已达上限（" + MAX_SESSIONS + " 个）";
        }
        return null;
    }

    private static long[] window(List<DiffStore.Edit> edits, String fromSpec, String toSpec) {
        long earliest = Long.MAX_VALUE;
        long latest = Long.MIN_VALUE;
        for (DiffStore.Edit e : edits) {
            earliest = Math.min(earliest, e.timeMs);
            latest = Math.max(latest, e.timeMs);
        }
        try {
            long from = fromSpec == null || fromSpec.isBlank() ? earliest
                    : ViewTime.parseAbsolute(fromSpec);
            long to = toSpec == null || toSpec.isBlank() ? latest + 1000L
                    : ViewTime.parseEnd(toSpec, from);
            if (to <= from) {
                to = from + 1000L;
            }
            return new long[]{from, to};
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static double clampSpeed(double speed) {
        return Math.max(0.1, Math.min(64.0, speed));
    }

    private String begin(MinecraftServer server, ServerPlayer viewer, Session s, String label) {
        s.snapshot = ViewSnapshot.capture(viewer);
        s.startWallMs = System.currentTimeMillis();
        s.startedTick = server.getTickCount();
        s.lastHudTick = s.startedTick;
        s.bar = new ServerBossEvent(UUID.randomUUID(),
                Component.literal("[Diff 回看] " + label),
                BossEvent.BossBarColor.GREEN, BossEvent.BossBarOverlay.PROGRESS);
        s.bar.addPlayer(viewer);
        sessions.put(s.viewer, s);

        viewer.setGameMode(GameType.SPECTATOR);
        if (s.single) {
            int x = s.cx * 16 + 8;
            int z = s.cz * 16 + 8;
            int y = s.level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 12;
            y = Math.max(s.level.getMinY() + 2, Math.min(s.level.getMaxY() - 2, y));
            viewer.teleportTo(s.level, x + .5, y + .5, z + .5, Set.of(),
                    viewer.getYRot(), 45f, false);
            viewer.setDeltaMovement(Vec3.ZERO);
            viewer.fallDistance = 0;
        }
        s.lastWriteDim = viewer.level().dimension().identifier().toString();
        s.lastWriteX = viewer.getX();
        s.lastWriteY = viewer.getY();
        s.lastWriteZ = viewer.getZ();
        s.bar.setProgress(0f);
        updateHud(s, server.getTickCount(), true);
        viewer.sendSystemMessage(Component.literal(
                "[Diff 回看] 只读模式：不写方块、不写 diff、不加载新区块。"
                        + "停止：/samsara diff view stop 或 /samsara chunk view stop"));
        Samsara.LOGGER.info("[Diff 回看] 开始: viewer={} scope={} from={} to={} speed={}",
                s.viewerName, label, ViewTime.format(s.fromMs), ViewTime.format(s.toMs), s.speed);
        return "开始 Diff 回看（" + label + " · " + ViewTime.format(s.fromMs)
                + " → " + ViewTime.format(s.toMs) + " · " + s.speed + "×）";
    }

    // ---- 每 tick ----

    public synchronized void tick(MinecraftServer server) {
        if (sessions.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        for (Session s : new ArrayList<>(sessions.values())) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
            if (viewer == null || viewer.hasDisconnected() || viewer.isRemoved()) {
                restore(s, "观看者离线", false);
                continue;
            }
            String dim = viewer.level().dimension().identifier().toString();
            if (!dim.equals(s.lastWriteDim)) {
                restore(s, "观看者换维度/被外部传送", false);
                continue;
            }
            long cursor = s.cursor(now);
            if (cursor >= s.toMs) {
                restore(s, "播放完毕", true);
                continue;
            }
            if (!s.single) {
                collectLoadedChunks(s, viewer);
            }
            long tick = server.getTickCount();
            boolean forceResend = isResendTick(tick - s.startedTick);
            if (forceResend) {
                for (BlockView bv : s.order) {
                    bv.lastSentId = bv.originalId;
                }
            }
            int[] budget = {MAX_PACKETS_PER_TICK};
            int sent = applyBlocks(viewer, s, cursor, budget);
            s.sentTotal += sent;
            s.bar.setProgress((float) Math.max(0d, Math.min(1d,
                    (cursor - s.fromMs) / (double) Math.max(1L, s.toMs - s.fromMs))));
            if (tick - s.lastHudTick >= 10) {
                updateHud(s, tick, false);
            }
        }
    }

    private static boolean isResendTick(long elapsed) {
        for (int t : RESEND_TICKS) {
            if (elapsed == t) {
                return true;
            }
        }
        return false;
    }

    private static void collectLoadedChunks(Session s, ServerPlayer viewer) {
        int pcx = viewer.chunkPosition().x();
        int pcz = viewer.chunkPosition().z();
        ChunkDiffTarget target = ChunkDiffTarget.ofLevel(s.level);
        if (target == null) {
            return;
        }
        int radius = s.radius;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (Math.hypot(dx, dz) > radius) {
                    continue;
                }
                int cx = pcx + dx;
                int cz = pcz + dz;
                if (s.level.getChunkSource().getChunkNow(cx, cz) == null) {
                    continue;
                }
                long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                if (!s.chunks.add(key)) {
                    continue;
                }
                buildChunk(s, target, cx, cz, true);
            }
        }
    }

    private static boolean buildChunk(Session s, ChunkDiffTarget target, int cx, int cz,
                                      boolean includeCycles) {
        List<DiffStore.Edit> edits = target.readEdits(cx, cz, includeCycles);
        if (edits.isEmpty()) {
            return false;
        }
        Map<ChunkDiffLogic.Pos, List<DiffStore.Edit>> grouped = new LinkedHashMap<>();
        for (DiffStore.Edit e : edits) {
            grouped.computeIfAbsent(new ChunkDiffLogic.Pos(e.x, e.y, e.z), k -> new ArrayList<>())
                    .add(e);
        }
        int added = 0;
        for (Map.Entry<ChunkDiffLogic.Pos, List<DiffStore.Edit>> entry : grouped.entrySet()) {
            ChunkDiffLogic.Pos p = entry.getKey();
            BlockPos pos = new BlockPos(p.x(), p.y(), p.z());
            if (!s.level.isLoaded(pos)) {
                continue;
            }
            List<DiffStore.Edit> list = entry.getValue();
            list.sort(Comparator.comparingLong(e -> e.timeMs));
            if (s.blocks.containsKey(pos)) {
                continue;
            }
            BlockState original = s.level.getBlockState(pos);
            BlockEntity be = s.level.getBlockEntity(pos);
            ClientboundBlockEntityDataPacket bePacket = null;
            if (be != null) {
                try {
                    bePacket = ClientboundBlockEntityDataPacket.create(be);
                } catch (RuntimeException ignored) {
                    // 方块实体数据拿不到时只回看方块状态
                }
            }
            BlockView bv = new BlockView(pos, original, list, bePacket, s.fromMs);
            s.blocks.put(pos, bv);
            s.order.add(bv);
            added++;
        }
        if (added > 0) {
            s.processedChunks++;
        }
        return added > 0;
    }

    private static int applyBlocks(ServerPlayer viewer, Session s, long cursor, int[] budget) {
        if (s.order.isEmpty()) {
            return 0;
        }
        int sent = 0;
        int size = s.order.size();
        for (int n = 0; n < size && budget[0] > 0; n++) {
            BlockView bv = s.order.get(s.scanIndex);
            s.scanIndex = (s.scanIndex + 1) % size;
            while (bv.index < bv.edits.size() && bv.edits.get(bv.index).timeMs <= cursor) {
                bv.index++;
            }
            int targetId = bv.index < bv.edits.size() ? bv.edits.get(bv.index).stateId
                    : bv.originalId;
            if (targetId == bv.lastSentId) {
                continue;
            }
            BlockState state = targetId == bv.originalId ? bv.original
                    : stateOf(targetId);
            if (state == null) {
                continue;
            }
            viewer.connection.send(new ClientboundBlockUpdatePacket(bv.pos, state));
            bv.lastSentId = targetId;
            sent++;
            budget[0]--;
        }
        return sent;
    }

    private static BlockState stateOf(int stateId) {
        try {
            return Block.stateById(stateId);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static void updateHud(Session s, long tick, boolean force) {
        if (s.bar == null) {
            return;
        }
        if (!force && tick - s.lastHudTick < 10) {
            return;
        }
        s.lastHudTick = tick;
        long cursor = s.cursor(System.currentTimeMillis());
        String scope = s.single ? "区块 " + s.cx + "," + s.cz
                : "全局 · 半径 " + s.radius + " · 区块 " + s.processedChunks;
        s.bar.setName(Component.literal("[Diff 回看] " + scope + " · "
                + ViewTime.format(cursor) + " · " + s.speed + "× · 已发 "
                + s.sentTotal + " 格"));
    }

    // ---- 停止/恢复 ----

    public synchronized String stop(MinecraftServer server, ServerPlayer actor, String target,
                                    boolean allowOthers) {
        if (target == null || target.isBlank()) {
            if (actor == null) {
                return stopAll(server);
            }
            Session s = sessions.get(actor.getUUID());
            if (s == null) {
                return "你当前没有进行中的 diff 回看";
            }
            restore(s, "手动停止", true);
            return "已停止 diff 回看";
        }
        if ("all".equalsIgnoreCase(target)) {
            if (actor != null && !allowOthers) {
                return "停止全部回看需要管理员权限";
            }
            return stopAll(server);
        }
        Session found = null;
        for (Session s : sessions.values()) {
            if (s.viewerName.equalsIgnoreCase(target) || s.viewer.toString().equalsIgnoreCase(target)) {
                found = s;
                break;
            }
        }
        if (found == null) {
            return "没有该观看者的进行中回看: " + target;
        }
        if (actor != null && !actor.getUUID().equals(found.viewer) && !allowOthers) {
            return "停止他人回看需要管理员权限";
        }
        restore(found, "手动停止", true);
        return "已停止 " + found.viewerName + " 的 diff 回看";
    }

    private String stopAll(MinecraftServer server) {
        if (sessions.isEmpty()) {
            return "当前没有进行中的 diff 回看";
        }
        int n = sessions.size();
        for (Session s : new ArrayList<>(sessions.values())) {
            restore(s, "手动停止（all）", true);
        }
        return "已停止全部 diff 回看（" + n + " 个）";
    }

    /** 统一恢复入口：幂等；先发回原始方块，再恢复玩家状态。 */
    public synchronized void restore(Session s, String reason, boolean message) {
        if (s == null || s.restored) {
            return;
        }
        s.restored = true;
        sessions.remove(s.viewer, s);
        MinecraftServer server = s.level.getServer();
        ServerPlayer viewer = server == null ? null : server.getPlayerList().getPlayer(s.viewer);
        if (viewer != null) {
            for (BlockView bv : s.order) {
                if (bv.lastSentId == bv.originalId) {
                    continue;
                }
                viewer.connection.send(new ClientboundBlockUpdatePacket(bv.pos, bv.original));
                if (bv.bePacket != null) {
                    viewer.connection.send(bv.bePacket);
                }
            }
            if (s.bar != null) {
                s.bar.removeAllPlayers();
                s.bar.setVisible(false);
                s.bar = null;
            }
            if (s.snapshot != null) {
                try {
                    ViewRestore.Gate gate = new ViewRestore.Gate(
                            s.lastWriteDim, s.lastWriteX, s.lastWriteY, s.lastWriteZ,
                            "SPECTATOR", 512.0, !viewer.isDeadOrDying());
                    ViewRestore.restore(s.snapshot.data(), ViewRestore.sink(viewer), gate);
                } catch (RuntimeException e) {
                    Samsara.LOGGER.warn("[Diff 回看] 恢复观看者状态失败: {}", e.getMessage());
                }
                if (message) {
                    viewer.sendSystemMessage(Component.literal("[Diff 回看] 已结束：" + reason));
                }
            }
        } else if (s.bar != null) {
            s.bar.removeAllPlayers();
            s.bar.setVisible(false);
            s.bar = null;
        }
        Samsara.LOGGER.info("[Diff 回看] 结束: viewer={} scope={} reason={} sent={}",
                s.viewerName, s.single ? "chunk" : "global", reason, s.sentTotal);
    }

    public synchronized String status() {
        if (sessions.isEmpty()) {
            return "当前没有进行中的 diff 回看";
        }
        StringBuilder out = new StringBuilder("进行中的 diff 回看（").append(sessions.size()).append(" 个）：");
        long now = System.currentTimeMillis();
        for (Session s : sessions.values()) {
            out.append("\n  ").append(s.viewerName).append(" · ")
                    .append(s.single ? "区块 " + s.cx + "," + s.cz
                            : "全局 · 半径 " + s.radius + " · 区块 " + s.processedChunks)
                    .append(" · ").append(s.speed).append("×")
                    .append(" · ").append(ViewTime.format(s.cursor(now)))
                    .append(" / ").append(ViewTime.format(s.toMs))
                    .append(" · 已发 ").append(s.sentTotal).append(" 格");
        }
        return out.toString();
    }

    public synchronized int activeSessions() {
        return sessions.size();
    }

    public synchronized void shutdown(MinecraftServer server) {
        for (Session s : new ArrayList<>(sessions.values())) {
            restore(s, "服务器停止", false);
        }
    }
}
