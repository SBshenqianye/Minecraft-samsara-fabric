package com.shenqianye.samsara.track.view;

/**
 * 回看参数（设计 5.7.6 / F-80）。
 * <p>
 * 不可变；解析失败时不产生任何会话，也不修改观看者状态。
 */
public record ViewOptions(
        double speed,
        boolean follow,
        boolean spectator,
        boolean interact,
        boolean glow,
        boolean nameTag,
        long endMs,
        boolean explicitEnd,
        Integer cycle) {

    public static final double MIN_SPEED = 0.1;
    public static final double MAX_SPEED = 64.0;
    public static final String SUPPORTED = "支持参数：--speed <0.1..64>、--to <时间>、--duration <30s/10m/2h>、"
            + "--follow[=on|off]、--spectator、--interact、--glow、--no-name-tag、--cycle <cycleN>";

    public ViewOptions {
        if (speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new IllegalArgumentException("倍速须在 " + MIN_SPEED + ".." + MAX_SPEED + " 内");
        }
        if (endMs <= 0) {
            throw new IllegalArgumentException("结束时间必须为正");
        }
    }

    /**
     * 解析 greedyString 选项。允许任意顺序、{@code --k v} 与 {@code --k=v}；
     * 未知键立即报错并列出支持项。
     *
     * @param raw      选项原文（可为 null/空）
     * @param startMs  开始时刻（用于 --to 的 HH:mm 补日期）
     * @param defaultEnd 未指定 --to/--duration 时的窗口终点
     */
    public static ViewOptions parse(String raw, long startMs, long defaultEnd) {
        double speed = 1.0;
        boolean follow = true;
        boolean spectator = false;
        boolean interact = false;
        boolean glow = false;
        boolean nameTag = true;
        Long end = null;
        Integer cycle = null;

        String[] tokens = raw == null ? new String[0] : raw.trim().split("\\s+");
        for (int i = 0; i < tokens.length; i++) {
            String token = tokens[i];
            if (token.isBlank()) {
                continue;
            }
            if (!token.startsWith("--")) {
                throw new IllegalArgumentException("无法识别的参数 '" + token + "'。" + SUPPORTED);
            }
            String key;
            String inline = null;
            int eq = token.indexOf('=');
            if (eq >= 0) {
                key = token.substring(2, eq).toLowerCase();
                inline = token.substring(eq + 1);
            } else {
                key = token.substring(2).toLowerCase();
            }
            switch (key) {
                case "speed" -> {
                    String value;
                    if (inline != null) {
                        value = inline;
                    } else {
                        value = value(key, tokens, ++i);
                    }
                    speed = parseSpeed(value);
                }
                case "to" -> {
                    String value = inline != null ? inline : value(key, tokens, ++i);
                    if (end != null) {
                        throw new IllegalArgumentException("--to 与 --duration 不能同时使用");
                    }
                    end = ViewTime.parseEnd(value, startMs);
                }
                case "duration" -> {
                    String value = inline != null ? inline : value(key, tokens, ++i);
                    long duration = com.shenqianye.samsara.season.DurationUtil.parse(value);
                    if (duration <= 0) {
                        throw new IllegalArgumentException("--duration 无效（示例：30s / 10m / 2h）: " + value);
                    }
                    if (end != null) {
                        throw new IllegalArgumentException("--to 与 --duration 不能同时使用");
                    }
                    end = startMs + duration;
                }
                case "follow" -> {
                    if (inline == null) {
                        // --follow 后面可跟 on/off；为避免误吞下一个 --key，只在显式单词时消费
                        if (i + 1 < tokens.length && !tokens[i + 1].startsWith("--")
                                && isOnOff(tokens[i + 1])) {
                            follow = parseOnOff(tokens[++i]);
                        } else {
                            follow = true;
                        }
                    } else {
                        follow = parseOnOff(inline);
                    }
                }
                case "spectator" -> spectator = true;
                case "interact" -> interact = true;
                case "glow" -> glow = true;
                case "no-name-tag" -> nameTag = false;
                case "cycle" -> {
                    String value = inline != null ? inline : value(key, tokens, ++i);
                    Integer parsed = com.shenqianye.samsara.track.TrackReplay.parseCycle(value);
                    if (parsed == null) {
                        throw new IllegalArgumentException("--cycle 无效（示例：cycle3 或 3）: " + value);
                    }
                    cycle = parsed;
                }
                case "loop" -> throw new IllegalArgumentException("--loop 本轮（P1）未实现；请用 --duration 控制窗口");
                default -> throw new IllegalArgumentException("未知参数 --" + key + "。" + SUPPORTED);
            }
        }
        if (end != null && end <= startMs) {
            throw new IllegalArgumentException("结束时间必须晚于开始时间");
        }
        long effectiveEnd = end == null ? defaultEnd : end;
        return new ViewOptions(speed, follow, spectator, interact, glow, nameTag,
                effectiveEnd, end != null, cycle);
    }

    private static String value(String key, String[] tokens, int nextIndex) {
        if (nextIndex >= tokens.length || tokens[nextIndex].startsWith("--")) {
            throw new IllegalArgumentException("--" + key + " 缺少值");
        }
        String v = tokens[nextIndex];
        if (v.isBlank()) {
            throw new IllegalArgumentException("--" + key + " 缺少值");
        }
        return v;
    }

    private static double parseSpeed(String value) {
        try {
            double v = Double.parseDouble(value);
            if (v < MIN_SPEED || v > MAX_SPEED) {
                throw new IllegalArgumentException("--speed 须在 " + MIN_SPEED + ".." + MAX_SPEED + " 内: " + value);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--speed 不是数字: " + value);
        }
    }

    private static boolean isOnOff(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("off")
                || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
    }

    private static boolean parseOnOff(String s) {
        if (!isOnOff(s)) {
            throw new IllegalArgumentException("--follow 只接受 on/off: " + s);
        }
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true");
    }
}
