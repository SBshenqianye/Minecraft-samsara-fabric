package com.shenqianye.samsara.identity;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * v0.20 身份组服务：解析、只读诊断、账号切换同步。
 *
 * <p>身份组不是认证系统：不注册、不登录、不加锁。唯一职责是把多个账号入口
 * 映射到同一个 data UUID；安全由其他认证模组负责。
 */
public final class IdentityService {
    private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss");
    private static final IdentityService INSTANCE = new IdentityService();

    private volatile boolean ready;
    private volatile MinecraftServer server;
    private IdentityConfigStore configStore;
    private volatile IdentityConfig config = new IdentityConfig();
    private IdentityStateStore stateStore;
    private IdentityDataSync dataSync;
    private Path identityRoot;
    private volatile String lastError = "";
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    private IdentityService() {
    }

    public static IdentityService getInstance() {
        return INSTANCE;
    }

    public static boolean isReady() {
        return INSTANCE.ready;
    }

    /** mod init：配置路径已知，先加载 identity.yml（运行时根要等服务器启动）。 */
    public synchronized void initConfig() {
        if (configStore == null) {
            configStore = new IdentityConfigStore(
                    StoragePaths.configRoot().resolve("identity.yml"));
        }
        config = configStore.load();
        Samsara.LOGGER.info("[周目管理 samsara] 身份组配置：{}（{} 个组，{}）",
                configStore.file(), config.groups.size(),
                config.enabled ? "已开启" : "已关闭");
        for (IdentityValidator.Issue issue : configStore.issues()) {
            Samsara.LOGGER.warn("[周目管理 samsara] identity.yml 非法：{} · {}",
                    issue.field(), issue.message());
        }
    }

    /** SERVER_STARTING：初始化运行时根、状态、journal 恢复与 v0.19 迁移。 */
    public synchronized void init(MinecraftServer server) {
        this.server = server;
        if (configStore == null) {
            initConfig();
        }
        identityRoot = StoragePaths.get().identityDataRoot();
        try {
            Files.createDirectories(identityRoot.resolve("playerdata"));
            Files.createDirectories(identityRoot.resolve("journal"));
            Files.createDirectories(identityRoot.resolve("archive"));
            Files.createDirectories(identityRoot.resolve("audit"));
        } catch (IOException e) {
            lastError = "身份数据目录创建失败：" + e.getMessage();
            Samsara.LOGGER.warn("[周目管理 samsara] {}", lastError);
        }
        stateStore = new IdentityStateStore(identityRoot.resolve("state.json"));
        stateStore.load();
        dataSync = new IdentityDataSync(identityRoot,
                server.getWorldPath(LevelResource.PLAYER_DATA_DIR),
                server.getWorldPath(LevelResource.PLAYER_ADVANCEMENTS_DIR),
                server.getWorldPath(LevelResource.PLAYER_STATS_DIR));
        IdentityJournal.Recovery recovery = dataSync.journal().recover();
        if (recovery.rolledBack() > 0 || recovery.cleaned() > 0) {
            Samsara.LOGGER.info("[周目管理 samsara] 身份 journal 恢复：回滚 {} · 清理 {}",
                    recovery.rolledBack(), recovery.cleaned());
        }
        for (String blocked : recovery.blocked()) {
            Samsara.LOGGER.warn("[周目管理 samsara] 身份 journal 无法确认：{}", blocked);
        }
        String migration = IdentityMigration.migrate(StoragePaths.get(), configStore);
        if (!migration.isBlank()) {
            Samsara.LOGGER.info("[周目管理 samsara] {}", migration);
        }
        config = configStore.load();
        if (config.enabled && !configStore.valid()) {
            // 配置非法时禁止自动同步，避免用错误映射覆盖数据。
            for (IdentityStateStore.GroupState state : stateStore.all().values()) {
                state.syncBlocked = true;
            }
            stateStore.save();
        }
        ready = true;
        Samsara.LOGGER.info("[周目管理 samsara] 身份运行时：{} · {} 个组 · 同步{}",
                identityRoot, config.groups.size(),
                config.enabled ? (config.syncPlayerData ? "开启" : "关闭") : "整体关闭");
    }

