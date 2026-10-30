package com.shenqianye.samsara.command.ui;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Tab 补全与聊天栏候选共用的静态数据表。
 *
 * <p>新增一个固定候选（选项、时长、倍速、小枚举）时，只改这里：
 * <ul>
 *   <li>候选列表：{@link #viewOptions()} 等；</li>
 *   <li>悬停说明：{@link #VIEW_OPTION_HINTS} / {@link #DURATION_HINTS}；</li>
 *   <li>命令侧仍通过 {@link SuggestionProviders} 取 provider，不需要知道数据放在哪。</li>
 * </ul>
 */
public final class SuggestionCatalog {
    /** 回看可选参数（与 {@code ViewOptions.parse} 支持的键一致） */
    private static final List<String> VIEW_OPTIONS = List.of(
            "--speed", "--duration", "--cycle", "--follow", "--spectator",
            "--to", "--glow", "--no-glow", "--no-name-tag",
            "--aim", "--aim-deadzone", "--aim-edge", "--aim-speed", "--aim-pitch");

    private static final List<String> DURATIONS = List.of(
            "30s", "1m", "5m", "10m", "30m", "1h", "2h", "6h", "12h", "1d");

    private static final List<String> SPEEDS = List.of(
            "0.25", "0.5", "1", "2", "4", "8", "16", "32", "64");

    private static final List<String> SEASON_MODES = List.of("same-world", "new-world");

    private static final List<String> FAKE_PLAYER_HINTS = List.of(
            "clear", "1", "3", "5", "10", "20");

    /** 选项 → hover 说明 */
    private static final Map<String, String> VIEW_OPTION_HINTS = Map.ofEntries(
            Map.entry("--speed", "播放倍速 0.1~64，例如 --speed 4"),
            Map.entry("--duration", "从开始时间起播放多久，例如 --duration 30m"),
            Map.entry("--to", "显式结束时间，例如 --to 22:00（与 --duration 二选一）"),
            Map.entry("--cycle", "指定周目，例如 --cycle cycle3；跨周目窗口必须显式指定"),
            Map.entry("--follow", "观看者跟随人偶（默认开，可写 --follow=off）"),
            Map.entry("--spectator", "回看期间保持旁观（默认就是旁观，可显式写出）"),
            Map.entry("--aim", "自动瞄准：默认关闭，--aim=on 开启；可即时用 /track view aim 切换"),
            Map.entry("--aim-deadzone", "自瞄内圈（度），默认 6；圈内不修正"),
            Map.entry("--aim-edge", "自瞄外圈（度），默认 24；越接近外圈修正越快"),
            Map.entry("--aim-speed", "自瞄最大角速度（度/秒），默认 90"),
            Map.entry("--aim-pitch", "是否同时修正俯仰，默认 on"),
            Map.entry("--glow", "人偶发光高亮（默认开）"),
            Map.entry("--no-glow", "关闭人偶发光"),
            Map.entry("--no-name-tag", "隐藏人偶头顶名牌"));

    private static final Map<String, String> DURATION_HINTS = Map.of(
            "30s", "30 秒（快速预览）",
            "1m", "1 分钟",
            "5m", "5 分钟",
            "10m", "10 分钟",
            "30m", "30 分钟（常用）",
            "1h", "1 小时",
            "2h", "2 小时",
            "6h", "6 小时",
            "12h", "12 小时",
            "1d", "1 天");

    private SuggestionCatalog() {
    }

    public static List<String> viewOptions() {
        return VIEW_OPTIONS;
    }

    public static List<String> durations() {
        return DURATIONS;
    }

    public static List<String> speeds() {
        return SPEEDS;
    }

    public static List<String> seasonModes() {
        return SEASON_MODES;
    }

    public static List<String> fakePlayerHints() {
        return FAKE_PLAYER_HINTS;
    }

    /** 回看选项的悬停说明；非选项返回 null。 */
    public static String viewOptionHint(String candidate) {
        return candidate == null ? null : VIEW_OPTION_HINTS.get(candidate);
    }

    /** 时长的悬停说明；非时长返回 null。 */
    public static String durationHint(String candidate) {
        return candidate == null ? null : DURATION_HINTS.get(candidate);
    }

    /** 是否为已知倍速候选。 */
    public static boolean isSpeed(String candidate) {
        return candidate != null && SPEEDS.contains(candidate);
    }

    /**
     * 回看选项/值的统一悬停说明（Tab 与聊天栏候选共用）。
     *
     * @param cycles 当前周目候选，用于判断 {@code cycleN} 是否存在
     */
    public static String viewTooltip(String candidate, Collection<String> cycles) {
        if (candidate == null) {
            return null;
        }
        String hint = VIEW_OPTION_HINTS.get(candidate);
        if (hint != null) {
            return hint;
        }
        String lower = candidate.toLowerCase(Locale.ROOT);
        if (lower.startsWith("--cycle")) {
            return cycles != null && cycles.contains(candidate) ? "回看周目 " + candidate : null;
        }
        if (lower.startsWith("cycle")) {
            return "回看周目 " + candidate;
        }
        String durationHint = DURATION_HINTS.get(candidate);
        if (durationHint != null) {
            return "时长 " + candidate + "：" + durationHint;
        }
        if (SPEEDS.contains(candidate)) {
            return "倍速 " + candidate + "×";
        }
        if (candidate.contains("T") && candidate.length() >= 16) {
            return "从 " + candidate.replace('T', ' ') + " 开始回看";
        }
        return null;
    }
}
