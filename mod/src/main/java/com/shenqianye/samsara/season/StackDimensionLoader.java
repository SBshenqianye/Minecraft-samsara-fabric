package com.shenqianye.samsara.season;

import com.mojang.serialization.DataResult;
import com.mojang.serialization.Dynamic;
import com.shenqianye.samsara.Samsara;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.RegistryOps;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 把栈世界作为额外 {@link ServerLevel} 加载到同一个 {@code world} 存档。
 *
 * <p>不再为每个栈使用独立存档访问：共享主世界的 {@link LevelStorageSource.LevelStorageAccess}，
 * 自定义维度 key {@code samsara:stack_<n>/<dim>} 天然把区块落到
 * {@code world/dimensions/samsara/stack_<n>/<dim>}。每个栈从自己的
 * {@code world_gen_settings.dat} 读取 seed/LevelStem，并用 {@link StackLevel} 覆写
 * {@code getSeed()}，保证地形使用该栈的种子。
 */
public final class StackDimensionLoader {
    private static final List<String> DIMS = List.of("overworld", "the_nether", "the_end");
    private static Map<String, LoadedLayer> loaded = new LinkedHashMap<>();
    private static Map<String, Failure> failures = new LinkedHashMap<>();
    /** 仅测试/演示用：强制让指定栈层加载失败，用于验证失败提示与重试。 */
    private static java.util.Set<String> debugFailures = new java.util.LinkedHashSet<>();

    private StackDimensionLoader() {
    }

    /** 某个栈层最近一次加载失败的信息；用于玩家提示与 {@code world info}。 */
    public static final class Failure {
        public final String message;
        public final int attempts;
        public final long lastAttemptMs;

        Failure(String message, int attempts, long lastAttemptMs) {
            this.message = message;
            this.attempts = attempts;
            this.lastAttemptMs = lastAttemptMs;
        }

        @Override
        public String toString() {
            return message + "（第 " + attempts + " 次失败，自动重试中）";
        }
    }

    public static final class LoadedLayer {
        public final StackLayer layer;
        public final Map<String, ServerLevel> levels = new LinkedHashMap<>();

        LoadedLayer(StackLayer layer) {
            this.layer = layer;
        }

        public ServerLevel level(String dim) {
            return levels.get(dim);
        }
    }

    public static synchronized void load(MinecraftServer server) {
        unload();
        failures = new LinkedHashMap<>();
        seedDebugFailures();
        Path worldDir = StackPaths.worldDir(server);
        StackManifest manifest = StackManifest.load(worldDir);
        if (manifest.layers.isEmpty()) {
            StackLayer top = StackLayer.top(server, server.getWorldGenSettings().options().seed());
            manifest.ensureTop(top);
            manifest.save(worldDir);
        }
        StackLayer top = manifest.top();
        if (top != null) {
            var overworld = server.getLevel(Level.OVERWORLD);
            if (overworld != null) {
                var pos = overworld.getRespawnData().pos();
                top.spawnX = pos.getX();
                top.spawnY = pos.getY();
                top.spawnZ = pos.getZ();
                top.seed = overworld.getSeed();
                top.levelType = StackBootstrap.readLevelType(worldDir.getParent());
            }
            Path meta = StackPaths.layerMeta(worldDir, top);
            SpawnHistory h = SpawnHistory.loadFrom(meta);
            if (h.isEmpty()) {
                h.ensureInitial(top.spawnX, top.spawnZ, System.currentTimeMillis());
                h.saveTo(meta);
            } else if (h.detect(top.spawnX, top.spawnZ, System.currentTimeMillis()) != null) {
                h.saveTo(meta);
            }
            manifest.save(worldDir);
        }
        for (StackLayer layer : manifest.layers) {
            if (layer.top) {
                continue;
            }
            try {
                LoadedLayer loadedLayer = loadLayer(server, worldDir, layer);
                if (loadedLayer != null) {
                    loaded.put(layer.id, loadedLayer);
                    Samsara.LOGGER.info("[周目管理 samsara] 已加载栈维度 {}（seed {}）：{}",
                            layer.id, layer.seed, String.join("/", loadedLayer.levels.keySet()));
                }
            } catch (Throwable t) {
                recordFailure(layer.id, rootMessage(t), System.currentTimeMillis());
            }
        }
        Samsara.LOGGER.info("[周目管理 samsara] {}", status());
    }

