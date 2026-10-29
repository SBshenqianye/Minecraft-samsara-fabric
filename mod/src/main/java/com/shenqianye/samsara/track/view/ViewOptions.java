package com.shenqianye.samsara.track.view;

/**
 * 回看参数（设计〈命令与参数〉/ F-80、F-100）。
 * <p>
 * 不可变；解析失败时不产生任何会话，也不修改观看者状态。
 * 回看人偶始终不可交互，因此不再提供 {@code --interact}。
 */
public record ViewOptions(
        double speed,
        boolean follow,
        boolean spectator,
        boolean glow,
        boolean nameTag,
        long endMs,
        boolean explicitEnd,
        Integer cycle,
        /** null = 使用 commands.json 的默认值；true/false = 显式覆盖 */
        Boolean aim,
        double aimDeadzone,
        double aimEdge,
        double aimSpeed,
        boolean aimPitch) {

    public static final double MIN_SPEED = 0.1;
    public static final double MAX_SPEED = 64.0;
    public static final double DEFAULT_AIM_DEADZONE = 6.0;
    public static final double DEFAULT_AIM_EDGE = 24.0;
    public static final double DEFAULT_AIM_SPEED = 90.0;
    public static final String SUPPORTED = "支持参数：--speed <0.1..64>、--to <时间>、--duration <30s/10m/2h>、"
            + "--follow[=on|off]、--spectator、--glow、--no-glow、--no-name-tag、--cycle <cycleN>、"
            + "--aim[=on|off]、--aim-deadzone <2..45>、--aim-edge <外圈>、--aim-speed <10..360>、--aim-pitch on|off";

    public ViewOptions {
        if (speed < MIN_SPEED || speed > MAX_SPEED) {
            throw new IllegalArgumentException("倍速须在 " + MIN_SPEED + ".." + MAX_SPEED + " 内");
        }
        if (endMs <= 0) {
            throw new IllegalArgumentException("结束时间必须为正");
        }
        // 复用 ViewAim 的校验，确保任何入口都得到同样的边界
        new ViewAim.Params(aimDeadzone, aimEdge, aimSpeed, aimPitch);
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
        boolean glow = true;
        boolean nameTag = true;
        Long end = null;
        Integer cycle = null;
        Boolean aim = null;
        double aimDeadzone = DEFAULT_AIM_DEADZONE;
        double aimEdge = DEFAULT_AIM_EDGE;
        double aimSpeed = DEFAULT_AIM_SPEED;
        boolean aimPitch = true;

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
                case "interact" -> throw new IllegalArgumentException(
                        "回看始终不可交互；可交互仅属于捕捉式回放（含开新周目时的回放）");
                case "glow" -> glow = true;
                case "no-glow" -> glow = false;
                case "no-name-tag" -> nameTag = false;
                case "aim" -> {
                    if (inline == null) {
                        if (i + 1 < tokens.length && !tokens[i + 1].startsWith("--")
                                && isOnOff(tokens[i + 1])) {
                            aim = parseOnOff(tokens[++i]);
                        } else {
                            aim = true;
                        }
                    } else {
                        aim = parseOnOff(inline);
                    }
                }
                case "aim-deadzone" -> {
                    String value = inline != null ? inline : value(key, tokens, ++i);
                    aimDeadzone = parseDouble(key, value, 2.0, 45.0);
                    if (aimEdge <= aimDeadzone) {
                        aimEdge = Math.min(75.0, aimDeadzone + 1.0);
                    }
                }
                case "aim-edge" -> {
                    String value = inline != null ? inline : value(key, tokens, ++i);
                    aimEdge = parseDouble(key, value, 2.0, 75.0);
                    if (aimEdge <= aimDeadzone) {
                        throw new IllegalArgumentException("--aim-edge 必须大于 --aim-deadzone（"
                                + aimDeadzone + "）: " + value);
                    }
                }
                case "aim-speed" -> {
                    String value = inline != null ? inline : value(key, tokens, ++i);
                    aimSpeed = parseDouble(key, value, 10.0, 360.0);
                }
                case "aim-pitch" -> {
                    String value;
                    if (inline != null) {
                        value = inline;
                    } else if (i + 1 < tokens.length && !tokens[i + 1].startsWith("--")
                            && isOnOff(tokens[i + 1])) {
                        value = tokens[++i];
                    } else {
                        value = "on";
                    }
                    aimPitch = parseOnOff(value);
                }
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
        return new ViewOptions(speed, follow, spectator, glow, nameTag,
                effectiveEnd, end != null, cycle,
                aim, aimDeadzone, aimEdge, aimSpeed, aimPitch);
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
        return parseDouble("speed", value, MIN_SPEED, MAX_SPEED);
    }

    private static double parseDouble(String key, String value, double min, double max) {
        try {
            double v = Double.parseDouble(value);
            if (!Double.isFinite(v) || v < min || v > max) {
                throw new IllegalArgumentException("--" + key + " 须在 " + min + ".." + max + " 内: " + value);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("--" + key + " 不是数字: " + value);
        }
    }

    private static boolean isOnOff(String s) {
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("off")
                || s.equalsIgnoreCase("true") || s.equalsIgnoreCase("false");
    }

    private static boolean parseOnOff(String s) {
        if (!isOnOff(s)) {
            throw new IllegalArgumentException("只接受 on/off: " + s);
        }
        return s.equalsIgnoreCase("on") || s.equalsIgnoreCase("true");
    }
}
