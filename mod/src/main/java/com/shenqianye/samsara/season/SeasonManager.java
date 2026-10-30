package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.rewind.RewindEngine;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelData;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;

/**
 * 周目状态机（M1）+ 分组出生与倒流流程（M2）：season.json 持久化 + create/start/end 流转。
 * <p>
 * 状态机（设计文档 §3.4）：
 * PENDING --(start)--> ACTIVE --(end)--> ENDED --(archive, M 后续)--> ARCHIVED
 * <p>
 * M2 的 /season start 流程（§4，same-world 带倒流时）：
 * 校验 → 新出生中心（距旧中心 15000~40000 随机方向，避开已用方向）→ 分组
 * （3~5 人/组、角度均分+抖动、组距≥300，来源 = 在线 + usercache + playerdata）
 * → 持久化 spawnCenter/groupSpawns → 启动倒流（默认 1 天可配置）
 * → 倒流到期 → 全员传送 + 广播；离线玩家上线后自动传送。
 * <p>
 * 单例：所有命令/功能共享同一实例与内存状态，避免多实例不同步
 * （曾出现 /season 与 /samsara debug 各持实例导致 debug 读到旧状态）。
 */
public class SeasonManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SeasonManager instance;
    private final Path file;
    private final Path pendingFile;
    private SeasonData data;
    /** 倒流完成后离线玩家的待传送（uuid → 分组出生点），上线即传送；持久化以免重启丢失 */
    private final Map<UUID, int[]> pendingTeleports = new HashMap<>();

    private SeasonManager() {
        Path dir = com.shenqianye.samsara.storage.StoragePaths.get().worldDataRoot();
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 世界数据目录", e);
        }
        file = dir.resolve("season.json");
        pendingFile = dir.resolve("pending-teleports.json");
        load();
        loadPendingTeleports();
    }

    public static synchronized SeasonManager getInstance() {
        if (instance == null) {
            instance = new SeasonManager();
        }
        return instance;
    }

    private void load() {
        if (Files.exists(file)) {
            try {
                data = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), SeasonData.class);
                if (data == null) {
                    data = new SeasonData();
                }
            } catch (Exception e) {
                throw new RuntimeException("season.json 解析失败（可删除该文件后重启重建）: " + e.getMessage(), e);
            }
            // 迁移旧字段：rewindDurationDays（天）→ rewindDurationMs（毫秒）
            // 仅旧文件（无 rewindDurationMs 字段）迁移；新文件两者都有，以 ms 为准
            if (data.settings != null && data.settings.rewindDurationDays != null) {
                String raw;
                try {
                    raw = Files.readString(file, StandardCharsets.UTF_8);
                } catch (IOException e) {
                    raw = "";
                }
                if (!raw.contains("rewindDurationMs")) {
                    data.settings.rewindDurationMs =
                            (long) (data.settings.rewindDurationDays * 86_400_000L);
                }
            }
        } else {
            data = new SeasonData();
        }
        if (data.settings == null) {
            data.settings = new SeasonData.Settings();
        }
        data.settings.normalize();
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("season.json 写入失败: " + e.getMessage(), e);
        }
    }

    // ---- 离线玩家待传送持久化（重启不丢，L3 修复） ----

    /** 序列化结构：pending-teleports.json */
    public static class PendingState {
        public List<Entry> entries = new ArrayList<>();

        public static class Entry {
            public String uuid;
            public int x;
            public int z;
        }
    }

    private void savePendingTeleports() {
        try {
            PendingState s = new PendingState();
            for (Map.Entry<UUID, int[]> e : pendingTeleports.entrySet()) {
                PendingState.Entry en = new PendingState.Entry();
                en.uuid = e.getKey().toString();
                en.x = e.getValue()[0];
                en.z = e.getValue()[1];
                s.entries.add(en);
            }
            Files.writeString(pendingFile, GSON.toJson(s), StandardCharsets.UTF_8);
        } catch (IOException e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[周目管理 samsara] pending-teleports 写入失败: {}", e.getMessage());
        }
    }

    private void loadPendingTeleports() {
        pendingTeleports.clear();
        if (!Files.exists(pendingFile)) {
            return;
        }
        try {
            PendingState s = GSON.fromJson(Files.readString(pendingFile, StandardCharsets.UTF_8), PendingState.class);
            if (s != null) {
                for (PendingState.Entry en : s.entries) {
                    try {
                        pendingTeleports.put(UUID.fromString(en.uuid), new int[]{en.x, en.z});
                    } catch (IllegalArgumentException ignored) {
                    }
                }
            }
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.warn("[周目管理 samsara] pending-teleports 解析失败: {}", e.getMessage());
        }
    }

    // ---- 状态机操作 ----

    /** /season create <name> [mode] */
    public String create(String name, String mode) {
        if (currentPending() != null) {
            return "存在未开始的周目 #" + currentPending().id + "「" + currentPending().name + "」，请先 start 或 end";
        }
        // M2 修复：不允许在已有 ACTIVE 周目时再创建（否则双 ACTIVE 并存，current() 取错）
        Season active = firstActive();
        if (active != null) {
            return "已有进行中的周目 #" + active.id + "「" + active.name + "」（ACTIVE），请先 /season end 再创建";
        }
        if (!"same-world".equals(mode) && !"new-world".equals(mode)) {
            return "模式必须为 same-world 或 new-world";
        }
        Season s = new Season();
        s.id = data.seasons.isEmpty() ? 1 : data.seasons.get(data.seasons.size() - 1).id + 1;
        s.name = name;
        s.mode = mode;
        data.seasons.add(s);
        save();
        return "已创建周目 #" + s.id + "「" + name + "」（" + mode + "，PENDING）";
    }

    /**
     * /season start（M2 完整流程，设计文档 §4）。
     * <p>
     * same-world：新出生中心与全部历史中心集合都 ≥ newCenterMinDistance；
     * new-world：使用世界出生点。随后分组并持久化；same-world 且 settings.rewindDurationMs &gt; 0
     * 时启动时间倒流，倒流到期后自动传送；否则立即传送 + 广播。
     */
    public String start(MinecraftServer server) {
        // 拆分：setup（准备）→ 倒流或传送，见 flow 系列命令
        String setup = setupSeason(server);
        if (!setup.startsWith("周目 #")) {
            return setup; // setup 失败
        }
        Season s = current();
        if (s == null) {
            return setup;
        }
        SeasonData.Settings cfg = data.settings;
        // same-world + 倒流 >0 → 启动倒流（到期自动传送）；否则立即传送
        if ("same-world".equals(s.mode) && cfg.rewindDurationMs > 0) {
            // 记录窗口在 RewindEngine.start 内冻结（此后新改动属于下个窗口）
            String r = RewindEngine.getInstance().start(server, s.id, cfg.rewindDurationMs,
                    () -> onRewindComplete(server, s.id));
            // M3 修复：世界无可倒流区块（全部受保护/为空）→ 不卡 ACTIVE，改为直接传送
            if (r.equals(RewindEngine.NO_REWINDABLE_MSG)) {
                int t = teleportSeasonPlayers(server, s);
                broadcast(server, "🎉 周目 #" + s.id + "「" + s.name + "」已开启（世界无可倒流区块，跳过倒流）· 出生中心 ("
                        + s.spawnCenter.x + ", " + s.spawnCenter.z + ") · 已传送 " + t + " 名在线玩家");
                return setup + "\n" + r + "\n（已跳过倒流，改为直接传送在线玩家 " + t + " 名）";
            }
            return setup + "\n" + r;
        }
        int t = teleportSeasonPlayers(server, s);
        broadcast(server, "🎉 周目 #" + s.id + "「" + s.name + "」已开启！出生中心 (" + s.spawnCenter.x + ", " + s.spawnCenter.z
                + ") · 分组 " + s.groupSpawns.size() + " 组 · 已传送 " + t + " 名在线玩家");
        return setup + "\n已传送在线玩家 " + t + " 名（离线玩家上线后自动传送）";
    }

    /**
     * flow setup：只做「准备」——计算出生中心 + 分组 + 持久化 + 置 ACTIVE。
     * 不启动倒流、不传送。返回摘要；失败返回错误。
     */
    public String setupSeason(MinecraftServer server) {
        Season s = currentPending();
        if (s == null) {
            return "没有待开始的周目（先 /season create）";
        }
        if (RewindEngine.getInstance().running()) {
            return "已有进行中的时间倒流，请先 /samsara debug rewind abort 再操作";
        }
        // M2 修复：已有其它 ACTIVE 周目时不允许再开启（避免双 ACTIVE 并存）
        Season active = firstActive();
        if (active != null && active.id != s.id) {
            return "已有进行中的周目 #" + active.id + "「" + active.name + "」（ACTIVE），请先 /season end 再开启";
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return "主世界不可用";
        }
        Random rnd = new Random();
        SeasonData.Settings cfg = data.settings;

        // 1) 旧出生中心（上一个已开始周目的中心，否则世界出生点）
        Season prev = lastStarted();
        BlockPos spawn = overworld.getRespawnData().pos();
        int oldX = prev != null ? prev.spawnCenter.x : spawn.getX();
        int oldZ = prev != null ? prev.spawnCenter.z : spawn.getZ();

        // 2) 新出生中心：must be ≥ newCenterMinDistance 远离每个历史中心
        int centerX;
        int centerZ;
        String centerNote;
        if ("new-world".equals(s.mode)) {
            centerX = spawn.getX();
            centerZ = spawn.getZ();
            centerNote = "new-world 模式：使用世界出生点";
        } else {
            List<int[]> used = new ArrayList<>();
            used.add(new int[]{spawn.getX(), spawn.getZ()});
            for (Season se : data.seasons) {
                if (se == s) {
                    continue;
                }
                if (se.startTime != null) {
                    used.add(new int[]{se.spawnCenter.x, se.spawnCenter.z});
                }
            }
            SpawnCenterCalculator.CenterResult c =
                    SpawnCenterCalculator.next(oldX, oldZ, used, cfg, rnd);
            centerX = c.x();
            centerZ = c.z();
            centerNote = "历史中心集合 " + used.size() + " 个 · 距最近 " + c.minDist() + " 格";
        }
        s.spawnCenter.x = centerX;
        s.spawnCenter.z = centerZ;
        s.spawnCenter.radius = cfg.spawnRadius;

        // 3) 分组 + 持久化（同一身份组只保留一个账号入口，按 data UUID 去重）
        List<PlayerRegistry.RegPlayer> players = dedupePlayers(
                PlayerRegistry.getInstance().read(server));
        // L6 修复：真实开周目自动清空测试注入池（避免假玩家残留进后续分组）
        int injectedCleared = PlayerRegistry.getInstance().injectedCount();
        if (injectedCleared > 0) {
            PlayerRegistry.getInstance().clearInjected();
        }
        GroupPlanner.Plan plan = GroupPlanner.plan(centerX, centerZ, cfg.spawnRadius, players, cfg, rnd);
        s.groupSpawns.clear();
        for (GroupPlanner.GroupSpawnPoint g : plan.groups) {
            Season.GroupSpawn gs = new Season.GroupSpawn();
            gs.x = g.x;
            gs.z = g.z;
            for (UUID u : g.playerUuids) {
                gs.playerUuids.add(u.toString());
            }
            s.groupSpawns.add(gs);
        }

        // 4) 状态流转
        s.state = Season.State.ACTIVE;
        s.startTime = Instant.now().toEpochMilli();
        data.currentSeasonId = s.id;
        save();

        String injectedNote = injectedCleared > 0
                ? "（已自动清空注入的测试玩家 " + injectedCleared + " 名）" : "";
        return "周目 #" + s.id + "「" + s.name + "」已准备（ACTIVE）· 出生中心 ("
                + centerX + ", " + centerZ + ") · " + centerNote + injectedNote + "\n" + plan.report();
    }

    /**
     * flow rewind：只启动倒流（不传送）。需当前已有 ACTIVE 周目。
     * @param teleportAfter 倒流完成后是否自动传送（true=完整流程；false=只倒流不传送）
     */
    public String startRewindOnly(MinecraftServer server, boolean teleportAfter) {
        Season s = current();
        if (s == null || s.state != Season.State.ACTIVE) {
            return "当前没有 ACTIVE 周目（先 /samsara debug flow setup）";
        }
        if (RewindEngine.getInstance().running()) {
            return "已有进行中的时间倒流，请先 abort";
        }
        SeasonData.Settings cfg = data.settings;
        if (cfg.rewindDurationMs <= 0) {
            return "倒流时长未配置（/samsara debug config rewind <时长>）";
        }
        com.shenqianye.samsara.rewind.DiffRecorder.setEnabled(true); // 记录常开，确保已初始化
        String r = RewindEngine.getInstance().start(server, s.id, cfg.rewindDurationMs,
                teleportAfter ? () -> onRewindComplete(server, s.id) : null);
        return r;
    }

    /**
     * flow teleport：只传送当前 ACTIVE 周目玩家（不启动倒流）。
     */
    public String teleportOnly(MinecraftServer server) {
        Season s = current();
        if (s == null || s.state != Season.State.ACTIVE) {
            return "当前没有 ACTIVE 周目（先 /samsara debug flow setup）";
        }
        int t = teleportSeasonPlayers(server, s);
        broadcast(server, "🎉 传送完成！周目 #" + s.id + "「" + s.name + "」出生中心 ("
                + s.spawnCenter.x + ", " + s.spawnCenter.z + ") · " + t + " 名在线玩家就位");
        return "已传送 " + t + " 名在线玩家（离线玩家上线后自动传送）";
    }

    /** 倒流完成回调：传送全员 + 广播（由 RewindEngine 到期调用） */
    public void onRewindComplete(MinecraftServer server, int seasonId) {
        Season s = findById(seasonId);
        if (s == null) {
            return;
        }
        int t = teleportSeasonPlayers(server, s);
        broadcast(server, "🎉 全员传送完成！周目 #" + s.id + "「" + s.name + "」出生中心 ("
                + s.spawnCenter.x + ", " + s.spawnCenter.z + ") · " + t + " 名在线玩家就位"
                + (pendingTeleports.isEmpty() ? "" : " · " + pendingTeleports.size() + " 名离线玩家上线后自动传送"));
    }

    /**
     * /season respawn-groups：不换周目，围绕当前周目出生中心重新分组（§4 末尾）。
     * 立即传送（不触发倒流）。
     */
    public String respawnGroups(MinecraftServer server) {
        Season s = current();
        if (s == null || s.state != Season.State.ACTIVE) {
            return "当前没有进行中的周目";
        }
        Random rnd = new Random();
        SeasonData.Settings cfg = data.settings;
        List<PlayerRegistry.RegPlayer> players = dedupePlayers(
                PlayerRegistry.getInstance().read(server));
        GroupPlanner.Plan plan = GroupPlanner.plan(s.spawnCenter.x, s.spawnCenter.z,
                Math.max(1, s.spawnCenter.radius), players, cfg, rnd);
        s.groupSpawns.clear();
        for (GroupPlanner.GroupSpawnPoint g : plan.groups) {
            Season.GroupSpawn gs = new Season.GroupSpawn();
            gs.x = g.x;
            gs.z = g.z;
            for (UUID u : g.playerUuids) {
                gs.playerUuids.add(u.toString());
            }
            s.groupSpawns.add(gs);
        }
        save();
        int t = teleportSeasonPlayers(server, s);
        broadcast(server, "🔄 周目 #" + s.id + "「" + s.name + "」重新分组完成 · "
                + plan.groups.size() + " 组 · 已传送 " + t + " 名在线玩家");
        return plan.report() + "\n已传送在线玩家 " + t + " 名（离线玩家上线后自动传送）";
    }

    /** /season end —— 若倒流进行中，先中止倒流，再置 ENDED */
    public String end() {
        Season s = current();
        if (s == null || s.state != Season.State.ACTIVE) {
            return "当前没有进行中的周目";
        }
        String rewindNote = "";
        if (RewindEngine.getInstance().running()) {
            rewindNote = "\n（已中止进行中的时间倒流）" + RewindEngine.getInstance().abort().replaceFirst("^[^：]*：", " ");
        }
        // 记录常开（diff 窗口继续累计），但清空本季待传送（已结束的周目不再补传送）
        pendingTeleports.clear();
        savePendingTeleports();
        s.state = Season.State.ENDED;
        s.endTime = Instant.now().toEpochMilli();
        save();
        return "周目 #" + s.id + "「" + s.name + "」已结束（ENDED）" + rewindNote;
    }

    /** /season skip —— 跳过当前 PENDING 周目（不开始，特殊标记 SKIPPED 以便/season status 速记） */
    public String skip() {
        Season s = currentPending();
        if (s == null) {
            return "当前没有待开始的 PENDING 周目（无待跳过项）";
        }
        s.state = Season.State.SKIPPED;
        s.endTime = Instant.now().toEpochMilli(); // 复用 endTime 记跳过时刻
        save();
        return "已跳过周目 #" + s.id + "「" + s.name + "」（标记 SKIPPED，不开始）\n下次 /season status 会显示 SKIPPED；可 /season create 开新周目";
    }

    /** 玩家加入（M2：倒流完成后的离线玩家补传送 + 重置本人出生点） */
    public void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }
        int[] pos = pendingTeleports.remove(player.getUUID());
        if (pos == null) {
            // 待传送按实际 UUID 保存；换号上线时按 data UUID 查找同一身份的条目。
            for (UUID key : new ArrayList<>(pendingTeleports.keySet())) {
                if (com.shenqianye.samsara.identity.IdentityService.getInstance()
                        .sameIdentity(key, player.getUUID())) {
                    pos = pendingTeleports.remove(key);
                    break;
                }
            }
        }
        if (pos != null) {
            teleportPlayer(player, overworld, pos[0], pos[1]);
            setPlayerBirthpoint(player, overworld, pos[0], pos[1], data.settings);
            savePendingTeleports(); // 待传送被消费，落盘
            player.sendSystemMessage(Component.literal("[周目管理] 欢迎回来！你已被传送至本周目分组出生点 ("
                    + pos[0] + ", " + pos[1] + ")"));
            return;
        }
        // 未在待传送名单，但属于当前 ACTIVE 周目分组 → 仅重置出生点（不强制传送）
        Season s = current();
        if (s != null && s.state == Season.State.ACTIVE) {
            for (Season.GroupSpawn g : s.groupSpawns) {
                if (containsIdentity(g.playerUuids, player.getUUID())) {
                    int[] indiv = individualBirthpoint(
                            com.shenqianye.samsara.identity.IdentityService.getInstance()
                                    .identityOf(player.getUUID()),
                            g.x, g.z, data.settings);
                    setPlayerBirthpoint(player, overworld, indiv[0], indiv[1], data.settings);
                    break;
                }
            }
        }
    }

    /** 同一身份组的多个 alias 只保留一个分组条目。 */
    private static List<PlayerRegistry.RegPlayer> dedupePlayers(
            List<PlayerRegistry.RegPlayer> players) {
        Set<UUID> seen = new HashSet<>();
        List<PlayerRegistry.RegPlayer> out = new ArrayList<>();
        for (PlayerRegistry.RegPlayer player : players) {
            UUID data = com.shenqianye.samsara.identity.IdentityService.getInstance()
                    .identityOf(player.uuid);
            if (seen.add(data)) {
                out.add(player);
            }
        }
        return out;
    }

    private static boolean containsIdentity(List<String> uuids, UUID player) {
        if (uuids == null || player == null) {
            return false;
        }
        for (String text : uuids) {
            try {
                if (com.shenqianye.samsara.identity.IdentityService.getInstance()
                        .sameIdentity(UUID.fromString(text), player)) {
                    return true;
                }
            } catch (IllegalArgumentException ignored) {
            }
        }
        return false;
    }

    // ---- 查询 ----

    public Season currentPending() {
        for (Season s : data.seasons) {
            if (s.state == Season.State.PENDING) {
                return s;
            }
        }
        return null;
    }

    /** 第一个 ACTIVE 周目（无则 null）—— 用于阻止双 ACTIVE 并存（M2 修复） */
    private Season firstActive() {
        for (Season s : data.seasons) {
            if (s.state == Season.State.ACTIVE) {
                return s;
            }
        }
        return null;
    }

    public Season current() {
        for (Season s : data.seasons) {
            if (s.state == Season.State.ACTIVE || s.state == Season.State.PENDING) {
                return s;
            }
        }
        return data.seasons.isEmpty() ? null : data.seasons.get(data.seasons.size() - 1);
    }

    /**
     * 某个墙钟时刻所属的周目 id（用于轨迹按周目分目录/回放反查）。
     * <p>
     * 规则：{@code startTime <= t <= endTime}（未结束则上界为 +∞），多个命中取开始最晚的；
     * 若 t 落在周目之间的空隙，取 t 之前最近开始的周目；t 早于所有周目或没有周目 → {@code 0}
     * （对应 {@code cycle0}）。
     */
    public int cycleIdAt(long timeMs) {
        return cycleIdAt(data.seasons, timeMs);
    }

    /** 纯函数版本（单测覆盖；不依赖 season.json / FabricLoader） */
    public static int cycleIdAt(List<Season> seasons, long timeMs) {
        Season best = null;
        for (Season s : seasons) {
            if (s.startTime == null) {
                continue;
            }
            long end = s.endTime != null ? s.endTime : Long.MAX_VALUE;
            if (s.startTime <= timeMs && timeMs <= end
                    && (best == null || s.startTime > best.startTime)) {
                best = s;
            }
        }
        if (best != null) {
            return best.id;
        }
        for (Season s : seasons) {
            if (s.startTime != null && s.startTime <= timeMs
                    && (best == null || s.startTime > best.startTime)) {
                best = s;
            }
        }
        return best == null ? 0 : best.id;
    }

    /** 上一个已开始的周目（startTime 非空且非 PENDING；用于 same-world 旧中心） */
    public Season lastStarted() {
        Season found = null;
        for (Season s : data.seasons) {
            if (s.startTime != null && s.state != Season.State.PENDING) {
                found = s;
            }
        }
        return found;
    }

    /** 周目总数（概要展示用） */
    public int seasonCount() {
        return data.seasons.size();
    }

    public SeasonData data() {
        return data;
    }

    public SeasonData.Settings settings() {
        return data.settings;
    }

    public int pendingTeleportCount() {
        return pendingTeleports.size();
    }

    public String statusText() {
        return statusText(0);
    }

    /**
     * 周目列表文本。
     *
     * @param page 页码（&gt;0 时仅显示该页，每页 8 条；&lt;=0 显示全部）
     */
    public String statusText(int page) {
        final int pageSize = 8;
        StringBuilder sb = new StringBuilder();
        if (data.seasons.isEmpty()) {
            sb.append("尚无周目，使用 /season create <名称> 创建");
        } else {
            int total = data.seasons.size();
            int pages = Math.max(1, (int) Math.ceil(total / (double) pageSize));
            if (page > 0 && page > pages) {
                page = pages;
            }
            int from = page > 0 ? (page - 1) * pageSize : 0;
            int to = page > 0 ? Math.min(from + pageSize, total) : total;
            for (int i = from; i < to; i++) {
                Season s = data.seasons.get(i);
                sb.append("#").append(s.id).append("「").append(s.name).append("」 ")
                        .append(s.state).append(" · ").append(s.mode);
                if (s.startTime != null) {
                    sb.append(" · 始于 ").append(fmt(s.startTime));
                }
                if (s.endTime != null) {
                    sb.append(" · 止于 ").append(fmt(s.endTime));
                }
                if (!s.groupSpawns.isEmpty()) {
                    sb.append(" · 分组 ").append(s.groupSpawns.size()).append(" 组");
                }
                sb.append("\n");
            }
            if (page > 0 && pages > 1) {
                sb.append("--- 第 ").append(page).append("/").append(pages).append(" 页，共 ").append(total).append(" 条 ---\n");
            }
            Season c = current();
            if (c != null) {
                sb.append("当前：#").append(c.id).append("「").append(c.name).append("」 ").append(c.state);
            }
        }
        if (!data.worldStack.isEmpty()) {
            sb.append("\n世界栈：").append(String.join(" → ", data.worldStack));
        }
        if (RewindEngine.getInstance().running()) {
            sb.append("\n时间倒流进行中（").append(RewindEngine.getInstance().status()).append("）");
        }
        return sb.toString().trim();
    }

    private static String fmt(long ms) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault())
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }

    // ---- 传送/广播（M2） ----

    /** 传送周目玩家到各自分组出生点；离线玩家记入待传送。返回已传送在线人数。 */
    private int teleportSeasonPlayers(MinecraftServer server, Season s) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return 0;
        }
        // L3 修复：先清空再按当前周目名单填充，避免旧周目的残留待传送条目
        pendingTeleports.clear();
        Map<UUID, ServerPlayer> onlineByData = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            onlineByData.putIfAbsent(com.shenqianye.samsara.identity.IdentityService
                    .getInstance().identityOf(p.getUUID()), p);
        }
        Set<UUID> assigned = new HashSet<>();
        int teleported = 0;
        for (Season.GroupSpawn g : s.groupSpawns) {
            for (String us : g.playerUuids) {
                UUID u;
                try {
                    u = UUID.fromString(us);
                } catch (IllegalArgumentException e) {
                    continue;
                }
                UUID identityData = com.shenqianye.samsara.identity.IdentityService.getInstance()
                        .identityOf(u);
                if (!assigned.add(identityData)) {
                    continue; // 同一身份组只处理一次
                }
                int[] indiv = individualBirthpoint(identityData, g.x, g.z, data.settings);
                ServerPlayer p = onlineByData.get(identityData);
                if (p != null) {
                    teleportPlayer(p, overworld, indiv[0], indiv[1]);
                    setPlayerBirthpoint(p, overworld, indiv[0], indiv[1], data.settings);
                    teleported++;
                } else {
                    // 归属数据写实际 UUID；上线时按 data 解析
                    pendingTeleports.put(u, new int[]{indiv[0], indiv[1]});
                }
            }
        }
        // 在线但未分组的玩家 → 出生中心
        for (ServerPlayer p : onlineByData.values()) {
            if (!assigned.contains(com.shenqianye.samsara.identity.IdentityService
                    .getInstance().identityOf(p.getUUID()))) {
                teleportPlayer(p, overworld, s.spawnCenter.x, s.spawnCenter.z);
                setPlayerBirthpoint(p, overworld, s.spawnCenter.x, s.spawnCenter.z, data.settings);
                teleported++;
            }
        }
        savePendingTeleports(); // 持久化离线待传送（重启不丢）
        return teleported;
    }

    /**
     * 玩家独立出生点 = 组出生点 + 由 UUID 确定的确定性抖动（方括号内），
     * 保证同组不同玩家也相差一部分格、且跨重启稳定。
     * jitter &lt;= 0 时返回组中心（同组共用）。
     */
    private int[] individualBirthpoint(UUID uuid, int gx, int gz, SeasonData.Settings s) {
        int j = s.birthpointJitter;
        if (j <= 0) {
            return new int[]{gx, gz};
        }
        // 从 UUID 高位派生 [0,2j] 的抖动，减 j 后落在 [-j, j]
        long h = uuid.getMostSignificantBits() ^ uuid.getLeastSignificantBits();
        int dx = (int) ((h & 0x7FFFFF) % (2L * j + 1)) - j;
        int dz = (int) (((h >>> 16) & 0x7FFFFF) % (2L * j + 1)) - j;
        return new int[]{gx + dx, gz + dz};
    }

    /** 重置玩家系统出生点（死后无床重生的默认位置）到 (x,z)（M2，可配置关闭） */
    private void setPlayerBirthpoint(ServerPlayer player, ServerLevel overworld, int x, int z,
                                     SeasonData.Settings s) {
        if (!s.resetIndividualBirthpoint) {
            return;
        }
        // y 用与传送一致的“地表上方一格”，避免 getHeight 异常导致 y=2 埋地底
        int y = findSafeSurfaceY(overworld, x, z);
        // 与 SetSpawnCommand(/spawnpoint) 完全一致的写法：
        //  RespawnData.of(dimension, pos, yaw, pitch)；forced=false（仅无床/锚时生效）
        var respawnData = LevelData.RespawnData.of(overworld.dimension(), new BlockPos(x, y, z),
                player.getYRot(), player.getXRot());
        player.setRespawnPosition(new ServerPlayer.RespawnConfig(respawnData, false), false);
    }

    /** debug：读取玩家当前重生点（RespawnConfig pos/dimension/forced），用于定位重生点 bug */
    public String spawnInfo(ServerPlayer player, ServerLevel overworld) {
        if (overworld == null) {
            return "主世界不可用";
        }
        ServerPlayer.RespawnConfig rc = player.getRespawnConfig();
        if (rc == null || rc.respawnData() == null) {
            return "当前无自定义重生点（死后将回世界出生点）。world spawn: "
                    + overworld.getRespawnData().pos().getX() + ", "
                    + overworld.getRespawnData().pos().getZ();
        }
        LevelData.RespawnData rd = rc.respawnData();
        BlockPos p = rd.pos();
        return "重生点 pos=(" + p.getX() + "," + p.getY() + "," + p.getZ() + ") · 维度=" + rd.dimension()
                + " · forced=" + rc.forced() + "\n世界出生点: "
                + overworld.getRespawnData().pos().getX() + ", "
                + overworld.getRespawnData().pos().getZ();
    }

    private void teleportPlayer(ServerPlayer player, ServerLevel overworld, int x, int z) {
        int y = findSafeSurfaceY(overworld, x, z);
        player.teleportTo(overworld, x + 0.5, y, z + 0.5, Set.of(),
                player.getYRot(), player.getXRot(), false);
        player.sendSystemMessage(Component.literal("[周目管理] 你已传送至新出生区 (" + x + ", " + y + ", " + z + ")"));
    }

    /**
     * 在 (x,z) 找安全站立高度：先强制加载区块，再取 MOTION_BLOCKING 高度。
     * 防止 getHeight 在未加载/异常时返回极值（曾出现 Y=700000 传送到地底/天上）。
     * 返回该列地表上方一格的 Y。
     */
    private int findSafeSurfaceY(ServerLevel overworld, int x, int z) {
        try {
            overworld.getChunk(x >> 4, z >> 4); // 强制加载/生成
        } catch (Exception e) {
            // 加载失败则回退世界出生点高度
            return overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        }
        int h = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int minY = overworld.dimensionType().minY();
        int maxY = minY + overworld.dimensionType().height();
        // 钳制：正常高度应在 [minY+1, maxY]；异常（0/极值）则从顶部往下扫
        if (h <= minY || h > maxY) {
            for (int yy = maxY - 1; yy > minY; yy--) {
                var bs = overworld.getBlockState(new BlockPos(x, yy, z));
                if (!bs.isAir() && bs.isCollisionShapeFullBlock(overworld, new BlockPos(x, yy, z))) {
                    h = yy;
                    break;
                }
            }
        }
        return Math.max(minY + 1, Math.min(maxY, h + 1));
    }

    private void broadcast(MinecraftServer server, String msg) {
        server.getPlayerList().broadcastSystemMessage(Component.literal("[周目管理] " + msg), false);
    }

    // ---- 调试/测试支持（/samsara debug，OWNER 权限） ----

    /** 分组 dry-run（不落盘，使用当前中心参考点） */
    public String previewGroups(MinecraftServer server, List<PlayerRegistry.RegPlayer> players) {
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return "主世界不可用";
        }
        Season cur = current();
        int cx;
        int cz;
        if (cur != null) {
            cx = cur.spawnCenter.x;
            cz = cur.spawnCenter.z;
        } else {
            BlockPos spawn = overworld.getRespawnData().pos();
            cx = spawn.getX();
            cz = spawn.getZ();
        }
        GroupPlanner.Plan plan = GroupPlanner.plan(cx, cz, data.settings.spawnRadius,
                dedupePlayers(players), data.settings, new Random());
        return plan.report();
    }

    /** 设置倒流时长（支持 d/h/m/s + 纯数字=秒，如 1h、90m、3600s、0.5d） */
    public String setRewindDuration(String spec) {
        long ms = DurationUtil.parse(spec);
        if (ms <= 0) {
            return "时长格式无效（支持 1h、90m、3600s、0.5d 或纯数字秒；<=0 关闭倒流直接传送）";
        }
        data.settings.rewindDurationMs = ms;
        save();
        return "已设置倒流时长 " + DurationUtil.formatMs(ms) + "（下一次 /season start 生效）";
    }

    /** M2 配置项汇总文本 */
    public String settingsText() {
        try {
            SeasonData.Settings s = data.settings;
            return "分组/出生配置：\n"
                    + "组大小 " + s.groupSizeMin + "~" + s.groupSizeMax
                    + " · 组距 " + s.groupMinDistance + " (0=共用)\n"
                    + "出生中心距历史集合 " + s.newCenterMinDistance + "~" + s.newCenterMaxDistance + "（仅下限生效）\n"
                    + "每个玩家出生点独立抖动 " + s.birthpointJitter + " 格 (0=同组共用)\n"
                    + "系统出生点重置 " + (s.resetIndividualBirthpoint ? "开" : "关")
                    + " · 出生区半径 " + s.spawnRadius
                    + " · 倒流时长 " + DurationUtil.formatMs(s.rewindDurationMs) + "（可配 如 1h / 30m / 3600s / 1d / 0.5d）\n"
                    + "倒流节奏：曲线 k=" + s.rewindCurveK + " · 近档速率 r=" + s.rewindNearRatio
                    + " · 近档 ≤" + s.rewindNearRadius + " 区块 / 出档 >" + s.rewindExitRadius
                    + " · 驻留 " + (s.rewindNearDwellMs / 1000) + "s · 斜坡 " + (s.rewindRampMs / 1000) + "s\n"
                    + "玩家轨迹：" + s.trackSampleHz + "Hz · 骨架下限 " + s.trackSkeletonPerDay + " 点/天";
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[周目管理 samsara] settingsText 异常: ", e);
            return "settingsText 异常: " + e;
        }
    }

    /**
     * 统一配置命令 /samsara debug config [key [value]]。
     * 无参返回全部配置；只有 key 返回该配置当前值；key+value 设置并回显。
     * 返回 null 表示 key 不存在（|null）以便命令层提示错误。
     */
    public String config(String key, String value) {
        SeasonData.Settings s = data.settings;
        if (key == null || key.isBlank()) {
            // 全部配置 + 每个 key 可用值类型说明
            return "可配置项（键 · 类型 · 当前值）：\n"
                    + "  groupdist · 整数(格) · " + s.groupMinDistance + "（0=所有组共用中心）\n"
                    + "  birthjitter · 整数(格) · " + s.birthpointJitter + "（0=同组共用）\n"
                    + "  birthreset · true|false · " + s.resetIndividualBirthpoint + "（是否重置玩家出生点）\n"
                    + "  rewind · 时长(1h/30m/3600s/1d/0.5d) · " + DurationUtil.formatMs(s.rewindDurationMs) + "\n"
                    + "  groupmin · 整数(1~groupmax) · " + s.groupSizeMin + "\n"
                    + "  groupmax · 整数(groupmin~100) · " + s.groupSizeMax + "\n"
                    + "  spawnradius · 整数(>=0) · " + s.spawnRadius + "\n"
                    + "  centermin · 整数(>=0) · " + s.newCenterMinDistance + "（新中心距每个历史中心都≥此值）\n"
                    + "  centermax · 整数(>=centermin) · " + s.newCenterMaxDistance + "\n"
                    + "  curve · 小数(1~8) · " + s.rewindCurveK + "（A 光标曲线 p=1-(1-t)^k）\n"
                    + "  nearratio · 小数(0.1~1) · " + s.rewindNearRatio + "（近档 B 速率 = A×r）\n"
                    + "  nearradius · 整数(1~16) · " + s.rewindNearRadius + "（进近档区块距离）\n"
                    + "  exitradius · 整数(>nearradius) · " + s.rewindExitRadius + "（出近档区块距离）\n"
                    + "  neardwell · 秒(>=0) · " + (s.rewindNearDwellMs / 1000) + "（最短驻留）\n"
                    + "  ramp · 秒(>=0) · " + (s.rewindRampMs / 1000) + "（出档追平 A 的斜坡）\n"
                    + "  trackhz · 小数(2~20) · " + s.trackSampleHz + "（轨迹采样率）\n"
                    + "  trackskeleton · 整数(>=100) · " + s.trackSkeletonPerDay + "（每天骨架保留点数）\n"
                    + "用法：/samsara debug config <键> 查看当前值；config <键> <值> 设置并回显";
        }
        // 读取当前值 helper（lambda 内不可直接改局部，用 switch）
        switch (key) {
            case "groupdist" -> {
                if (value != null) {
                    return setGroupMinDistance(parseInt(value));
                }
                return "groupdist = " + s.groupMinDistance + "（0=所有组共用中心）";
            }
            case "birthjitter" -> {
                if (value != null) {
                    return setBirthpointJitter(parseInt(value));
                }
                return "birthjitter = " + s.birthpointJitter + "（0=同组共用）";
            }
            case "birthreset" -> {
                if (value != null) {
                    return setResetIndividualBirthpoint(Boolean.parseBoolean(value));
                }
                return "birthreset = " + s.resetIndividualBirthpoint;
            }
            case "rewind" -> {
                if (value != null) {
                    return setRewindDuration(value);
                }
                return "rewind = " + DurationUtil.formatMs(s.rewindDurationMs) + "（支持 1h/30m/3600s/1d/0.5d）";
            }
            case "groupmin" -> {
                if (value != null) {
                    return setGroupSizeMin(parseInt(value));
                }
                return "groupmin = " + s.groupSizeMin;
            }
            case "groupmax" -> {
                if (value != null) {
                    return setGroupSizeMax(parseInt(value));
                }
                return "groupmax = " + s.groupSizeMax;
            }
            case "spawnradius" -> {
                if (value != null) {
                    return setSpawnRadius(parseInt(value));
                }
                return "spawnradius = " + s.spawnRadius;
            }
            case "centermin" -> {
                if (value != null) {
                    return setNewCenterMin(parseInt(value));
                }
                return "centermin = " + s.newCenterMinDistance;
            }
            case "centermax" -> {
                if (value != null) {
                    return setNewCenterMax(parseInt(value));
                }
                return "centermax = " + s.newCenterMaxDistance;
            }
            case "curve" -> {
                if (value != null) {
                    return setRewindCurve(parseDouble(value));
                }
                return "curve = " + s.rewindCurveK;
            }
            case "nearratio" -> {
                if (value != null) {
                    return setRewindNearRatio(parseDouble(value));
                }
                return "nearratio = " + s.rewindNearRatio;
            }
            case "nearradius" -> {
                if (value != null) {
                    return setRewindNearRadius(parseInt(value));
                }
                return "nearradius = " + s.rewindNearRadius;
            }
            case "exitradius" -> {
                if (value != null) {
                    return setRewindExitRadius(parseInt(value));
                }
                return "exitradius = " + s.rewindExitRadius;
            }
            case "neardwell" -> {
                if (value != null) {
                    return setRewindNearDwell(parseInt(value));
                }
                return "neardwell = " + (s.rewindNearDwellMs / 1000) + "s";
            }
            case "ramp" -> {
                if (value != null) {
                    return setRewindRamp(parseInt(value));
                }
                return "ramp = " + (s.rewindRampMs / 1000) + "s";
            }
            case "trackhz" -> {
                if (value != null) {
                    return setTrackSampleHz(parseDouble(value));
                }
                return "trackhz = " + s.trackSampleHz;
            }
            case "trackskeleton" -> {
                if (value != null) {
                    return setTrackSkeleton(parseInt(value));
                }
                return "trackskeleton = " + s.trackSkeletonPerDay;
            }
            default -> {
                return null; // 未知配置项
            }
        }
    }

    private static int parseInt(String v) {
        try {
            return Integer.parseInt(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    private static double parseDouble(String v) {
        try {
            return Double.parseDouble(v.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    // ---- 倒流节奏 / 轨迹配置 ----

    public String setRewindCurve(double k) {
        if (k < 1 || k > 8) {
            return "curve 须在 [1, 8] 内（越大开场越快、尾段越平）";
        }
        data.settings.rewindCurveK = k;
        save();
        return "已设置倒流权威光标曲线 k = " + k + "（下一次 /season start 生效）";
    }

    public String setRewindNearRatio(double r) {
        if (r < 0.1 || r > 1) {
            return "nearratio 须在 [0.1, 1] 内（1 = 近档与远档同速）";
        }
        data.settings.rewindNearRatio = r;
        save();
        return "已设置近档速率 r = " + r + "（近档 B 速率 = A × r）";
    }

    public String setRewindNearRadius(int r) {
        if (r < 1 || r > 16) {
            return "nearradius 须在 [1, 16] 区块内";
        }
        data.settings.rewindNearRadius = r;
        if (data.settings.rewindExitRadius <= r) {
            data.settings.rewindExitRadius = r + 1;
        }
        save();
        return "已设置近档半径 " + r + " 区块（出档 > " + data.settings.rewindExitRadius + "）";
    }

    public String setRewindExitRadius(int r) {
        if (r <= data.settings.rewindNearRadius) {
            return "exitradius 须 > nearradius(" + data.settings.rewindNearRadius + ")";
        }
        data.settings.rewindExitRadius = r;
        save();
        return "已设置出近档半径 " + r + " 区块（保持带 "
                + data.settings.rewindNearRadius + "~" + r + "）";
    }

    public String setRewindNearDwell(int seconds) {
        if (seconds < 0) {
            return "neardwell 须 >= 0 秒";
        }
        data.settings.rewindNearDwellMs = seconds * 1000L;
        save();
        return "已设置近档最短驻留 " + seconds + " 秒";
    }

    public String setRewindRamp(int seconds) {
        if (seconds < 0) {
            return "ramp 须 >= 0 秒";
        }
        data.settings.rewindRampMs = seconds * 1000L;
        save();
        return "已设置出档斜坡 " + seconds + " 秒";
    }

    public String setTrackSampleHz(double hz) {
        if (hz < 2 || hz > 20) {
            return "trackhz 须在 [2, 20] 内";
        }
        data.settings.trackSampleHz = hz;
        save();
        return "已设置轨迹采样率 " + hz + "Hz（下一次采样生效）";
    }

    public String setTrackSkeleton(int points) {
        if (points < 100) {
            return "trackskeleton 须 >= 100 点/天";
        }
        data.settings.trackSkeletonPerDay = points;
        save();
        return "已设置轨迹骨架下限 " + points + " 点/天";
    }

    /** 设置组间距（方块；0 = 所有组共用中心） */
    public String setGroupMinDistance(int blocks) {
        data.settings.groupMinDistance = blocks;
        save();
        return "已设置组间最小距离 " + blocks + " 格（" + (blocks <= 0 ? "0 = 所有组共用中心" : "下一次 start/respawn-groups 生效") + "）";
    }

    /** 设置每个玩家出生点独立抖动（方块；0 = 同组共用） */
    public String setBirthpointJitter(int blocks) {
        data.settings.birthpointJitter = blocks;
        save();
        return "已设置每玩家出生点独立抖动 " + blocks + " 格（0 = 同组共用组出生点）";
    }

    /** 开关把每个玩家系统出生点重置到分组出生点 */
    public String setResetIndividualBirthpoint(boolean on) {
        data.settings.resetIndividualBirthpoint = on;
        save();
        return "系统出生点重置已" + (on ? "开启" : "关闭");
    }

    private String setGroupSizeMin(int v) {
        if (v < 1 || v > data.settings.groupSizeMax) {
            return "groupmin 须在 [1, groupmax] 内";
        }
        data.settings.groupSizeMin = v;
        save();
        return "组大小下限 = " + v;
    }

    private String setGroupSizeMax(int v) {
        if (v < data.settings.groupSizeMin || v > 100) {
            return "groupmax 须在 [groupmin, 100] 内";
        }
        data.settings.groupSizeMax = v;
        save();
        return "组大小上限 = " + v;
    }

    private String setSpawnRadius(int v) {
        if (v < 0) {
            return "spawnradius 须 >= 0";
        }
        data.settings.spawnRadius = v;
        save();
        return "出生区半径 = " + v;
    }

    private String setNewCenterMin(int v) {
        if (v < 0) {
            return "centermin 须 >= 0";
        }
        data.settings.newCenterMinDistance = v;
        save();
        return "新中心最小距离 = " + v + "（距每个历史中心都 >= 此值）";
    }

    private String setNewCenterMax(int v) {
        if (v < data.settings.newCenterMinDistance) {
            return "centermax 须 >= centermin";
        }
        data.settings.newCenterMaxDistance = v;
        save();
        return "新中心采样最大距离 = " + v;
    }

    /** 差异库状态（记录窗口 / 恢复源 / 记录数） */
    public String diffStatus() {
        String sb = "差异库（常开记录）：记录 " + (com.shenqianye.samsara.rewind.DiffRecorder.recording() ? "开" : "关");
        if (com.shenqianye.samsara.rewind.DiffRecorder.currentRoot() == null) {
            return sb + "\n记录窗口未初始化（需重启，DiffRecorder.init 未执行）";
        }
        sb += "\n记录窗口: diff/live/" + com.shenqianye.samsara.rewind.DiffRecorder.worldKey()
                + "（世界/维度分目录）· 当前记录数 "
                + com.shenqianye.samsara.rewind.DiffRecorder.countCurrent();
        if (com.shenqianye.samsara.rewind.RewindEngine.getInstance().running()) {
            int sid = com.shenqianye.samsara.rewind.RewindEngine.getInstance().currentSeasonId();
            sb += "\n恢复源: diff/cycle" + sid + "/"
                    + com.shenqianye.samsara.rewind.RewindEngine.getInstance().currentWorldKey()
                    + "/minecraft/overworld（本次倒流用，含上次删块后至今的全部改动）";
        }
        return sb;
    }

    /** 重新从磁盘加载 season.json（测试 load 路径） */
    public String reload() {
        load();
        return "已从磁盘重新加载 " + file.getFileName();
    }

    /** 清空全部周目数据，重置为初始状态 */
    public String reset() {
        data = new SeasonData();
        save();
        pendingTeleports.clear();
        savePendingTeleports();
        return "已清空全部周目数据（重置为初始状态）";
    }

    /** season.json 原始内容（pretty JSON） */
    public String rawJson() {
        return GSON.toJson(data);
    }

    /**
     * season.json 绝对路径（normalize 去除 '.\' 前缀）。
     */
    public String configPath() {
        return file.toAbsolutePath().normalize().toString();
    }

    /**
     * 批量创建"历史周目"（直接 ENDED，每天一个），用于快速构造多周目状态列表
     * 与 ID 递增的测试数据；绕过 PENDING 独占限制。
     */
    public String createBulk(String baseName, String mode, int count) {
        if (!"same-world".equals(mode) && !"new-world".equals(mode)) {
            return "模式必须为 same-world 或 new-world";
        }
        List<String> created = new ArrayList<>();
        long now = Instant.now().toEpochMilli();
        for (int i = 1; i <= count; i++) {
            Season s = new Season();
            s.id = data.seasons.isEmpty() ? 1 : data.seasons.get(data.seasons.size() - 1).id + 1;
            s.name = count == 1 ? baseName : baseName + "-" + i;
            s.mode = mode;
            s.state = Season.State.ENDED;
            s.startTime = now - (long) (count - i) * 86_400_000L;   // 每天一个
            s.endTime = s.startTime + 3_600_000L;
            data.seasons.add(s);
            created.add("#" + s.id + "「" + s.name + "」");
        }
        save();
        return "已创建 " + count + " 个历史周目（ENDED）：" + String.join(", ", created);
    }

    /** 直接设置周目时间戳（测试时间显示/排序）；value 支持 now 或 epoch 毫秒 */
    public String setTime(int id, String field, String value) {
        Season s = findById(id);
        if (s == null) {
            return "未找到周目 #" + id;
        }
        long ms;
        if ("now".equals(value)) {
            ms = System.currentTimeMillis();
        } else {
            try {
                ms = Long.parseLong(value);
            } catch (NumberFormatException e) {
                return "时间值必须为 now 或 epoch 毫秒数";
            }
        }
        switch (field) {
            case "start" -> s.startTime = ms;
            case "end" -> s.endTime = ms;
            default -> {
                return "字段必须为 start 或 end";
            }
        }
        save();
        String result = "已设置周目 #" + id + " 的 " + field + " = " + fmt(ms);
        // 警告：start/end 时间倒挂（debug 允许构造边界，但提醒）
        if (s.startTime != null && s.endTime != null && s.startTime > s.endTime) {
            result += "\n⚠ 注意：该周目的开始时间晚于结束时间（时间倒挂），属非正常状态";
        }
        return result;
    }

    private Season findById(int id) {
        for (Season s : data.seasons) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }
}