    private static LoadedLayer loadLayer(MinecraftServer server, Path worldDir, StackLayer layer)
            throws Exception {
        if (debugFailures.contains(layer.id)) {
            throw new IllegalStateException("调试注入的加载失败（samsara.debug.failStackLayer）");
        }
        WorldGenSettings gen = readWorldGen(server, worldDir, layer);
        LevelStorageSource.LevelStorageAccess access = storageAccess(server);
        ServerLevelData levelData = server.getWorldData().overworldData();
        Object executor = executor(server);
        LoadedLayer out = new LoadedLayer(layer);
        try {
            for (String dim : DIMS) {
                ResourceKey<LevelStem> stemKey = stemKey(dim);
                Optional<LevelStem> stem = gen.dimensions().get(stemKey);
                if (stem.isEmpty()) {
                    throw new IllegalStateException("缺少维度 " + stemKey);
                }
                // 额外栈层共享 top 的 ServerLevelData；若 tickTime=true，每个维度都会推进同一个
                // dayTime，游戏时间会变成 10 倍速并出现跳时。时间只由原版主世界推进，栈维度只 tick 区块/实体。
                ServerLevel level = StackLevel.create(server, (java.util.concurrent.Executor) executor,
                        access, levelData, layer.key(dim), stem.get(), false,
                        layer.seed, List.of(), false);
                register(server, layer.key(dim), level);
                out.levels.put(dim, level);
            }
            return out;
        } catch (Throwable t) {
            // 部分维度注册成功后失败：清理已注册的 key，避免下一次重试生成重复 ServerLevel。
            for (String dim : new ArrayList<>(out.levels.keySet())) {
                try {
                    unregister(server, layer.key(dim));
                } catch (Throwable ignored) {
                }
            }
            if (t instanceof Exception e) {
                throw e;
            }
            throw new RuntimeException(t);
        }
    }

    /**
     * 按需加载/重试某个栈层。
     *
     * <p>正常启动时 {@link #load(MinecraftServer)} 已经加载全部旧栈层；这里只覆盖
     * “启动时加载失败、玩家仍沿跨栈链到达该层”以及导入/提升后新增层的边界情况。
     * 失败后按 {@link StackLoadPolicy} 指数退避重试，失败原因可通过
     * {@link #failureReason(String)} 返回给玩家，而不是只提示“未加载”。
     */
    public static synchronized ServerLevel ensureLevel(MinecraftServer server, StackLayer layer, String dim) {
        if (layer == null) {
            return null;
        }
        if (layer.top) {
            return server.getLevel(switch (dim) {
                case "the_nether" -> Level.NETHER;
                case "the_end" -> Level.END;
                default -> Level.OVERWORLD;
            });
        }
        LoadedLayer current = loaded.get(layer.id);
        if (current != null) {
            ServerLevel existing = current.level(dim);
            if (existing != null) {
                return existing;
            }
        }
        Failure previous = failures.get(layer.id);
        long now = System.currentTimeMillis();
        if (previous != null
                && !StackLoadPolicy.shouldRetry(previous.attempts, previous.lastAttemptMs, now)) {
            return null;
        }
        try {
            LoadedLayer created = loadLayer(server, StackPaths.worldDir(server), layer);
            loaded.put(layer.id, created);
            failures.remove(layer.id);
            Samsara.LOGGER.info("[周目管理 samsara] 已按需加载栈维度 {}（seed {}）：{}",
                    layer.id, layer.seed, String.join("/", created.levels.keySet()));
            return created.level(dim);
        } catch (Throwable t) {
            recordFailure(layer.id, rootMessage(t), now);
            return null;
        }
    }

    private static void recordFailure(String id, String message, long now) {
        Failure previous = failures.get(id);
        int attempts = previous == null ? 1 : previous.attempts + 1;
        failures.put(id, new Failure(message, attempts, now));
        if (previous == null || !message.equals(previous.message)) {
            Samsara.LOGGER.warn("[周目管理 samsara] 加载栈维度 {} 失败（第 {} 次，{} ms 后允许重试）: {}",
                    id, attempts, StackLoadPolicy.nextDelayMs(attempts), message);
        } else {
            Samsara.LOGGER.debug("[周目管理 samsara] 加载栈维度 {} 仍失败（第 {} 次）: {}",
                    id, attempts, message);
        }
    }

    /** 某层最近一次加载失败的可读原因；从未失败时返回一个中性的兜底说明。 */
    public static synchronized String failureReason(String id) {
        Failure failure = failures.get(id);
        if (failure == null) {
            return "维度未加载，服务器日志中没有记录到具体失败原因";
        }
        return failure.toString();
    }

    /** 是否有失败记录（供 world info / 调试使用）。 */
    public static synchronized boolean hasFailure(String id) {
        return failures.containsKey(id);
    }

    /**
     * 测试/演示开关：强制某个栈层加载失败（或解除）。
     *
     * <p>只影响后续加载，不会卸载已经加载的层；用于在真实服务器上验证
     * “跨栈链目标层加载失败 → 明确提示 → 解除后自动重试成功”。
     */
    public static synchronized String setDebugFailure(String id, boolean fail) {
        if (fail) {
            debugFailures.add(id);
        } else {
            debugFailures.remove(id);
        }
        failures.remove(id);
        return "栈层 " + id + " 调试注入失败已" + (fail ? "开启" : "关闭");
    }

