package com.shenqianye.samsara.track.view;

/**
 * 回看“拖动模式”的纯数学部分（设计〈时间轴跳转与输入限制〉）。
 * <p>
 * 不使用滚轮：vanilla 旁观模式不会把滚轮发给服务端。拖动模式只读取客户端本来就会发送的
 * 移动输入（A/D、空格、Shift），由服务端锚定观看者位置并推进光标。
 */
public final class ViewScrub {
    /** 基础速率：按住 1 秒现实时间推进 4 秒轨迹。 */
    public static final double BASE_RATE = 4.0;
    /** Shift 加速倍数。 */
    public static final double FAST_MULTIPLIER = 4.0;

    private ViewScrub() {
    }

    /**
     * @param current 当前轨迹时间
     * @param start 游标窗口起点
     * @param end 游标窗口终点
     * @param back A 键
     * @param forward D 键
     * @param fast Shift
     * @param dtSeconds 距上一 tick 的秒数
     * @return 钳制后的目标时间；A/D 同时按或都不按时返回 current
     */
    public static long target(long current, long start, long end,
                              boolean back, boolean forward, boolean fast, double dtSeconds) {
        if (back == forward || dtSeconds <= 0) {
            return Math.max(start, Math.min(end, current));
        }
        double rate = BASE_RATE * (fast ? FAST_MULTIPLIER : 1.0);
        long delta = Math.max(1L, Math.round(dtSeconds * 1000.0 * rate));
        long target = forward ? current + delta : current - delta;
        return Math.max(start, Math.min(end, target));
    }
}
