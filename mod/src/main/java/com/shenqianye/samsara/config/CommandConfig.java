package com.shenqianye.samsara.config;

import com.shenqianye.samsara.track.TrackRetention;

import java.nio.file.Path;
import java.util.List;

/**
 * 命令功能配置（{@code config/samsara/commands.yml}）。
 *
 * <p>所有键都由 {@link ConfigSchema} 定义；本类只把值缓存成字段，供命令侧读取。
 * 修改后可用 {@code /samsara debug reload} 或 Web 面板热重载（无需重启）。
 */
public final class CommandConfig {
    private static CommandConfig instance;

    private boolean whereEnabled;
    private boolean whereBroadcast;
    private int whereBroadcastCooldownSeconds;
    private boolean whereShowCoordinates;
    private boolean whereShowDimension;
    private boolean whereShowChunk;
    private boolean whereShowFacing;

    private boolean tpaEnabled;
    private int tpaRequestTimeoutSeconds;
    private boolean tpaCrossDimension;

    private boolean viewProtectViewer;
    private int viewFreeRoamRadius;
    private boolean viewScrubEnabled;

    private boolean viewAimEnabled;
    private double viewAimDeadzone;
    private double viewAimEdge;
    private double viewAimMaxDegPerSec;
    private boolean viewAimPitch;
    private boolean viewAimSnap;
    private int viewAimHz;
    private boolean viewAllowEquipmentDrop;
    private int viewGroupMaxTargets;
    private int viewGroupMaxSamples;
    private int viewDedupeWindowMs;
    private boolean viewChatToConsole;

    private boolean rewindChatHolograms;
    private int rewindChatMaxDisplays;
    private int rewindChatRadius;
    private int rewindChatWindowMs;

    private boolean trackRecordInventory;
    private TrackRetention.Settings trackRetention = TrackRetention.Settings.defaults();
    private int trackChatRetentionDays;
    private int trackClipMaxDurationMs;
    private int trackClipMaxPerPlayer;

    private boolean chatCompletionEnabled;
    private boolean completionFuzzyEnabled;
    private int completionMaxItems;

    private int shutdownCountdownSeconds;
    private boolean outputAutoContinuePrefix;

    private boolean chainVoidDropSlowFalling;
    private int chainVoidDropSlowFallingSeconds;

    private CommandConfig() {
        reload();
    }

    public static synchronized CommandConfig getInstance() {
        if (instance == null) instance = new CommandConfig();
        return instance;
    }

    // ---- where 命令查询 ----

    public boolean isWhereEnabled() {
        return whereEnabled;
    }

    public boolean isWhereBroadcastEnabled() {
        return whereBroadcast;
    }

    public int whereBroadcastCooldownSeconds() {
        return whereBroadcastCooldownSeconds;
    }

    public boolean whereShowCoordinates() {
        return whereShowCoordinates;
    }

    public boolean whereShowDimension() {
        return whereShowDimension;
    }

    public boolean whereShowChunk() {
        return whereShowChunk;
    }

    public boolean whereShowFacing() {
        return whereShowFacing;
    }

    // ---- tpa 命令查询 ----

    public boolean isTpaEnabled() {
        return tpaEnabled;
    }

    public int tpaRequestTimeoutSeconds() {
        return tpaRequestTimeoutSeconds;
    }

    public boolean tpaCrossDimension() {
        return tpaCrossDimension;
    }

    // ---- 回看（track view）查询 ----

    public boolean viewProtectViewer() {
        return viewProtectViewer;
    }

    public int viewFreeRoamRadius() {
        return viewFreeRoamRadius;
    }

    public boolean viewScrubEnabled() {
        return viewScrubEnabled;
    }

    public boolean viewAimEnabled() {
        return viewAimEnabled;
    }

    public double viewAimDeadzone() {
        return viewAimDeadzone;
    }

    public double viewAimEdge() {
        return viewAimEdge;
    }

    public double viewAimMaxDegPerSec() {
        return viewAimMaxDegPerSec;
    }

    public boolean viewAimPitch() {
        return viewAimPitch;
    }

    /** 自动瞄准是否使用快速对准（snap）；false = 平滑转动。 */
    public boolean viewAimSnap() {
        return viewAimSnap;
    }

