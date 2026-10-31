package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 世界栈：世界文件夹 = 栈层，周目 = 世界内的出生点历史。
 *
 * <p>物理模型：每个栈层是服务器根目录下的独立存档文件夹（{@code world} / {@code world_s1} / …），
 * 拥有独立种子、独立下界/末地、独立 {@code <世界>/samsara/}。MC 服务端一次只能加载一个主存档，
 * 所以 {@code enter}/{@code leave}/{@code reset new} 都是「写 {@code server.properties} 的
 * {@code level-name} → 停服 → 由启动脚本重新开服」。
 *
 * <p><b>权威元数据在世界上，不在服务器缓存里</b>：
 * <ul>
 *   <li>{@code <世界>/samsara/stack.json}：{@code index}/{@code parent}/{@code worldId}/{@code createdAt}；</li>
 *   <li>{@code <世界>/samsara/spawn-history.json}：出生点历史（见 {@link SpawnHistory}）；</li>
 *   <li>{@code <服务器根>/samsara/world-stack.json}：可重建缓存（栈顺序 + 当前指针 + pending）。</li>
 * </ul>
 *
 * <p>启动时扫描所有含 {@code level.dat} 的文件夹：有 {@code stack.json} 的按 index 排序，
 * 没有的按「旧缓存顺序 → 目录名自然顺序」一次性分配。分配后顺序不再因最后活动时间改变。
 */
