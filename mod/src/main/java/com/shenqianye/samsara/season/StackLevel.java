package com.shenqianye.samsara.season;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.CustomSpawner;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.dimension.LevelStem;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.ServerLevelData;
import net.minecraft.resources.ResourceKey;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * 带独立 seed 的 ServerLevel（spike 用）。
 *
 * <p>原版 {@link ServerLevel#getSeed()} 直接返回服务器全局 {@code WorldGenSettings.options.seed}，
 * 不是构造器参数；如果共享主存档的 {@link ServerLevelData}，构造器 seed 只影响 biome zoom，
 * 地形仍会用主世界种子。这里复写 {@code getSeed()}，让区块生成器读取每栈 seed。
 *
 * <p>构造期间原版可能调用 {@code getSeed()}，此时字段还没赋值，所以用 ThreadLocal 过渡。
 */
public class StackLevel extends ServerLevel {
    private static final ThreadLocal<Long> PENDING_SEED = new ThreadLocal<>();
    private final long stackSeed;

    public static StackLevel create(MinecraftServer server, Executor executor,
                                    LevelStorageSource.LevelStorageAccess access,
                                    ServerLevelData levelData, ResourceKey<Level> dimension,
                                    LevelStem stem, boolean debug, long seed,
                                    List<CustomSpawner> spawners, boolean tickTime) {
        PENDING_SEED.set(seed);
        try {
            return new StackLevel(server, executor, access, levelData, dimension, stem,
                    debug, seed, spawners, tickTime);
        } finally {
            PENDING_SEED.remove();
        }
    }

    private StackLevel(MinecraftServer server, Executor executor,
                       LevelStorageSource.LevelStorageAccess access, ServerLevelData levelData,
                       ResourceKey<Level> dimension, LevelStem stem, boolean debug, long seed,
                       List<CustomSpawner> spawners, boolean tickTime) {
        super(server, executor, access, levelData, dimension, stem, debug, seed, spawners, tickTime);
        this.stackSeed = seed;
    }

    @Override
    public long getSeed() {
        Long pending = PENDING_SEED.get();
        return pending != null ? pending : stackSeed;
    }
}