    /** snap 模式每秒对准次数。 */
    public int viewAimHz() {
        return viewAimHz;
    }

    /** F-107：捕捉后普通实体死亡时默认允许掉落录制装备。 */
    public boolean viewAllowEquipmentDrop() {
        return viewAllowEquipmentDrop;
    }

    /** F-97：群组时段回放最多目标数。 */
    public int viewGroupMaxTargets() {
        return viewGroupMaxTargets;
    }

    /** F-97：群组回放合计样本上限。 */
    public int viewGroupMaxSamples() {
        return viewGroupMaxSamples;
    }

    /** F-99：同目标共享人偶的最大轨迹时间差（毫秒）。 */
    public int viewDedupeWindowMs() {
        return viewDedupeWindowMs;
    }

    /** F-111：回看聊天是否同时写服务端日志。 */
    public boolean viewChatToConsole() {
        return viewChatToConsole;
    }

    // ---- 倒流留言 ----

    public boolean rewindChatHolograms() {
        return rewindChatHolograms;
    }

    public int rewindChatMaxDisplays() {
        return rewindChatMaxDisplays;
    }

    public int rewindChatRadius() {
        return rewindChatRadius;
    }

    public int rewindChatWindowMs() {
        return rewindChatWindowMs;
    }

    // ---- 轨迹 v2 查询 ----

    public boolean trackRecordInventory() {
        return trackRecordInventory;
    }

    public TrackRetention.Settings trackRetention() {
        return trackRetention;
    }

    /** F-109：聊天记录保留天数，0 = 永久。 */
    public int trackChatRetentionDays() {
        return trackChatRetentionDays;
    }

    public int trackClipMaxDurationMs() {
        return trackClipMaxDurationMs;
    }

    public int trackClipMaxPerPlayer() {
        return trackClipMaxPerPlayer;
    }

    // ---- 指令补全查询 ----

    public boolean isChatCompletionEnabled() {
        return chatCompletionEnabled;
    }

    public boolean isCompletionFuzzyEnabled() {
        return completionFuzzyEnabled;
    }

    public int completionMaxItems() {
        return completionMaxItems;
    }

    public int shutdownCountdownSeconds() {
        return shutdownCountdownSeconds;
    }

    public boolean outputAutoContinuePrefix() {
        return outputAutoContinuePrefix;
    }

    // ---- 跨世界传送链 ----

    public boolean chainVoidDropSlowFalling() {
        return chainVoidDropSlowFalling;
    }

    public int chainVoidDropSlowFallingSeconds() {
        return chainVoidDropSlowFallingSeconds;
    }

    /** 配置文件绝对路径 */
    public String configPath() {
        return ConfigManager.getInstance().file(ConfigSchema.COMMANDS).toAbsolutePath()
                .normalize().toString();
    }

    // ---- 热重载 ----

