package com.shenqianye.samsara;

/**
 * 全局调试开关（通过 /samsara debug on|off 控制）。
 * <p>
 * 所有 debug 信息输出前检查 {@link #isDebugEnabled()}，
 * 默认关闭，避免在实际游戏中刷屏。
 */
public class DebugToggle {
    private static volatile boolean enabled = false;

    public static boolean isDebugEnabled() { return enabled; }

    /** 切换并返回新状态 */
    public static boolean toggle() { enabled = !enabled; return enabled; }

    /** 强制设置 */
    public static void set(boolean v) { enabled = v; }
}
