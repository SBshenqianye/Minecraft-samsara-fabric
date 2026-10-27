package com.shenqianye.samsara.activity;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.shenqianye.samsara.Samsara;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.stats.ServerStatsCounter;
import net.minecraft.stats.Stats;
import net.minecraft.util.Mth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToIntFunction;

/**
 * 静默活动记录 —— 只写数据，不做任何统计、分类或判定，也不提供命令。
 * <p>
 * 目的：为事后的 Web 统计表保留「谁在什么时候登录、在线多久、这一局干了什么」。
 * 数据按天落在 {@code config/samsara/activity/YYYY-MM-DD.jsonl}（一行一个 JSON 事件），
 * 由外部脚本/面板自行聚合；模组内不再读取这些文件。
 *
 * <h3>事件</h3>
 * <table border="1" cellpadding="4">
 *   <tr><th>ev</th><th>时机</th><th>字段</th></tr>
 *   <tr><td>{@code join}</td><td>玩家加入</td><td>uuid · name · dim · x/y/z · mode</td></tr>
 *   <tr><td>{@code leave}</td><td>玩家退出（或停服时补写）</td><td>uuid · name · dim · x/y/z · mode · onlineSec · reason? · stats?</td></tr>
 *   <tr><td>{@code server_start}</td><td>服务器启动完成</td><td>—</td></tr>
 *   <tr><td>{@code server_stop}</td><td>停服窗口（补写所有在线玩家后）</td><td>open</td></tr>
 * </table>
 * <p>
 * {@code stats} 是本次会话的增量（退出时的原版统计 − 加入时的原版统计），缺省键表示 0，
 * 单位沿用原版：{@code playSec} 秒、{@code *Cm} 厘米、其余为次数。
 *
 * <h3>开销</h3>
 * 只在加入/退出/启停各写一行，<b>没有</b> tick 轮询或周期采样；加入与退出各做一次注册表汇总
 * （方块/物品统计求和），量级为万级 Map 查询，可忽略。典型写入量：每个会话 2 行、约 0.5 KB。
 * <p>
 * 崩溃语义：只有 {@code join} 没有 {@code leave} 表示上次会话被强杀/崩溃截断，
 * 消费方应以 {@code server_stop} 或下一条 {@code server_start} 为界自行截断。
 */
public final class ActivityRecorder {
    /** 数据格式版本（写在每行，便于 Web 侧演进解析）。 */
    public static final int SCHEMA_VERSION = 1;

    private static ActivityRecorder instance;
    private static final Gson GSON = new Gson();

    private final Path dir;
    private final Map<UUID, Session> sessions = new HashMap<>();

    private ActivityRecorder() {
        dir = FabricLoader.getInstance().getConfigDir().resolve("samsara").resolve("activity");
    }

    public static synchronized ActivityRecorder getInstance() {
        if (instance == null) {
            instance = new ActivityRecorder();
        }
        return instance;
    }

