package com.shenqianye.samsara.track;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.rewind.DiffStore;
import com.shenqianye.samsara.season.PlayerRegistry;
import com.shenqianye.samsara.season.Season;
import com.shenqianye.samsara.season.SeasonData;
import com.shenqianye.samsara.season.SeasonManager;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

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
    /** 保留档位：跨过阈值后把采样率降到对应档（Hz） */
    private static final int[] TIER_DAYS = {1, 4, 16, 64, 256};
    private static final double[] TIER_HZ = {2.5, 1.25, 0.625, 0.3125, 0.15625};
    private static final double SKELETON_HZ = 0.05;

    private static TrackRecorder instance;
    /** track 根目录；世界层/周目层在其下 */
    private final Path root;
    /** 当前世界键（世界目录名）；服务器启动后确定 */
    private volatile String worldKey;
    /** 当前世界目录：track/&lt;世界&gt; */
    private volatile Path worldRoot;
    /** 捕捉截断点（capture cutoff，周目隔离） */
    private final TrackCuts cuts;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();
    /** 正在被在线玩家追加的文件：保留扫描不要碰 */
    private final java.util.Set<Path> openFiles = ConcurrentHashMap.newKeySet();
    private volatile boolean sweeping;
    private long lastSweepMs;

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
    }

    private TrackRecorder() {
        root = FabricLoader.getInstance().getConfigDir().resolve("samsara").resolve("track");
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 无法创建 track 目录: {}", e.getMessage());
        }
        cuts = new TrackCuts(root.resolve("cutoffs.json"));
    }

    public static synchronized TrackRecorder getInstance() {
        if (instance == null) {
            instance = new TrackRecorder();
        }
        return instance;
    }

    /** 注册采样/生命周期钩子（主入口调用一次） */
    public static void init() {
        TrackRecorder r = getInstance();
        // 提前在主线程加载 season.json，避免首次保留扫描在后台线程触发 SeasonManager 初始化
        SeasonManager.getInstance();
        ServerTickEvents.END_SERVER_TICK.register(r::tick);
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                r.startPlayer(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) ->
                r.stopPlayer(handler.getPlayer().getUUID()));
        ServerLifecycleEvents.SERVER_STOPPED.register(server -> r.stopAll());
        ServerLifecycleEvents.SERVER_STARTED.register(r::initWorld);
        ServerLifecycleEvents.SERVER_STARTED.register(server -> r.sweepAsync());
    }

    /**
     * 服务器启动：把根切到 {@code track/<世界>}，并迁移旧布局：
     * <ul>
     *   <li>{@code track/<日期>/} → {@code track/<世界>/cycle<id>/<日期>/}（更早的无世界层版本）；</li>
     *   <li>{@code track/<世界>/<日期>/} → {@code track/<世界>/cycle<id>/<日期>/}（v0.12.0）。</li>
     * </ul>
     * 旧数据归入当前周目；服务器还没有周目时归入 {@code cycle0}。日志写明迁移结果。
     */
    private void initWorld(MinecraftServer server) {
        String key = DiffStore.sanitizeWorldKey(
                server.getWorldPath(net.minecraft.world.level.storage.LevelResource.ROOT)
                        .getFileName().toString());
        Path target = TrackLayout.worldRoot(root, key);
        try {
            Files.createDirectories(target);
            int cycle = cycleIdNow();
            int legacy = TrackLayout.migrateLegacyRoot(root, target, cycle);
            int worldLevel = TrackLayout.migrateWorldLayout(target, cycle);
            if (legacy + worldLevel > 0) {
                Samsara.LOGGER.info("[周目管理 samsara] 轨迹旧布局已迁移: {} 个日期目录 → {}/{}/"
                                + "（旧数据归入当前周目；无周目时 cycle0）",
                        legacy + worldLevel, key, TrackLayout.cycleDirName(cycle));
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] track 世界层初始化失败: {}", e.getMessage());
        }
        worldKey = key;
        worldRoot = target;
        // 世界切换（单人换存档）/外部编辑后重新读取截断点
        cuts.load();
    }

    private void tick(MinecraftServer server) {
        if (worldRoot == null) {
            return; // 世界层尚未初始化（SERVER_STARTED 之前）
        }
        long now = System.currentTimeMillis();
        SeasonData.Settings cfg = SeasonManager.getInstance().settings();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            State st = states.computeIfAbsent(p.getUUID(), k -> {
                State s = new State();
                s.uuid = k;
                return s;
            });
            st.settings = cfg;
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

    private void stopAll() {
        for (State st : states.values()) {
            close(st);
        }
        states.clear();
    }

    private void sample(ServerPlayer player, State st, long now, SeasonData.Settings cfg) {
        if (worldRoot == null) {
            return;
        }
        if (st.settings == null) {
            st.settings = cfg;
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
            boolean rotateDay = st.day == null || !today.equals(st.day);
            // 周目切换：目录跟着换（跨周目必须开新段，旧段留在旧周目目录）
            boolean rotateCycle = st.cycleId != cycleId;
            boolean newSegment = rotateDay || rotateCycle || st.dimension == null
                    || !st.dimension.equals(dim) || st.out == null;
            if (rotateDay || rotateCycle) {
                closeStream(st);
                st.cycleId = cycleId;
                st.day = today;
                st.file = TrackLayout.file(worldRoot, cycleId, today, st.uuid);
                Files.createDirectories(st.file.getParent());
                openAppend(st);
                if (Files.size(st.file) == 0) {
                    st.out.write(TrackStore.header());
                }
            }
            if (st.out == null) {
                openAppend(st);
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
            }
            st.out.write(TrackStore.encodeSample(st.last, cur));
            st.last = cur;
            st.lastSampleMs = now;
            if (now - st.lastFlushMs > 2000) {
                st.out.flush();
                st.lastFlushMs = now;
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹写入失败 {}: {}", st.uuid, e.getMessage());
            closeStream(st);
        }
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
            SeasonData.Settings cfg = SeasonManager.getInstance().settings();
            int skeleton = Math.max(100, cfg.trackSkeletonPerDay);
            LocalDate today = LocalDate.now();
            if (!Files.isDirectory(root)) {
                return;
            }
            try (var top = Files.list(root)) {
                for (Path child : (Iterable<Path>) top::iterator) {
                    if (!Files.isDirectory(child)) {
                        continue;
                    }
                    // 更早的无世界层布局：track/<日期>/
                    if (TrackLayout.isDateDir(child.getFileName().toString())) {
                        sweepDay(child, today, skeleton);
                        continue;
                    }
                    sweepWorldLevel(child, today, skeleton);
                }
            }
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 轨迹保留扫描异常: {}", e.getMessage());
        } finally {
            sweeping = false;
        }
    }

    /** 一个世界目录：子项可能是 cycle&lt;id&gt; 目录，也可能是尚未迁移的日期目录 */
    private void sweepWorldLevel(Path worldDir, LocalDate today, int skeleton) throws IOException {
        try (var children = Files.list(worldDir)) {
            for (Path child : (Iterable<Path>) children::iterator) {
                if (!Files.isDirectory(child)) {
                    continue;
                }
                if (TrackLayout.isDateDir(child.getFileName().toString())) {
                    sweepDay(child, today, skeleton);
                } else if (TrackLayout.cycleIdOf(child.getFileName().toString()) >= 0) {
                    sweepCycle(child, today, skeleton);
                }
            }
        }
    }

    private void sweepCycle(Path cycleDir, LocalDate today, int skeleton) throws IOException {
        try (var days = Files.list(cycleDir)) {
            for (Path dayDir : (Iterable<Path>) days::iterator) {
                if (Files.isDirectory(dayDir)
                        && TrackLayout.isDateDir(dayDir.getFileName().toString())) {
                    sweepDay(dayDir, today, skeleton);
                }
            }
        }
    }

    /** 对一个日期目录做降采样（世界 + 周目 + 日期口径） */
    private void sweepDay(Path dayDir, LocalDate today, int skeleton) throws IOException {
        LocalDate date;
        try {
            date = LocalDate.parse(dayDir.getFileName().toString());
        } catch (Exception e) {
            return;
        }
        long age = ChronoUnit.DAYS.between(date, today);
        double targetHz = targetHz(age);
        if (targetHz >= 5.0) {
            return;
        }
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
            try {
                if (TrackStore.decimate(f, targetHz, skeleton)) {
                    Samsara.LOGGER.info("[周目管理 samsara] 轨迹降采样: {} → {}Hz（{} 天前）",
                            f.getFileName(), targetHz, age);
                }
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 轨迹降采样失败 {}: {}", f, e.getMessage());
            }
        }
    }

    private static double targetHz(long ageDays) {
        for (int i = 0; i < TIER_DAYS.length; i++) {
            if (ageDays < TIER_DAYS[i]) {
                return i == 0 ? 5.0 : TIER_HZ[i - 1];
            }
        }
        return SKELETON_HZ;
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
        Path base = worldRoot;
        if (base == null) {
            base = TrackLayout.worldRoot(root, worldKey == null ? "world" : worldKey);
        }
        LocalDate d = Instant.ofEpochMilli(timeMs)
                .atZone(ZoneId.systemDefault()).toLocalDate();
        for (int i = 0; i <= 2; i++) {
            Path f = TrackLayout.dayDir(base, cycleId, d.minusDays(i)).resolve(uuid + ".bin");
            if (Files.isRegularFile(f)) {
                return f;
            }
        }
        return null;
    }

    /** 某个玩家最近的文件（跨周目，新周目优先；状态展示用） */
    public List<Path> recentFiles(UUID uuid, int limit) {
        if (worldRoot == null || !Files.isDirectory(worldRoot)) {
            return List.of();
        }
        List<Path> out = new ArrayList<>();
        List<Integer> cycles = cycleIds();
        for (int cycle : cycles) {
            for (long i = 0; i < 7; i++) {
                Path f = TrackLayout.dayDir(worldRoot, cycle, LocalDate.now().minusDays(i))
                        .resolve(uuid + ".bin");
                if (Files.isRegularFile(f)) {
                    out.add(f);
                    if (out.size() >= limit) {
                        return out;
                    }
                }
            }
        }
        return out;
    }

    /** 当前世界下已有的周目目录 id（新→旧）；无目录时返回 [当前周目] */
    public List<Integer> cycleIds() {
        List<Integer> ids = new ArrayList<>();
        if (worldRoot != null && Files.isDirectory(worldRoot)) {
            try (var list = Files.list(worldRoot)) {
                for (Path p : (Iterable<Path>) list::iterator) {
                    int id = TrackLayout.cycleIdOf(p.getFileName().toString());
                    if (id >= 0 && Files.isDirectory(p)) {
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

    /** 捕捉截断点存储（周目隔离） */
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
