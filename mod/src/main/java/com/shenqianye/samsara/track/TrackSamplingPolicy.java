package com.shenqianye.samsara.track;

/**
 * 轨迹采样分档策略。保持 .bin/.evt 格式不变，只决定“什么时候写位置样本”。
 *
 * <p>分档：静止（idle）→ 心跳骨架点；攻击/使用/载具（combat）→ combatHz；
 * 其余纯移动 → moveHz；{@code adaptive=false} 时回到固定的 baseHz（仍保留 idle）。
 */
public final class TrackSamplingPolicy {
    private TrackSamplingPolicy() {
    }

    public record Config(boolean adaptive, double baseHz, double moveHz, double combatHz,
                         int combatWindowMs, int idleAfterMs, int idleHeartbeatMs) {
        public Config {
            baseHz = clamp(baseHz, 0.5, 20.0);
            moveHz = clamp(moveHz, 0.5, 20.0);
            combatHz = clamp(combatHz, 0.5, 20.0);
            combatWindowMs = Math.max(0, combatWindowMs);
            idleAfterMs = Math.max(1, idleAfterMs);
            idleHeartbeatMs = Math.max(1, idleHeartbeatMs);
        }
    }

    public record Decision(boolean write, boolean heartbeat, double hz, String tier) {
    }

    /**
     * @param samePose       与上一条已写样本相比位置/朝向/flags 是否未变
     * @param idleSinceMs    静止起始时刻；0 表示未静止
     * @param lastWrittenMs  上一条位置样本的墙钟
     * @param combatUntilMs  战斗/使用标记的截止墙钟
     * @param vehicle        当前是否骑乘/在载具中
     */
    public static Decision decide(boolean samePose, long idleSinceMs, long lastWrittenMs,
                                  long combatUntilMs, boolean vehicle, long now, Config cfg) {
        boolean idle = samePose && idleSinceMs > 0L
                && now - idleSinceMs >= cfg.idleAfterMs();
        if (idle) {
            boolean heartbeat = now - lastWrittenMs >= cfg.idleHeartbeatMs();
            return new Decision(heartbeat, heartbeat,
                    heartbeat ? 1000.0 / cfg.idleHeartbeatMs() : 0.0, "idle");
        }
        double hz;
        String tier;
        if (!cfg.adaptive()) {
            hz = cfg.baseHz();
            tier = "fixed";
        } else if (vehicle || (combatUntilMs > 0L && now < combatUntilMs)) {
            hz = cfg.combatHz();
            tier = vehicle ? "vehicle" : "combat";
        } else {
            hz = cfg.moveHz();
            tier = "move";
        }
        long interval = Math.max(10L, Math.round(1000.0 / hz));
        return new Decision(now - lastWrittenMs >= interval, false, hz, tier);
    }

    /** 目标 Hz 对应的最小写样本间隔（毫秒）。 */
    public static long intervalMs(double hz) {
        return Math.max(10L, Math.round(1000.0 / clamp(hz, 0.5, 20.0)));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }
}
