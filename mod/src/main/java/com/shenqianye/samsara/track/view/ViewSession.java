package com.shenqianye.samsara.track.view;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/**
 * 一个观看者的回看会话（设计 5.7.3 / F-87）。
 * <p>
 * 状态机：IDLE → LOADING → PLAYING ⇄ PAUSED → RESTORING → IDLE；
 * LOADING 失败 → FAILED，且此前的观看者状态从未被修改。
 * 每个观看者同时最多 1 个会话；所有退出路径统一走 {@link ViewManager#restore}。
 */
public final class ViewSession {
    public enum State {
        IDLE, LOADING, PLAYING, PAUSED, RESTORING, FAILED
    }

    final UUID viewer;
    final String viewerName;
    final String targetName;
    final UUID targetUuid;
    final ViewOptions options;
    final int cycleId;
    final long windowFrom;
    final long windowTo;
    String sourceSummary;
    com.mojang.authlib.GameProfile profile;
    int liveCycleAtStart;

    volatile State state = State.LOADING;
    ViewCursor cursor;
    ViewMannequin mannequin;
    ViewDisplay display;
    com.shenqianye.samsara.track.FakeVehicle vehicle;
    ViewSnapshot snapshot;
    ServerPlayer playerRef;
    java.util.UUID vehicleId;

    /** F-97：本次回放的全部目标；单目标时只有 1 个。 */
    final java.util.List<ViewTarget> targets = new java.util.ArrayList<>();
    ViewTarget primary;
    /** 群组共享时间轴（所有目标游标窗口的并集边界）。 */
    long timelineStartMs;
    /** 当前实际跟随的目标；null = 不跟随。 */
    UUID followTarget;
    boolean followActive;
    /** 是否群组回放（2+ 目标）。 */
    boolean group;
    /** 展示名（单目标 = 名字；群组 = 逗号列表或 N 个目标）。 */
    String displayName;
    /** 本次会话加载到的聊天条数（F-111）。 */
    int chatCount;

    /** 本次会话的活动维度；其他维度目标跳过（F-97）。 */
    String activeDimension;

    long startWallMs;
    long startTrackMs;
    long endTrackMs;
    long pausedTrackMs;
    /** 当前倍速与方向（1 = 正放，-1 = 倒放）；点击控制面板可动态修改 */
    double speed;
    int direction = 1;
    /** 自动瞄准（会话内可即时开关；参数来自选项或 commands.json） */
    boolean aimEnabled;
    double aimDeadzone = ViewOptions.DEFAULT_AIM_DEADZONE;
    double aimEdge = ViewOptions.DEFAULT_AIM_EDGE;
    double aimSpeed = ViewOptions.DEFAULT_AIM_SPEED;
    boolean aimPitch = true;
    boolean aimSnap = true;
    int aimHz = 5;
    double aimSnapAccumSec = 1.0;
    long lastAimAtMs;
    ViewAim.Tracker aimTracker;

    /** 拖动模式：Shift 后退 / Ctrl 快进 / 空格暂停；长按自动加速；不读取滚轮 */
    boolean scrubMode = true;
    String scrubAnchorDim;
    double scrubAnchorX;
    double scrubAnchorY;
    double scrubAnchorZ;
    boolean lastScrubJump;
    long lastScrubAtMs;
    /** 本 tick 是否正在拖动（用于暂停自动跟随，避免抢镜头） */
    boolean scrubbingThisTick;
    /** 方向键按住的起点：长按 0.6 秒后自动加速到 4× */
    long scrubHoldSinceMs;
    boolean truncated;
    boolean cutoffApplied;
    /** track v2 事件侧车（旧轨迹为空） */
    com.shenqianye.samsara.track.TrackEventCursor events;
    long lastEventTrackMs;
    boolean eventsJumpPending = true;
    boolean eventsTruncated;
    boolean legacyEquipment;
    /** F-107：本次回看是否允许捕捉后人偶掉落录制装备（回看本身永不掉落） */
    boolean equipmentDrop;
    /** 右键人偶查看背包的分页状态：1.5 秒内连续右键翻页 */
    long lastInspectAtMs;
    int inspectPage = 1;
    boolean restored;
    /** 自由活动/跟随状态 */
    boolean followInitialized;
    String lastFollowDim;
    double lastFollowMannequinX;
    double lastFollowMannequinY;
    double lastFollowMannequinZ;
    /** 人偶本 tick 是否发生了瞬移（跨度过大），需要把观看者拉过去 */
    boolean mannequinTeleported;
    /** 因附近没有安全落脚点而临时切换旁观模式（结束时按快照恢复） */
    boolean forcedSpectator;
    /** 已提示过"临时旁观"，避免每 tick 刷屏 */
    boolean forceSpectatorNotified;
    String stopReason = "";

