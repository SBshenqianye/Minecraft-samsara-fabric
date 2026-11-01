package com.shenqianye.samsara.season;

/**
 * 栈维度加载失败后的重试策略（纯逻辑，便于单测）。
 *
 * <p>栈维度通常在 {@code SERVER_STARTED} 一次性加载；如果某个栈层当时加载失败
 * （例如临时 I/O 错误、region 文件短暂占用），玩家沿跨世界链到达该层时不应只看到
 * “未加载”提示，而应自动重试。重试采用指数退避，避免确定性错误每 tick 刷日志：
 * 5s → 10s → 20s → 40s → 60s（封顶）。
 */
public final class StackLoadPolicy {
    public static final long BASE_DELAY_MS = 5_000L;
    public static final long MAX_DELAY_MS = 60_000L;
    private static final int MAX_SHIFT = 6;

    private StackLoadPolicy() {
    }

    /** 第 {@code failedAttempts} 次失败后，距离下一次允许重试的等待毫秒数。 */
    public static long nextDelayMs(int failedAttempts) {
        if (failedAttempts <= 0) {
            return 0L;
        }
        int shift = Math.min(failedAttempts - 1, MAX_SHIFT);
        return Math.min(MAX_DELAY_MS, BASE_DELAY_MS << shift);
    }

    /** 是否允许再次尝试。{@code failedAttempts<=0} 表示从未失败，总是允许。 */
    public static boolean shouldRetry(int failedAttempts, long lastAttemptMs, long nowMs) {
        if (failedAttempts <= 0) {
            return true;
        }
        return nowMs - lastAttemptMs >= nextDelayMs(failedAttempts);
    }
}
