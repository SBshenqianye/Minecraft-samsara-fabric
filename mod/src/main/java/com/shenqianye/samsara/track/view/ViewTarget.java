package com.shenqianye.samsara.track.view;

import com.mojang.authlib.GameProfile;
import com.shenqianye.samsara.track.ChatLogStore;
import com.shenqianye.samsara.track.FakeVehicle;
import com.shenqianye.samsara.track.TrackEventCursor;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * 群组回放里的一个目标（F-97）。
 *
 * <p>每个目标保留自己的 {@link ViewCursor}、人偶、事件游标与区块 ticket；
 * 墙钟、倍速、方向、暂停状态由 {@link ViewSession} 统一管理。
 */
public final class ViewTarget {
    final UUID uuid;
    final String name;
    final List<UUID> aliases;
    final int cycleId;
    /** 所属会话；创建后由 ViewManager 注入。 */
    ViewSession session;

    ViewCursor cursor;
    ViewMannequin mannequin;
    GameProfile profile;
    TrackEventCursor events;
    long lastEventTrackMs;
    boolean eventsJumpPending = true;
    boolean eventsTruncated;
    boolean legacyEquipment;
    final Set<Long> tickets = new HashSet<>();
    net.minecraft.server.level.ServerLevel ticketLevel;
    FakeVehicle vehicle;
    UUID vehicleId;

    /** 当前渲染的人偶是否由本会话持有；false = 借用其他会话的人偶。 */
    boolean ownsMannequin;
    SharedViewMannequin.Host sharedHost;

    double prevX;
    double prevY;
    double prevZ;
    boolean hasPrev;
    boolean teleported;

    /** 本 tick 是否可见（在自身样本区间、当前维度内）。 */
    boolean visible;
    /** 状态文本（状态命令展示用）。 */
    String status = "";
    boolean truncated;
    boolean cutoffApplied;

    List<ChatLogStore.Entry> chats = List.of();
    long lastChatTrackMs;

    ViewTarget(UUID uuid, String name, List<UUID> aliases, int cycleId) {
        this.uuid = uuid;
        this.name = name == null ? uuid.toString() : name;
        this.aliases = aliases == null || aliases.isEmpty() ? List.of(uuid) : List.copyOf(aliases);
        this.cycleId = cycleId;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public ViewCursor cursor() {
        return cursor;
    }

    public ViewMannequin mannequin() {
        return mannequin;
    }

    /** 该目标当前轨迹时刻是否有自己的样本覆盖（段间空隙返回 false）。 */
    boolean covers(long trackMs) {
        return cursor != null && cursor.covers(trackMs);
    }

    boolean hasOwnMannequin() {
        return mannequin != null && ownsMannequin && !mannequin.isRemoved();
    }

    boolean hasMannequin() {
        return mannequin != null && !mannequin.isRemoved();
    }
}
