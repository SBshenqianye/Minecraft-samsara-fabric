package com.shenqianye.samsara.shutdown;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.output.OutputRenderer;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 关服倒计时与退出界面提示。
 *
 * <p>通过 {@code /samsara restart [秒]} 主动触发时，会先广播倒计时，再调用服务器
 * 正常停服流程；外部直接 {@code /stop} 不会倒计时，但仍会在 SERVER_STOPPING 阶段
 * 向在线玩家发送退出界面提示。强制杀进程/崩溃无法执行任何代码，因此不可能发送。
 *
 * <p>退出提示不再猜测具体重新开服时间，只提示“服务器已关闭，请稍后重新连接”。
 */
public final class ShutdownAnnouncer {
    private static boolean active;
    private static int ticksLeft;
    private static int lastSecond = -1;
    private static boolean disconnectSent;

    private ShutdownAnnouncer() {
    }

    public static synchronized boolean request(MinecraftServer server, int seconds) {
        if (server == null || active) {
            return false;
        }
        int countdown = seconds > 0 ? seconds : CommandConfig.getInstance().shutdownCountdownSeconds();
        active = true;
        ticksLeft = countdown * 20;
        lastSecond = -1;
        disconnectSent = false;
        broadcast(server, "shutdown.countdown", countdown);
        Samsara.LOGGER.info("[周目管理 samsara] 已开始 {} 秒关服倒计时", countdown);
        return true;
    }

    /** 每个服务器 tick 调用；倒计时结束立即进入正常停服流程。 */
    public static synchronized void tick(MinecraftServer server) {
        if (!active || server == null) {
            return;
        }
        ticksLeft--;
        int seconds = Math.max(0, (ticksLeft + 19) / 20);
        if (seconds > 0 && seconds != lastSecond) {
            lastSecond = seconds;
            broadcast(server, "shutdown.countdown", seconds);
        }
        if (ticksLeft <= 0) {
            active = false;
            server.halt(false);
        }
    }

    /** SERVER_STOPPING：无论是否经过倒计时，都给在线玩家发送退出界面提示。 */
    public static synchronized void onStopping(MinecraftServer server) {
        if (server == null || disconnectSent) {
            return;
        }
        Component message = OutputRenderer.render("shutdown.disconnect", Map.of(), null, null);
        int online = server.getPlayerList().getPlayers().size();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            try {
                player.connection.disconnect(message);
            } catch (RuntimeException e) {
                Samsara.LOGGER.warn("[周目管理 samsara] 发送退出界面提示失败：{}", e.getMessage());
            }
        }
        Samsara.LOGGER.info("[周目管理 samsara] 已向 {} 名在线玩家发送退出界面提示", online);
        disconnectSent = true;
        active = false;
    }

    /** 新服务器启动时清理上一次的倒计时状态。 */
    public static synchronized void onStarted() {
        active = false;
        ticksLeft = 0;
        lastSecond = -1;
        disconnectSent = false;
    }

    public static synchronized boolean active() {
        return active;
    }

    private static void broadcast(MinecraftServer server, String key, int seconds) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("seconds", seconds);
        Component message = OutputRenderer.render(key, values, null, null);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            player.sendSystemMessage(message);
        }
    }
}
