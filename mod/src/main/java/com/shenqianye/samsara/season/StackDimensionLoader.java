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

    private StackDimensionLoader() {
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
                Samsara.LOGGER.warn("[周目管理 samsara] 加载栈维度 {} 失败（跨世界传送将不可用）: {}",
                        layer.id, rootMessage(t));
            }
        }
        Samsara.LOGGER.info("[周目管理 samsara] {}", status());
    }

    private static LoadedLayer loadLayer(MinecraftServer server, Path worldDir, StackLayer layer)
            throws Exception {
        WorldGenSettings gen = readWorldGen(server, worldDir, layer);
        LevelStorageSource.LevelStorageAccess access = storageAccess(server);
        ServerLevelData levelData = server.getWorldData().overworldData();
        Object executor = executor(server);
        RegistryAccess registries = server.registryAccess();
        LoadedLayer out = new LoadedLayer(layer);
        for (String dim : DIMS) {
            ResourceKey<LevelStem> stemKey = stemKey(dim);
            Optional<LevelStem> stem = gen.dimensions().get(stemKey);
            if (stem.isEmpty()) {
                throw new IllegalStateException("缺少维度 " + stemKey);
            }
            ServerLevel level = StackLevel.create(server, (java.util.concurrent.Executor) executor,
                    access, levelData, layer.key(dim), stem.get(), false,
                    layer.seed, List.of(), true);
            register(server, layer.key(dim), level);
            out.levels.put(dim, level);
        }
        return out;
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
        if (loaded.isEmpty()) {
            return "栈维度：未加载";
        }
        List<String> parts = new ArrayList<>();
        for (LoadedLayer l : loaded.values()) {
            parts.add(l.layer.id + "=" + String.join("/", l.levels.keySet()));
        }
        return "栈维度：" + String.join(" · ", parts);
    }
}
