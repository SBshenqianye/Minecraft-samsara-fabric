package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.config.CommandConfig;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;

import java.util.Map;
import java.util.UUID;

/** 回看期间的观看者保护：相机归还与伤害豁免判定。 */
final class ViewProtection {

    private ViewProtection() {
    }

    /** 人偶被移除/替换前，如果相机正接管在它身上，先归还观看者。 */
    static void releaseCamera(MinecraftServer server, ViewSession s, Entity mannequin) {
        if (server == null || mannequin == null) {
            return;
        }
        ServerPlayer viewer = server.getPlayerList().getPlayer(s.viewer);
        if (viewer != null && viewer.getCamera() == mannequin) {
            viewer.setCamera(viewer);
        }
    }

    static boolean protectViewer() {
        return CommandConfig.getInstance().viewProtectViewer();
    }

    /** 该玩家是否处于受保护的回看会话中（服务端伤害取消事件使用）。 */
    static boolean protectsViewer(Map<UUID, ViewSession> sessions, UUID viewer) {
        ViewSession s = sessions.get(viewer);
        return s != null && !s.restored
                && (s.state == ViewSession.State.PLAYING || s.state == ViewSession.State.PAUSED)
                && protectViewer();
    }
}
