package com.shenqianye.samsara.track.view;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 回看会话存储：进行中的会话、加载中的异步任务与每个观看者的启动冷却。
 * <p>
 * 线程安全由 {@link ViewManager} 的 {@code synchronized} 方法保证，本类只做数据结构与查找。
 */
final class ViewSessionStore {

    private final Map<UUID, ViewSession> sessions = new LinkedHashMap<>();
    private final Map<UUID, ViewManager.Pending> pending = new LinkedHashMap<>();
    private final Map<UUID, Long> lastStart = new HashMap<>();

    Map<UUID, ViewSession> sessions() {
        return sessions;
    }

    Map<UUID, ViewManager.Pending> pending() {
        return pending;
    }

    ViewSession get(UUID viewer) {
        return viewer == null ? null : sessions.get(viewer);
    }

    boolean isViewing(UUID viewer) {
        return viewer != null && sessions.containsKey(viewer);
    }

    int activeCount() {
        return sessions.size() + pending.size();
    }

    long lastStartMs(UUID viewer) {
        return lastStart.getOrDefault(viewer, 0L);
    }

    void markStart(UUID viewer, long now) {
        lastStart.put(viewer, now);
    }

    /** 按目标参数找会话：先按玩家名/UUID 解析，再按观看者名匹配。 */
    ViewSession find(MinecraftServer server, ServerPlayer actor, String target) {
        if (target == null || target.isBlank()) {
            return actor == null ? null : sessions.get(actor.getUUID());
        }
        UUID id = ViewTargetResolver.resolvePlayerId(server, target);
        ViewSession s = id == null ? null : sessions.get(id);
        if (s == null) {
            for (ViewSession candidate : sessions.values()) {
                if (candidate.viewerName.equalsIgnoreCase(target)) {
                    return candidate;
                }
            }
        }
        return s;
    }
}