    public synchronized String reload() {
        ConfigManager config = ConfigManager.getInstance();
        String id = ConfigSchema.COMMANDS;
        whereEnabled = config.bool(id, "where.enabled");
        whereBroadcast = config.bool(id, "where.broadcast");
        whereBroadcastCooldownSeconds = config.integer(id, "where.broadcastCooldownSeconds");
        whereShowCoordinates = config.bool(id, "where.showCoordinates");
        whereShowDimension = config.bool(id, "where.showDimension");
        whereShowChunk = config.bool(id, "where.showChunk");
        whereShowFacing = config.bool(id, "where.showFacing");

        tpaEnabled = config.bool(id, "tpa.enabled");
        tpaRequestTimeoutSeconds = config.integer(id, "tpa.requestTimeoutSeconds");
        tpaCrossDimension = config.bool(id, "tpa.crossDimension");

        viewProtectViewer = config.bool(id, "view.protectViewer");
        viewFreeRoamRadius = config.integer(id, "view.freeRoamRadius");
        viewScrubEnabled = config.bool(id, "view.scrub.enabled");
        viewAimEnabled = config.bool(id, "view.aim.enabled");
        viewAimDeadzone = config.decimal(id, "view.aim.deadzone");
        viewAimEdge = Math.max(viewAimDeadzone + 1.0, config.decimal(id, "view.aim.edge"));
        viewAimMaxDegPerSec = config.decimal(id, "view.aim.maxDegPerSec");
        viewAimPitch = config.bool(id, "view.aim.pitch");
        viewAimSnap = !"smooth".equalsIgnoreCase(config.string(id, "view.aim.mode"));
        viewAimHz = Math.max(2, Math.min(20, config.integer(id, "view.aim.hz")));
        viewAllowEquipmentDrop = config.bool(id, "view.allowEquipmentDrop");
        viewGroupMaxTargets = Math.max(1, config.integer(id, "view.groupMaxTargets"));
        viewGroupMaxSamples = Math.max(10_000, config.integer(id, "view.groupMaxSamples"));
        viewDedupeWindowMs = Math.max(0, config.integer(id, "view.dedupeWindowMs"));
        viewChatToConsole = config.bool(id, "view.chatToConsole");

        rewindChatHolograms = config.bool(id, "rewind.chatHolograms");
        rewindChatMaxDisplays = Math.max(1, config.integer(id, "rewind.chatMaxDisplays"));
        rewindChatRadius = Math.max(16, config.integer(id, "rewind.chatRadius"));
        rewindChatWindowMs = Math.max(1_000, config.integer(id, "rewind.chatWindowMs"));

        trackRecordInventory = config.bool(id, "track.recordInventory");
        trackRetention = new TrackRetention.Settings(
                TrackRetention.Mode.parse(config.string(id, "track.retention.mode")),
                clamp(config.decimal(id, "track.retention.adaptive.q"), 0.0001, 0.9999),
                Math.max(1, config.integer(id, "track.retention.adaptive.skeletonPerDay")),
                config.bool(id, "track.retention.keepAll"),
                Math.max(0, config.integer(id, "track.retention.maxBytesPerPlayer")),
                TrackRetention.parseCurve(config.value(id, "track.retention.curve")));
        trackChatRetentionDays = Math.max(0, config.integer(id, "track.chatRetentionDays"));
        trackClipMaxDurationMs = Math.max(1_000, config.integer(id, "track.clip.maxDurationMs"));
        trackClipMaxPerPlayer = Math.max(1, config.integer(id, "track.clip.maxPerPlayer"));

        chatCompletionEnabled = config.bool(id, "completion.chatList");
        completionFuzzyEnabled = config.bool(id, "completion.fuzzy");
        completionMaxItems = Math.max(1, config.integer(id, "completion.maxItems"));
        shutdownCountdownSeconds = Math.max(1, config.integer(id, "shutdown.countdownSeconds"));
        outputAutoContinuePrefix = config.bool(id, "output.autoContinuePrefix");
        chainVoidDropSlowFalling = config.bool(id, "chain.voidDropSlowFalling");
        chainVoidDropSlowFallingSeconds = Math.max(0,
                Math.min(300, config.integer(id, "chain.voidDropSlowFallingSeconds")));
        return "命令配置已重载：" + (whereEnabled ? "where 启用" : "where 禁用")
                + " · 广播" + (whereBroadcast ? "允许" : "禁止")
                + " · tpa " + (tpaEnabled ? "启用" : "禁用")
                + "（超时 " + tpaRequestTimeoutSeconds + "s）"
                + " · 回看保护 " + (viewProtectViewer ? "开" : "关")
                + " · 回看拖动 " + (viewScrubEnabled ? "默认开" : "默认关")
                + " · 回看自瞄 " + (viewAimEnabled ? "默认开" : "默认关")
                + (viewAimEnabled ? "（" + (viewAimSnap ? "snap " + viewAimHz + "Hz" : "smooth")
                        + "）" : "")
                + " · 装备掉落 " + (viewAllowEquipmentDrop ? "允许" : "禁止")
                + " · 背包快照 " + (trackRecordInventory ? "开" : "关")
                + " · 保留策略 " + trackRetention.effectiveMode().id()
                + " · 聊天栏补全 " + (chatCompletionEnabled ? "开" : "关")
                + " · 来源 " + configPath();
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** 供测试/迁移后强制重读。 */
    public synchronized void load() {
        reload();
    }

    Path file() {
        return ConfigManager.getInstance().file(ConfigSchema.COMMANDS);
    }
}