    // ---- 查询 ----

    public IdentityConfig config() {
        return config;
    }

    /** Web 保存成功后同步内存模型（不再二次读盘）。 */
    public void applySavedConfig(IdentityConfig next) {
        if (next != null) {
            config = next.copy();
        }
    }

    public IdentityConfigStore configStore() {
        return configStore;
    }

    public IdentityStateStore stateStore() {
        return stateStore;
    }

    public IdentityDataSync dataSync() {
        return dataSync;
    }

    public Path identityRoot() {
        return identityRoot;
    }

    public MinecraftServer server() {
        return server;
    }

    public boolean enabled() {
        return config != null && config.enabled;
    }

    public boolean enabledAndValid() {
        return enabled() && configStore != null && configStore.valid();
    }

    public int maxOwners() {
        return config == null ? IdentityConfig.DEFAULT_MAX_OWNERS : config.maxOwners;
    }

    /** 实际账号 UUID → 数据 UUID；关闭/非法配置/不在组内时原样返回。 */
    public UUID identityOf(UUID uuid) {
        return IdentityResolver.identityOf(config, configStore != null && configStore.valid(), uuid);
    }

    public boolean sameIdentity(UUID a, UUID b) {
        UUID left = identityOf(a);
        UUID right = identityOf(b);
        return left != null && left.equals(right);
    }

    /** 按账号入口查找组；data 本身也接受，便于诊断/预览。 */
    public IdentityConfig.Group groupOf(UUID uuid) {
        return IdentityResolver.groupOf(config, uuid);
    }

    public IdentityConfig.Group groupByName(String name) {
        return IdentityResolver.groupByName(config, name);
    }

    /** 同一身份组的全部账号入口 + data UUID（轨迹/活动聚合用）。 */
    public List<UUID> aliasesOf(UUID uuid) {
        if (!enabledAndValid()) {
            return uuid == null ? List.of() : List.of(uuid);
        }
        return IdentityResolver.aliasesOf(config, uuid);
    }

    /** 按身份去重，保留首次出现的实际 UUID。 */
    public List<UUID> dedupeByData(Collection<UUID> uuids) {
        return IdentityResolver.dedupeByData(config,
                configStore != null && configStore.valid(), uuids);
    }