    /** 忽略退避窗口，立即重试某一层；返回可读结果。 */
    public static synchronized String retryNow(MinecraftServer server, String id) {
        StackLayer layer = StackManifest.load(StackPaths.worldDir(server)).byId(id);
        if (layer == null) {
            return "栈层 " + id + " 不存在";
        }
        if (layer.top) {
            return "栈层 " + id + " 是栈顶，由服务器直接提供";
        }
        failures.remove(id);
        ServerLevel level = ensureLevel(server, layer, "overworld");
        if (level != null) {
            return "已加载栈层 " + id + " · " + level.dimension().identifier();
        }
        return "栈层 " + id + " 仍加载失败：" + failureReason(id);
    }

    private static void seedDebugFailures() {
        String value = System.getProperty("samsara.debug.failStackLayer", "");
        for (String part : value.split(",")) {
            String id = part.trim();
            if (!id.isEmpty()) {
                debugFailures.add(id);
            }
        }
    }

    private static WorldGenSettings readWorldGen(MinecraftServer server, Path worldDir, StackLayer layer) {
        Path file = StackPaths.worldGenSettings(worldDir, layer);
        if (Files.exists(file)) {
            try {
                CompoundTag root = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
                CompoundTag data = root.getCompoundOrEmpty("data");
                RegistryOps<Tag> ops = RegistryOps.create(NbtOps.INSTANCE, server.registryAccess());
                DataResult<WorldGenSettings> result = WorldGenSettings.CODEC.parse(ops, data);
                if (result.result().isPresent()) {
                    return result.result().get();
                }
                Samsara.LOGGER.warn("[周目管理 samsara] {} 解析失败: {}",
                        file, result.error().map(Object::toString).orElse("unknown"));
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 读取 {} 失败: {}", file, e.getMessage());
            }
        }
        // 兜底：复用当前栈顶的生成器，只覆盖 seed
        WorldGenSettings current = server.getWorldGenSettings();
        WorldOptions options = new WorldOptions(layer.seed,
                current.options().generateStructures(), current.options().generateBonusChest());
        return new WorldGenSettings(options, current.dimensions());
    }

    private static ResourceKey<LevelStem> stemKey(String dim) {
        return switch (dim) {
            case "the_nether" -> LevelStem.NETHER;
            case "the_end" -> LevelStem.END;
            default -> LevelStem.OVERWORLD;
        };
    }

    @SuppressWarnings("unchecked")
    private static void register(MinecraftServer server, ResourceKey<Level> key, ServerLevel level)
            throws Exception {
        Field field = MinecraftServer.class.getDeclaredField("levels");
        field.setAccessible(true);
        Map<ResourceKey<Level>, ServerLevel> levels =
                (Map<ResourceKey<Level>, ServerLevel>) field.get(server);
        levels.put(key, level);
    }

    @SuppressWarnings("unchecked")
    private static void unregister(MinecraftServer server, ResourceKey<Level> key) throws Exception {
        Field field = MinecraftServer.class.getDeclaredField("levels");
        field.setAccessible(true);
        Map<ResourceKey<Level>, ServerLevel> levels =
                (Map<ResourceKey<Level>, ServerLevel>) field.get(server);
        levels.remove(key);
    }

    private static LevelStorageSource.LevelStorageAccess storageAccess(MinecraftServer server)
            throws Exception {
        Field field = MinecraftServer.class.getDeclaredField("storageSource");
        field.setAccessible(true);
        return (LevelStorageSource.LevelStorageAccess) field.get(server);
    }

    private static Object executor(MinecraftServer server) throws Exception {
        Field field = MinecraftServer.class.getDeclaredField("executor");
        field.setAccessible(true);
        return field.get(server);
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null) {
            cur = cur.getCause();
        }
        return cur.getClass().getSimpleName() + ": " + cur.getMessage();
    }

    public static synchronized void unload() {
        loaded = new LinkedHashMap<>();
        failures = new LinkedHashMap<>();
    }

    public static synchronized LoadedLayer layer(String id) {
        return loaded.get(id);
    }

    public static synchronized Map<String, LoadedLayer> all() {
        return Map.copyOf(loaded);
    }

    public static synchronized ServerLevel level(StackLayer layer, String dim) {
        if (layer.top) {
            return null; // top 由 MinecraftServer 提供
        }
        LoadedLayer l = loaded.get(layer.id);
        return l == null ? null : l.level(dim);
    }

    public static synchronized String status() {
        if (loaded.isEmpty() && failures.isEmpty()) {
            return "栈维度：未加载";
        }
        List<String> parts = new ArrayList<>();
        for (LoadedLayer l : loaded.values()) {
            parts.add(l.layer.id + "=" + String.join("/", l.levels.keySet()));
        }
        for (Map.Entry<String, Failure> entry : failures.entrySet()) {
            parts.add(entry.getKey() + " 加载失败（" + entry.getValue() + "）");
        }
        return "栈维度：" + String.join(" · ", parts);
    }
}
