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
 * → 持久化 spawnCenter/groupSpawns → 启动倒流（档2，默认 1 天可配置）
 * → 倒流到期 → 全员传送 + 广播；离线玩家上线后自动传送。
 * <p>
 * 单例：所有命令/功能共享同一实例与内存状态，避免多实例不同步
 * （曾出现 /season 与 /samsara debug 各持实例导致 debug 读到旧状态）。
 */
public class SeasonManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static SeasonManager instance;
    private final Path file;
    private SeasonData data;
    /** 倒流完成后离线玩家的待传送（uuid → 分组出生点），上线即传送 */
    private final Map<UUID, int[]> pendingTeleports = new HashMap<>();

    private SeasonManager() {
        Path dir = FabricLoader.getInstance().getConfigDir().resolve("samsara");
        try {
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        file = dir.resolve("season.json");
        load();
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
        } else {
            data = new SeasonData();
        }
    }

    private void save() {
        try {
            Files.writeString(file, GSON.toJson(data), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("season.json 写入失败: " + e.getMessage(), e);
        }
    }

    // ---- 状态机操作 ----

    /** /season create <name> [mode] */
    public String create(String name, String mode) {
        if (currentPending() != null) {
            return "存在未开始的周目 #" + currentPending().id + "「" + currentPending().name + "」，请先 start 或 end";
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
     * same-world：新出生中心 = 距旧中心 15000~40000 格随机方向（避开已用方向）；
     * new-world：使用世界出生点。随后分组并持久化；same-world 且 settings.rewindDurationDays &gt; 0
     * 时启动时间倒流（档2），倒流到期后自动传送；否则立即传送 + 广播。
     */
    public String start(MinecraftServer server) {
        Season s = currentPending();
        if (s == null) {
            return "没有待开始的周目（先 /season create）";
        }
        if (RewindEngine.getInstance().running()) {
            return "已有进行中的时间倒流，请先 /samsara debug rewind abort 再开始新周目";
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

        // 2) 新出生中心
        int centerX;
        int centerZ;
        String centerNote;
        if ("new-world".equals(s.mode)) {
            centerX = spawn.getX();
            centerZ = spawn.getZ();
            centerNote = "new-world 模式：使用世界出生点";
        } else {
            List<int[]> used = new ArrayList<>();
            for (Season se : data.seasons) {
                if (se.startTime != null) {
                    used.add(new int[]{se.spawnCenter.x, se.spawnCenter.z});
                }
            }
            SpawnCenterCalculator.CenterResult c = SpawnCenterCalculator.next(oldX, oldZ, used, cfg, rnd);
            centerX = c.x();
            centerZ = c.z();
            centerNote = "距旧中心 (" + oldX + ", " + oldZ + ") " + c.distance() + " 格";
        }
        s.spawnCenter.x = centerX;
        s.spawnCenter.z = centerZ;
        s.spawnCenter.radius = cfg.spawnRadius;

        // 3) 分组 + 持久化
        List<PlayerRegistry.RegPlayer> players = PlayerRegistry.getInstance().read(server);
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

        String header = "周目 #" + s.id + "「" + s.name + "」开始（ACTIVE）· 出生中心 ("
                + centerX + ", " + centerZ + ") · " + centerNote;

        // 5) same-world → 时间倒流（档2），到期再传送；否则立即传送
        if ("same-world".equals(s.mode) && cfg.rewindDurationDays > 0) {
            long durMs = (long) Math.max(1, cfg.rewindDurationDays * 86_400_000L);
            String r = RewindEngine.getInstance().start(server, s.id, durMs, () -> onRewindComplete(server, s.id));
            return header + "\n" + r;
        }
        int t = teleportSeasonPlayers(server, s);
        broadcast(server, "🎉 周目 #" + s.id + "「" + s.name + "」已开启！出生中心 (" + centerX + ", " + centerZ
                + ") · 分组 " + plan.groups.size() + " 组 · 已传送 " + t + " 名在线玩家");
        return header + "\n" + plan.report() + "\n已传送在线玩家 " + t + " 名（离线玩家上线后自动传送）";
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
        List<PlayerRegistry.RegPlayer> players = PlayerRegistry.getInstance().read(server);
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

    /** /season end */
    public String end() {
        Season s = current();
        if (s == null || s.state != Season.State.ACTIVE) {
            return "当前没有进行中的周目";
        }
        s.state = Season.State.ENDED;
        s.endTime = Instant.now().toEpochMilli();
        save();
        return "周目 #" + s.id + "「" + s.name + "」已结束（ENDED）";
    }

    /** 玩家加入（M2：倒流完成后的离线玩家补传送） */
    public void onPlayerJoin(MinecraftServer server, ServerPlayer player) {
        int[] pos = pendingTeleports.remove(player.getUUID());
        if (pos == null) {
            return;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld == null) {
            return;
        }
        teleportPlayer(player, overworld, pos[0], pos[1]);
        player.sendSystemMessage(Component.literal("[周目管理] 欢迎回来！你已被传送至本周目分组出生点 ("
                + pos[0] + ", " + pos[1] + ")"));
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

    public Season current() {
        for (Season s : data.seasons) {
            if (s.state == Season.State.ACTIVE || s.state == Season.State.PENDING) {
                return s;
            }
        }
        return data.seasons.isEmpty() ? null : data.seasons.get(data.seasons.size() - 1);
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
        Map<UUID, ServerPlayer> online = new HashMap<>();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            online.put(p.getUUID(), p);
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
                assigned.add(u);
                ServerPlayer p = online.get(u);
                if (p != null) {
                    teleportPlayer(p, overworld, g.x, g.z);
                    teleported++;
                } else {
                    pendingTeleports.put(u, new int[]{g.x, g.z});
                }
            }
        }
        // 在线但未分组的玩家 → 出生中心
        for (ServerPlayer p : online.values()) {
            if (!assigned.contains(p.getUUID())) {
                teleportPlayer(p, overworld, s.spawnCenter.x, s.spawnCenter.z);
                teleported++;
            }
        }
        return teleported;
    }

    private void teleportPlayer(ServerPlayer player, ServerLevel overworld, int x, int z) {
        int top = overworld.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z);
        int y = Math.max(1, top) + 1;
        player.teleportTo(overworld, x + 0.5, y, z + 0.5, Set.of(),
                player.getYRot(), player.getXRot(), false);
        player.sendSystemMessage(Component.literal("[周目管理] 你已传送至新出生区 (" + x + ", " + y + ", " + z + ")"));
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
        GroupPlanner.Plan plan = GroupPlanner.plan(cx, cz, data.settings.spawnRadius, players,
                data.settings, new Random());
        return plan.report();
    }

    /** 设置倒流时长（天，可配小数；&lt;=0 = 关闭倒流直接传送） */
    public String setRewindDuration(double days) {
        data.settings.rewindDurationDays = days;
        save();
        return "已设置倒流时长 " + days + " 天（下一次 /season start 生效）";
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