    /** 注册全部采集钩子（主入口调用一次）。 */
    public static void init() {
        ActivityRecorder r = getInstance();
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> r.onJoin(handler.getPlayer()));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> r.onLeave(handler.getPlayer(), null));
        ServerLifecycleEvents.SERVER_STARTED.register(r::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(r::onServerStopping);
    }

    // ---- 事件 ----

    /** 玩家加入：记一条 {@code join}，并保存本次会话的统计基线。 */
    public synchronized void onJoin(ServerPlayer player) {
        try {
            long now = System.currentTimeMillis();
            UUID uuid = player.getUUID();
            // 同一 UUID 未收到 DISCONNECT 又 JOIN（重连）：先闭合上一段，避免出现两个 join 一个 leave
            if (sessions.containsKey(uuid)) {
                onLeave(player, "rejoin");
            }
            String name = player.getGameProfile().name();
            Map<String, Integer> baseline = snapshot(player);
            sessions.put(uuid, new Session(now, name, baseline));

            JsonObject event = event("join", now);
            event.addProperty("uuid", uuid.toString());
            event.addProperty("name", name);
            addLocation(event, player);
            append(event);
        } catch (Exception e) {
            warn("join", e);
        }
    }

    /**
     * 玩家退出：记一条 {@code leave}，含在线时长与本次会话的原版统计增量。
     *
     * @param reason 非空时写入 {@code reason}（停服补写用 {@code server_stop}）
     */
    public synchronized void onLeave(ServerPlayer player, String reason) {
        try {
            Session session = sessions.remove(player.getUUID());
            if (session == null) {
                return; // 未经过 onJoin（极少见），不写残缺记录
            }
            long now = System.currentTimeMillis();
            JsonObject event = event("leave", now);
            event.addProperty("uuid", player.getUUID().toString());
            event.addProperty("name", player.getGameProfile().name());
            addLocation(event, player);
            event.addProperty("onlineSec", Math.max(0, (now - session.startMs) / 1000));
            if (reason != null) {
                event.addProperty("reason", reason);
            }
            event.add("stats", delta(session.stats, snapshot(player)));
            append(event);
        } catch (Exception e) {
            warn("leave", e);
        }
    }

    private synchronized void onServerStarted(MinecraftServer server) {
        sessions.clear();
        append(event("server_start", System.currentTimeMillis()));
    }

    private synchronized void onServerStopping(MinecraftServer server) {
        try {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                onLeave(player, "server_stop");
            }
            // 兜底：会话仍在但玩家已不在列表里，补一条无统计的 leave，避免悬空 join
            long now = System.currentTimeMillis();
            for (Map.Entry<UUID, Session> entry : new ArrayList<>(sessions.entrySet())) {
                Session session = entry.getValue();
                JsonObject event = event("leave", now);
                event.addProperty("uuid", entry.getKey().toString());
                event.addProperty("name", session.name);
                event.addProperty("onlineSec", Math.max(0, (now - session.startMs) / 1000));
                event.addProperty("reason", "server_stop");
                append(event);
            }
            sessions.clear();

            JsonObject stop = event("server_stop", now);
            stop.addProperty("open", 0);
            append(stop);
        } catch (Exception e) {
            warn("server_stop", e);
        }
    }

    // ---- 原版统计快照 ----

    /**
     * 读取玩家当前的原版累计统计。全部来自内存中的 {@link ServerStatsCounter}，无磁盘 I/O。
     * 顺序固定，便于 delta() 逐键相减。
     */
    private static Map<String, Integer> snapshot(ServerPlayer player) {
        ServerStatsCounter stats = player.getStats();
        Map<String, Integer> out = new LinkedHashMap<>();
        out.put("playTicks", custom(stats, Stats.PLAY_TIME));
        out.put("walkCm", custom(stats, Stats.WALK_ONE_CM));
        out.put("sprintCm", custom(stats, Stats.SPRINT_ONE_CM));
        out.put("flyCm", custom(stats, Stats.FLY_ONE_CM));
        out.put("swimCm", custom(stats, Stats.SWIM_ONE_CM));
        out.put("jump", custom(stats, Stats.JUMP));
        out.put("deaths", custom(stats, Stats.DEATHS));
        out.put("mobKills", custom(stats, Stats.MOB_KILLS));
        out.put("playerKills", custom(stats, Stats.PLAYER_KILLS));
        out.put("fish", custom(stats, Stats.FISH_CAUGHT));
        out.put("bred", custom(stats, Stats.ANIMALS_BRED));
        out.put("sleep", custom(stats, Stats.SLEEP_IN_BED));
        out.put("chest", custom(stats, Stats.OPEN_CHEST));
        out.put("enderChest", custom(stats, Stats.OPEN_ENDERCHEST));
        out.put("trade", custom(stats, Stats.TRADED_WITH_VILLAGER));
        out.put("enchant", custom(stats, Stats.ENCHANT_ITEM));
        // 分类统计没有"总计"接口，按注册表求和（仅上下线各一次）
        out.put("blocksMined", sum(BuiltInRegistries.BLOCK, b -> stats.getValue(Stats.BLOCK_MINED, b)));
        out.put("itemsUsed", sum(BuiltInRegistries.ITEM, i -> stats.getValue(Stats.ITEM_USED, i)));
        out.put("itemsCrafted", sum(BuiltInRegistries.ITEM, i -> stats.getValue(Stats.ITEM_CRAFTED, i)));
        out.put("itemsBroken", sum(BuiltInRegistries.ITEM, i -> stats.getValue(Stats.ITEM_BROKEN, i)));
        out.put("itemsPicked", sum(BuiltInRegistries.ITEM, i -> stats.getValue(Stats.ITEM_PICKED_UP, i)));
        out.put("itemsDropped", sum(BuiltInRegistries.ITEM, i -> stats.getValue(Stats.ITEM_DROPPED, i)));
        return out;
    }

    private static int custom(ServerStatsCounter stats, Identifier id) {
        return stats.getValue(Stats.CUSTOM, id);
    }

    private static <T> int sum(Iterable<T> values, ToIntFunction<T> getter) {
        int total = 0;
        for (T value : values) {
            total += getter.applyAsInt(value);
        }
        return total;
    }

    /** 本次会话增量：只输出非 0 键（缺省即 0）；playTicks 折算为 playSec。 */
    private static JsonObject delta(Map<String, Integer> before, Map<String, Integer> after) {
        JsonObject out = new JsonObject();
        for (Map.Entry<String, Integer> entry : after.entrySet()) {
            int diff = Math.max(0, entry.getValue() - before.getOrDefault(entry.getKey(), 0));
            if ("playTicks".equals(entry.getKey())) {
                out.addProperty("playSec", diff / 20);
            } else if (diff > 0) {
                out.addProperty(entry.getKey(), diff);
            }
        }
        return out;
    }

    // ---- 写入 ----

    private static JsonObject event(String type, long epochMs) {
        JsonObject event = new JsonObject();
        event.addProperty("v", SCHEMA_VERSION);
        event.addProperty("t", epochMs);
        event.addProperty("ev", type);
        return event;
    }

    private static void addLocation(JsonObject event, ServerPlayer player) {
        event.addProperty("dim", player.level().dimension().identifier().toString());
        event.addProperty("x", Mth.floor(player.getX()));
        event.addProperty("y", Mth.floor(player.getY()));
        event.addProperty("z", Mth.floor(player.getZ()));
        event.addProperty("mode", player.gameMode().getSerializedName());
    }

    private synchronized void append(JsonObject event) {
        try {
            Files.createDirectories(dir);
            Path file = dir.resolve(fileName(event.get("t").getAsLong()));
            Files.writeString(file, GSON.toJson(event) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            warn("write", e);
        }
    }

    private static String fileName(long epochMs) {
        LocalDate day = LocalDate.ofInstant(Instant.ofEpochMilli(epochMs), ZoneId.systemDefault());
        return day + ".jsonl";
    }

    private static void warn(String stage, Exception e) {
        Samsara.LOGGER.warn("[周目管理 samsara] 活动记录（{}）失败: {}", stage, e.toString());
    }

    /** 一次登录会话：加入时刻 + 统计基线。 */
    private static final class Session {
        final long startMs;
        final String name;
        final Map<String, Integer> stats;

        Session(long startMs, String name, Map<String, Integer> stats) {
            this.startMs = startMs;
            this.name = name;
            this.stats = stats;
        }
    }
}
