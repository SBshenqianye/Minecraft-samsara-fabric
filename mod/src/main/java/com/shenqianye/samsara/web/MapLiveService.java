package com.shenqianye.samsara.web;

import com.shenqianye.samsara.activity.MapActivityRecorder;
import com.shenqianye.samsara.season.StackPaths;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 地图实时层：在线玩家、已加载实体、短轨迹与最近方块操作。
 *
 * <p>轨迹只存在内存中（每个实体最多 48 个点、约 2 分钟），不写存档；实体枚举只覆盖
 * 当前已加载的 {@link ServerLevel}，未加载区块中的实体不可见。HTTP 线程通过
 * {@link MinecraftServer#execute} 切到服务器线程读取，避免并发访问实体。
 */
public final class MapLiveService {
    private static final int MAX_ENTITIES = 1200;
    private static final int MAX_TRAIL_POINTS = 48;
    private static final long TRAIL_SAMPLE_MS = 700;
    private static final long TRAIL_FORGET_MS = 120_000;
    private static final double MIN_MOVE = 0.6;

    private final MinecraftServer server;
    private final Map<String, Trail> trails = new HashMap<>();
    private long lastSampleAt;

    private static final int MAX_CHAT = 120;
    private static final Deque<Map<String, Object>> CHAT = new ArrayDeque<>();
    private static final int MAX_SYSTEM = 80;
    private static final Deque<Map<String, Object>> SYSTEM = new ArrayDeque<>();
    private static final java.util.Set<String> RARE_ENTITIES = java.util.Set.of(
            "ender_dragon", "wither", "elder_guardian", "warden", "evoker", "illusioner",
            "ravager", "piglin_brute", "breeze", "bogged", "creaking", "sniffer",
            "armadillo", "axolotl", "mooshroom", "panda", "polar_bear", "goat");
    private static final java.util.regex.Pattern GAMEPLAY = java.util.regex.Pattern.compile(
            "(?i)(joined the game|left the game|slain|killed|shot|blew up|fell|drowned|"
                    + "burned|wither|starved|suffocated|magic|advanced|advancement|challenge|"
                    + "goal|unlocked|raid|victory|tamed|bred|sleeping|respawned)");

    /** 记录一条玩法层面的系统提示（死亡/加入/离开/进度），过滤命令反馈。 */
    public static void recordSystem(String text) {
        if (text == null || text.isBlank()) return;
        String value = text.strip();
        String lower = value.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("/") || lower.contains("commands.")
                || lower.contains("unknown or incomplete command")
                || lower.contains("usage:") || lower.contains("permission")) {
            return;
        }
        if (!GAMEPLAY.matcher(value).find()) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("timeMs", System.currentTimeMillis());
        item.put("text", value.length() > 300 ? value.substring(0, 300) : value);
        synchronized (SYSTEM) {
            SYSTEM.addLast(item);
            while (SYSTEM.size() > MAX_SYSTEM) SYSTEM.removeFirst();
        }
    }

    private static List<Map<String, Object>> recentSystem() {
        synchronized (SYSTEM) {
            List<Map<String, Object>> out = new ArrayList<>(SYSTEM.size());
            for (Map<String, Object> item : SYSTEM) out.add(new LinkedHashMap<>(item));
            return out;
        }
    }

    /** 记录一条玩家发言，供实时层展示（不写盘；完整记录在轨迹 .evt）。 */
    public static void recordChat(ServerPlayer player, String text) {
        if (player == null || text == null || text.isBlank()) return;
        String stackId = StackPaths.layerIdOf(player.level().dimension());
        String dim = StackPaths.dimensionOf(player.level().dimension());
        if (stackId == null || dim == null) return;
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("timeMs", System.currentTimeMillis());
        item.put("name", player.getName().getString());
        item.put("text", text.length() > 300 ? text.substring(0, 300) : text);
        item.put("stackId", stackId);
        item.put("dimension", dim);
        item.put("dimensionId", player.level().dimension().identifier().toString());
        item.put("x", round(player.getX()));
        item.put("y", round(player.getY()));
        item.put("z", round(player.getZ()));
        synchronized (CHAT) {
            CHAT.addLast(item);
            while (CHAT.size() > MAX_CHAT) CHAT.removeFirst();
        }
    }

    private static List<Map<String, Object>> recentChat() {
        synchronized (CHAT) {
            List<Map<String, Object>> out = new ArrayList<>(CHAT.size());
            for (Map<String, Object> item : CHAT) out.add(new LinkedHashMap<>(item));
            return out;
        }
    }

    public MapLiveService(MinecraftServer server) {
        this.server = server;
    }

    public Map<String, Object> snapshot(boolean includeSensitive) {
        if (server == null) {
            return empty();
        }
        try {
            if (server.isSameThread()) {
                return snapshotOnServer(includeSensitive);
            }
            CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
            server.execute(() -> {
                try {
                    future.complete(snapshotOnServer(includeSensitive));
                } catch (Throwable t) {
                    future.completeExceptionally(t);
                }
            });
            return future.get(4, TimeUnit.SECONDS);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.warn("[web] 地图实时层读取失败: {}", e.toString());
            return empty();
        }
    }

    public Map<String, Object> empty() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("generatedAt", System.currentTimeMillis());
        out.put("players", List.of());
        out.put("entities", List.of());
        out.put("operations", List.of());
        out.put("chat", List.of());
        out.put("system", List.of());
        out.put("truncated", false);
        return out;
    }

    private Map<String, Object> snapshotOnServer(boolean includeSensitive) {
        long now = System.currentTimeMillis();
        boolean sample = now - lastSampleAt >= TRAIL_SAMPLE_MS;
        if (sample) lastSampleAt = now;
        List<Map<String, Object>> players = new ArrayList<>();
        List<Map<String, Object>> entities = new ArrayList<>();
        Map<String, Long> seen = new HashMap<>();

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            String stackId = StackPaths.layerIdOf(player.level().dimension());
            String dim = StackPaths.dimensionOf(player.level().dimension());
            if (stackId == null || dim == null) continue;
            String key = "p:" + player.getUUID();
            seen.put(key, now);
            List<Map<String, Object>> trail = trail(key, player.getX(), player.getZ(), now, sample);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("kind", "player");
            item.put("name", player.getName().getString());
            item.put("type", "minecraft:player");
            item.put("stackId", stackId);
            item.put("dimension", dim);
            item.put("dimensionId", player.level().dimension().identifier().toString());
            item.put("x", round(player.getX()));
            item.put("y", round(player.getY()));
            item.put("z", round(player.getZ()));
            item.put("yaw", round(player.getYRot()));
            item.put("health", round(player.getHealth()));
            item.put("trail", trail);
            if (includeSensitive) item.put("uuid", player.getUUID().toString());
            players.add(item);
        }

        boolean truncated = false;
        for (ServerLevel level : server.getAllLevels()) {
            String stackId = StackPaths.layerIdOf(level.dimension());
            String dim = StackPaths.dimensionOf(level.dimension());
            if (stackId == null || dim == null) continue;
            for (Entity entity : level.getAllEntities()) {
                if (entity instanceof ServerPlayer) continue;
                if (entities.size() >= MAX_ENTITIES) {
                    truncated = true;
                    break;
                }
                String key = "e:" + entity.getUUID();
                seen.put(key, now);
                List<Map<String, Object>> trail = trail(key, entity.getX(), entity.getZ(), now, sample);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("kind", "entity");
                item.put("name", entity.getName().getString());
                item.put("type", BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).toString());
                item.put("category", entity.getType().getCategory().getSerializedName());
                item.put("rarity", rarityOf(entity));
                if (entity.hasCustomName()) {
                    item.put("customName", entity.getName().getString());
                }
                item.put("stackId", stackId);
                item.put("dimension", dim);
                item.put("dimensionId", level.dimension().identifier().toString());
                item.put("x", round(entity.getX()));
                item.put("y", round(entity.getY()));
                item.put("z", round(entity.getZ()));
                item.put("yaw", round(entity.getYRot()));
                if (entity instanceof LivingEntity living) {
                    item.put("health", round(living.getHealth()));
                }
                item.put("trail", trail);
                if (includeSensitive) item.put("uuid", entity.getUUID().toString());
                entities.add(item);
            }
            if (truncated) break;
        }
        if (sample) {
            forgetOld(seen, now);
        }
        players.sort(Comparator.comparing(item -> String.valueOf(item.get("name")),
                String.CASE_INSENSITIVE_ORDER));
        entities.sort(Comparator.comparing((Map<String, Object> item) -> String.valueOf(item.get("type")))
                .thenComparing(item -> String.valueOf(item.get("name")),
                        String.CASE_INSENSITIVE_ORDER));

        List<Map<String, Object>> operations = MapActivityRecorder.recent(80);
        if (!includeSensitive) {
            for (Map<String, Object> operation : operations) {
                operation.remove("actorUuid");
            }
        }

        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", true);
        out.put("generatedAt", now);
        out.put("players", players);
        out.put("entities", entities);
        out.put("operations", operations);
        out.put("chat", recentChat());
        out.put("system", recentSystem());
        out.put("truncated", truncated);
        return out;
    }

    private List<Map<String, Object>> trail(String key, double x, double z, long now, boolean sample) {
        Trail trail = trails.computeIfAbsent(key, ignored -> new Trail());
        if (sample && (trail.points.isEmpty()
                || Math.hypot(x - trail.lastX, z - trail.lastZ) >= MIN_MOVE
                || now - trail.lastAt >= 3000)) {
            trail.points.addLast(new Point(round(x), round(z), now));
            while (trail.points.size() > MAX_TRAIL_POINTS) trail.points.removeFirst();
            trail.lastX = x;
            trail.lastZ = z;
            trail.lastAt = now;
        } else if (!sample && trail.points.isEmpty()) {
            trail.points.addLast(new Point(round(x), round(z), now));
            trail.lastX = x;
            trail.lastZ = z;
            trail.lastAt = now;
        }
        List<Map<String, Object>> out = new ArrayList<>(trail.points.size());
        for (Point point : trail.points) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("x", point.x);
            item.put("z", point.z);
            item.put("t", point.t);
            out.add(item);
        }
        return out;
    }

    private void forgetOld(Map<String, Long> seen, long now) {
        Iterator<Map.Entry<String, Trail>> it = trails.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Trail> entry = it.next();
            Long lastSeen = seen.get(entry.getKey());
            long last = lastSeen == null ? entry.getValue().lastAt : Math.max(lastSeen, entry.getValue().lastAt);
            if (now - last > TRAIL_FORGET_MS) it.remove();
        }
    }

    private static double round(double value) {
        return Math.round(value * 10.0) / 10.0;
    }

    /** 0 = 玩家/命名/稀有，1 = 敌对，2 = 被动，3 = 其他（掉落物/投射物等）。 */
    private static int rarityOf(Entity entity) {
        if (entity.hasCustomName()) return 0;
        String id = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType()).getPath();
        if (RARE_ENTITIES.contains(id)) return 0;
        String category = entity.getType().getCategory().getSerializedName();
        if ("monster".equals(category)) return 1;
        if ("creature".equals(category) || "water_creature".equals(category)
                || "axolotls".equals(category) || "ambient".equals(category)) return 2;
        return 3;
    }

    private static final class Trail {
        final Deque<Point> points = new ArrayDeque<>();
        double lastX;
        double lastZ;
        long lastAt;
    }

    private record Point(double x, double z, long t) {
    }
}