    /** 该实际账号是否有同一身份的在线玩家。 */
    public ServerPlayer onlineFor(UUID ownerUuid) {
        if (ownerUuid == null || server == null) {
            return null;
        }
        UUID data = identityOf(ownerUuid);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (identityOf(player.getUUID()).equals(data)) {
                return player;
            }
        }
        return null;
    }

    public boolean isOnlineInGroup(IdentityConfig.Group group) {
        if (group == null || server == null) {
            return false;
        }
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (sameIdentity(player.getUUID(), group.dataUuid())
                    || group.contains(player.getUUID())) {
                return true;
            }
        }
        return false;
    }

    // ---- 生命周期 ----

    public void onJoin(ServerPlayer player) {
        if (!ready || server == null || player == null || config == null || !config.enabled) {
            return;
        }
        UUID uuid = player.getUUID();
        String name = player.getGameProfile().name();
        if (!configStore.valid()) {
            String reason = "身份组配置非法，已拒绝进入：" + firstIssue();
            IdentityAudit.log(identityRoot, "join_rejected", name, uuid, reason);
            disconnect(player, reason);
            return;
        }
        IdentityConfig.Group group = groupOf(uuid);
        if (group == null || !config.syncPlayerData) {
            return;
        }
        UUID data = group.dataUuid();
        if (data == null) {
            disconnect(player, "身份组 data UUID 无效，请联系管理员");
            return;
        }
        IdentityStateStore.GroupState state = stateStore.getOrCreate(data);
        if (state.syncBlocked) {
            String reason = "身份组「" + group.name + "」同步被阻断（sync-blocked），请联系管理员";
            IdentityAudit.log(identityRoot, "join_blocked", name, uuid, reason);
            disconnect(player, reason);
            return;
        }
        UUID lastWriter = state.lastWriterUuid();
        if (lastWriter == null) {
            // F-47：用当前登录账号数据初始化 data，不反向覆盖当前账号。
            archiveData(data, "init");
            SyncResult push = pushPlayer(player, data, group.name);
            if (!push.ok()) {
                failJoin(player, group, data, state, push, "初始化");
                return;
            }
            state.lastWriter = uuid.toString();
            state.lastSyncAt = System.currentTimeMillis();
            state.syncBlocked = false;
            stateStore.put(data, state);
            stateStore.save();
            IdentityAudit.log(identityRoot, "sync_init", name, uuid,
                    "data=" + data + " group=" + group.name);
        } else if (!lastWriter.equals(uuid)) {
            // F-46：只有账号切换才同步 data → 当前账号。
            IdentityDataSync.Result pull = dataSync.pull(data, uuid, group.name);
            if (!pull.ok()) {
                failJoin(player, group, data, state,
                        new SyncResult(pull.ok(), pull.businessFailure(), pull.message(),
                                pull.blocked()), "恢复");
                return;
            }
            if (!applyIdentityData(player, data)) {
                state.syncBlocked = true;
                stateStore.put(data, state);
                stateStore.save();
                IdentityAudit.log(identityRoot, "sync_apply_failed", name, uuid, "data=" + data);
                disconnect(player, "身份组「" + group.name
                        + "」数据应用失败，已阻止进入以保护存档");
                return;
            }
            state.lastWriter = uuid.toString();
            state.lastSyncAt = System.currentTimeMillis();
            stateStore.put(data, state);
            stateStore.save();
            IdentityAudit.log(identityRoot, "sync_pull", name, uuid,
                    "data=" + data + " group=" + group.name);
        }
        sessions.put(uuid, new Session(data, group.name, name));
    }

    private void failJoin(ServerPlayer player, IdentityConfig.Group group, UUID data,
                          IdentityStateStore.GroupState state, SyncResult result, String phase) {
        if (result.technical()) {
            state.syncBlocked = true;
            stateStore.put(data, state);
            stateStore.save();
        }
        IdentityAudit.log(identityRoot, "sync_failed", player.getGameProfile().name(),
                player.getUUID(), phase + " data=" + data + " " + result.message());
        disconnect(player, "身份组「" + group.name + "」" + phase + "失败，已拒绝进入："
                + result.message());
    }

    public void onDisconnect(ServerPlayer player) {
        if (!ready || player == null) {
            return;
        }
        UUID uuid = player.getUUID();
        Session session = sessions.remove(uuid);
        if (session == null || dataSync == null || config == null || !config.enabled) {
            return;
        }
        IdentityConfig.Group group = groupOf(uuid);
        if (group == null || !config.syncPlayerData) {
            return;
        }
        SyncResult push = pushPlayer(player, session.data, group.name);
        IdentityStateStore.GroupState state = stateStore.getOrCreate(session.data);
        if (push.ok()) {
            state.lastWriter = uuid.toString();
            state.lastSyncAt = System.currentTimeMillis();
            state.syncBlocked = false;
        } else if (push.technical()) {
            state.syncBlocked = true;
        }
        stateStore.put(session.data, state);
        stateStore.save();
        IdentityAudit.log(identityRoot, push.ok() ? "sync_push" : "sync_push_failed",
                player.getGameProfile().name(), uuid,
                "data=" + session.data + " group=" + group.name + " " + push.message());
    }

    public void onServerStopping(MinecraftServer server) {
        if (!ready || server == null) {
            return;
        }
        for (ServerPlayer player : new ArrayList<>(server.getPlayerList().getPlayers())) {
            onDisconnect(player);
        }
        sessions.clear();
    }

    public void tick(MinecraftServer server) {
        com.shenqianye.samsara.pet.PetFollowTracker.tick(server);
    }

    // ---- 玩家数据 ----

    /** 把在线玩家当前状态写到 identity/playerdata/<data>.dat，并同步进度/统计。 */
    private SyncResult pushPlayer(ServerPlayer player, UUID data, String group) {
        try {
            saveProgress(player);
            TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING,
                    server.registryAccess());
            player.saveWithoutId(output);
            CompoundTag tag = output.buildResult();
            tag.remove("UUID");
            if (tag.isEmpty()) {
                // 极端情况下序列化结果为空（或玩家已进入移除流程）：
                // 回退到服务器刚保存的原版玩家文件，绝不把空数据写进 data。
                IdentityDataSync.Result fallback = dataSync.push(player.getUUID(), data, group);
                if (!fallback.ok()) {
                    return new SyncResult(false, fallback.businessFailure(),
                            "玩家数据序列化为空且原版文件不可用：" + fallback.message(),
                            fallback.blocked());
                }
                return SyncResult.success();
            }
            writeTagAtomic(tag, data, player.getUUID(), group);
            IdentityDataSync.Result meta = dataSync.pushMetadata(player.getUUID(), data, group);
            if (!meta.ok()) {
                return new SyncResult(false, meta.businessFailure(), meta.message(), meta.blocked());
            }
            return SyncResult.success();
        } catch (Exception e) {
            return new SyncResult(false, false, e.getMessage(), true);
        }
    }

    private void writeTagAtomic(CompoundTag tag, UUID data, UUID from, String group) {
        Path target = dataSync.identityData(data);
        Path staged = target.resolveSibling(target.getFileName() + ".staged");
        Path backup = target.resolveSibling(target.getFileName() + ".bak");
        Path journalFile = null;
        try {
            Files.createDirectories(target.getParent());
            NbtIo.writeCompressed(tag, staged);
            String checksum = IdentityJournal.sha256(staged);
            journalFile = dataSync.journal().prepare(group, from, data, target, backup, checksum);
            IdentityJournal.copyAtomic(staged, target, backup);
            dataSync.journal().commit(journalFile);
        } catch (IOException e) {
            throw new RuntimeException("玩家数据写入失败：" + e.getMessage(), e);
        } finally {
            try {
                Files.deleteIfExists(staged);
            } catch (IOException ignored) {
            }
        }
    }

    private boolean applyIdentityData(ServerPlayer player, UUID data) {
        if (server == null || player == null) {
            return false;
        }
        UUID current = player.getUUID();
        Path file = dataSync.worldData(current);
        try {
            CompoundTag tag = NbtIo.readCompressed(file, NbtAccounter.unlimitedHeap());
            tag.remove("UUID");
            Position position = readPosition(tag, player);
            player.load(TagValueInput.create(ProblemReporter.DISCARDING,
                    server.registryAccess(), tag));
            ServerLevel target = server.getLevel(position.dimension());
            if (target != null) {
                player.teleportTo(target, position.x(), position.y(), position.z(),
                        Set.of(), position.yaw(), position.pitch(), false);
            }
            player.inventoryMenu.broadcastChanges();
            reloadAdvancements(player);
            reloadStats(player);
            return true;
        } catch (Exception e) {
            lastError = "应用身份数据失败：" + e.getMessage();
            Samsara.LOGGER.warn("[周目管理 samsara] {}", lastError);
            return false;
        }
    }

    private void saveProgress(ServerPlayer player) {
        try {
            player.getAdvancements().save();
        } catch (Exception e) {
            Samsara.LOGGER.debug("[周目管理 samsara] 进度落盘失败：{}", e.getMessage());
        }
        try {
            player.getStats().save();
        } catch (Exception e) {
            Samsara.LOGGER.debug("[周目管理 samsara] 统计落盘失败：{}", e.getMessage());
        }
    }

    private void reloadAdvancements(ServerPlayer player) {
        try {
            player.getAdvancements().reload(server.getAdvancements());
        } catch (Exception e) {
            Samsara.LOGGER.debug("[周目管理 samsara] 进度重载失败：{}", e.getMessage());
        }
    }

    private void reloadStats(ServerPlayer player) {
        Path file = dataSync.worldStats(player.getUUID());
        if (!Files.exists(file)) {
            return;
        }
        try {
            String json = Files.readString(file, java.nio.charset.StandardCharsets.UTF_8);
            player.getStats().parse(server.getFixerUpper(),
                    com.google.gson.JsonParser.parseString(json));
            player.getStats().markAllDirty();
            player.getStats().sendStats(player);
        } catch (Exception e) {
            Samsara.LOGGER.debug("[周目管理 samsara] 统计重载失败：{}", e.getMessage());
        }
    }

    private void archiveData(UUID data, String label) {
        if (dataSync == null || data == null) {
            return;
        }
        Path dir = identityRoot.resolve("archive").resolve(label + "-"
                + LocalDateTime.now().format(TS));
        try {
            IdentityDataSync.backup(dataSync.identityData(data), dir, "data");
            IdentityDataSync.backup(dataSync.identityAdvancements(data), dir, "advancements");
            IdentityDataSync.backup(dataSync.identityStats(data), dir, "stats");
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 身份数据备份失败：{}", e.getMessage());
        }
    }

    // ---- 命令/诊断 ----

    public String status(ServerPlayer player) {
        if (!enabled()) {
            return "身份验证已关闭（identity.enabled=false）。身份组只做账号映射，不提供注册/登录。";
        }
        if (configStore != null && !configStore.valid()) {
            return "身份组配置非法，身份解析已停用：\n" + issueLines();
        }
        IdentityConfig.Group group = player == null ? null : groupOf(player.getUUID());
        if (group == null) {
            return "未加入身份组 · data = 自身 UUID " + (player == null ? "?" : player.getUUID());
        }
        IdentityStateStore.GroupState state = stateStore == null ? null
                : stateStore.get(group.dataUuid());
        StringBuilder out = new StringBuilder("身份组「").append(group.name).append("」\n")
                .append("data UUID: ").append(group.data).append("\n")
                .append("premium: ").append(group.premium == null ? "（未设置）" : group.premium)
                .append("\n")
                .append("offline: ").append(group.offline == null ? "（未设置）" : group.offline);
        if (group.note != null && !group.note.isBlank()) {
            out.append("\n备注: ").append(group.note);
        }
        if (state != null) {
            out.append("\n最后写入账号: ")
                    .append(state.lastWriter == null ? "（尚未同步）" : state.lastWriter);
            if (state.syncBlocked) {
                out.append("\n⚠ 同步已阻断（sync-blocked），请联系管理员");
            }
        }
        return out.toString();
    }

    public String map() {
        if (config == null || config.groups.isEmpty()) {
            return enabled() ? "身份组列表为空" : "身份验证已关闭，身份组列表为空";
        }
        StringBuilder out = new StringBuilder(enabled() ? "身份组：" : "身份验证已关闭；当前配置：");
        int index = 1;
        for (IdentityConfig.Group group : config.groups) {
            if (group == null) {
                continue;
            }
            out.append("\n").append(index++).append(". ").append(group.name)
                    .append(" · data=").append(group.data)
                    .append(" · premium=").append(group.premium == null ? "-" : group.premium)
                    .append(" · offline=").append(group.offline == null ? "-" : group.offline);
            if (stateStore != null) {
                IdentityStateStore.GroupState state = stateStore.get(group.dataUuid());
                if (state != null) {
                    out.append(" · lastWriter=")
                            .append(state.lastWriter == null ? "-" : state.lastWriter);
                    if (state.syncBlocked) {
                        out.append(" · SYNC-BLOCKED");
                    }
                }
            }
        }
        return out.toString();
    }

    public String preview(UUID uuid) {
        if (uuid == null) {
            return "请提供有效 UUID 或玩家名";
        }
        IdentityConfig.Group group = groupOf(uuid);
        UUID data = identityOf(uuid);
        StringBuilder out = new StringBuilder();
        out.append("UUID: ").append(uuid).append("\n")
                .append("身份组: ").append(group == null ? "（无，按自身处理）" : group.name).append("\n")
                .append("data UUID: ").append(data).append("\n")
                .append("同组账号: ");
        List<UUID> aliases = aliasesOf(uuid);
        for (int i = 0; i < aliases.size(); i++) {
            if (i > 0) {
                out.append(", ");
            }
            out.append(aliases.get(i));
        }
        out.append("\n").append(IdentityPreview.describe(server, identityRoot, data, aliases));
        return out.toString();
    }

    public String auditReport() {
        StringBuilder out = new StringBuilder(enabled() ? "身份组审计：" : "身份验证已关闭；审计配置：");
        if (configStore != null && !configStore.valid()) {
            out.append("\n配置非法：");
            for (IdentityValidator.Issue issue : configStore.issues()) {
                out.append("\n  · ").append(issue.field()).append("：").append(issue.message());
            }
        } else {
            out.append("\n配置校验通过，共 ").append(config.groups.size()).append(" 个组");
        }
        if (stateStore != null) {
            for (Map.Entry<String, IdentityStateStore.GroupState> entry
                    : stateStore.all().entrySet()) {
                IdentityStateStore.GroupState state = entry.getValue();
                out.append("\n· data ").append(entry.getKey())
                        .append(" · lastWriter=")
                        .append(state.lastWriter == null ? "-" : state.lastWriter)
                        .append(state.syncBlocked ? " · SYNC-BLOCKED" : "");
            }
        }
        return out.toString();
    }

    public String reload() {
        if (configStore == null) {
            initConfig();
        }
        IdentityConfigStore.SaveResult result = configStore.reload();
        config = configStore.config();
        if (!result.ok()) {
            return "identity.yml 重载失败：\n" + issueLines();
        }
        IdentityAudit.log(identityRoot, "reload", "system", null,
                "groups=" + config.groups.size());
        return "identity.yml 已重载：" + config.groups.size() + " 个组 · "
                + (config.enabled ? "已开启" : "已关闭");
    }

    private String issueLines() {
        StringBuilder out = new StringBuilder();
        for (IdentityValidator.Issue issue : configStore.issues()) {
            if (out.length() > 0) {
                out.append("\n");
            }
            out.append("  · ").append(issue.field()).append("：").append(issue.message());
        }
        return out.toString();
    }

    private String firstIssue() {
        return configStore.issues().isEmpty() ? "未知错误"
                : configStore.issues().get(0).field() + " "
                + configStore.issues().get(0).message();
    }

    private void disconnect(ServerPlayer player, String message) {
        try {
            player.connection.disconnect(Component.literal("[samsara] " + message));
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] 断开玩家失败：{}", e.getMessage());
        }
    }

    public String lastError() {
        return lastError == null ? "" : lastError;
    }

    // ---- 工具 ----

    private static Position readPosition(CompoundTag tag, ServerPlayer fallback) {
        ListTag pos = tag.getListOrEmpty("Pos");
        double x = pos.getDoubleOr(0, fallback.getX());
        double y = pos.getDoubleOr(1, fallback.getY());
        double z = pos.getDoubleOr(2, fallback.getZ());
        ListTag rotation = tag.getListOrEmpty("Rotation");
        float yaw = rotation.getFloatOr(0, fallback.getYRot());
        float pitch = rotation.getFloatOr(1, fallback.getXRot());
        String dimension = tag.getStringOr("Dimension", "");
        ResourceKey<Level> key = Level.OVERWORLD;
        if (!dimension.isBlank()) {
            try {
                key = ResourceKey.create(Registries.DIMENSION, Identifier.parse(dimension));
            } catch (RuntimeException ignored) {
                // 保持主世界
            }
        }
        return new Position(key, x, y, z, yaw, pitch);
    }

    private record Position(ResourceKey<Level> dimension, double x, double y, double z,
                            float yaw, float pitch) {
    }

    private record Session(UUID data, String group, String name) {
    }

    private record SyncResult(boolean ok, boolean businessFailure, String message,
                              boolean blocked) {
        static SyncResult success() {
            return new SyncResult(true, false, "", false);
        }

        boolean technical() {
            return !ok && !businessFailure;
        }
    }
}
