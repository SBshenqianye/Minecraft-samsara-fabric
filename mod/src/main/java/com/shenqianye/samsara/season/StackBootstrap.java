package com.shenqianye.samsara.season;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.shenqianye.samsara.Samsara;
import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

/**
 * 新世界/栈顶切换的启动前准备。
 *
 * <p>{@code reset new} 只写 {@code world/samsara/pending-new.json} 并停服；
 * 下一次 mod init（世界尚未打开）时把当前栈顶的 dimensions/元数据搬到
 * {@code world/samsara/stack/stack_<n>/} 与 {@code world/dimensions/samsara/stack_<n>/}，
 * 然后让原版用新的 {@code level-seed}/{@code level-type} 在 {@code world} 生成新栈顶。
 * 只有目录 rename + 少量元数据复制，不复制区块。
 */
public final class StackBootstrap {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final int SWITCH_SECONDS = 5;
    private static int ticksLeft;
    private static int lastSecond = -1;

    private StackBootstrap() {
    }

    public static final class PendingNew {
        public String phase = "demote"; // demote | generating
        public int oldTopIndex;
        public long oldSeed;
        public long newSeed;
        public String levelType = "normal";
        public String generatorSettings = "";
        public boolean isolateData;
        public long createdAt;
    }

    private static Path pendingFile(Path worldDir) {
        return StackPaths.samsara(worldDir).resolve("pending-new.json");
    }

    /** 命令阶段：写 pending，倒计时停服。 */
    public static synchronized String scheduleNew(MinecraftServer server, Long seed, String levelType,
                                                  String generatorSettings, boolean isolateData) {
        Path worldDir = StackPaths.worldDir(server);
        if (Files.exists(pendingFile(worldDir))) {
            return "已有待执行的新世界生成，请重启服务器让 StackBootstrap 完成";
        }
        StackManifest manifest = StackManifest.load(worldDir);
        if (manifest.layers.isEmpty()) {
            manifest.ensureTop(StackLayer.top(server, server.getWorldGenSettings().options().seed()));
            manifest.save(worldDir);
        }
        StackLayer top = manifest.top();
        PendingNew p = new PendingNew();
        p.oldTopIndex = top.index;
        p.oldSeed = top.seed != 0 ? top.seed : server.getWorldGenSettings().options().seed();
        p.newSeed = seed != null ? seed : new java.util.Random().nextLong();
        p.levelType = levelType == null || levelType.isBlank() ? "normal" : levelType;
        p.generatorSettings = generatorSettings == null ? "" : generatorSettings;
        p.isolateData = isolateData;
        p.createdAt = System.currentTimeMillis();
        save(worldDir, p);
        broadcast(server, "🌍 将把当前世界 " + top.id + " 压入栈层 stack_" + top.index
                + "，并生成新栈顶（种子 " + p.newSeed + " / " + p.levelType + "）· "
                + SWITCH_SECONDS + " 秒后停服");
        ticksLeft = SWITCH_SECONDS * 20;
        lastSecond = -1;
        return "已安排新世界入栈：\n"
                + "· 旧栈顶 " + top.id + " → stack_" + top.index + "\n"
                + "· 新栈顶 world（种子 " + p.newSeed + " · level-type " + p.levelType + "）\n"
                + "· 数据隔离：" + (p.isolateData ? "是（旧 data/minecraft 归档）" : "否（共享 data/minecraft）") + "\n"
                + "· 服务器将在 " + SWITCH_SECONDS + " 秒后停服，请启动脚本重新开服\n"
                + "· 只有目录 rename，不复制区块";
    }

    public static synchronized void tick(MinecraftServer server) {
        if (ticksLeft <= 0) {
            return;
        }
        ticksLeft--;
        int seconds = Math.max(0, (ticksLeft + 19) / 20);
        if (seconds > 0 && seconds != lastSecond) {
            lastSecond = seconds;
            broadcast(server, "🌍 世界切换倒计时 " + seconds + " 秒");
        }
        if (ticksLeft <= 0) {
            server.halt(false);
        }
    }

