package com.shenqianye.samsara.storage;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.storage.LevelResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 三层存储根目录的唯一入口（F-118~F-120、F-128）。
 *
 * <pre>
 * configRoot      = FabricLoader.getConfigDir()/samsara          （服务器级配置）
 * worldDataRoot   = server.getWorldPath(ROOT)/samsara            （随存档）
 * globalDataRoot  = worldDataRoot/global  或  gameDir/samsara/global
 * </pre>
 *
 * <p><b>任何模块都不得自己拼 {@code FabricLoader.getConfigDir()}。</b>
 * 新增数据路径时先在这里加方法，再由模块调用。
 *
 * <p>启动顺序：
 * <ol>
 *   <li>mod init：{@link #bootstrapConfig()} 先把旧 {@code config/samsara/} 改名为
 *       {@code config/samsara.legacy}（保留备份），重建新配置目录；</li>
 *   <li>配置类读 YAML（{@link ConfigManager}）；</li>
 *   <li>{@code SERVER_STARTING}：{@link #init(MinecraftServer)} 解析存档根并迁移世界数据。
 *       必须早于任何世界模块读取路径。</li>
 * </ol>
 */
public final class StoragePaths {
    private static final String LEGACY_SUFFIX = ".legacy";
    private static volatile StoragePaths instance;
    private static volatile Path configRoot;
    private static volatile Path legacyRoot;
    private static volatile String migrationSummary = "尚未初始化";
    private static volatile String globalMigrationSummary = "";

    /** 迁移期间暂停全局数据读写（v0.20 身份运行时数据已独立，不再走此锁）。 */
    private static final ReentrantReadWriteLock GLOBAL_IO_LOCK = new ReentrantReadWriteLock();
    private static volatile boolean globalIoPaused;

    private final Path configRootDir;
    private final Path worldRoot;
    private final Path worldDataRoot;
    private final Path serverDataRoot;
    private volatile Path globalDataRoot;
    private volatile boolean globalFollowWorld;

    private StoragePaths(Path configRoot, Path worldRoot, Path worldDataRoot,
                         Path serverDataRoot, Path globalDataRoot, boolean globalFollowWorld) {
        this.configRootDir = configRoot;
        this.worldRoot = worldRoot;
        this.worldDataRoot = worldDataRoot;
        this.serverDataRoot = serverDataRoot;
        this.globalDataRoot = globalDataRoot;
        this.globalFollowWorld = globalFollowWorld;
    }

    // ---- 配置根（mod init 即可用） ----

    public static synchronized Path configRoot() {
        if (configRoot == null) {
            configRoot = FabricLoader.getInstance().getConfigDir().resolve("samsara")
                    .toAbsolutePath().normalize();
        }
        return configRoot;
    }

    /** 旧配置目录；没有迁移过时为 null。 */
    public static Path legacyConfigRoot() {
        if (legacyRoot == null) {
            Path candidate = configRoot().resolveSibling("samsara" + LEGACY_SUFFIX);
            if (Files.exists(candidate)) {
                legacyRoot = candidate;
            }
        }
        return legacyRoot;
    }

    /**
     * 配置阶段：旧目录整体改名为 {@code config/samsara.legacy}，重建配置目录。
     * 这样旧 JSON 与旧世界数据都留在 legacy，迁移器只从一个方向读取，幂等且可回滚。
     */
    public static synchronized void bootstrapConfig() {
        Path root = configRoot();
        Path legacy = root.resolveSibling("samsara" + LEGACY_SUFFIX);
        boolean hasStorage = Files.exists(root.resolve("storage.yml"));
        if (!hasStorage && hasLegacyEntries(root) && !Files.exists(legacy)) {
            try {
                try {
                    Files.move(root, legacy, StandardCopyOption.ATOMIC_MOVE);
                } catch (IOException atomicFailure) {
                    Files.move(root, legacy);
                }
                Samsara.LOGGER.info("[周目管理 samsara] 旧配置目录已改名：{} → {}（保留备份）",
                        root, legacy);
                legacyRoot = legacy;
            } catch (IOException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 旧配置目录改名失败，将原地迁移：{}",
                        e.getMessage());
            }
        }
        try {
            Files.createDirectories(root);
        } catch (IOException e) {
            throw new RuntimeException("无法创建 samsara 配置目录", e);
        }
        if (Files.exists(legacy)) {
            legacyRoot = legacy;
        }
    }

    private static boolean hasLegacyEntries(Path root) {
        if (!Files.exists(root)) {
            return false;
        }
        for (String name : StorageMigration.WORLD_ENTRIES) {
            if (Files.exists(root.resolve(name))) {
                return true;
            }
        }
        for (String name : List.of("commands.json", "permissions.json", "claim-particles.json",
                "global")) {
            if (Files.exists(root.resolve(name))) {
                return true;
            }
        }
        return false;
    }

    // ---- 服务器阶段 ----

    /** 解析存档根并执行一次世界数据迁移。必须在世界模块初始化之前调用。 */
    public static synchronized void init(MinecraftServer server) {
        Path worldRoot = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        Path gameDir = FabricLoader.getInstance().getGameDir().toAbsolutePath().normalize();
        Path serverDataRoot = gameDir.resolve("samsara");
        ConfigManager config = ConfigManager.getInstance();
        boolean follow = config.bool(ConfigSchema.STORAGE, "globalFollowWorld");
        String worldOverride = config.string(ConfigSchema.STORAGE, "worldDataRoot").trim();
        String globalOverride = config.string(ConfigSchema.STORAGE, "globalDataRoot").trim();

        Path worldDataRoot = worldOverride.isEmpty()
                ? worldRoot.resolve("samsara")
                : Path.of(worldOverride).toAbsolutePath().normalize();
        Path globalDataRoot = globalOverride.isEmpty()
                ? resolveGlobalRoot(worldDataRoot, serverDataRoot, follow, "")
                : Path.of(globalOverride).toAbsolutePath().normalize();

        int version = config.integer(ConfigSchema.STORAGE, "migration.version");
        String result = StorageMigration.migrate(legacyConfigRoot(), configRoot(),
                worldDataRoot, globalDataRoot, version);
        migrationSummary = result;
        try {
            Files.createDirectories(worldDataRoot);
            Files.createDirectories(globalDataRoot);
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 无法创建数据目录: {}", e.getMessage());
        }
        if (version < StorageMigration.CURRENT_VERSION) {
            config.save(ConfigSchema.STORAGE,
                    java.util.Map.of("migration.version", StorageMigration.CURRENT_VERSION));
        }
        instance = new StoragePaths(configRoot(), worldRoot, worldDataRoot,
                serverDataRoot, globalDataRoot, follow);
        Samsara.LOGGER.info("[周目管理 samsara] 存储根：配置 {} · 世界数据 {} · 全局数据 {}（跟随存档={}）",
                configRoot(), worldDataRoot, globalDataRoot, follow);
    }

    public static boolean ready() {
        return instance != null;
    }

    public static StoragePaths get() {
        StoragePaths paths = instance;
        if (paths == null) {
            throw new IllegalStateException("StoragePaths 尚未初始化（应在 SERVER_STARTING 调用 init）");
        }
        return paths;
    }

    // ---- 路径 ----

    /** 计算全局数据根（纯函数，便于单测；override 为空 = 按开关推导）。 */
    public static Path resolveGlobalRoot(Path worldDataRoot, Path serverDataRoot,
                                         boolean globalFollowWorld, String override) {
        if (override != null && !override.isBlank()) {
            return Path.of(override).toAbsolutePath().normalize();
        }
        return globalFollowWorld ? worldDataRoot.resolve("global")
                : serverDataRoot.resolve("global");
    }

    public Path configRootPath() {
        return configRootDir;
    }

    public Path worldRoot() {
        return worldRoot;
    }

    /** 世界数据根（随存档）。 */
    public Path worldDataRoot() {
        return worldDataRoot;
    }

    /** 服务器级数据根（仅 globalFollowWorld=false 时使用）。 */
    public Path serverDataRoot() {
        return serverDataRoot;
    }

    /** 全局数据根（跨世界审计等；v0.20 起身份组运行数据不再放这里）。 */
    public Path globalDataRoot() {
        return globalDataRoot;
    }

    /**
     * 身份运行时数据根：{@code <主存档根>/samsara/identity/}。
     *
     * <p>固定跟随主存档，不随世界栈或 {@code globalFollowWorld} 切换；
     * 即使 {@code worldDataRoot} 被覆盖也保持这个位置，避免切换世界分裂背包。
     */
    public Path identityDataRoot() {
        return worldRoot.resolve("samsara").resolve("identity").toAbsolutePath().normalize();
    }

    public boolean globalFollowWorld() {
        return globalFollowWorld;
    }

    public String migrationSummary() {
        return migrationSummary;
    }

    public String globalMigrationSummary() {
        return globalMigrationSummary;
    }

    /** 世界数据路径的安全解析（相对路径不得越界）。 */
    public Path world(String relative) {
        return safeResolve(worldDataRoot, relative);
    }

    public Path global(String relative) {
        return safeResolve(globalDataRoot, relative);
    }

    private static Path safeResolve(Path root, String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("路径越界: " + relative);
        }
        return resolved;
    }

    // ---- globalFollowWorld 切换（双向迁移） ----

    /**
     * 切换全局数据位置并迁移。返回 null = 成功；否则为错误说明。
     * 迁移期间暂停全局数据读写；迁移成功后才切换 {@link #globalDataRoot}。
     */
    public static synchronized String applyGlobalFollowWorld(boolean follow) {
        StoragePaths paths = instance;
        if (paths == null) {
            // 服务器还没起来：保存开关即可，首次 init 会按新值放置。
            return null;
        }
        if (paths.globalFollowWorld == follow) {
            return null;
        }
        ConfigManager config = ConfigManager.getInstance();
        String override = config.string(ConfigSchema.STORAGE, "globalDataRoot").trim();
        Path target = resolveGlobalRoot(paths.worldDataRoot, paths.serverDataRoot,
                follow, override);
        Path source = paths.globalDataRoot;
        if (source.equals(target)) {
            paths.globalFollowWorld = follow;
            return null;
        }
        String error;
        GLOBAL_IO_LOCK.writeLock().lock();
        globalIoPaused = true;
        try {
            error = StorageMigration.moveGlobal(source, target);
        } finally {
            globalIoPaused = false;
            GLOBAL_IO_LOCK.writeLock().unlock();
        }
        if (error != null) {
            globalMigrationSummary = "失败：" + error;
            Samsara.LOGGER.warn("[周目管理 samsara] 全局数据迁移失败，保持原开关：{}", error);
            return error;
        }
        paths.globalDataRoot = target;
        paths.globalFollowWorld = follow;
        globalMigrationSummary = "成功：" + source + " → " + target;
        Samsara.LOGGER.info("[周目管理 samsara] 全局数据位置已切换（跟随存档={}）：{} → {}",
                follow, source, target);
        return null;
    }

    public static boolean globalIoPaused() {
        return globalIoPaused;
    }

    /** 使用读锁访问全局数据；迁移期间会阻塞。 */
    public static <T> T withGlobalData(java.util.function.Supplier<T> action) {
        GLOBAL_IO_LOCK.readLock().lock();
        try {
            return action.get();
        } finally {
            GLOBAL_IO_LOCK.readLock().unlock();
        }
    }
}
