package com.shenqianye.samsara.track.view;

import com.shenqianye.samsara.season.DurationUtil;

/**
 * 回看进度跳转（设计〈时间轴跳转与输入限制〉/ F-101）：纯 Java，可单测。
 * <p>
 * 支持绝对时间（复用 {@link ViewTime}）、相对时间（{@code +30s} / {@code -10m}）
 * 与百分比（{@code 50%}）。结果钳到当前游标窗口。
 */
public final class ViewSeek {
    private ViewSeek() {
    }

    /**
     * @param spec  跳转表达式
     * @param currentTrackMs 当前轨迹时间（相对时间的基准）
     * @param startMs 游标窗口起点
     * @param endMs 游标窗口终点
     * @return 钳制后的目标时间
     */
    public static long target(String spec, long currentTrackMs, long startMs, long endMs) {
        String value = spec == null ? "" : spec.trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("缺少跳转目标（示例：+30s、-10m、50%、2026-10-28T21:20）");
        }
        long target;
        if (value.endsWith("%")) {
            double percent;
            try {
                percent = Double.parseDouble(value.substring(0, value.length() - 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("百分比跳转格式无效: " + value);
            }
            if (!Double.isFinite(percent) || percent < 0 || percent > 100) {
                throw new IllegalArgumentException("百分比须在 0..100 内: " + value);
            }
            long span = Math.max(0, endMs - startMs);
            target = startMs + Math.round(span * percent / 100.0);
        } else if (value.startsWith("+") || value.startsWith("-")) {
            String duration = value.substring(1).trim();
            long delta = DurationUtil.parse(duration);
            if (delta <= 0) {
                throw new IllegalArgumentException("相对跳转时长无效（示例：+30s / -10m）: " + value);
            }
            target = value.startsWith("-") ? currentTrackMs - delta : currentTrackMs + delta;
        } else {
            target = ViewTime.parseAbsolute(value);
        }
        return Math.max(startMs, Math.min(endMs, target));
    }
}
