package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 注册玩家列表（M2 分组输入，设计文档 §4 步骤 4）。
 * <p>
 * 数据来源（按优先级合并，按最后上线时间倒序）：
 * <ol>
 *   <li>测试注入池（/samsara debug fakeplayers，视为刚上线）；</li>
 *   <li>当前在线玩家（视为最后上线 = 现在）；</li>
 *   <li>{@code usercache.json}（expiresOn ≈ 最后登录 + 3 个月，兼做 name↔uuid 解析）；</li>
 *   <li>{@code players/data/&lt;uuid&gt;.dat}（1.19 起已无 lastPlayed，仅贡献 uuid 存在性）。</li>
 * </ol>
 * <p>
 * 单例：fakeplayers 注入池是全局状态，必须跨命令共享（设计规范 §6）。
 */
public class PlayerRegistry {
    private static final Gson GSON = new Gson();
    private static final Type USER_CACHE_TYPE = new TypeToken<List<Map<String, String>>>() { }.getType();
    private static final DateTimeFormatter EXPIRES_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss Z");

    /** 单个注册玩家 */
    public static class RegPlayer {
        public final String name;
        public final UUID uuid;
        public final long lastLoginMs;
        /**
         * {@link #name} 是否来自真实来源（在线 / 注入池 / usercache）。
         * <p>
         * 仅 {@code players/data/<uuid>.dat} 存在、但 usercache 里已经没有记录时，
         * 只能生成 {@code 玩家-<uuid 前 8 位>} 这样的占位名；这种名字不是玩家真实 ID，
         * 不能用于命令（也不是合法的 {@code word()} 参数），补全时应跳过。
         */
        public final boolean nameKnown;

        public RegPlayer(String name, UUID uuid, long lastLoginMs) {
            this(name, uuid, lastLoginMs, true);
        }

        public RegPlayer(String name, UUID uuid, long lastLoginMs, boolean nameKnown) {
            this.name = name;
            this.uuid = uuid;
            this.lastLoginMs = lastLoginMs;
            this.nameKnown = nameKnown;
        }
    }

    private static PlayerRegistry instance;
    /** 测试注入池：名字 → 离线 UUID（fakeplayers 注入，分组时可被选中；假的最后上线 = now） */
    private final Map<String, UUID> injected = new LinkedHashMap<>();

    private PlayerRegistry() {
    }

    public static synchronized PlayerRegistry getInstance() {
        if (instance == null) {
            instance = new PlayerRegistry();
        }
        return instance;
    }

    /** 注入一组测试玩家（名 → 离线 UUID，规则与 /samsara debug fakeplayers 一致） */
    public synchronized void inject(List<String> names) {
        for (String name : names) {
            injected.put(name, offlineUuid(name));
        }
    }

    /** 只注入尚未存在的名字（重复注入不覆盖） */
    public synchronized void injectIfAbsent(List<String> names) {
        for (String name : names) {
            injected.putIfAbsent(name, offlineUuid(name));
        }
    }

    /** 清空注入池 */
    public synchronized void clearInjected() {
        injected.clear();
    }

    public synchronized int injectedCount() {
        return injected.size();
    }

    /** 注入池里的玩家名（内存快照；供 Tab 补全，不涉及文件 IO）。 */
    public synchronized List<String> injectedNames() {
        return List.copyOf(injected.keySet());
    }

    /**
     * 构建注册玩家列表：注入池 + 在线玩家 + usercache + playerdata，
     * 按最后上线时间倒序（未知名取 0，在线/注入取 now）。
     */
    public synchronized List<RegPlayer> read(MinecraftServer server) {
        Map<UUID, RegPlayer> byUuid = new HashMap<>();
        long now = System.currentTimeMillis();

        // 1) 测试注入池
        for (Map.Entry<String, UUID> e : injected.entrySet()) {
            byUuid.put(e.getValue(), new RegPlayer(e.getKey(), e.getValue(), now));
        }
        // 2) 在线玩家
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            UUID u = p.getUUID();
            byUuid.put(u, new RegPlayer(p.getGameProfile().name(), u, now));
        }
        // 3) usercache.json（name/uuid/expiresOn 三合一）
        Map<String, Long> nameLastLogin = new HashMap<>();
        Map<UUID, String> uuidName = new HashMap<>();
        Map<UUID, Long> uuidLastLogin = new HashMap<>();
        try {
            // 26.x 的 usercache.json 在服务端根目录，不在存档目录；旧版本/特殊布局再回退到存档目录。
            Path uc = server.getServerDirectory().resolve("usercache.json");
            if (!Files.exists(uc)) {
                uc = server.getWorldPath(LevelResource.ROOT).resolve("usercache.json");
            }
            if (Files.exists(uc)) {
                List<Map<String, String>> list = GSON.fromJson(Files.readString(uc, StandardCharsets.UTF_8), USER_CACHE_TYPE);
                if (list != null) {
                    for (Map<String, String> row : list) {
                        String name = row.get("name");
                        String uuidStr = row.get("uuid");
                        if (name == null || uuidStr == null) {
                            continue;
                        }
                        UUID u;
                        try {
                            u = UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException ex) {
                            continue;
                        }
                        long login = parseExpiresOn(row.get("expiresOn"));
                        nameLastLogin.putIfAbsent(name, login);
                        uuidName.putIfAbsent(u, name);
                        uuidLastLogin.putIfAbsent(u, login);
                    }
                }
            }
        } catch (Exception e) {
            // usercache 解析失败不阻断分组（降级为只用在线+注入）
        }
        for (Map.Entry<UUID, String> e : uuidName.entrySet()) {
            byUuid.putIfAbsent(e.getKey(), new RegPlayer(e.getValue(), e.getKey(),
                    uuidLastLogin.getOrDefault(e.getKey(), 0L)));
        }
        // 4) playerdata/<uuid>.dat（仅 uuid 存在性；名字/登录时间回退 usercache）
        try {
            Path pd = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
            if (Files.isDirectory(pd)) {
                try (var stream = Files.list(pd)) {
                    for (Path f : (Iterable<Path>) stream::iterator) {
                        String fn = f.getFileName().toString();
                        if (!fn.endsWith(".dat") || fn.endsWith("_old")) {
                            continue;
                        }
                        String uuidStr = fn.substring(0, fn.length() - 4);
                        UUID u;
                        try {
                            u = UUID.fromString(uuidStr);
                        } catch (IllegalArgumentException ex) {
                            continue;
                        }
                        if (!byUuid.containsKey(u)) {
                            String name = uuidName.get(u);
                            long login = uuidLastLogin.getOrDefault(u, 0L);
                            byUuid.put(u, new RegPlayer(
                                    name != null ? name : "玩家-" + uuidStr.substring(0, 8),
                                    u, login, name != null));
                        }
                    }
                }
            }
        } catch (IOException e) {
            // playerdata 目录读取失败不阻断
        }
        List<RegPlayer> result = new ArrayList<>(byUuid.values());
        result.sort(Comparator.comparingLong((RegPlayer p) -> p.lastLoginMs).reversed());
        return result;
    }

    /** 离线 UUID（与 /samsara debug fakeplayers、设计文档 §16 一致） */
    public static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8));
    }

    private static long parseExpiresOn(String s) {
        if (s == null || s.isBlank()) {
            return 0;
        }
        try {
            return OffsetDateTime.parse(s.trim(), EXPIRES_FMT).toInstant().toEpochMilli();
        } catch (Exception e) {
            return 0;
        }
    }
}