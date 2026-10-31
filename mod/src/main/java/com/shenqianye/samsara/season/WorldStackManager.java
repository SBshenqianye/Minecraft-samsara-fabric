package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 世界栈门面（v0.24 维度模型）。
 *
 * <p>权威清单在 {@code world/samsara/stack.json}：index 0 = 栈底/最旧，
 * 栈顶固定是 {@code world}。旧世界是 {@code world/dimensions/samsara/stack_<n>}
 * 的自定义维度，不再切换 {@code level-name}。
 *
 * <p>类里保留旧的 {@link Manifest}/{@link Pending} 纯数据类，供旧单测/兼容代码使用。
 */
public final class WorldStackManager {
    private static WorldStackManager instance;

    private WorldStackManager() {
    }

    public static synchronized WorldStackManager getInstance() {
        if (instance == null) {
            instance = new WorldStackManager();
        }
        return instance;
    }

    // ---- 新模型 ----

    public synchronized StackManifest manifest(MinecraftServer server) {
        return StackManifest.load(StackPaths.worldDir(server));
    }

    public synchronized void refresh(MinecraftServer server, String preferredTop) {
        StackManifest m = manifest(server);
        if (m.layers.isEmpty()) {
            m.ensureTop(StackLayer.top(server, server.getWorldGenSettings().options().seed()));
        }
        m.save(StackPaths.worldDir(server));
    }

    public synchronized List<String> stack(MinecraftServer server) {
        List<String> ids = new ArrayList<>();
        for (StackLayer l : manifest(server).layers) {
            ids.add(l.id);
        }
        return ids;
    }

    public synchronized String top(MinecraftServer server) {
        StackLayer top = manifest(server).top();
        return top == null ? StackPaths.TOP_ID : top.id;
    }

    public synchronized String current(MinecraftServer server) {
        return StackPaths.TOP_ID; // 主存档永远是栈顶 world
    }

    public synchronized boolean isTopCurrent(MinecraftServer server) {
        return true;
    }

    public synchronized String listText(MinecraftServer server) {
        StackManifest m = manifest(server);
        if (m.layers.isEmpty()) {
            return "世界栈为空（启动后会创建 world 栈顶）";
        }
        StringBuilder sb = new StringBuilder("世界栈（栈底 → 栈顶）：");
        for (StackLayer l : m.layers) {
            sb.append("\n").append(l.index).append(". ").append(l.id);
            if (l.top) {
                sb.append("  ← 当前/栈顶");
            }
            if (!l.parent.isBlank()) {
                sb.append("  (父 ").append(l.parent).append(")");
            }
            int count = onlineIn(server, l);
            if (count > 0) {
                sb.append(" · 在线 ").append(count);
            }
        }
        return sb.toString();
    }

    public synchronized String infoText(MinecraftServer server, ServerPlayer player) {
        StackManifest m = manifest(server);
        StackLayer current = player == null ? m.top() : StackTeleport.layerOf(server, player);
        StringBuilder sb = new StringBuilder("世界栈信息：");
        sb.append("\n· 实际加载：").append(currentWorldName(server));
        sb.append("\n· 栈顶：").append(m.top() == null ? "world" : m.top().id);
        sb.append("\n· 层数：").append(m.layers.size());
        if (current != null) {
            sb.append("\n· 你所在：").append(current.id).append("（index ").append(current.index).append("）");
            sb.append("\n· 当前种子：").append(current.seed);
            sb.append("\n· 出生点：").append(current.spawnX).append(", ")
                    .append(current.spawnY).append(", ").append(current.spawnZ);
            SpawnHistory h = spawnHistory(server, current.id);
            sb.append("\n· ").append(h.summaryText());
        }
        sb.append("\n· ").append(StackDimensionLoader.status());
        return sb.toString();
    }