    /** mod init：在世界打开之前完成 demote（如果 pending 存在）。 */
    public static synchronized void prepare() {
        Path worldDir = StackPaths.worldDir();
        Path file = pendingFile(worldDir);
        if (!Files.exists(file)) {
            return;
        }
        PendingNew p;
        try {
            p = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PendingNew.class);
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] pending-new 解析失败，已忽略: {}", e.getMessage());
            return;
        }
        if (p == null) {
            return;
        }
        if ("demote".equals(p.phase)) {
            try {
                demote(worldDir, p);
                p.phase = "generating";
                save(worldDir, p);
                Samsara.LOGGER.info("[周目管理 samsara] 已把旧栈顶压入 stack_{}，等待新 world 生成",
                        p.oldTopIndex);
            } catch (Exception e) {
                Samsara.LOGGER.error("[周目管理 samsara] 新世界 demote 失败，保留 pending 以便重试: {}",
                        e.getMessage());
            }
        }
    }

    private static void demote(Path worldDir, PendingNew p) throws IOException {
        StackManifest manifest = StackManifest.load(worldDir);
        StackLayer oldTop = manifest.top();
        if (oldTop == null) {
            throw new IOException("栈清单没有栈顶");
        }
        String layerId = StackPaths.STACK_PREFIX + p.oldTopIndex;
        StackLayer layer = oldTop.copy();
        layer.top = false;
        layer.id = layerId;
        layer.index = p.oldTopIndex;
        layer.seed = p.oldSeed;
        layer.levelType = readLevelType(worldDir.getParent());
        if (Files.exists(worldDir.resolve("level.dat"))) {
            copy(worldDir.resolve("level.dat"), StackPaths.layerMeta(worldDir, layer).resolve("level.dat"));
        }
        Path oldGen = worldDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat");
        // 顶层 dimensions -> 栈层 dimensions
        for (String dim : List.of("overworld", "the_nether", "the_end")) {
            Path src = worldDir.resolve("dimensions").resolve("minecraft").resolve(dim);
            Path dst = StackPaths.layerDimension(worldDir, layer, dim);
            moveDir(src, dst);
        }
        // 顶层 samsara 世界数据 -> 栈层
        Path meta = StackPaths.layerMeta(worldDir, layer);
        for (String name : List.of("claims", "diff", "track")) {
            moveDir(worldDir.resolve("samsara").resolve(name), meta.resolve(name));
        }
        Path spawn = worldDir.resolve("samsara").resolve("spawn-history.json");
        if (Files.exists(spawn)) {
            moveFile(spawn, meta.resolve("spawn-history.json"));
        }
        for (String file : List.of("season.json", "rewind.json", "pending-delete.json", "reset-pending.json")) {
            Path src = worldDir.resolve("samsara").resolve(file);
            if (Files.exists(src)) {
                moveFile(src, meta.resolve(file));
            }
        }
        // data/minecraft 隔离
        Path dataMinecraft = worldDir.resolve("data").resolve("minecraft");
        if (p.isolateData) {
            moveDir(dataMinecraft, meta.resolve("data").resolve("minecraft"));
        } else {
            if (Files.exists(oldGen)) {
                copy(oldGen, StackPaths.worldGenSettings(worldDir, layer));
            }
            // 只移除生成器，保留计分板/天气/规则共享
            Files.deleteIfExists(worldDir.resolve("data").resolve("minecraft").resolve("world_gen_settings.dat"));
        }
        // 栈层自己的 stack.json 由 finalizeBoot 统一写，避免被 load 扫描成重复层
        layer.normalize();

        // 删除旧顶层 level.dat，让原版生成新栈顶
        Files.deleteIfExists(worldDir.resolve("level.dat"));
        Files.deleteIfExists(worldDir.resolve("level.dat_old"));
        writeLevelProperties(worldDir, p);
    }

    static String readLevelType(Path serverDir) {
        Path props = serverDir.resolve("server.properties");
        if (!Files.exists(props)) {
            return "normal";
        }
        try {
            for (String line : Files.readAllLines(props, StandardCharsets.UTF_8)) {
                if (line.startsWith("level-type=")) {
                    String value = line.substring("level-type=".length()).trim();
                    return value.isBlank() ? "normal" : value;
                }
            }
        } catch (IOException ignored) {
        }
        return "normal";
    }

    private static void writeLevelProperties(Path worldDir, PendingNew p) throws IOException {        Path props = worldDir.getParent().resolve("server.properties");
        List<String> lines = Files.exists(props)
                ? new ArrayList<>(Files.readAllLines(props, StandardCharsets.UTF_8))
                : new ArrayList<>();
        boolean name = false;
        boolean seed = false;
        boolean type = false;
        boolean gen = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.startsWith("level-name=")) {
                lines.set(i, "level-name=world");
                name = true;
            } else if (line.startsWith("level-seed=")) {
                lines.set(i, "level-seed=" + p.newSeed);
                seed = true;
            } else if (line.startsWith("level-type=")) {
                lines.set(i, "level-type=" + p.levelType);
                type = true;
            } else if (line.startsWith("generator-settings=")) {
                lines.set(i, "generator-settings=" + p.generatorSettings);
                gen = true;
            }
        }
        if (!name) lines.add("level-name=world");
        if (!seed) lines.add("level-seed=" + p.newSeed);
        if (!type) lines.add("level-type=" + p.levelType);
        if (!gen) lines.add("generator-settings=" + p.generatorSettings);
        Files.write(props, lines, StandardCharsets.UTF_8);
    }

    /** SERVER_STARTED：新栈顶生成后收尾。 */
    public static synchronized void finalizeBoot(MinecraftServer server) {
        Path worldDir = StackPaths.worldDir(server);
        Path file = pendingFile(worldDir);
        if (!Files.exists(file)) {
            return;
        }
        PendingNew p;
        try {
            p = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8), PendingNew.class);
        } catch (Exception e) {
            Samsara.LOGGER.warn("[周目管理 samsara] pending-new 解析失败: {}", e.getMessage());
            return;
        }
        if (p == null || !"generating".equals(p.phase)) {
            return;
        }
        StackManifest manifest = StackManifest.load(worldDir);
        StackLayer oldTop = manifest.top();
        if (oldTop != null) {
            oldTop.top = false;
            oldTop.id = StackPaths.STACK_PREFIX + p.oldTopIndex;
            oldTop.index = p.oldTopIndex;
            oldTop.seed = p.oldSeed;
        }
        StackLayer top = StackLayer.top(server, p.newSeed);
        top.index = p.oldTopIndex + 1;
        top.levelType = p.levelType;
        top.generatorSettings = p.generatorSettings;
        manifest.ensureTop(top);
        manifest.save(worldDir);
        SpawnHistory h = new SpawnHistory();
        h.ensureInitial(top.spawnX, top.spawnZ, System.currentTimeMillis());
        h.save(worldDir);
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 清除 pending-new 失败: {}", e.getMessage());
        }
        Samsara.LOGGER.info("[周目管理 samsara] 新栈顶 world 已生成（seed {}），栈：{}",
                p.newSeed, manifest.summary());
    }

    // ---- file helpers ----

    private static void copy(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    private static void moveFile(Path src, Path dst) throws IOException {
        Files.createDirectories(dst.getParent());
        Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
    }

    /** 目录 move；目标已存在时把子项合并进去（幂等恢复用）。 */
    private static void moveDir(Path src, Path dst) throws IOException {
        if (!Files.exists(src)) {
            return;
        }
        Files.createDirectories(dst.getParent());
        if (!Files.exists(dst)) {
            Files.move(src, dst);
            return;
        }
        try (var stream = Files.list(src)) {
            for (Path child : (Iterable<Path>) stream::iterator) {
                Path target = dst.resolve(child.getFileName().toString());
                if (Files.isDirectory(child) && Files.exists(target)) {
                    moveDir(child, target);
                } else if (!Files.exists(target)) {
                    Files.move(child, target);
                }
            }
        }
        try (var stream = Files.list(src)) {
            if (stream.findAny().isEmpty()) {
                Files.deleteIfExists(src);
            }
        }
    }

    private static void save(Path worldDir, PendingNew p) {
        try {
            Path file = pendingFile(worldDir);
            Files.createDirectories(file.getParent());
            Files.writeString(file, GSON.toJson(p), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] pending-new 写入失败: {}", e.getMessage());
        }
    }

    private static void broadcast(MinecraftServer server, String message) {
        for (var player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(net.minecraft.network.chat.Component.literal(message));
        }
        Samsara.LOGGER.info("[周目管理 samsara] {}", message);
    }
}
