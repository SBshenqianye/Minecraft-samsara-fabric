package com.shenqianye.samsara.season;

import com.mojang.serialization.Dynamic;
import com.shenqianye.samsara.Samsara;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.storage.LevelDataAndDimensions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 阶段 C spike：把世界栈里「其它层」作为额外 {@link ServerLevel} 同时加载。
 *
 * <p>验证目标：跨世界传送链（F-29~F-34）需要同时访问多层的 overworld/nether/end。
 * 这里<b>不把维度作为主存储</b>：每层仍使用自己的存档文件夹；额外层通过
 * 自定义 dimension key（{@code samsara_stack_<index>:../minecraft/overworld}）映射回
 * 该层原本的 {@code dimensions/minecraft/overworld} / {@code the_nether} / {@code the_end}。
 * 数据仍写在各层文件夹里，移动/备份单个世界不会丢身份。
 *
 * <p>风险点（spike 结论见 docs/实现状态.md）：
 * <ul>
 *   <li>{@link LevelStorageSource.LevelStorageAccess} 的构造器/锁由原版管理，额外层会持有该世界的
 *       {@code session.lock}；不能在同一个进程里再把它当主存档打开。</li>
 *   <li>额外层注册进 {@code MinecraftServer.levels} 后，原版 tick/save 会遍历到它们；
 *       但客户端只认识原版维度类型（这里复用 overworld/nether/end），自定义 dimension key
 *       能否被客户端正确重建需要在真实客户端上验证。</li>
 *   <li>原版 {@code MinecraftServer} 没有为“多主存档”设计，任何版本升级都可能破坏此实现。</li>
 * </ul>
 *
 * <p>失败时 fail-soft：记录 WARN 并返回空表，跨世界传送链会明确提示限制，不会静默降级。
 */
public final class MultiWorldLoader {
    private static final String DIMENSIONS = "dimensions";

    private MultiWorldLoader() {
    }

    /** 一层额外世界：三个维度的 ServerLevel。 */
    public static final class LoadedWorld {
        public final String name;
        public final int index;
        public final Map<String, ServerLevel> levels = new LinkedHashMap<>();
        public final Map<ResourceKey<Level>, ServerLevel> byKey = new LinkedHashMap<>();
        public final LevelStorageSource.LevelStorageAccess access;

        LoadedWorld(String name, int index, LevelStorageSource.LevelStorageAccess access) {
            this.name = name;
            this.index = index;
            this.access = access;
        }

        public ServerLevel overworld() {
            return levels.get("overworld");
        }

        public ServerLevel nether() {
            return levels.get("the_nether");
        }

        public ServerLevel end() {
            return levels.get("the_end");
        }

        public ServerLevel forDimension(ResourceKey<Level> key) {
            return byKey.get(key);
        }