    public synchronized String enter(MinecraftServer server, String target) {
        int moved = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String r = StackTeleport.enter(p, target);
            if (!r.contains("不在栈中") && !r.contains("未加载")) {
                moved++;
            }
        }
        if (moved == 0) {
            return "没有玩家被传送；确认世界 " + target + " 存在且维度已加载";
        }
        return "已把 " + moved + " 名在线玩家送入 " + target;
    }

    public synchronized String leave(MinecraftServer server) {
        int moved = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            StackLayer current = StackTeleport.layerOf(server, p);
            if (current == null) {
                continue;
            }
            StackLayer below = manifest(server).below(current);
            if (below != null && StackTeleport.enter(p, below.id).contains("已进入")) {
                moved++;
            }
        }
        return moved == 0 ? "没有玩家需要 leave（或已在栈底）" : "已把 " + moved + " 名玩家送回下层";
    }

    // ---- 新世界 / 出生点 ----

    public synchronized String startNewWorld(MinecraftServer server, Season season) {
        return StackBootstrap.scheduleNew(server, season.seed, "normal", "", false);
    }

    public synchronized String resetNew(MinecraftServer server, Long seed, int seasonId, String name) {
        return StackBootstrap.scheduleNew(server, seed, "normal", "", false);
    }

    public synchronized int nextSeasonId(MinecraftServer server) {
        int max = 0;
        SeasonData data = SeasonManager.getInstance().data();
        if (data != null && data.seasons != null) {
            for (Season s : data.seasons) {
                max = Math.max(max, s.id);
            }
        }
        return max + 1;
    }

    public SpawnHistory ensureSpawnHistory(MinecraftServer server, String layerId, boolean migrate) {
        StackManifest m = manifest(server);
        StackLayer layer = m.byId(layerId);
        if (layer == null) {
            layer = m.top();
        }
        if (layer == null) {
            return new SpawnHistory();
        }
        Path file = StackPaths.spawnHistory(StackPaths.worldDir(server), layer);
        SpawnHistory h = SpawnHistory.loadFrom(StackPaths.layerMeta(StackPaths.worldDir(server), layer));
        if (h.isEmpty()) {
            if (layer.top && migrate) {
                SeasonData legacy = SeasonManager.getInstance().data();
                if (legacy != null && legacy.seasons != null && !legacy.seasons.isEmpty()) {
                    h = SpawnHistory.fromSeasons(legacy, layer.spawnX, layer.spawnZ,
                            System.currentTimeMillis());
                } else {
                    h.ensureInitial(layer.spawnX, layer.spawnZ, System.currentTimeMillis());
                }
            } else {
                h.ensureInitial(layer.spawnX, layer.spawnZ, System.currentTimeMillis());
            }
            h.saveTo(StackPaths.layerMeta(StackPaths.worldDir(server), layer));
        } else {
            SpawnHistory.Cycle c = h.detect(layer.spawnX, layer.spawnZ, System.currentTimeMillis());
            if (c != null) {
                h.saveTo(StackPaths.layerMeta(StackPaths.worldDir(server), layer));
            }
        }
        return h;
    }

    public SpawnHistory spawnHistory(MinecraftServer server, String layerId) {
        StackManifest m = manifest(server);
        StackLayer layer = m.byId(layerId);
        if (layer == null) {
            layer = m.top();
        }
        if (layer == null) {
            return new SpawnHistory();
        }
        return SpawnHistory.loadFrom(
                StackPaths.layerMeta(StackPaths.worldDir(server), layer));
    }

    public SpawnHistory currentSpawnHistory(MinecraftServer server) {
        StackManifest m = manifest(server);
        StackLayer top = m.top();
        return top == null ? new SpawnHistory()
                : SpawnHistory.loadFrom(
                StackPaths.layerMeta(StackPaths.worldDir(server), top));
    }

    public String spawnText(MinecraftServer server, ServerPlayer player, boolean history) {
        StackLayer layer = player == null ? manifest(server).top()
                : StackTeleport.layerOf(server, player);
        if (layer == null) {
            layer = manifest(server).top();
        }
        if (layer == null) {
            return "世界栈为空";
        }
        SpawnHistory h = spawnHistory(server, layer.id);
        return history ? h.historyText()
                : "世界 " + layer.id + " · " + h.summaryText();
    }

    // ---- 旧命令兼容 ----

    public synchronized String debugPush(MinecraftServer server, String world) {
        return "v0.24 栈世界是 world 内的自定义维度，不再支持 debug world push；请用旧布局迁移或 reset new";
    }

    public synchronized String debugPop(MinecraftServer server) {
        return "v0.24 栈顶固定是 world，不能弹出；旧栈层可用 /samsara world list 查看";
    }

    public synchronized String debugReset(MinecraftServer server) {
        return "v0.24 不再重置栈清单；请手动备份 world 后处理 samsara/stack.json";
    }

    // ---- 路径/静态 ----

    public static String currentWorldName(MinecraftServer server) {
        Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        Path dir = levelDat == null ? null : levelDat.getParent();
        Path name = dir == null ? null : dir.getFileName();
        return name == null ? StackPaths.TOP_ID : name.toString();
    }

    public static Path worldDir(MinecraftServer server, String worldName) {
        return StackPaths.worldDir(server);
    }

    public static Path worldParent(MinecraftServer server) {
        return StackPaths.worldDir(server).getParent();
    }

    public synchronized String newWorldName(MinecraftServer server) {
        return "stack_" + manifest(server).layers.size();
    }

    public static synchronized void tick(MinecraftServer server) {
        // 由 StackBootstrap.tick 处理倒计时
    }

    private static int onlineIn(MinecraftServer server, StackLayer layer) {
        int n = 0;
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            String id = StackPaths.layerIdOf(p.level().dimension());
            if (layer.id.equals(id)) {
                n++;
            }
        }
        return n;
    }

    // ---- 旧纯数据类（单测/兼容） ----

    public static final class Manifest {
        public List<String> stack = new ArrayList<>();
        public String current = "";
        public int nextSeasonId = 1;
        public Pending pending;

        public String top() {
            return stack.isEmpty() ? "" : stack.get(stack.size() - 1);
        }

        public boolean isTopCurrent() {
            return !current.isEmpty() && current.equals(top());
        }

        public boolean contains(String world) {
            return world != null && stack.contains(world);
        }

        public boolean push(String world) {
            if (world == null || world.isBlank() || stack.contains(world)) {
                return false;
            }
            stack.add(world);
            return true;
        }

        public boolean enter(String world) {
            if (!contains(world)) {
                return false;
            }
            current = world;
            return true;
        }

        public boolean leave() {
            int index = stack.indexOf(current);
            if (index <= 0) {
                return false;
            }
            current = stack.get(index - 1);
            return true;
        }

        public int indexOf(String world) {
            return stack.indexOf(world);
        }
    }

    public static final class Pending {
        public String action = "";
        public String target = "";
        public String from = "";
        public int seasonId;
        public String name = "";
        public Long seed;
        public long createdAt;
    }
}
