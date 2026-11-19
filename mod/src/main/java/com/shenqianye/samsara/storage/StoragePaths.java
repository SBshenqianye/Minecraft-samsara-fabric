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
    /** 服务器级配置目录（相对服务器根）。 */
    public static final String CONFIG_DIR = "config/samsara";
    /** Agent token 文件名（位于 {@link #configRoot()}）。 */
    public static final String AGENT_TOKENS_FILE = "agent-tokens.json";
    /** OP 认证文件名（位于 {@link #configRoot()}）。 */
    public static final String OP_AUTH_FILE = "op-auth.yml";

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

    /**
     * 当前运行实例的配置根下固定文件。
     *
     * <p>只接受文件名：带路径分隔符或 `..` 的输入直接拒绝，避免调用方把配置写到根目录之外。
     */
    public static Path configFile(String name) {
        if (name == null || name.isBlank()
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            throw new IllegalArgumentException("非法配置文件名: " + name);
        }
        return configRoot().resolve(name);
    }

    /**
     * 游戏 config 根目录（{@code <game>/config}）。
     *
     * <p>只有「按 serverRoot 计算第三方配置位置」的调用方需要它；FabricLoader 不可用时
     * 回退到 {@code <serverRoot>/config}（单测/独立进程）。
     */
    public static Path gameConfigDir(Path fallbackServerRoot) {
        try {
            return FabricLoader.getInstance().getConfigDir().toAbsolutePath().normalize();
        } catch (RuntimeException | LinkageError e) {
            return fallbackServerRoot.resolve("config").toAbsolutePath().normalize();
        }
    }

    /** 服务器根下的 samsara 配置目录（备份、展示等按 serverRoot 计算的场景）。 */
    public static Path configDirAt(Path serverRoot) {
        return serverRoot.toAbsolutePath().normalize().resolve("config").resolve("samsara");
    }

    /** 服务器根下的服务器级数据根 {@code <serverRoot>/samsara}。 */
    public static Path serverDataDirAt(Path serverRoot) {
        return serverRoot.toAbsolutePath().normalize().resolve("samsara");
    }

    /** 服务器根下的跨周目数据根 {@code <serverRoot>/samsara/shared}。 */
    public static Path sharedDirAt(Path serverRoot) {
        return serverDataDirAt(serverRoot).resolve("shared");
    }

    /** 服务器根下的运行数据根 {@code <serverRoot>/samsara/runtime}。 */
    public static Path runtimeDirAt(Path serverRoot) {
        return serverDataDirAt(serverRoot).resolve("runtime");
    }

    /**
     * 当前运行实例的运行数据文件（金价/周目投票/公告历史）。
     *
     * <p>只接受文件名，与 {@link #configFile} 同样的越界检查。
     */
    public static Path runtimeFile(String name) {
        if (name == null || name.isBlank()
                || name.indexOf('/') >= 0 || name.indexOf('\\') >= 0 || name.contains("..")) {
            throw new IllegalArgumentException("非法运行数据文件名: " + name);
        }
        return get().runtimeDataRoot().resolve(name);
    }

    /** 本模组在服务器根下的备份根 {@code <serverRoot>/backups/samsara}。 */
    public static Path backupsRootAt(Path serverRoot) {
        return serverRoot.toAbsolutePath().normalize().resolve("backups").resolve("samsara");
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
            Files.createDirectories(serverDataRoot.resolve("shared"));
            Files.createDirectories(serverDataRoot.resolve("runtime"));
        } catch (IOException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 无法创建数据目录: {}", e.getMessage());
        }
        // 跨周目数据（奖池/保险基金）：旧版放在 world/samsara 下，换周目会清零；
        // 这里一次性搬到服务器级 shared，失败保留源目录。
        StorageMigration.migrateShared(worldDataRoot, serverDataRoot.resolve("shared"));
        // 运行数据（金价/周目投票/公告）从 config 复制到服务器级 runtime；
        // 源文件保留（服主可能还在看），失败不影响启动。
        StorageMigration.migrateRuntime(configRoot(), serverDataRoot.resolve("runtime"));
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

    /**
     * 跨周目共享数据根：{@code <服务器根>/samsara/shared}。
     *
     * <p>奖池/保险基金这类“换周目要滚存”的数据固定放在这里，不受
     * {@code globalFollowWorld} 影响，也不随世界备份/删除。
     */
    public Path sharedDataRoot() {
        return serverDataRoot.resolve("shared");
    }

    /** 运行数据根：{@code <服务器根>/samsara/runtime}（金价/周目投票/公告历史）。 */
    public Path runtimeDataRoot() {
        return serverDataRoot.resolve("runtime");
    }

    /** 服务器根（world 目录的上一级），用于服务器级备份路径。 */
    public Path serverRoot() {
        Path parent = worldRoot.getParent();
        return parent == null ? worldRoot : parent;
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