    /** 会话最后一次写入观看者的位置（外部干预守卫用） */
    String lastWriteDim;
    double lastWriteX;
    double lastWriteY;
    double lastWriteZ;
    long lastWriteAtMs;
    double prevMannequinX;
    double prevMannequinY;
    double prevMannequinZ;
    boolean hasPrevMannequin;

    /** 人偶当前临时区块 ticket（引用计数式：换区块先移除旧的） */
    final Set<Long> tickets = new HashSet<>();
    ServerLevel ticketLevel;

    ViewSession(UUID viewer, String viewerName, String targetName, UUID targetUuid,
                ViewOptions options, int cycleId, long windowFrom, long windowTo,
                String sourceSummary) {
        this.viewer = viewer;
        this.viewerName = viewerName;
        this.targetName = targetName;
        this.targetUuid = targetUuid;
        this.options = options;
        this.cycleId = cycleId;
        this.windowFrom = windowFrom;
        this.windowTo = windowTo;
        this.sourceSummary = sourceSummary;
        this.speed = options.speed();
    }

    public UUID viewer() {
        return viewer;
    }

    public String viewerName() {
        return viewerName;
    }

    public String targetName() {
        return targetName;
    }

    public UUID targetUuid() {
        return targetUuid;
    }

    public ViewOptions options() {
        return options;
    }

    public int cycleId() {
        return cycleId;
    }

    public State state() {
        return state;
    }

    public ViewCursor cursor() {
        return cursor;
    }

    public boolean truncated() {
        return truncated;
    }

    public boolean cutoffApplied() {
        return cutoffApplied;
    }

    /** 当前轨迹时间（墙钟 × 倍速 × 方向；PAUSED 时冻结） */
    public long trackTime(long nowWallMs) {
        if (state == State.PAUSED) {
            return pausedTrackMs;
        }
        long t = startTrackMs + (long) ((nowWallMs - startWallMs) * speed * direction);
        return Math.max(timelineStartMs, Math.min(endTrackMs, t));
    }

    public long timelineStartMs() {
        return timelineStartMs;
    }

    public double speed() {
        return speed;
    }

    public int direction() {
        return direction;
    }

    /** 改倍速：以当前轨迹时间为新起点，避免时间跳变 */
    void setSpeed(double newSpeed) {
        long now = System.currentTimeMillis();
        long t = trackTime(now);
        speed = newSpeed;
        startTrackMs = t;
        startWallMs = now;
        if (state == State.PAUSED) {
            pausedTrackMs = t;
        }
    }

    /**
     * 跳转到指定轨迹时间（F-101）：以当前轨迹时间为新起点，避免时间跳变。
     * 暂停时同时更新 {@link #pausedTrackMs}，人偶由调用方立即刷新。
     */
    void seekTo(long targetTrackMs) {
        if (cursor == null) {
            return;
        }
        long now = System.currentTimeMillis();
        long t = Math.max(timelineStartMs, Math.min(endTrackMs, targetTrackMs));
        startTrackMs = t;
        startWallMs = now;
        if (state == State.PAUSED) {
            pausedTrackMs = t;
        }
    }

    /** 切换正放/倒放：以当前轨迹时间为新起点 */
    void toggleDirection() {
        long now = System.currentTimeMillis();
        long t = trackTime(now);
        direction = -direction;
        startTrackMs = t;
        startWallMs = now;
        if (state == State.PAUSED) {
            pausedTrackMs = t;
        }
    }

    public long remainingMs(long nowWallMs) {
        if (direction < 0) {
            return Math.max(0, trackTime(nowWallMs) - timelineStartMs);
        }
        return Math.max(0, endTrackMs - trackTime(nowWallMs));
    }

    public float progress(long nowWallMs) {
        long from = timelineStartMs;
        long span = endTrackMs - from;
        if (span <= 0) {
            return 1f;
        }
        return Math.max(0f, Math.min(1f, (trackTime(nowWallMs) - from) / (float) span));
    }

    /** 把聊天模拟游标重置到 t（跳转/倒放不补发历史消息，F-111）。 */
    void resetChatCursors(long t) {
        for (ViewTarget target : targets) {
            target.lastChatTrackMs = t;
        }
    }

    void pause() {
        if (state == State.PLAYING) {
            pausedTrackMs = trackTime(System.currentTimeMillis());
            state = State.PAUSED;
        }
    }

    void resume() {
        if (state == State.PAUSED) {
            startWallMs = System.currentTimeMillis();
            startTrackMs = pausedTrackMs;
            state = State.PLAYING;
        }
    }

    /** 会话是否已结束（用于异步加载回调判断） */
    boolean closed() {
        return state == State.RESTORING || state == State.IDLE || state == State.FAILED;
    }
}