        public void close() {
            // 不要在这里调用 ServerLevel.close()：它会 saveAllChunks 并等待主线程 executor，
            // 而本方法通常就在主线程（SERVER_STOPPED）里执行，会死锁。
            // 原版停服流程已经保存/关闭了 levels；这里只释放额外存档访问的 session.lock。
            try {
                access.close();
            } catch (Exception e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 关闭额外世界 {} 的存档访问失败: {}",
                        name, e.getMessage());
            }
        }
    }

    /**
     * 加载除当前世界外的所有栈层。失败的世界跳过，返回成功加载的表。
     */
    public static Map<String, LoadedWorld> loadOthers(MinecraftServer server,
                                                      List<String> stack,
                                                      String current) {
        Map<String, LoadedWorld> loaded = new LinkedHashMap<>();
        for (String name : stack) {
            if (name.equals(current)) {
                continue;
            }
            try {
                LoadedWorld world = loadOne(server, name);
                if (world != null) {
                    loaded.put(name, world);
                    Samsara.LOGGER.info("[周目管理 samsara] 已额外加载世界 {}（index {}）：overworld/nether/end",
                            name, world.index);
                }
            } catch (Throwable t) {
                // spike 阶段宁可少加载，也不能让主世界起不来
                Samsara.LOGGER.warn("[周目管理 samsara] 额外加载世界 {} 失败（跨世界传送链将不可用）: {}",
                        name, rootMessage(t));
            }
        }
        return loaded;
    }

    private static LoadedWorld loadOne(MinecraftServer server, String name) throws Exception {
        Path worldDir = WorldStackManager.worldDir(server, name);
        if (!Files.isRegularFile(worldDir.resolve("level.dat"))) {
            return null;
        }
        int index = Math.max(0, WorldStackManager.getInstance().indexOf(name));
        String namespace = "samsara_stack_" + index;
        // 让 `..` 在 Linux 上也能解析：确保 dimensions/<namespace> 目录存在
        Files.createDirectories(worldDir.resolve(DIMENSIONS).resolve(namespace));

        LevelStorageSource source = LevelStorageSource.createDefault(WorldStackManager.worldParent(server));
        LevelStorageSource.LevelStorageAccess access = source.createAccess(name);
        LoadedWorld loaded = new LoadedWorld(name, index, access);
        boolean ok = false;
        try {
            Dynamic<?> tag = access.getUnfixedDataTag(false);
            RegistryAccess.Frozen registries = server.registryAccess();
            Registry<LevelStem> stems = registries.lookupOrThrow(Registries.LEVEL_STEM);
            LevelDataAndDimensions data = LevelStorageSource.getLevelDataAndDimensions(
                    access, tag, server.getWorldData().getDataConfiguration(), stems, registries);
            ServerLevelData levelData = data.worldDataAndGenSettings().data().overworldData();
            WorldGenSettings gen = data.worldDataAndGenSettings().genSettings();
            Registry<LevelStem> altStems = data.dimensions().dimensions();
            long seed = gen.options().seed();
            Object executor = executor(server);

            addDimension(server, loaded, access, levelData, seed, executor, altStems,
                    namespace, "overworld", LevelStem.OVERWORLD);
            addDimension(server, loaded, access, levelData, seed, executor, altStems,
                    namespace, "the_nether", LevelStem.NETHER);
            addDimension(server, loaded, access, levelData, seed, executor, altStems,
                    namespace, "the_end", LevelStem.END);
            ok = loaded.levels.size() == 3;
        } finally {
            if (!ok) {
                unregister(server, loaded);
                loaded.close();
            }
        }
        return ok ? loaded : null;
    }

    @SuppressWarnings("unchecked")
    private static void unregister(MinecraftServer server, LoadedWorld loaded) throws Exception {
        Field field = MinecraftServer.class.getDeclaredField("levels");
        field.setAccessible(true);
        Map<ResourceKey<Level>, ServerLevel> levels =
                (Map<ResourceKey<Level>, ServerLevel>) field.get(server);
        for (ResourceKey<Level> key : loaded.byKey.keySet()) {
            levels.remove(key);
        }
    }

    private static void addDimension(MinecraftServer server, LoadedWorld loaded,
                                     LevelStorageSource.LevelStorageAccess access,
                                     ServerLevelData levelData, long seed, Object executor,
                                     Registry<LevelStem> altStems, String namespace,
                                     String folder, ResourceKey<LevelStem> stemKey) throws Exception {
        LevelStem stem = altStems.getValue(stemKey);
        if (stem == null) {
            throw new IllegalStateException("世界缺少维度 " + stemKey);
        }
        // 自定义 key 的路径用 `../minecraft/<folder>`：标准化后仍落在该世界自己的 dimensions/minecraft 下
        Identifier id = Identifier.fromNamespaceAndPath(namespace, "../minecraft/" + folder);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, id);
        ServerLevel level = new ServerLevel(server, (java.util.concurrent.Executor) executor, access,
                levelData, key, stem, false, seed, List.of(), true);
        register(server, key, level);
        loaded.levels.put(folder, level);
        loaded.byKey.put(key, level);
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

    /** 额外世界的 key → 层名（传送链反查用）。 */
    public static String worldNameOf(Map<String, LoadedWorld> loaded, ServerLevel level) {
        for (LoadedWorld world : loaded.values()) {
            if (world.byKey.containsKey(level.dimension())) {
                return world.name;
            }
        }
        return null;
    }

    /** 额外世界的维度名（overworld/the_nether/the_end）。 */
    public static String dimensionOf(Map<String, LoadedWorld> loaded, ServerLevel level) {
        for (LoadedWorld world : loaded.values()) {
            for (Map.Entry<String, ServerLevel> e : world.levels.entrySet()) {
                if (e.getValue() == level) {
                    return e.getKey();
                }
            }
        }
        return null;
    }

    /** 诊断：已加载的额外世界列表。 */
    public static String status(Map<String, LoadedWorld> loaded) {
        if (loaded.isEmpty()) {
            return "额外世界：未加载（跨世界传送链不可用）";
        }
        List<String> parts = new ArrayList<>();
        for (LoadedWorld world : loaded.values()) {
            parts.add(world.name + "=" + String.join("/", world.levels.keySet()));
        }
        return "额外世界：" + String.join(" · ", parts);
    }
}
