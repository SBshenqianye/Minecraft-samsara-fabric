package com.shenqianye.samsara.track;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.rewind.DiffStore;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonData;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.animal.equine.AbstractChestedHorse;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.equine.Horse;
import net.minecraft.world.item.ItemStack;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 玩家轨迹记录（设计文档 §5.4/§5.5）。
 * <p>
 * 在线玩家按固定频率采样位置/朝向/状态，写入
 * {@code config/samsara/track/<世界>/cycle<周目id>/<日期>/<uuid>.bin}；
 * 无周目（服务器还没有任何周目）时写入 {@code cycle0}。
 * 与倒流共用 {@code System.currentTimeMillis()} 时钟，回放位置才能与方块恢复进度对齐。
 * <p>
 * <b>周目隔离</b>：录制时按 {@link SeasonManager#current()} 的 id 分目录；跨周目、跨天、重登、
 * 换维度都开新段（新周目本身就是新文件）。回放按开始时刻与
 * {@link Season#startTime}/{@link Season#endTime} 反查所属周目，也可由命令显式指定。
 * <p>
 * 保留策略：按天轮转 + 固定档位（1/4/16/64/256 天）做几何简化（RDP，误差 0.5 格/档），
 * 并用"每天至少 skeleton 点"兜底，避免远期轨迹被抽空。扫描口径 = 世界 + 周目 + 日期。
 */
public final class TrackRecorder {
    private static TrackRecorder instance;
    /** track 根目录；服务器启动时由 StoragePaths 注入 */
    private volatile Path root;
    /** 当前世界键（仅用于日志；新布局不再带世界层） */
    private volatile String worldKey;
    /** track 根（无世界层） */
    private volatile Path worldRoot;
    /** 服务器世界目录（用于枚举各栈层 track 根） */
    private volatile Path worldDir;
    /** 捕捉截断点（capture cutoff，周目隔离） */
    private volatile TrackCuts cuts;
    /** 每个栈根自己的 cutoffs（旧栈的捕捉截断不再写到栈顶） */
    private final Map<Path, TrackCuts> cutsByRoot = new ConcurrentHashMap<>();
    /** 每个栈根自己的收藏表（{@code clips.json}） */
    private final Map<Path, TrackClips> clipsByRoot = new ConcurrentHashMap<>();
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** 正在被在线玩家追加的文件：保留扫描不要碰 */
    private final java.util.Set<Path> openFiles = ConcurrentHashMap.newKeySet();
    /** maxBytesPerPlayer 降级到 adaptive 后仍超限的玩家：拒绝新归档 */
    private final java.util.Set<UUID> archiveRefused = ConcurrentHashMap.newKeySet();
    private volatile boolean sweeping;
    private long lastSweepMs;
    /** Web 容量预估用的实际 .bin 用量缓存（5 分钟）。 */
    private volatile Usage cachedUsage;
    private volatile long cachedUsageAtMs;

    private static final class State {
        OutputStream out;
        Path file;
        LocalDate day;
        String dimension;
        UUID uuid;
        SeasonData.Settings settings;
        TrackStore.Sample last;
        long lastSampleMs;
        long lastFlushMs;
        long segmentStartMs;
        double hz;
        /** 当前正在写的周目目录（-1 = 尚未确定） */
        int cycleId = -1;
        /** 当前栈层的 track 根（栈顶/旧栈隔离） */
        Path layerRoot;

        // ---- track v2：事件侧车与连续状态 ----
        OutputStream evtOut;
        Path evtFile;
        TrackEventStore.Equipment lastEquipment;
        int lastSelectedSlot = -1;
        int lastFlightMode = -2;
        TrackEvents.VehicleInfo lastVehicle;
        boolean lastUsing;
        int lastUseHand = -1;
        long lastUseStartedMs;
        long lastUseFallbackStopMs;
        long lastSwingMs = Long.MIN_VALUE;
        int lastSwingHand = -1;
        List<ItemStack> lastInventory;
        long lastEventMs;
        long lastEventFlushMs;
        net.minecraft.core.RegistryAccess registries;
        boolean archiveRefused;
        long refusedLogMs;
    }

    private TrackRecorder() {
        // 路径在 initRoot 注入（SERVER_STARTING，StoragePaths 初始化之后）
    }

    public static synchronized TrackRecorder getInstance() {
        if (instance == null) {
            instance = new TrackRecorder();
        }
        return instance;
    }

    /** 注入世界数据根：{@code <worldDataRoot>/track}。必须在 SERVER_STARTING 调用一次。 */
    public synchronized void initRoot(Path worldDataRoot) {
        this.root = worldDataRoot.resolve("track").toAbsolutePath().normalize();
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 无法创建 track 目录: {}", e.getMessage());
        }
        this.cuts = new TrackCuts(root.resolve("cutoffs.json"));
        this.cuts.load();
        this.cutsByRoot.clear();
        this.cutsByRoot.put(root, this.cuts);
        this.clipsByRoot.clear();
        this.clipsByRoot.put(root, new TrackClips(root.resolve("clips.json")));
        this.worldRoot = root;
    }

    /** 注册采样/生命周期钩子（主入口调用一次） */
    public static void init() {
        TrackRecorder r = getInstance();
        ServerTickEvents.END_SERVER_TICK.register(r::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                r.startPlayer(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                r.stopPlayer(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> r.stopAll());
        ServerLifecycleEvents.SERVER_STARTED.register(r::initWorld);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> r.sweepAsync());

        // 攻击挥动兜底（26.3 的 ServerboundPunchPacket mixin 为主路径）
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hitResult) -> {
            if (player instanceof ServerPlayer sp) {
                r.recordSwing(sp, hand, 0);
            }
            return InteractionResult.PASS;
        });
        AttackBlockCallback.EVENT.register((player, world, hand, pos, direction) -> {
            if (player instanceof ServerPlayer sp) {
                r.recordSwing(sp, hand, 0);
            }
            return InteractionResult.PASS;
        });
        // 一次性使用（放置/瞬发物品）补事件；长按使用由 isUsingItem 起止补齐
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayer sp) {
                r.recordUseStart(sp, hand, System.currentTimeMillis());
            }
            return InteractionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hitResult) -> {
            if (player instanceof ServerPlayer sp) {
                r.recordUseStart(sp, hand, System.currentTimeMillis());
            }
            return InteractionResult.PASS;
        });

        // 发言记录：写入 .evt 聊天事件，同时进入实时层最近的聊天列表
        ServerMessageEvents.CHAT_MESSAGE.register((message, sender, params) -> {
            String text = message.decoratedContent().getString();
            r.recordChat(sender, text);
            ChatLogStore.record(sender, text);
            com.shenqianye.samsara.web.MapLiveService.recordChat(sender, text);
        });
        // 玩法层系统提示（加入/离开/死亡/进度）；命令反馈不进入实时层。
        ServerMessageEvents.GAME_MESSAGE.register((server, message, overlay) -> {
            if (!overlay) {
                com.shenqianye.samsara.web.MapLiveService.recordSystem(message.getString());
            }
        });
    }

    /**
     * 服务器启动：把根切到 track（无世界层），并迁移旧布局。
     */
    private void initWorld(MinecraftServer server) {
        if (root == null) {
            return;
        }
        worldDir = com.shenqianye.samsara.season.StackPaths.worldDir(server);
        Path target = root;
        int cycle = cycleIdNow();
        for (Path trackRoot : trackRoots()) {
            try {
                Files.createDirectories(trackRoot);
                int legacy = TrackLayout.migrateLegacyRoot(trackRoot, trackRoot, cycle);
                int worldLevel = TrackLayout.migrateWorldLayout(trackRoot, cycle);
                int worldLayer = 0;
                if (trackRoot.equals(root)) {
                    worldLayer = com.shenqianye.samsara.storage.StorageMigration.stripTrackWorldLayer(root);
                }
                if (legacy + worldLevel + worldLayer > 0) {
                    Samsara.LOGGER.info("[周目管理 samsara] 轨迹旧布局已迁移 {}：{} 项 → {}",
                            trackRoot.getFileName(), legacy + worldLevel + worldLayer,
                            TrackLayout.cycleDirName(cycle));
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] track 根初始化失败 {}: {}",
                        trackRoot, e.getMessage());
            }
        }
        worldKey = DiffStore.sanitizeWorldKey(
                com.shenqianye.samsara.season.StackPaths.TOP_ID);
        worldRoot = target;
        cutsByRoot.clear();
        cuts = cutsForTrackRoot(root);
        cuts.load();
        clipsByRoot.clear();
        clipsForTrackRoot(root);
    }

    /** 栈层 track 根对应的 cutoffs（每个根一份，互不覆盖）。 */
    public TrackCuts cutsForTrackRoot(Path trackRoot) {
        Path rootKey = trackRoot.toAbsolutePath().normalize();
        return cutsByRoot.computeIfAbsent(rootKey, r -> {
            TrackCuts c = new TrackCuts(r.resolve("cutoffs.json"));
            c.load();
            return c;
        });
    }

    /** 栈层 track 根对应的片段收藏表（每个根一份）。 */
    public TrackClips clipsForTrackRoot(Path trackRoot) {
        Path rootKey = trackRoot.toAbsolutePath().normalize();
        return clipsByRoot.computeIfAbsent(rootKey, r -> new TrackClips(r.resolve("clips.json")));
    }

    /** 当前栈顶的收藏表；目录未初始化时返回 null。 */
    public TrackClips clips() {
        return root == null ? null : clipsForTrackRoot(root);
    }

    /** track 根 → 栈层 worldKey（栈顶 world / 旧层 stack_<n>）。 */
    public String worldKeyOfTrackRoot(Path trackRoot) {
        if (trackRoot == null) {
            return worldKey == null ? com.shenqianye.samsara.season.StackPaths.TOP_ID : worldKey;
        }
        Path dataRoot = trackRoot.toAbsolutePath().normalize().getParent();
        if (dataRoot == null) {
            return com.shenqianye.samsara.season.StackPaths.TOP_ID;
        }
        if (root != null && trackRoot.toAbsolutePath().normalize().equals(root)) {
            return com.shenqianye.samsara.season.StackPaths.TOP_ID;
        }
        Path name = dataRoot.getFileName();
        return name == null ? com.shenqianye.samsara.season.StackPaths.TOP_ID : name.toString();
    }

    /** 所有栈层的 track 根（栈顶 world 在前，旧栈 stack_<n> 其后）。 */
    public List<Path> trackRoots() {
        List<Path> roots = new ArrayList<>();
        if (root != null) {
            roots.add(root);
        }
        if (worldDir != null) {
            for (Path dataRoot : com.shenqianye.samsara.season.StackPaths.dataRoots(worldDir)) {
                Path trackRoot = dataRoot.resolve("track").toAbsolutePath().normalize();
                if (!roots.contains(trackRoot)) {
                    roots.add(trackRoot);
                }
            }
        }
        return roots;
    }

    private void tick(MinecraftServer server) {
        if (worldRoot == null) {
            return; // 世界层尚未初始化（SERVER_STARTED 之前）
        }
        long now = System.currentTimeMillis();
        SeasonData.Settings cfg = SeasonManager.getInstance().settings();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            // 回看期间观看者被强制旁观：不录制观察者本身（否则会生成观察者影像）。
            // 同时在这里兜底关闭状态，避免外部把玩家切成旁观后继续写 .bin/.evt。
            if (!shouldRecord(p.isSpectator(),
                    com.shenqianye.samsara.track.view.ViewManager.getInstance().isViewing(p.getUUID()))) {
                State skipped = states.remove(p.getUUID());
                close(skipped);
                continue;
            }
            State st = states.computeIfAbsent(p.getUUID(), k -> {
                State s = new State();
                s.uuid = k;
                return s;
            });
            st.settings = cfg;
            continuousEvents(p, st, now);
            sample(p, st, now, cfg);
        }
        // 每 6 小时跑一次保留扫描（异步，不阻塞主线程）
        if (now - lastSweepMs > 6L * 60 * 60 * 1000) {
            lastSweepMs = now;
            sweepAsync();
        }
    }

    private void startPlayer(ServerPlayer player) {
        State st = new State();
        st.uuid = player.getUUID();
        st.settings = SeasonManager.getInstance().settings();
        states.put(player.getUUID(), st);
        sample(player, st, System.currentTimeMillis(), st.settings);
    }

    private void stopPlayer(UUID uuid) {
        State st = states.remove(uuid);
        close(st);
    }

    /**
     * 回看/回放临时接管观看者前关闭其录制：关闭 .bin/.evt 并移除状态。
     * 结束后由 {@link #resumePlayer(ServerPlayer)} 重新开段，旧文件不会被改写。
     */
    public void pausePlayer(UUID uuid) {
        if (uuid == null) {
            return;
        }
        State st = states.remove(uuid);
        close(st);
    }

    /** 回看结束、观看者恢复原游戏模式后重新开始录制（新段）。 */
    public void resumePlayer(ServerPlayer player) {
        if (player == null || player.isSpectator()) {
            return;
        }
        if (com.shenqianye.samsara.track.view.ViewManager.getInstance().isViewing(player.getUUID())) {
            return;
        }
        if (states.containsKey(player.getUUID())) {
            return;
        }
        startPlayer(player);
    }

    /** 观察者/旁观者不写轨迹；抽成纯函数便于单测。 */
    public static boolean shouldRecord(boolean spectator, boolean viewing) {
        return !spectator && !viewing;
    }

    private void stopAll() {
        for (State st : states.values()) {
            close(st);
        }
        states.clear();
    }

    private Path stackTrackRoot(ServerPlayer player) {
        var server = player.level().getServer();
        Path dataRoot = com.shenqianye.samsara.season.StackPaths.dataRootFor(
                com.shenqianye.samsara.season.StackPaths.worldDir(server),
                player.level().dimension());
        return dataRoot.resolve("track");
    }

    private void sample(ServerPlayer player, State st, long now, SeasonData.Settings cfg) {
        if (worldRoot == null) {
            return;
        }
        if (!shouldRecord(player.isSpectator(),
                com.shenqianye.samsara.track.view.ViewManager.getInstance().isViewing(player.getUUID()))) {
            return;
        }
        if (st.settings == null) {
            st.settings = cfg;
        }
        var server = player.level().getServer();
        if (server != null) {
            st.registries = server.registryAccess();
        }
        double hz = Math.max(2, Math.min(20, cfg.trackSampleHz));
        long interval = Math.max(10, Math.round(1000.0 / hz));
        if (st.lastSampleMs > 0 && now - st.lastSampleMs < interval) {
            return;
        }
        try {
            LocalDate today = LocalDate.now();
            int cycleId = cycleIdNow();
            String dim = player.level().dimension().identifier().toString();
            Path layerRoot = stackTrackRoot(player);
            boolean rotateDay = st.day == null || !today.equals(st.day);
            // 周目切换：目录跟着换（跨周目必须开新段，旧段留在旧周目目录）
            boolean rotateCycle = st.cycleId != cycleId;
            boolean rotateStack = st.layerRoot == null || !st.layerRoot.equals(layerRoot);
            boolean rotateFile = rotateDay || rotateCycle || rotateStack;

            if (st.archiveRefused) {
                if (archiveRefused.contains(st.uuid)) {
                    logRefused(st, now);
                    return;
                }
                st.archiveRefused = false;
            }
            if (rotateFile && !allowNewArchive(st, now)) {
                return;
            }

            boolean newSegment = rotateFile || st.dimension == null
                    || !st.dimension.equals(dim) || st.out == null;
            if (rotateFile) {
                closeStream(st);
                st.cycleId = cycleId;
                st.day = today;
                st.layerRoot = layerRoot;
                st.file = TrackLayout.file(layerRoot, cycleId, today, st.uuid);
                Files.createDirectories(st.file.getParent());
                openAppend(st);
                openEvents(st, layerRoot, cycleId, today);
                if (Files.size(st.file) == 0) {
                    st.out.write(TrackStore.header());
                }
                resetEventState(st);
            }
            if (st.out == null) {
                openAppend(st);
                if (Files.size(st.file) == 0) {
                    st.out.write(TrackStore.header());
                }
            }
            boolean eventsOpened = false;
            if (st.evtOut == null) {
                openEvents(st, layerRoot, cycleId, today);
                resetEventState(st);
                eventsOpened = true;
            }
            TrackStore.Sample cur = new TrackStore.Sample(
                    now,
                    player.getX(), player.getY(), player.getZ(),
                    player.getYRot(), player.getXRot(),
                    flags(player));
            if (newSegment) {
                TrackStore.Segment header = new TrackStore.Segment(st.uuid, dim, now, hz);
                st.out.write(TrackStore.encodeSegmentHeader(header));
                st.dimension = dim;
                st.segmentStartMs = now;
                st.hz = hz;
                st.last = null;
                // 新段初始快照：首条事件必须是 EQUIP
                resetEventState(st);
                writeInitialEvents(player, st, now);
            } else if (eventsOpened) {
                writeInitialEvents(player, st, now);
            }
            st.out.write(TrackStore.encodeSample(st.last, cur));
            st.last = cur;
            st.lastSampleMs = now;

            // 5Hz 采样时比较装备；有变化才写 EQUIP
            maybeWriteEquipment(player, st, now);
            if (CommandConfig.getInstance().trackRecordInventory()) {
                maybeWriteInventory(player, st, now);
            }

            if (now - st.lastFlushMs > 2000) {
                st.out.flush();
                if (st.evtOut != null) {
                    st.evtOut.flush();
                    st.lastEventFlushMs = now;
                }
                st.lastFlushMs = now;
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹写入失败 {}: {}", st.uuid, e.getMessage());
            closeStream(st);
        }
    }

    /** 新归档前检查 maxBytesPerPlayer（降级由保留扫描完成；仍超限则拒绝）。 */
    private boolean allowNewArchive(State st, long now) {
        if (!archiveRefused.contains(st.uuid)) {
            return true;
        }
        st.archiveRefused = true;
        logRefused(st, now);
        return false;
    }

    private void logRefused(State st, long now) {
        if (now - st.refusedLogMs < 60_000L) {
            return;
        }
        st.refusedLogMs = now;
        Samsara.LOGGER.warn("[周目管理 samsara] 玩家 {} 的 .bin 轨迹已达到 maxBytesPerPlayer "
                + "且 adaptive 降级后仍超限，拒绝新归档（已关闭当前事件流）", st.uuid);
    }

    // ---- track v2：事件侧车写入 ----

    private void openEvents(State st, Path layerRoot, int cycleId, LocalDate day) throws IOException {
        st.evtFile = TrackLayout.evtFile(layerRoot, cycleId, day, st.uuid);
        Files.createDirectories(st.evtFile.getParent());
        // 追加已有侧车时，dt 必须接在上一条事件之后；新文件首条用绝对 epoch 毫秒。
        long previousEventMs = 0;
        if (Files.isRegularFile(st.evtFile) && Files.size(st.evtFile) > 0) {
            TrackEventStore.ReadResult existing = TrackEventStore.read(st.evtFile);
            if (!existing.events().isEmpty()) {
                previousEventMs = existing.events().get(existing.events().size() - 1).timeMs();
            }
            // 崩溃可能留下半条记录：追加前截断到最后一个完整事件，避免后续事件永远读不到
            if (existing.truncated() && existing.validBytes() > 0
                    && existing.validBytes() < Files.size(st.evtFile)) {
                try (var channel = java.nio.channels.FileChannel.open(st.evtFile,
                        StandardOpenOption.WRITE)) {
                    channel.truncate(existing.validBytes());
                }
            }
        }
        st.evtOut = new BufferedOutputStream(Files.newOutputStream(st.evtFile,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), 4096);
        openFiles.add(st.evtFile);
        if (Files.size(st.evtFile) == 0) {
            st.evtOut.write(TrackEvents.header());
        }
        st.lastEventMs = previousEventMs;
        st.lastEventFlushMs = System.currentTimeMillis();
    }

    private static void resetEventState(State st) {
        st.lastEquipment = null;
        st.lastSelectedSlot = -1;
        st.lastFlightMode = -2;
        st.lastVehicle = null;
        st.lastUsing = false;
        st.lastUseHand = -1;
        st.lastUseStartedMs = 0;
        st.lastUseFallbackStopMs = 0;
        st.lastInventory = null;
        // lastEventMs 由 openEvents 维护（追加文件时接上已有事件时间）
    }

    private static TrackEventStore.Equipment equipmentOf(ServerPlayer player) {
        ItemStack[] slots = new ItemStack[TrackEvents.SLOT_COUNT];
        slots[TrackEvents.SLOT_MAINHAND] = player.getItemBySlot(EquipmentSlot.MAINHAND);
        slots[TrackEvents.SLOT_OFFHAND] = player.getItemBySlot(EquipmentSlot.OFFHAND);
        slots[TrackEvents.SLOT_HEAD] = player.getItemBySlot(EquipmentSlot.HEAD);
        slots[TrackEvents.SLOT_CHEST] = player.getItemBySlot(EquipmentSlot.CHEST);
        slots[TrackEvents.SLOT_LEGS] = player.getItemBySlot(EquipmentSlot.LEGS);
        slots[TrackEvents.SLOT_FEET] = player.getItemBySlot(EquipmentSlot.FEET);
        return new TrackEventStore.Equipment(slots, player.getInventory().getSelectedSlot());
    }

    private void writeInitialEvents(ServerPlayer player, State st, long now) throws IOException {
        if (st.evtOut == null) {
            return;
        }
        TrackEventStore.Equipment equipment = equipmentOf(player);
        writeEquip(st, now, equipment);
        int flight = flightMode(player);
        writeEvent(st, now, TrackEvents.TYPE_FLIGHT_MODE, TrackEvents.flightPayload(flight));
        st.lastFlightMode = flight;
        TrackEvents.VehicleInfo vehicle = vehicleInfo(player);
        writeEvent(st, now, TrackEvents.TYPE_VEHICLE, TrackEvents.vehiclePayload(vehicle));
        st.lastVehicle = vehicle;
        // 正在使用中开新段：先记 START，保证回放有起止
        if (player.isUsingItem()) {
            int hand = handId(player.getUsedItemHand());
            writeEvent(st, now, TrackEvents.TYPE_USE_START, TrackEvents.useStartPayload(hand));
            st.lastUsing = true;
            st.lastUseHand = hand;
        }
        if (CommandConfig.getInstance().trackRecordInventory()) {
            List<ItemStack> inventory = inventoryOf(player);
            writeInventory(st, now, inventory);
            st.lastInventory = inventory;
        }
        // 初始快照立即落盘：崩溃时可从段首恢复装备基线
        st.evtOut.flush();
        st.lastEventFlushMs = now;
    }

    private void maybeWriteEquipment(ServerPlayer player, State st, long now) throws IOException {
        TrackEventStore.Equipment equipment = equipmentOf(player);
        if (st.lastEquipment != null
                && st.lastSelectedSlot == equipment.selectedSlot()
                && sameEquipment(st.lastEquipment, equipment)) {
            return;
        }
        writeEquip(st, now, equipment);
    }

    private static boolean sameEquipment(TrackEventStore.Equipment a, TrackEventStore.Equipment b) {
        for (int i = 0; i < TrackEvents.SLOT_COUNT; i++) {
            if (!ItemStack.matches(a.slots()[i], b.slots()[i])) {
                return false;
            }
        }
        return true;
    }

    private void writeEquip(State st, long now, TrackEventStore.Equipment equipment) throws IOException {
        byte[] payload = TrackEventStore.encodeEquipment(equipment.slots(), equipment.selectedSlot(),
                registryAccess(st));
        writeEvent(st, now, TrackEvents.TYPE_EQUIP, payload);
        st.lastEquipment = equipment;
        st.lastSelectedSlot = equipment.selectedSlot();
    }

    private void maybeWriteInventory(ServerPlayer player, State st, long now) throws IOException {
        List<ItemStack> inventory = inventoryOf(player);
        if (st.lastInventory != null && sameInventory(st.lastInventory, inventory)) {
            return;
        }
        writeInventory(st, now, inventory);
        st.lastInventory = inventory;
    }

    private static List<ItemStack> inventoryOf(ServerPlayer player) {
        List<ItemStack> out = new ArrayList<>();
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            out.add(player.getInventory().getItem(i));
        }
        return out;
    }

    private static boolean sameInventory(List<ItemStack> a, List<ItemStack> b) {
        if (a.size() != b.size()) {
            return false;
        }
        for (int i = 0; i < a.size(); i++) {
            if (!ItemStack.matches(a.get(i), b.get(i))) {
                return false;
            }
        }
        return true;
    }

    private void writeInventory(State st, long now, List<ItemStack> inventory) throws IOException {
        byte[] payload = TrackEventStore.encodeInventory(inventory, registryAccess(st));
        writeEvent(st, now, TrackEvents.TYPE_INVENTORY, payload);
    }

    private static net.minecraft.core.RegistryAccess registryAccess(State st) {
        // 事件写入发生在主线程；registryAccess 在 sample() 里从当前服务器缓存。
        return st.registries;
    }

    /** 每 tick 检查使用起止、飞行模式与载具变化（不依赖 5Hz 采样）。 */
    private void continuousEvents(ServerPlayer player, State st, long now) {
        if (st.evtOut == null) {
            return;
        }
        try {
            boolean using = player.isUsingItem();
            int hand = using ? handId(player.getUsedItemHand()) : -1;
            if (using && !st.lastUsing) {
                writeEvent(st, now, TrackEvents.TYPE_USE_START, TrackEvents.useStartPayload(hand));
                st.lastUsing = true;
                st.lastUseHand = hand;
                st.lastUseStartedMs = now;
                st.lastUseFallbackStopMs = 0;
            } else if (using && st.lastUsing && hand != st.lastUseHand) {
                writeEvent(st, now, TrackEvents.TYPE_USE_STOP, new byte[0]);
                writeEvent(st, now, TrackEvents.TYPE_USE_START, TrackEvents.useStartPayload(hand));
                st.lastUseHand = hand;
            } else if (!using && st.lastUsing && now >= st.lastUseFallbackStopMs) {
                writeEvent(st, now, TrackEvents.TYPE_USE_STOP, new byte[0]);
                st.lastUsing = false;
                st.lastUseHand = -1;
            }

            int flight = flightMode(player);
            if (flight != st.lastFlightMode) {
                writeEvent(st, now, TrackEvents.TYPE_FLIGHT_MODE, TrackEvents.flightPayload(flight));
                st.lastFlightMode = flight;
            }
            TrackEvents.VehicleInfo vehicle = vehicleInfo(player);
            if (st.lastVehicle == null || !st.lastVehicle.equals(vehicle)) {
                writeEvent(st, now, TrackEvents.TYPE_VEHICLE, TrackEvents.vehiclePayload(vehicle));
                st.lastVehicle = vehicle;
            }
            if (now - st.lastEventFlushMs > 2000L) {
                st.evtOut.flush();
                st.lastEventFlushMs = now;
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹事件写入失败 {}: {}", st.uuid, e.getMessage());
            closeStream(st);
        }
    }

    /** 记录玩家聊天发言到当前轨迹段的 .evt（与装备/挥动事件同一条时间轴）。 */
    public void recordChat(ServerPlayer player, String text) {
        State st = states.get(player.getUUID());
        if (st == null || st.evtOut == null || text == null || text.isBlank()) {
            return;
        }
        try {
            writeEvent(st, System.currentTimeMillis(), TrackEvents.TYPE_CHAT,
                    TrackEvents.chatPayload(text));
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 聊天事件写入失败 {}: {}", st.uuid, e.getMessage());
        }
    }

    public void recordSwing(ServerPlayer player, InteractionHand hand, int animationId) {        State st = states.get(player.getUUID());
        if (st == null || st.evtOut == null) {
            return;
        }
        long now = System.currentTimeMillis();
        int handId = handId(hand);
        if (st.lastSwingHand == handId && now - st.lastSwingMs < 50L) {
            return;
        }
        st.lastSwingHand = handId;
        st.lastSwingMs = now;
        try {
            writeEvent(st, now, TrackEvents.TYPE_SWING,
                    TrackEvents.swingPayload(handId, animationId));
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 挥动事件写入失败 {}: {}", st.uuid, e.getMessage());
        }
    }

    private void recordUseStart(ServerPlayer player, InteractionHand hand, long now) {
        State st = states.get(player.getUUID());
        if (st == null || st.evtOut == null) {
            return;
        }
        try {
            int handId = player.isUsingItem() ? handId(player.getUsedItemHand()) : handId(hand);
            // 同一个 tick 可能同时触发 UseItem/UseBlock 回调（主手+副手），只记一次
            boolean duplicate = st.lastUsing && now - st.lastUseStartedMs < 100L;
            if (!duplicate && (!st.lastUsing || st.lastUseHand != handId)) {
                writeEvent(st, now, TrackEvents.TYPE_USE_START, TrackEvents.useStartPayload(handId));
                st.lastUsing = true;
                st.lastUseHand = handId;
                st.lastUseStartedMs = now;
            }
            if (!player.isUsingItem()) {
                // 一次性使用：给客户端一点时间看到起手，下一 tick 补 STOP
                st.lastUseFallbackStopMs = now + 50L;
            } else {
                st.lastUseFallbackStopMs = 0;
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 使用事件写入失败 {}: {}", st.uuid, e.getMessage());
        }
    }

    private static void writeEvent(State st, long now, int type, byte[] payload) throws IOException {
        if (st.evtOut == null) {
            return;
        }
        TrackEvents.write(st.evtOut, st.lastEventMs, new TrackEvents.Event(now, type, payload));
        st.lastEventMs = now;
        // 侧车与 .bin 同样在 2 秒窗口 flush；崩溃时允许尾部不完整
    }

    private static int handId(InteractionHand hand) {
        return hand == InteractionHand.OFF_HAND ? 1 : 0;
    }

    private static int flightMode(ServerPlayer player) {
        if (player.isFallFlying()) {
            return TrackEvents.FLIGHT_ELYTRA;
        }
        if (player.getAbilities().flying) {
            return TrackEvents.FLIGHT_CREATIVE;
        }
        return TrackEvents.FLIGHT_NONE;
    }

    private static TrackEvents.VehicleInfo vehicleInfo(ServerPlayer player) {
        if (player.getVehicle() == null) {
            return TrackEvents.VehicleInfo.typeOnly("");
        }
        var vehicle = player.getVehicle();
        var key = BuiltInRegistries.ENTITY_TYPE.getKey(vehicle.getType());
        String type = key == null ? "" : key.toString();
        if (type.isEmpty() || !(vehicle instanceof AbstractHorse horse)) {
            return TrackEvents.VehicleInfo.typeOnly(type);
        }
        int variant = horse instanceof Horse h ? h.getVariant().getId() : 0;
        int markings = horse instanceof Horse h ? h.getMarkings().getId() : 0;
        boolean chested = horse instanceof AbstractChestedHorse ch && ch.hasChest();
        return new TrackEvents.VehicleInfo(
                type,
                true,
                horse.getMaxHealth(),
                horse.getAttributeValue(Attributes.MOVEMENT_SPEED),
                horse.getAttributeValue(Attributes.JUMP_STRENGTH),
                variant,
                markings,
                horse.isSaddled(),
                chested,
                horse.isBaby());
    }

    private static int flags(ServerPlayer p) {
        int f = 0;
        if (p.isShiftKeyDown()) {
            f |= TrackStore.FLAG_SNEAK;
        }
        if (p.isSprinting()) {
            f |= TrackStore.FLAG_SPRINT;
        }
        if (p.getAbilities().flying) {
            f |= TrackStore.FLAG_FLYING;
        }
        if (p.isSwimming()) {
            f |= TrackStore.FLAG_SWIMMING;
        }
        if (p.getVehicle() != null) {
            f |= TrackStore.FLAG_RIDING;
        }
        if (p.hurtTime > 0) {
            f |= TrackStore.FLAG_HURT;
        }
        if (p.isDeadOrDying()) {
            f |= TrackStore.FLAG_DEAD;
        }
        return f;
    }

    private void openAppend(State st) throws IOException {
        st.out = new BufferedOutputStream(Files.newOutputStream(st.file,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND), 8192);
        openFiles.add(st.file);
        st.lastFlushMs = System.currentTimeMillis();
    }

    private void closeStream(State st) {
        if (st.out != null) {
            try {
                st.out.flush();
                st.out.close();
            } catch (IOException ignored) {
            }
            st.out = null;
        }
        if (st.file != null) {
            openFiles.remove(st.file);
        }
        if (st.evtOut != null) {
            try {
                st.evtOut.flush();
                st.evtOut.close();
            } catch (IOException ignored) {
            }
            st.evtOut = null;
        }
        if (st.evtFile != null) {
            openFiles.remove(st.evtFile);
        }
    }

    private void close(State st) {
        if (st == null) {
            return;
        }
        closeStream(st);
    }

    // ---- 保留策略 ----

    /** 异步跑一次保留降采样（从服务器启动/每 6h） */
    public void sweepAsync() {
        if (sweeping) {
            return;
        }
        sweeping = true;
        lastSweepMs = System.currentTimeMillis();
        Thread t = new Thread(this::sweep, "samsara-track-retention");
        t.setDaemon(true);
        t.start();
    }

    /**
     * 保留扫描：按「世界 + 周目 + 日期」遍历。日期年龄决定目标采样率；
     * 未迁移的旧布局（世界下直接放日期目录 / 根下直接放日期目录）也能被扫到。
     */
    private void sweep() {
        try {
            TrackRetention.Settings retention = CommandConfig.getInstance().trackRetention();
            ChatLogStore.purgeExpired(CommandConfig.getInstance().trackChatRetentionDays());
            int skeleton = Math.max(1, retention.skeletonPerDay());
            Map<UUID, Long> before = trackBytesByPlayer();
            Map<UUID, TrackRetention.Mode> modes = new java.util.HashMap<>();
            TrackRetention.Mode configured = retention.effectiveMode();
            for (Map.Entry<UUID, Long> entry : before.entrySet()) {
                TrackRetention.Mode effective = TrackRetention.effectiveModeForBytes(
                        retention, entry.getValue());
                modes.put(entry.getKey(), effective);
                if (configured != TrackRetention.Mode.ADAPTIVE
                        && effective == TrackRetention.Mode.ADAPTIVE) {
                    Samsara.LOGGER.info("[周目管理 samsara] 玩家 {} 的轨迹 {} 字节超过 maxBytesPerPlayer={}，"
                                    + "保留策略从 {} 降级到 adaptive",
                            entry.getKey(), entry.getValue(), retention.maxBytesPerPlayer(), configured.id());
                }
            }

            LocalDate today = LocalDate.now();
            for (Path trackRoot : trackRoots()) {
                if (!Files.isDirectory(trackRoot)) {
                    continue;
                }
                try (var top = Files.list(trackRoot)) {
                    TrackClips clips = clipsForTrackRoot(trackRoot);
                    String world = worldKeyOfTrackRoot(trackRoot);
                    for (Path child : (Iterable<Path>) top::iterator) {
                        if (!Files.isDirectory(child)) {
                            continue;
                        }
                        // 更早的无世界层布局：track/<日期>/
                        if (TrackLayout.isDateDir(child.getFileName().toString())) {
                            sweepDay(child, today, retention, modes, clips, world, 0);
                            continue;
                        }
                        sweepWorldLevel(child, today, retention, modes, clips, world);
                    }
                }
            }

            // 降级后仍超限：拒绝新归档（录制端在轮转/新段前检查）
            Map<UUID, Long> after = trackBytesByPlayer();
            long max = retention.maxBytesPerPlayer();
            for (Map.Entry<UUID, Long> entry : after.entrySet()) {
                if (max <= 0 || entry.getValue() < max) {
                    archiveRefused.remove(entry.getKey());
                    continue;
                }
                archiveRefused.add(entry.getKey());
                Samsara.LOGGER.warn("[周目管理 samsara] 玩家 {} 的 .bin 轨迹 {} 字节仍超过 maxBytesPerPlayer={}"
                                + "（已先降级到 adaptive）；将拒绝新归档",
                        entry.getKey(), entry.getValue(), max);
            }
            // 已无文件/已降到上限以下的玩家也要解除
            archiveRefused.removeIf(id -> !after.containsKey(id));
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹保留扫描异常: {}", e.getMessage());
        } finally {
            sweeping = false;
        }
    }

    /** 实际 .bin 用量（容量预估校准）：总字节、总样本、文件数、玩家数。 */
    public record Usage(long totalBytes, long totalSamples, long fileCount, long playerCount) {
        public double bytesPerSample() {
            return totalSamples <= 0 ? 0.0 : (double) totalBytes / totalSamples;
        }

        public double bytesPerPlayer() {
            return playerCount <= 0 ? 0.0 : (double) totalBytes / playerCount;
        }
    }

    /**
     * 扫描所有栈层的 .bin，统计实际字节/样本/玩家用量。
     * 结果缓存 5 分钟；Web 预估页反复调整曲线时不会重复遍历。
     */
    public Usage usage() {
        long now = System.currentTimeMillis();
        Usage cached = cachedUsage;
        if (cached != null && now - cachedUsageAtMs < 5L * 60_000L) {
            return cached;
        }
        long bytes = 0;
        long samples = 0;
        long files = 0;
        java.util.Set<UUID> players = new java.util.HashSet<>();
        for (Path root : trackRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                for (Path file : stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".bin")).toList()) {
                    UUID owner = uuidOfTrackFile(file);
                    if (owner != null) {
                        players.add(owner);
                    }
                    files++;
                    try {
                        TrackStore.FileUsage usage = TrackStore.fileUsage(file);
                        bytes += usage.bytes();
                        samples += usage.samples();
                    } catch (IOException e) {
                        try {
                            bytes += Files.size(file);
                        } catch (IOException ignored) {
                        }
                    }
                }
            } catch (IOException ignored) {
            }
        }
        Usage usage = new Usage(bytes, samples, files, players.size());
        cachedUsage = usage;
        cachedUsageAtMs = now;
        return usage;
    }

    /** 统计每个玩家所有栈层/周目/日期的 .bin 字节数（不扫描 .evt）。 */
    private Map<UUID, Long> trackBytesByPlayer() {
        Map<UUID, Long> out = new java.util.HashMap<>();
        for (Path root : trackRoots()) {
            if (!Files.isDirectory(root)) {
                continue;
            }
            try (var stream = Files.walk(root)) {
                stream.filter(Files::isRegularFile)
                        .filter(p -> p.getFileName().toString().endsWith(".bin"))
                        .forEach(p -> {
                            UUID id = uuidOfTrackFile(p);
                            if (id == null) {
                                return;
                            }
                            try {
                                out.merge(id, Files.size(p), Long::sum);
                            } catch (IOException ignored) {
                            }
                        });
            } catch (IOException ignored) {
            }
        }
        return out;
    }

    private static UUID uuidOfTrackFile(Path file) {
        String name = file.getFileName().toString();
        if (!name.endsWith(".bin")) {
            return null;
        }
        try {
            return UUID.fromString(name.substring(0, name.length() - 4));
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** 一个世界目录：子项可能是 cycle&lt;id&gt; 目录，也可能是尚未迁移的日期目录 */
    private void sweepWorldLevel(Path worldDir, LocalDate today,
                                 TrackRetention.Settings retention,
                                 Map<UUID, TrackRetention.Mode> modes,
                                 TrackClips clips, String world) throws IOException {
        try (var children = Files.list(worldDir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (TrackLayout.isDateDir(child.getFileName().toString())) {
                    sweepDay(child, today, retention, modes, clips, world, 0);
                } else {
                    int cycle = TrackLayout.cycleIdOf(child.getFileName().toString());
                    if (cycle >= 0) {
                        sweepCycle(child, today, retention, modes, clips, world, cycle);
                    }
                }
            }
        }
    }

    private void sweepCycle(Path cycleDir, LocalDate today,
                            TrackRetention.Settings retention,
                            Map<UUID, TrackRetention.Mode> modes,
                            TrackClips clips, String world, int cycleId) throws IOException {
        try (var days = Files.list(cycleDir)) {
            for (Path dayDir : (Iterable<Path>) days::iterator) {
                if (Files.isDirectory(dayDir)
                        && TrackLayout.isDateDir(dayDir.getFileName().toString())) {
                    sweepDay(dayDir, today, retention, modes, clips, world, cycleId);
                }
            }
        }
    }

    /** 对一个日期目录做降采样（世界 + 周目 + 日期口径）；只处理 .bin，不碰 .evt。 */
    private void sweepDay(Path dayDir, LocalDate today,
                          TrackRetention.Settings retention,
                          Map<UUID, TrackRetention.Mode> modes,
                          TrackClips clips, String world, int cycleId) throws IOException {
        LocalDate date;
        try {
            date = LocalDate.parse(dayDir.getFileName().toString());
        } catch (Exception e) {
            return;
        }
        int age = (int) Math.max(0, ChronoUnit.DAYS.between(date, today));
        List<Path> files;
        try (var list = Files.list(dayDir)) {
            files = list.filter(Files::isRegularFile)
                    .filter(p -> p.getFileName().toString().endsWith(".bin"))
                    .toList();
        }
        for (Path f : files) {
            if (openFiles.contains(f)) {
                continue;
            }
            UUID owner = uuidOfTrackFile(f);
            TrackRetention.Mode mode = owner == null
                    ? retention.effectiveMode()
                    : modes.getOrDefault(owner, retention.effectiveMode());
            if (mode == TrackRetention.Mode.KEEP_ALL) {
                continue;
            }
            double targetHz = mode == TrackRetention.Mode.CURVE
                    ? TrackRetention.curveTargetHz(age, retention.curve())
                    : TrackRetention.adaptiveTargetHz(age);
            if (targetHz >= TrackRetention.FRESH_HZ) {
                continue;
            }
            try {
                List<TrackClips.Range> keepFull = owner == null || clips == null
                        ? List.of() : clips.protectedRanges(world, cycleId, owner);
                if (TrackStore.decimate(f, targetHz, retention.skeletonPerDay(), keepFull)) {
                    Samsara.LOGGER.info("[周目管理 samsara] 轨迹降采样: {} → {}Hz（{} 天前 · {}）{}",
                            f.getFileName(), targetHz, age, mode.id(),
                            keepFull.isEmpty() ? "" : " · 收藏保护 " + keepFull.size() + " 段");
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 轨迹降采样失败 {}: {}", f, e.getMessage());
            }
        }
    }

    // ---- 查询/回放支持 ----

    /** 解析玩家名 → UUID（在线优先，其次 usercache/玩家数据） */
    public static UUID resolveUuid(MinecraftServer server, String name) {
        ServerPlayer online = server.getPlayerList().getPlayerByName(name);
        if (online != null) {
            return online.getUUID();
        }
        for (PlayerRegistry.RegPlayer p : PlayerRegistry.getInstance().read(server)) {
            if (p.name.equalsIgnoreCase(name)) {
                return p.uuid;
            }
        }
        return null;
    }

    /** 当前周目 id（无周目 → 0，对应 cycle0） */
    public int cycleIdNow() {
        Season s = SeasonManager.getInstance().current();
        return s == null ? 0 : s.id;
    }

    /** 当前世界键（启动前为 null） */
    public String worldKey() {
        return worldKey;
    }

    /** 找到指定周目、包含指定时刻的轨迹文件（按日期就近查找，最多回退 2 天） */
    public Path fileFor(UUID uuid, long timeMs, int cycleId) {
        LocatedFile located = fileForAny(List.of(uuid), timeMs, cycleId);
        return located == null ? null : located.file();
    }

    /** 同一身份组多个账号入口的文件查找；返回命中的文件与实际 UUID。 */
    public LocatedFile fileForAny(List<UUID> uuids, long timeMs, int cycleId) {
        if (uuids == null || uuids.isEmpty()) {
            return null;
        }
        List<Path> bases = trackRoots();
        if (bases.isEmpty()) {
            bases = List.of(TrackLayout.worldRoot(root, worldKey == null ? "world" : worldKey));
        }
        LocalDate d = Instant.ofEpochMilli(timeMs)
                .atZone(ZoneId.systemDefault()).toLocalDate();
        for (Path base : bases) {
            for (UUID uuid : uuids) {
                for (int i = 0; i <= 2; i++) {
                    Path f = TrackLayout.dayDir(base, cycleId, d.minusDays(i)).resolve(uuid + ".bin");
                    if (Files.isRegularFile(f)) {
                        return new LocatedFile(f, uuid);
                    }
                }
            }
        }
        return null;
    }

    /** 命中的轨迹文件及其实际 UUID。 */
    public record LocatedFile(Path file, UUID owner) {
    }

    /** 某个玩家最近的文件（跨栈层/周目，新周目优先；状态展示用） */
    public List<Path> recentFiles(UUID uuid, int limit) {
        List<Path> out = new ArrayList<>();
        List<Integer> cycles = cycleIds();
        for (Path base : trackRoots()) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            for (int cycle : cycles) {
                for (long i = 0; i < 7; i++) {
                    Path f = TrackLayout.dayDir(base, cycle, LocalDate.now().minusDays(i))
                            .resolve(uuid + ".bin");
                    if (Files.isRegularFile(f)) {
                        out.add(f);
                        if (out.size() >= limit) {
                            return out;
                        }
                    }
                }
            }
        }
        return out;
    }

    /** 全部栈层下已有的周目目录 id（新→旧）；无目录时返回 [当前周目] */
    public List<Integer> cycleIds() {
        List<Integer> ids = new ArrayList<>();
        for (Path base : trackRoots()) {
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (var list = Files.list(base)) {
                for (Path p : (Iterable<Path>) list::iterator) {
                    int id = TrackLayout.cycleIdOf(p.getFileName().toString());
                    if (id >= 0 && Files.isDirectory(p) && !ids.contains(id)) {
                        ids.add(id);
                    }
                }
            } catch (IOException ignored) {
                // 保持空表，回落到当前周目
            }
        }
        ids.sort(Comparator.reverseOrder());
        if (ids.isEmpty()) {
            ids.add(cycleIdNow());
        }
        return ids;
    }

    /** 文件所属栈层 track 根；无法识别时回退栈顶。 */
    public Path trackRootOf(Path file) {
        if (file == null) {
            return root;
        }
        Path abs = file.toAbsolutePath().normalize();
        for (Path candidate : trackRoots()) {
            if (abs.startsWith(candidate.toAbsolutePath().normalize())) {
                return candidate.toAbsolutePath().normalize();
            }
        }
        return root;
    }

    /** 捕捉截断点存储（栈顶；跨栈层请用 {@link #cutsForTrackRoot(Path)}） */
    public TrackCuts cuts() {
        return cuts;
    }

    /** 轨迹根目录（track/） */
    public Path root() {
        return root;
    }

    /** 当前世界轨迹目录：track/&lt;世界&gt; */
    public Path worldRoot() {
        return worldRoot;
    }
}