public final class WorldStackManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String CACHE_FILE = "world-stack.json";
    private static final String WORLD_META_FILE = "stack.json";
    private static final int SWITCH_SECONDS = 5;
    private static final Pattern WORLD_S = Pattern.compile("world_s(\\d+)");

    private static WorldStackManager instance;

    private final Path file;
    private Manifest manifest = new Manifest();
    /** 扫描时加载的世界元数据；不写入服务器缓存，冲突时以世界内文件为准。 */
    private final Map<String, WorldMeta> metas = new LinkedHashMap<>();

    private static int switchTicksLeft;
    private static int switchLastSecond = -1;

    private WorldStackManager() {
        // 世界栈缓存是服务器级状态：不随当前存档走，也不属于可编辑配置
        this.file = FabricLoader.getInstance().getGameDir()
                .resolve("samsara").resolve(CACHE_FILE);
        load();
    }

    public static synchronized WorldStackManager getInstance() {
        if (instance == null) {
            instance = new WorldStackManager();
        }
        return instance;
    }

    // ---- 纯数据（可单测） ----

    /** 世界自己的栈身份（{@code <世界>/samsara/stack.json}）。 */
    public static final class WorldMeta {
        public int schemaVersion = 1;
        /** 0 = 栈底，越大越靠上；-1/excluded = 不参与栈。 */
        public int index;
        /** 创建它的下层世界（审计/回滚用，不决定排序）。 */
        public String parent = "";
        public String worldId = "";
        public long createdAt;
        /** 调试排除：世界仍在磁盘，但不自动入栈。 */
        public boolean excluded;

        public static WorldMeta load(Path worldDir) {
            Path file = worldDir.resolve("samsara").resolve(WORLD_META_FILE);
            if (!Files.exists(file)) {
                return null;
            }
            try {
                WorldMeta meta = GSON.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), WorldMeta.class);
                if (meta != null) {
                    return meta;
                }
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] {} 解析失败，将按目录名重新分配: {}",
                        file, e.getMessage());
            }
            return null;
        }

        public void save(Path worldDir) {
            Path file = worldDir.resolve("samsara").resolve(WORLD_META_FILE);
            try {
                Files.createDirectories(file.getParent());
                Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
                Files.writeString(tmp, GSON.toJson(this), StandardCharsets.UTF_8);
                if (Files.exists(file)) {
                    Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                            StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] {} 写入失败: {}", file, e.getMessage());
            }
        }

        public WorldMeta copy() {
            WorldMeta m = new WorldMeta();
            m.schemaVersion = schemaVersion;
            m.index = index;
            m.parent = parent;
            m.worldId = worldId;
            m.createdAt = createdAt;
            m.excluded = excluded;
            return m;
        }
    }

    /**
     * 自动发现的纯逻辑：输入磁盘上的世界名、旧缓存顺序、已读到的元数据，输出栈顺序 + 规范化元数据。
     * 不读写文件，便于单测覆盖「首次发现、重复 index、缺 index、排除」等场景。
     */
    public static final class Discovery {
        private Discovery() {
        }

        public record Result(List<String> order, Map<String, WorldMeta> metas,
                             List<String> warnings) {
        }

        public static Result assign(List<String> existingNames, List<String> oldStack,
                                    Map<String, WorldMeta> loaded, long now) {
            List<String> warnings = new ArrayList<>();
            List<String> names = new ArrayList<>(new LinkedHashSet<>(existingNames));
            names.sort(WorldStackManager::naturalCompare);

            Map<String, WorldMeta> available = new LinkedHashMap<>();
            for (String name : names) {
                WorldMeta meta = loaded.get(name);
                if (meta != null && !meta.excluded) {
                    available.put(name, meta.copy());
                }
            }

            List<String> indexed = new ArrayList<>();
            List<String> missing = new ArrayList<>();
            for (String name : names) {
                if (available.containsKey(name) && available.get(name).index >= 0) {
                    indexed.add(name);
                } else {
                    missing.add(name);
                }
            }

            Map<String, Integer> oldRank = new LinkedHashMap<>();
            int rank = 0;
            if (oldStack != null) {
                for (String name : oldStack) {
                    if (names.contains(name) && !oldRank.containsKey(name)) {
                        oldRank.put(name, rank++);
                    }
                }
            }
            for (String name : names) {
                oldRank.putIfAbsent(name, 100000 + rank++);
            }

            indexed.sort(Comparator
                    .comparingInt((String n) -> available.get(n).index)
                    .thenComparingInt(n -> oldRank.getOrDefault(n, Integer.MAX_VALUE))
                    .thenComparing(WorldStackManager::naturalCompare));

            // 重复 index：保留排序后的相对顺序，重新连续编号并记录 WARN
            boolean duplicate = false;
            for (int i = 1; i < indexed.size(); i++) {
                if (available.get(indexed.get(i)).index == available.get(indexed.get(i - 1)).index) {
                    duplicate = true;
                    break;
                }
            }
            if (duplicate) {
                warnings.add("世界 stack.json 存在重复 index，已按旧缓存/目录名顺序重新编号");
            }

            missing.sort(Comparator
                    .comparingInt((String n) -> oldRank.getOrDefault(n, Integer.MAX_VALUE))
                    .thenComparing(WorldStackManager::naturalCompare));

            List<String> order = new ArrayList<>(indexed);
            order.addAll(missing);

            Map<String, WorldMeta> out = new LinkedHashMap<>();
            String parent = "";
            for (int i = 0; i < order.size(); i++) {
                String name = order.get(i);
                WorldMeta meta = available.containsKey(name) ? available.get(name) : new WorldMeta();
                if (meta.worldId == null || meta.worldId.isBlank()) {
                    meta.worldId = stableWorldId(name);
                }
                if (meta.createdAt <= 0) {
                    meta.createdAt = now;
                }
                if (meta.parent == null) {
                    meta.parent = "";
                }
                if (meta.parent.isBlank() && i > 0) {
                    meta.parent = order.get(i - 1);
                }
                meta.index = i;
                meta.excluded = false;
                out.put(name, meta);
                parent = name;
            }
            if (parent.isEmpty()) {
                warnings.add("没有发现任何世界文件夹");
            }
            return new Result(order, out, warnings);
        }

        private static String stableWorldId(String name) {
            return UUID.nameUUIDFromBytes(("samsara:" + name).getBytes(StandardCharsets.UTF_8))
                    .toString();
        }
    }

    /** world-stack.json 根结构（缓存；权威数据在各世界 stack.json）。 */
    public static final class Manifest {
        /** 栈底 → 栈顶（缓存，启动时按 stack.json 重建）。 */
        public List<String> stack = new ArrayList<>();
        /** 当前加载的世界目录名；不一定是栈顶（允许回旧世界访问）。 */
        public String current = "";
        /** 全局周目编号游标，避免跨世界 id 冲突。 */
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

        /** 压入新世界；已存在则不重复。 */
        public boolean push(String world) {
            if (world == null || world.isBlank() || stack.contains(world)) {
                return false;
            }
            stack.add(world);
            return true;
        }

        /** 进入栈内任意世界（不改变栈顺序）。 */
        public boolean enter(String world) {
            if (!contains(world)) {
                return false;
            }
            current = world;
            return true;
        }

        /** 返回下一层（栈底方向）；已在栈底返回 false。 */
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

    /** 待执行的世界切换；服务器重启并加载目标世界后消费。 */
    public static final class Pending {
        /** new-world | enter | leave */
        public String action = "";
        public String target = "";
        public String from = "";
        public int seasonId;
        public String name = "";
        public Long seed;
        public long createdAt;

        public Pending() {
        }

        public Pending(String action, String target, String from) {
            this.action = action;
            this.target = target;
            this.from = from;
            this.createdAt = Instant.now().toEpochMilli();
        }
    }

    // ---- 读取/保存 ----

    public synchronized Manifest manifest() {
        return manifest;
    }

    public synchronized List<String> stack() {
        return List.copyOf(manifest.stack);
    }

    public synchronized String current() {
        return manifest.current;
    }

    public synchronized String top() {
        return manifest.top();
    }

    public synchronized boolean isTopCurrent() {
        return manifest.isTopCurrent();
    }

    public synchronized boolean hasPending() {
        return manifest.pending != null;
    }

    public synchronized int nextSeasonId() {
        int id = Math.max(1, manifest.nextSeasonId);
        manifest.nextSeasonId = id + 1;
        save();
        return id;
    }

    /** 把全局游标抬到至少 {@code next}，避免与旧 season.json 的 id 冲突。 */
    public synchronized void ensureNextSeasonId(int next) {
        if (next > manifest.nextSeasonId) {
            manifest.nextSeasonId = next;
            save();
        }
    }

    private void syncNextSeasonId() {
        SeasonData data = SeasonManager.getInstance().data();
        int max = 0;
        if (data != null && data.seasons != null) {
            for (Season s : data.seasons) {
                if (s != null) {
                    max = Math.max(max, s.id);
                }
            }
        }
        ensureNextSeasonId(max + 1);
    }

    public synchronized WorldMeta meta(String world) {
        WorldMeta meta = metas.get(world);
        return meta == null ? null : meta.copy();
    }

    public synchronized int indexOf(String world) {
        WorldMeta meta = metas.get(world);
        if (meta != null) {
            return meta.index;
        }
        return manifest.indexOf(world);
    }

    private synchronized void load() {
        if (!Files.exists(file)) {
            manifest = new Manifest();
            return;
        }
        try {
            String raw = Files.readString(file, StandardCharsets.UTF_8);
            Manifest loaded = GSON.fromJson(raw, Manifest.class);
            if (loaded == null) {
                loaded = new Manifest();
            }
            if (loaded.stack == null) {
                loaded.stack = new ArrayList<>();
            }
            manifest = loaded;
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] world-stack.json 解析失败，使用空栈: {}",
                    e.getMessage());
            manifest = new Manifest();
        }
    }

    private synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            Files.writeString(tmp, GSON.toJson(manifest), StandardCharsets.UTF_8);
            if (Files.exists(file)) {
                Files.copy(file, file.resolveSibling(file.getFileName() + ".bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] world-stack.json 写入失败: {}", e.getMessage());
        }
    }

    // ---- 服务器生命周期 ----

    /**
     * SERVER_STARTED：扫描世界文件夹、重建栈、迁移出生点历史，并消费上一次切换。
     * 必须在玩家加入前执行（新世界开局要建出生点/待传送）。
     */
    public synchronized void onServerStarted(MinecraftServer server) {
        String actual = currentWorldName(server);
        Pending pending = manifest.pending;
        refresh(server, pending != null ? pending.target : null);

        // 出生点历史：新世界等 onNewWorldBoot 设置好世界出生点后再写；其它世界现在就检测。
        if (pending == null || !"new-world".equals(pending.action) || !actual.equals(pending.target)) {
            ensureSpawnHistory(server, actual, true);
        }
        syncNextSeasonId();

        boolean changed = false;
        if (pending != null) {
            if (!actual.equals(pending.target)) {
                Samsara.LOGGER.warn("[周目管理 samsara] 世界切换未完成：期望 {}，实际 {}，已清除待执行标记",
                        pending.target, actual);
            } else if ("new-world".equals(pending.action)) {
                SeasonManager.getInstance().onNewWorldBoot(server, pending);
                ensureSpawnHistory(server, actual, false);
            }
            manifest.pending = null;
            changed = true;
        }
        syncNextSeasonId();
        manifest.current = actual;
        if (changed) {
            save();
        }
        SeasonManager.getInstance().syncWorldStack(manifest.stack);
        Samsara.LOGGER.info("[周目管理 samsara] 世界栈：{}（当前 {}）",
                String.join(" → ", manifest.stack), manifest.current);
    }

    /**
     * 扫描所有世界文件夹并重建栈。{@code preferredTop} 通常是 pending.target：
     * 新世界没有 stack.json 时会被追加到栈顶。
     */
    public synchronized void refresh(MinecraftServer server, String preferredTop) {
        String actual = currentWorldName(server);
        List<String> discovered = scanWorldNames(server);
        if (!discovered.contains(actual)) {
            discovered.add(actual);
        }

        Map<String, WorldMeta> loaded = new LinkedHashMap<>();
        for (String name : discovered) {
            loaded.put(name, WorldMeta.load(worldDir(server, name)));
        }
        // 已被 debug pop/reset 排除的世界不再自动入栈
        List<String> active = new ArrayList<>();
        for (String name : discovered) {
            WorldMeta meta = loaded.get(name);
            if (meta == null || !meta.excluded || name.equals(actual)
                    || name.equals(preferredTop)) {
                active.add(name);
            }
        }

        List<String> oldStack = new ArrayList<>(manifest.stack);
        if (preferredTop != null && !preferredTop.isBlank()) {
            oldStack.remove(preferredTop);
            oldStack.add(preferredTop);
        }

        Discovery.Result result = Discovery.assign(active, oldStack, loaded, System.currentTimeMillis());
        for (String warning : result.warnings()) {
            Samsara.LOGGER.warn("[周目管理 samsara] {}", warning);
        }

        metas.clear();
        metas.putAll(result.metas());
        for (Map.Entry<String, WorldMeta> e : result.metas().entrySet()) {
            WorldMeta current = loaded.get(e.getKey());
            if (current == null || !sameMeta(current, e.getValue())) {
                e.getValue().save(worldDir(server, e.getKey()));
                Samsara.LOGGER.info("[周目管理 samsara] 已写入世界栈身份：{} → index {}（父 {}）",
                        e.getKey(), e.getValue().index, e.getValue().parent);
            }
        }
        manifest.stack = new ArrayList<>(result.order());
        manifest.current = actual;
        save();
    }

    private static boolean sameMeta(WorldMeta a, WorldMeta b) {
        return a.index == b.index && a.excluded == b.excluded
                && java.util.Objects.equals(a.parent, b.parent)
                && java.util.Objects.equals(a.worldId, b.worldId);
    }

    /** 扫描服务器根目录下含 level.dat 的世界文件夹。 */
    public static List<String> scanWorldNames(MinecraftServer server) {
        List<String> names = new ArrayList<>();
        Path root = worldParent(server);
        if (root == null || !Files.isDirectory(root)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            for (Path path : stream) {
                if (!Files.isDirectory(path)) {
                    continue;
                }
                String name = path.getFileName().toString();
                if (name.startsWith(".")) {
                    continue;
                }
                if (!Files.isRegularFile(path.resolve("level.dat"))) {
                    continue;
                }
                if (isReserved(name)) {
                    continue;
                }
                names.add(name);
            }
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 扫描世界文件夹失败: {}", e.getMessage());
        }
        return names;
    }

    private static boolean isReserved(String name) {
        return switch (name) {
            case "backups", "backup", "logs", "config", "mods", "libraries", "versions",
                 "crash-reports", "debug", "samsara" -> true;
            default -> false;
        };
    }

    public synchronized void onNewWorldBoot(MinecraftServer server, Pending pending) {
        if (!manifest.stack.contains(pending.target)) {
            manifest.stack.add(pending.target);
        }
        manifest.current = pending.target;
        save();
    }

    // ---- 切换 ----

    /** 旧命令入口：为待开始周目创建新世界目录并安排重启。 */
    public synchronized String startNewWorld(MinecraftServer server, Season season) {
        if (RewindEngineGuard.running()) {
            return "已有进行中的时间倒流，请先 /samsara debug rewind abort 再操作";
        }
        if (manifest.pending != null) {
            return "已有世界切换待执行（目标 " + manifest.pending.target + "），请先重启服务器";
        }
        if (!manifest.isTopCurrent()) {
            return "只有栈顶世界可以开启新世界（当前 " + manifest.current
                    + "，栈顶 " + manifest.top() + "）";
        }
        String target = newWorldName(server);
        long seed = season.seed != null ? season.seed : new java.util.Random().nextLong();
        season.worldName = target;
        season.seed = seed;
        return scheduleNewWorld(server, target, seed, season.id,
                season.name == null ? "世界 " + target : season.name);
    }

    /**
     * 新流程入口：{@code /samsara reset new [种子]}。
     * 只安排切换；新世界在下次启动时由原版按 level-seed 生成并自动入栈。
     */
    public synchronized String resetNew(MinecraftServer server, Long seed, int seasonId, String name) {
        if (RewindEngineGuard.running()) {
            return "已有进行中的时间倒流，请先 /samsara debug rewind abort 再操作";
        }
        if (manifest.pending != null) {
            return "已有世界切换待执行（目标 " + manifest.pending.target + "），请先重启服务器";
        }
        if (!manifest.isTopCurrent()) {
            return "只有栈顶世界可以开启新世界（当前 " + manifest.current
                    + "，栈顶 " + manifest.top() + "）";
        }
        String target = newWorldName(server);
        long actualSeed = seed != null ? seed : new java.util.Random().nextLong();
        return scheduleNewWorld(server, target, actualSeed, seasonId, name);
    }

    private String scheduleNewWorld(MinecraftServer server, String target, long seed,
                                    int seasonId, String name) {
        manifest.pending = new Pending("new-world", target, manifest.current);
        manifest.pending.seasonId = seasonId;
        manifest.pending.name = name;
        manifest.pending.seed = seed;
        save();

        String error = writeLevelName(server, target, seed);
        if (error != null) {
            manifest.pending = null;
            save();
            return error;
        }
        broadcast(server, "🌍 正在创建新世界「" + target + "」（种子 " + seed
                + "）· 旧世界保留在世界栈中 · 服务器将在 " + SWITCH_SECONDS
                + " 秒后停服，请启动脚本重新开服");
        beginHaltCountdown();
        return "已安排新世界切换：\n· 新世界目录 " + target + "（种子 " + seed + "）\n"
                + "· 旧世界 " + manifest.current + " 保留在栈中\n"
                + "· 服务器将在 " + SWITCH_SECONDS + " 秒后停服；请用启动脚本重新开服\n"
                + "· 重启后新世界自动入栈（index " + (manifest.stack.size()) + "）并迁移民众\n"
                + "· 新世界出生点历史会自动写入第 1 条";
    }

    /** 进入栈内旧世界（不改变栈顺序）。 */
    public synchronized String enter(MinecraftServer server, String target) {
        if (target == null || target.isBlank()) {
            return "用法：/samsara world enter <世界名>";
        }
        if (!manifest.contains(target)) {
            return "世界 " + target + " 不在世界栈中；当前栈：" + String.join(" → ", manifest.stack);
        }
        if (target.equals(manifest.current)) {
            return "已经在世界 " + target + " 中";
        }
        if (manifest.pending != null) {
            return "已有世界切换待执行（目标 " + manifest.pending.target + "），请先重启服务器";
        }
        String old = manifest.current;
        manifest.pending = new Pending("enter", target, old);
        save();
        String error = writeLevelName(server, target, null);
        if (error != null) {
            manifest.pending = null;
            save();
            return error;
        }
        broadcast(server, "🌍 正在切换世界：" + old + " → " + target + " · 服务器将在 "
                + SWITCH_SECONDS + " 秒后停服，请启动脚本重新开服");
        beginHaltCountdown();
        return "已安排进入世界 " + target + "；服务器将在 " + SWITCH_SECONDS + " 秒后停服";
    }

    /** 返回世界栈的下一层（栈底方向）。 */
    public synchronized String leave(MinecraftServer server) {
        int index = manifest.indexOf(manifest.current);
        if (index <= 0) {
            return "已经在栈底世界 " + manifest.current + "，无法继续 leave";
        }
        String target = manifest.stack.get(index - 1);
        String result = enter(server, target);
        return result.replace("进入世界", "返回下层世界");
    }

    /** 调试：把已存在的世界目录压入栈（不切换）。 */
    public synchronized String debugPush(MinecraftServer server, String world) {
        if (world == null || world.isBlank()) {
            return "用法：/samsara debug world push <世界名>";
        }
        Path dir = worldDir(server, world);
        if (!Files.isRegularFile(dir.resolve("level.dat"))) {
            return "目录 " + dir + " 不是有效世界（缺少 level.dat）";
        }
        if (manifest.contains(world)) {
            return "世界 " + world + " 已在栈中";
        }
        WorldMeta meta = WorldMeta.load(dir);
        if (meta == null) {
            meta = new WorldMeta();
            meta.worldId = UUID.nameUUIDFromBytes(("samsara:" + world)
                    .getBytes(StandardCharsets.UTF_8)).toString();
        }
        meta.excluded = false;
        meta.index = manifest.stack.size();
        meta.parent = manifest.top();
        meta.createdAt = meta.createdAt <= 0 ? System.currentTimeMillis() : meta.createdAt;
        meta.save(dir);
        refresh(server, world);
        return "已压入世界 " + world + "；当前栈：" + String.join(" → ", manifest.stack);
    }

    /** 调试：弹出栈顶（当前世界不能弹出；会写 excluded 防止重启自动发现）。 */
    public synchronized String debugPop(MinecraftServer server) {
        if (manifest.stack.isEmpty()) {
            return "世界栈为空";
        }
        if (manifest.top().equals(manifest.current)) {
            return "当前世界就是栈顶，不能弹出；先 enter 到下层世界";
        }
        String removed = manifest.top();
        WorldMeta meta = WorldMeta.load(worldDir(server, removed));
        if (meta != null) {
            meta.excluded = true;
            meta.index = -1;
            meta.save(worldDir(server, removed));
        }
        refresh(server, null);
        return "已弹出栈顶世界 " + removed + "（世界文件夹保留；重启后不会自动入栈）"
                + "；当前栈：" + String.join(" → ", manifest.stack);
    }

    /** 调试：清空栈并只保留当前世界。其它世界写 excluded，避免重启后被自动发现。 */
    public synchronized String debugReset(MinecraftServer server) {
        String actual = currentWorldName(server);
        for (String name : scanWorldNames(server)) {
            if (name.equals(actual)) {
                continue;
            }
            WorldMeta meta = WorldMeta.load(worldDir(server, name));
            if (meta == null) {
                meta = new WorldMeta();
                meta.worldId = UUID.nameUUIDFromBytes(("samsara:" + name)
                        .getBytes(StandardCharsets.UTF_8)).toString();
            }
            meta.excluded = true;
            meta.index = -1;
            meta.save(worldDir(server, name));
        }
        WorldMeta current = WorldMeta.load(worldDir(server, actual));
        if (current == null) {
            current = new WorldMeta();
            current.worldId = UUID.nameUUIDFromBytes(("samsara:" + actual)
                    .getBytes(StandardCharsets.UTF_8)).toString();
        }
        current.excluded = false;
        current.index = 0;
        current.parent = "";
        current.save(worldDir(server, actual));
        manifest.pending = null;
        refresh(server, actual);
        return "世界栈已重置：仅保留当前世界 " + actual + "（其它世界文件夹保留但已排除）";
    }

    // ---- 出生点历史 ----

    /** 读取/初始化当前世界的出生点历史；{@code migrate} 为真时把旧 season.json 迁移进来。 */
    public SpawnHistory ensureSpawnHistory(MinecraftServer server, String worldName, boolean migrate) {
        Path dir = worldDir(server, worldName);
        SpawnHistory history = SpawnHistory.load(dir);
        BlockPos spawn = worldSpawn(server, worldName);
        int x = spawn == null ? 0 : spawn.getX();
        int z = spawn == null ? 0 : spawn.getZ();
        long now = System.currentTimeMillis();
        boolean save = false;
        if (history.isEmpty()) {
            SeasonData legacy = migrate ? SeasonManager.getInstance().data() : null;
            if (legacy != null && legacy.seasons != null && !legacy.seasons.isEmpty()) {
                history = SpawnHistory.fromSeasons(legacy, x, z, now);
                Samsara.LOGGER.info("[周目管理 samsara] 已把 {} 的旧 season.json 出生中心迁移为出生点历史（{} 条）",
                        worldName, history.size());
            } else {
                history.ensureInitial(x, z, now);
            }
            save = true;
        } else {
            save = history.detect(x, z, now) != null;
        }
        if (save) {
            history.save(dir);
        }
        return history;
    }

    public SpawnHistory spawnHistory(MinecraftServer server, String worldName) {
        return SpawnHistory.load(worldDir(server, worldName));
    }

    public SpawnHistory currentSpawnHistory(MinecraftServer server) {
        return spawnHistory(server, currentWorldName(server));
    }

    private static BlockPos worldSpawn(MinecraftServer server, String worldName) {
        if (!worldName.equals(currentWorldName(server))) {
            return null;
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        return overworld == null ? null : overworld.getRespawnData().pos();
    }

    // ---- 展示 ----

    public synchronized String listText() {
        if (manifest.stack.isEmpty()) {
            return "世界栈为空（服务器启动后会自动扫描世界文件夹）";
        }
        StringBuilder sb = new StringBuilder("世界栈（栈底 → 栈顶）：");
        for (int i = 0; i < manifest.stack.size(); i++) {
            String world = manifest.stack.get(i);
            sb.append("\n").append(i).append(". ").append(world);
            if (world.equals(manifest.current)) {
                sb.append("  ← 当前");
            }
            if (i == manifest.stack.size() - 1) {
                sb.append("  [栈顶]");
            }
            WorldMeta meta = metas.get(world);
            if (meta != null && !meta.parent.isBlank()) {
                sb.append("  (父 ").append(meta.parent).append(")");
            }
        }
        if (manifest.pending != null) {
            sb.append("\n待执行：").append(manifest.pending.action)
                    .append(" → ").append(manifest.pending.target);
        }
        return sb.toString();
    }

    public synchronized String infoText(MinecraftServer server) {
        String actual = currentWorldName(server);
        SpawnHistory history = spawnHistory(server, actual);
        StringBuilder sb = new StringBuilder("世界栈信息：");
        sb.append("\n· 实际加载：").append(actual);
        sb.append("\n· 清单当前：").append(manifest.current);
        sb.append("\n· 栈顶：").append(manifest.top());
        sb.append("\n· 层数：").append(manifest.stack.size());
        WorldMeta meta = metas.get(actual);
        if (meta != null) {
            sb.append("\n· 当前栈位：index ").append(meta.index)
                    .append("（父 ").append(meta.parent.isBlank() ? "无" : meta.parent).append("）");
        }
        ServerLevel overworld = server.getLevel(Level.OVERWORLD);
        if (overworld != null) {
            sb.append("\n· 当前种子：").append(overworld.getSeed());
        }
        sb.append("\n· ").append(history.summaryText());
        if (manifest.pending != null) {
            sb.append("\n· 待执行：").append(manifest.pending.action)
                    .append(" → ").append(manifest.pending.target)
                    .append("（创建于 ")
                    .append(Instant.ofEpochMilli(manifest.pending.createdAt)).append("）");
        }
        if (!actual.equals(manifest.current)) {
            sb.append("\n⚠ 实际世界与清单不一致，重启后会自动校正");
        }
        return sb.toString();
    }

    // ---- 内部 ----

    /** 当前实际加载的世界目录名。 */
    public static String currentWorldName(MinecraftServer server) {
        // ROOT 的 id 为空，不同版本 getWorldPath(ROOT) 可能返回 "."；level.dat 的父目录才是世界目录
        Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        Path dir = levelDat == null ? null : levelDat.getParent();
        Path name = dir == null ? null : dir.getFileName();
        return name == null ? "world" : name.toString();
    }

    /** 存档根（专用服务器通常是服务器根；单人存档是 saves/）。 */
    public static Path worldParent(MinecraftServer server) {
        String current = currentWorldName(server);
        Path serverDir = server.getServerDirectory();
        if (serverDir != null && Files.isRegularFile(serverDir.resolve(current).resolve("level.dat"))) {
            return serverDir;
        }
        Path levelDat = server.getWorldPath(LevelResource.LEVEL_DATA_FILE);
        if (levelDat != null && levelDat.getParent() != null
                && levelDat.getParent().getParent() != null) {
            return levelDat.getParent().getParent();
        }
        return serverDir;
    }

    public static Path worldDir(MinecraftServer server, String worldName) {
        return worldParent(server).resolve(worldName).toAbsolutePath().normalize();
    }

    /** 找一个未被栈或磁盘占用的新世界名：world_s1、world_s2 … */
    public synchronized String newWorldName(MinecraftServer server) {
        int n = 1;
        while (true) {
            String candidate = "world_s" + n;
            if (!manifest.stack.contains(candidate)
                    && !Files.exists(worldParent(server).resolve(candidate))) {
                return candidate;
            }
            n++;
        }
    }

    /** 修改 server.properties 的 level-name/level-seed；成功返回 null。 */
    private String writeLevelName(MinecraftServer server, String target, Long seed) {
        Path props = server.getServerDirectory().resolve("server.properties");
        try {
            List<String> lines = Files.exists(props)
                    ? new ArrayList<>(Files.readAllLines(props, StandardCharsets.UTF_8))
                    : new ArrayList<>();
            boolean nameSet = false;
            boolean seedSet = false;
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i);
                if (line.startsWith("level-name=")) {
                    lines.set(i, "level-name=" + target);
                    nameSet = true;
                } else if (line.startsWith("level-seed=")) {
                    if (seed != null) {
                        lines.set(i, "level-seed=" + seed);
                    }
                    seedSet = true;
                }
            }
            if (!nameSet) {
                lines.add("level-name=" + target);
            }
            if (!seedSet && seed != null) {
                lines.add("level-seed=" + seed);
            }
            if (Files.exists(props)) {
                Files.copy(props, props.resolveSibling("server.properties.bak"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(props, lines, StandardCharsets.UTF_8);
            return null;
        } catch (IOException e) {
            return "写入 server.properties 失败：" + e.getMessage();
        }
    }

    private static void broadcast(MinecraftServer server, String message) {
        Component component = Component.literal(message);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(component);
        }
        Samsara.LOGGER.info("[周目管理 samsara] {}", message);
    }

    private static void beginHaltCountdown() {
        switchTicksLeft = SWITCH_SECONDS * 20;
        switchLastSecond = -1;
    }

    /** 每个服务器 tick 调用；世界切换倒计时结束走正常停服流程。 */
    public static synchronized void tick(MinecraftServer server) {
        if (switchTicksLeft <= 0) {
            return;
        }
        switchTicksLeft--;
        int seconds = Math.max(0, (switchTicksLeft + 19) / 20);
        if (seconds > 0 && seconds != switchLastSecond) {
            switchLastSecond = seconds;
            broadcast(server, "🌍 世界切换倒计时 " + seconds + " 秒");
        }
        if (switchTicksLeft <= 0) {
            server.halt(false);
        }
    }

    /** 自然顺序：world → world_s1 → world_s2 … → 其它字典序。 */
    static int naturalCompare(String a, String b) {
        if (a.equals(b)) {
            return 0;
        }
        if (a.equals("world")) {
            return -1;
        }
        if (b.equals("world")) {
            return 1;
        }
        Matcher ma = WORLD_S.matcher(a);
        Matcher mb = WORLD_S.matcher(b);
        boolean na = ma.matches();
        boolean nb = mb.matches();
        if (na && nb) {
            int ia = Integer.parseInt(ma.group(1));
            int ib = Integer.parseInt(mb.group(1));
            if (ia != ib) {
                return Integer.compare(ia, ib);
            }
            return a.compareTo(b);
        }
        if (na != nb) {
            return na ? -1 : 1;
        }
        return a.compareTo(b);
    }

    /**
     * 避免 WorldStackManager 直接依赖 RewindEngine 造成类初始化环（单测只加载本类时也能用）。
     */
    private static final class RewindEngineGuard {
        private RewindEngineGuard() {
        }

        static boolean running() {
            return com.shenqianye.samsara.rewind.RewindEngine.getInstance().running();
        }
    }
}
