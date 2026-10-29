package com.shenqianye.samsara.track.view;

import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.BossEvent;

import java.util.UUID;

/**
 * 回看临时展示物（设计 5.7.4 / F-85）：BossBar + 人偶发光/名牌。
 * <p>
 * 只创建会话内的临时对象，{@link #release()} 幂等清理；不碰计分板/队伍等
 * 持久数据（回看不需要创建它们）。
 */
public final class ViewDisplay {
    private final ServerBossEvent bar;
    private final UUID viewer;
    private boolean released;

    public ViewDisplay(ServerPlayer viewer, String targetName, int cycleId) {
        this.viewer = viewer.getUUID();
        this.bar = new ServerBossEvent(
                UUID.randomUUID(),
                Component.literal("[回看] " + targetName + (cycleId > 0 ? " · 周目 " + cycleId : "")),
                BossEvent.BossBarColor.BLUE,
                BossEvent.BossBarOverlay.PROGRESS);
        this.bar.setProgress(0f);
        this.bar.addPlayer(viewer);
    }

    public void update(String text, float progress) {
        if (released) {
            return;
        }
        bar.setName(Component.literal(text));
        bar.setProgress(Math.max(0f, Math.min(1f, progress)));
    }

    public void release() {
        if (released) {
            return;
        }
        released = true;
        try {
            bar.removeAllPlayers();
            bar.setVisible(false);
        } catch (Exception ignored) {
            // 玩家已离线时移除 BossBar 失败不影响恢复流程
        }
    }

    public UUID viewer() {
        return viewer;
    }
}
