package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 世界栈（M7）：管理「栈底 → 栈顶」的世界目录，以及跨世界切换。
 *
 * <p>物理模型：每个栈层是独立的存档目录（{@code world} / {@code world_s1} / …）。
 * MC 服务端一次只能加载一个主存档，所以 {@code enter}/{@code leave}/{@code new-world}
 * 都是「写 {@code server.properties} 的 {@code level-name} → 停服 → 由启动脚本重新开服」。
 * 这保证每层拥有独立种子、独立下界/末地、独立 {@code <存档>/samsara/} 数据。
 *
 * <p>栈清单保存在服务器级 {@code config/samsara/world-stack.json}，不随当前存档走，
 * 否则切换世界后清单会丢失。每个世界的 {@code season.json} 只保存该世界的周目。
 */
public final class WorldStackManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final String FILE_NAME = "world-stack.json";
    private static final int SWITCH_SECONDS = 5;

    private static WorldStackManager instance;

    private final Path file;
    private Manifest manifest = new Manifest();

    private static int switchTicksLeft;
    private static int switchLastSecond = -1;
    private static String switchTarget = "";

    private WorldStackManager() {
        // 世界栈是服务器级状态：不随当前存档走，也不属于可编辑配置
        this.file = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir()
                .resolve("samsara").resolve(FILE_NAME);
        load();
    }

    public static synchronized WorldStackManager getInstance() {
        if (instance == null) {
            instance = new WorldStackManager();
        }
        return instance;
    }

    // ---- 纯数据（可单测） ----

    /** world-stack.json 根结构。 */
    public static final class Manifest {
        /** 栈底 → 栈顶。 */
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
     * SERVER_STARTED：把实际加载的世界登记进栈，并消费上一次切换。
     * 必须在玩家加入前执行（新世界开局要建周目/分组/待传送）。
     */
    public synchronized void onServerStarted(MinecraftServer server) {
        String actual = currentWorldName(server);
        boolean changed = false;
        if (manifest.stack.isEmpty()) {
            manifest.stack.add(actual);
            changed = true;
        }
        if (!manifest.stack.contains(actual)) {
            manifest.stack.add(actual);
            changed = true;
        }
        manifest.current = actual;

        Pending pending = manifest.pending;
        if (pending != null) {
            if (!actual.equals(pending.target)) {
                Samsara.LOGGER.warn("[周目管理 samsara] 世界切换未完成：期望 {}，实际 {}，已清除待执行标记",
                        pending.target, actual);
            } else if ("new-world".equals(pending.action)) {
                SeasonManager.getInstance().onNewWorldBoot(server, pending);
            }
            manifest.pending = null;
            changed = true;
        }
        if (changed) {
            save();
        }
        SeasonManager.getInstance().syncWorldStack(manifest.stack);
        Samsara.LOGGER.info("[周目管理 samsara] 世界栈：{}（当前 {}）",
                String.join(" → ", manifest.stack), manifest.current);
    }

    /** 新世界开局：建周目、分组、传送，然后更新栈顶。 */
    public synchronized void onNewWorldBoot(MinecraftServer server, Pending pending) {
        if (!manifest.stack.contains(pending.target)) {
            manifest.stack.add(pending.target);
        }
        manifest.current = pending.target;
        save();
    }

    // ---- 切换 ----

    /** new-world：为待开始周目创建新世界目录并安排重启。 */
    public synchronized String startNewWorld(MinecraftServer server, Season season) {
        if (manifest.pending != null) {
            return "已有世界切换待执行（目标 " + manifest.pending.target + "），请先重启服务器";
        }
        if (!manifest.isTopCurrent()) {
            return "只有栈顶世界可以开启 new-world（当前 " + manifest.current
                    + "，栈顶 " + manifest.top() + "）";
        }
        String target = newWorldName(server);
        long seed = season.seed != null ? season.seed : new Random().nextLong();
        season.worldName = target;
        season.seed = seed;

        manifest.pending = new Pending("new-world", target, manifest.current);
        manifest.pending.seasonId = season.id;
        manifest.pending.name = season.name;
        manifest.pending.seed = seed;
        save();

        String result = writeLevelName(server, target, seed);
        if (result != null) {
            manifest.pending = null;
            save();
            return result;
        }
        broadcast(server, "🌍 正在创建新世界「" + target + "」（种子 " + seed
                + "）· 旧世界保留在世界栈中 · 服务器将在 " + SWITCH_SECONDS
                + " 秒后停服，请启动脚本重新开服");
        beginHaltCountdown();
        return "已安排 new-world 切换：\n· 新世界目录 " + target + "（种子 " + seed + "）\n"
                + "· 旧世界 " + manifest.current + " 保留在栈中\n"
                + "· 服务器将在 " + SWITCH_SECONDS + " 秒后停服；请用启动脚本重新开服\n"
                + "· 重启后自动创建周目 #" + season.id + "「" + season.name + "」并全员迁移";
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
    public synchronized String debugPush(String world) {
        if (world == null || world.isBlank()) {
            return "用法：/samsara debug world push <世界名>";
        }
        if (manifest.contains(world)) {
            return "世界 " + world + " 已在栈中";
        }
        manifest.stack.add(world);
        save();
        return "已压入世界 " + world + "；当前栈：" + String.join(" → ", manifest.stack);
    }

    /** 调试：弹出栈顶（当前世界不能弹出）。 */
    public synchronized String debugPop() {
        if (manifest.stack.isEmpty()) {
            return "世界栈为空";
        }
        if (manifest.top().equals(manifest.current)) {
            return "当前世界就是栈顶，不能弹出；先 enter 到下层世界";
        }
        String removed = manifest.stack.remove(manifest.stack.size() - 1);
        save();
        return "已弹出栈顶世界 " + removed + "；当前栈：" + String.join(" → ", manifest.stack);
    }

    /** 调试：清空栈并只保留当前世界。 */
    public synchronized String debugReset(MinecraftServer server) {
        String actual = currentWorldName(server);
        manifest.stack = new ArrayList<>();
        manifest.stack.add(actual);
        manifest.current = actual;
        manifest.pending = null;
        save();
        return "世界栈已重置：仅保留当前世界 " + actual;
    }

    // ---- 展示 ----

    public synchronized String listText() {
        if (manifest.stack.isEmpty()) {
            return "世界栈为空（服务器启动后会自动登记当前世界）";
        }
        StringBuilder sb = new StringBuilder("世界栈（栈底 → 栈顶）：");
        for (int i = 0; i < manifest.stack.size(); i++) {
            String world = manifest.stack.get(i);
            sb.append("\n").append(i + 1).append(". ").append(world);
            if (world.equals(manifest.current)) {
                sb.append("  ← 当前");
            }
            if (i == manifest.stack.size() - 1) {
                sb.append("  [栈顶]");
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
        StringBuilder sb = new StringBuilder("世界栈信息：");
        sb.append("\n· 实际加载：").append(actual);
        sb.append("\n· 清单当前：").append(manifest.current);
        sb.append("\n· 栈顶：").append(manifest.top());
        sb.append("\n· 层数：").append(manifest.stack.size());
        if (server.getLevel(net.minecraft.world.level.Level.OVERWORLD) != null) {
            sb.append("\n· 当前种子：")
                    .append(server.getLevel(net.minecraft.world.level.Level.OVERWORLD).getSeed());
        }
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

    /** 找一个未被栈或磁盘占用的新世界名：world_s1、world_s2 … */
    public synchronized String newWorldName(MinecraftServer server) {
        int n = 1;
        while (true) {
            String candidate = "world_s" + n;
            if (!manifest.stack.contains(candidate)
                    && !Files.exists(server.getServerDirectory().resolve(candidate))) {
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
}
