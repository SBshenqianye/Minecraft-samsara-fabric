package com.shenqianye.samsara.season;

import com.shenqianye.samsara.Samsara;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;

import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * 阶段 C spike：在同一个 {@code world} 存档里，把栈世界作为自定义维度同时加载。
 *
 * <p>验证目标（不作为最终实现）：
 * <ul>
 *   <li>共享主世界的 {@link LevelStorageSource.LevelStorageAccess}，额外维度数据写到
 *       {@code world/dimensions/samsara/<id>/<dim>}；</li>
 *   <li>每个栈维度使用自己的 seed（由 {@link WorldOptions} 覆盖）；</li>
 *   <li>共享主世界的 {@link ServerLevelData}，不产生每栈 level.dat，也不误写主 level.dat；</li>
 *   <li>额外维度加入 {@code MinecraftServer.levels} 后能被 tick/save，并可用
 *       {@link ServerPlayer#teleportTo} 传送。</li>
 * </ul>
 *
 * <p>spike 通过后再把 {@code WorldStackManager}/{@code MultiWorldLoader} 改成正式模型。
 * 失败时只记录 WARN，不影响主世界启动。
 */
public final class StackDimensionSpike {
    private static final String NAMESPACE = "samsara";
    private static final Map<String, ServerLevel> LEVELS = new LinkedHashMap<>();

    private StackDimensionSpike() {
    }

    public static synchronized String create(MinecraftServer server, String id, long seed) {
        if (!id.matches("[a-z0-9_]+")) {
            return "spike id 只能用小写字母/数字/下划线";
        }
        if (!LEVELS.isEmpty()) {
            return "spike 已创建：" + status();
        }
        try {
            LevelStorageSource.LevelStorageAccess access = storageAccess(server);
            ServerLevelData levelData = server.getWorldData().overworldData();
            WorldGenSettings current = server.getWorldGenSettings();
            WorldGenSettings gen = new WorldGenSettings(
                    new WorldOptions(seed, current.options().generateStructures(),
                            current.options().generateBonusChest()),
                    current.dimensions());
            Object executor = executor(server);
            create(server, access, levelData, gen, executor, id, "overworld", LevelStem.OVERWORLD, seed);
            create(server, access, levelData, gen, executor, id, "the_nether", LevelStem.NETHER, seed);
            create(server, access, levelData, gen, executor, id, "the_end", LevelStem.END, seed);
            Samsara.LOGGER.info("[周目管理 samsara] stackspike 已创建 {}：{}", id, status());
            return "已创建 spike 维度 " + id + "（seed " + seed + "）\n" + status();
        } catch (Throwable t) {
            Samsara.LOGGER.warn("[周目管理 samsara] stackspike 失败: {}", rootMessage(t));
            LEVELS.clear();
            return "stackspike 失败：" + rootMessage(t);
        }
    }

    private static void create(MinecraftServer server, LevelStorageSource.LevelStorageAccess access,
                               ServerLevelData levelData, WorldGenSettings gen, Object executor,
                               String id, String folder, ResourceKey<LevelStem> stemKey, long seed)
            throws Exception {
        Optional<LevelStem> stem = gen.dimensions().get(stemKey);
        if (stem.isEmpty()) {
            throw new IllegalStateException("缺少维度 " + stemKey);
        }
        Identifier identifier = Identifier.fromNamespaceAndPath(NAMESPACE, id + "/" + folder);
        ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION, identifier);
        ServerLevel level = StackLevel.create(server, (java.util.concurrent.Executor) executor, access,
                levelData, key, stem.get(), false, seed, List.of(), true);
        register(server, key, level);
        LEVELS.put(folder, level);
    }

    public static synchronized String status() {
        if (LEVELS.isEmpty()) {
            return "spike：未创建";
        }
        StringBuilder sb = new StringBuilder("spike：");
        for (Map.Entry<String, ServerLevel> e : LEVELS.entrySet()) {
            sb.append("\n· ").append(e.getValue().dimension().identifier())
                    .append(" @ ").append(e.getValue().getSeed());
        }
        return sb.toString();
    }

    public static synchronized String teleport(ServerPlayer player) {
        ServerLevel level = LEVELS.get("overworld");
        if (level == null) {
            return "spike 未创建，先 /samsara debug world stackspike create <seed>";
        }
        int x = 0;
        int z = 0;
        level.getChunk(x >> 4, z >> 4); // 强制生成/加载
        int y = level.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING, x, z) + 1;
        player.teleportTo(level, x + 0.5, y, z + 0.5, java.util.Set.of(),
                player.getYRot(), player.getXRot(), false);
        return "已传送到 " + level.dimension().identifier() + " (" + x + ", " + y + ", " + z + ")";
    }

    /** 额外层已进入 server.levels，停服由原版保存/关闭；这里只清内存引用。 */
    public static synchronized void unload() {
        LEVELS.clear();
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
}
