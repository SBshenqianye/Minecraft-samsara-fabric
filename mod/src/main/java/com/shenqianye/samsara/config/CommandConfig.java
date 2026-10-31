package com.shenqianye.samsara.config;

import java.nio.file.Path;

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

    private boolean viewAimEnabled;
    private double viewAimDeadzone;
    private double viewAimEdge;
    private double viewAimMaxDegPerSec;
    private boolean viewAimPitch;

    private boolean chatCompletionEnabled;
    private boolean completionFuzzyEnabled;
    private int completionMaxItems;

    private int shutdownCountdownSeconds;
    private boolean outputAutoContinuePrefix;

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
        viewAimEnabled = config.bool(id, "view.aim.enabled");
        viewAimDeadzone = config.decimal(id, "view.aim.deadzone");
        viewAimEdge = Math.max(viewAimDeadzone + 1.0, config.decimal(id, "view.aim.edge"));
        viewAimMaxDegPerSec = config.decimal(id, "view.aim.maxDegPerSec");
        viewAimPitch = config.bool(id, "view.aim.pitch");

        chatCompletionEnabled = config.bool(id, "completion.chatList");
        completionFuzzyEnabled = config.bool(id, "completion.fuzzy");
        completionMaxItems = Math.max(1, config.integer(id, "completion.maxItems"));
        shutdownCountdownSeconds = Math.max(1, config.integer(id, "shutdown.countdownSeconds"));
        outputAutoContinuePrefix = config.bool(id, "output.autoContinuePrefix");
        return "命令配置已重载：" + (whereEnabled ? "where 启用" : "where 禁用")
                + " · 广播" + (whereBroadcast ? "允许" : "禁止")
                + " · tpa " + (tpaEnabled ? "启用" : "禁用")
                + "（超时 " + tpaRequestTimeoutSeconds + "s）"
                + " · 回看保护 " + (viewProtectViewer ? "开" : "关")
                + " · 回看自瞄 " + (viewAimEnabled ? "默认开" : "默认关")
                + " · 聊天栏补全 " + (chatCompletionEnabled ? "开" : "关")
                + " · 来源 " + configPath();
    }

    /** 供测试/迁移后强制重读。 */
    public synchronized void load() {
        reload();
    }

    Path file() {
        return ConfigManager.getInstance().file(ConfigSchema.COMMANDS);
    }
}
