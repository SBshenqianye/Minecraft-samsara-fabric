package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.config.ConfigManager;
import com.shenqianye.samsara.config.ConfigSchema;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 圈地保护范围配置（{@code config/samsara/claim-range.yml}）。
 * <p>
 * 圈地旗的延伸距离原本写死为 {@code 染料颜色 + 1}（1~16 区块）。本类把它拆成服务器可配置项：
 * <ul>
 *   <li>{@code mode=dye}：每个染料颜色对应一个距离（默认 1~16，保持旧行为）；</li>
 *   <li>{@code mode=fixed}：所有颜色统一使用 {@code fixedDistance}，适合不想让染料影响范围的服；</li>
 *   <li>{@code maxDistance}：单方向距离上限，任何模式都受它约束。</li>
 *   <li>{@code patternDirections.<pattern>}：图案在单位长度上的方向；颜色只负责延长，图案不决定距离。</li>
 * </ul>
 * <p>
 * 配置只影响“从旗帜向哪些方向延伸多少区块”，不改变「化线为面」的算法。
 * Web 配置页用染料色块 + 3×3 方向罗盘 + 实时形状预览编辑同一份配置。
 */
public final class ClaimRangeConfig {
    /** MC 染料颜色数量（0=白 … 15=黑）。 */
    public static final int COLOR_COUNT = 16;
    /** 默认保持旧行为：颜色 0~15 → 1~16 区块。 */
    public static final List<Integer> DEFAULT_COLOR_DISTANCES =
            List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
    public static final int DEFAULT_FIXED_DISTANCE = 4;
    public static final int DEFAULT_MAX_DISTANCE = 16;
    /** 图案在单位长度上的方向（世界方向，facing=north）；颜色只负责延长。 */
    public static final List<String> DIRECTION_NAMES = List.of(
            "north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest", "center");
    public static final Map<String, List<String>> DEFAULT_PATTERN_DIRECTIONS = defaults();
    /** 圈地支持的 15 种旗帜图案；顺序与 Web 图案方向表一致。 */
    public static final List<String> PATTERN_IDS = List.of(
            "border", "cross", "straight_cross",
            "stripe_top", "stripe_bottom", "stripe_left", "stripe_right",
            "square_top_left", "square_top_right", "square_bottom_left", "square_bottom_right",
            "stripe_downleft", "stripe_downright", "stripe_center", "stripe_middle");

    private static Map<String, List<String>> defaults() {
        Map<String, List<String>> out = new LinkedHashMap<>();
        // 统一采用“看旗人正面视角”：图标的上/下/左/右 = 北/南/西/东。
        out.put("stripe_top", List.of("north"));
        out.put("stripe_bottom", List.of("south"));
        out.put("stripe_left", List.of("west"));
        out.put("stripe_right", List.of("east"));
        out.put("square_top_left", List.of("northwest"));
        out.put("square_top_right", List.of("northeast"));
        out.put("square_bottom_left", List.of("southwest"));
        out.put("square_bottom_right", List.of("southeast"));
        // 斜纹按“图标上的斜线方向”命名：stripe_downleft = 左下↗右上，stripe_downright = 左上↘右下。
        out.put("stripe_downleft", List.of("northeast", "southwest"));
        out.put("stripe_downright", List.of("northwest", "southeast"));
        out.put("stripe_center", List.of("north", "south"));
        out.put("stripe_middle", List.of("east", "west"));
        out.put("cross", List.of("northeast", "southeast", "southwest", "northwest"));
        out.put("straight_cross", List.of("north", "east", "south", "west"));
        out.put("border", DIRECTION_NAMES);
        return Map.copyOf(out);
    }

    private static ClaimRangeConfig instance;

    private Mode mode = Mode.DYE;
    private int fixedDistance = DEFAULT_FIXED_DISTANCE;
    private List<Integer> colorDistances = DEFAULT_COLOR_DISTANCES;
    private int maxDistance = DEFAULT_MAX_DISTANCE;
    private Map<String, List<String>> patternDirections = DEFAULT_PATTERN_DIRECTIONS;

    public enum Mode {
        DYE,
        FIXED;

        public static Mode from(String raw) {
            return "fixed".equalsIgnoreCase(raw) ? FIXED : DYE;
        }
    }

    private ClaimRangeConfig() {
        // 构造器不读文件：load() 由 getInstance() 在实例化后调用
    }

    public static synchronized ClaimRangeConfig getInstance() {
        if (instance == null) {
            instance = new ClaimRangeConfig();
            instance.load();
        }
        return instance;
    }

    /**
     * 把染料颜色换算成实际延伸距离。
     *
     * @param fixed         是否固定模式
     * @param fixedDistance 固定模式距离
     * @param colorDistances 染料颜色表（索引 0~15）
     * @param maxDistance   单方向上限
     * @param color         染料颜色 ordinal（越界按 16 取模）
     * @return 至少 1、至多 maxDistance 的区块数
     */
    public static int resolveDistance(boolean fixed, int fixedDistance,
                                      List<Integer> colorDistances, int maxDistance,
                                      int color) {
        int index = Math.floorMod(color, COLOR_COUNT);
        int base = fixed
                ? fixedDistance
                : (colorDistances != null && index < colorDistances.size()
                        ? colorDistances.get(index)
                        : index + 1);
        int cap = Math.max(1, maxDistance);
        return Math.max(1, Math.min(base, cap));
    }

    public int distanceForColor(int color) {
        return resolveDistance(mode == Mode.FIXED, fixedDistance, colorDistances, maxDistance, color);
    }

    public Mode mode() {
        return mode;
    }

    public int fixedDistance() {
        return fixedDistance;
    }

    public List<Integer> colorDistances() {
        return colorDistances;
    }

    public Map<String, List<String>> patternDirections() {
        return patternDirections;
    }

    /** 某图案在单位长度上的世界方向（facing=north）。 */
    public List<String> directionsFor(String patternId) {
        return patternDirections.getOrDefault(patternId, List.of());
    }

    public int maxDistance() {
        return maxDistance;
    }

    /**
     * 几何相关配置的指纹。地图页按 {@code claims.json} 的 mtime/size 缓存保护区块，
     * 但保护形状还依赖本配置；配置热重载后 claims.json 可能没变，缓存必须靠指纹失效。
     */
    public String geometryFingerprint() {
        return geometryFingerprint(mode, fixedDistance, maxDistance,
                colorDistances, patternDirections);
    }

    /** 纯函数版本，供地图缓存与单测使用。 */
    public static String geometryFingerprint(Mode mode, int fixedDistance, int maxDistance,
                                             List<Integer> colorDistances,
                                             Map<String, List<String>> patternDirections) {
        StringBuilder sb = new StringBuilder(256);
        sb.append(mode == null ? Mode.DYE.name() : mode.name()).append('|')
                .append(fixedDistance).append('|').append(maxDistance).append('|');
        if (colorDistances != null) {
            for (int distance : colorDistances) {
                sb.append(distance).append(',');
            }
        }
        sb.append('|');
        Map<String, List<String>> directions =
                patternDirections == null ? Map.of() : patternDirections;
        for (String pattern : PATTERN_IDS) {
            sb.append(pattern).append('=');
            for (String direction : directions.getOrDefault(pattern, List.of())) {
                sb.append(direction).append(',');
            }
            sb.append(';');
        }
        return sb.toString();
    }

    /** 重新读取 YAML（启动与配置热重载）。失败时保留上一次有效值。 */
    public synchronized boolean load() {
        try {
            ConfigManager config = ConfigManager.getInstance();
            String id = ConfigSchema.CLAIM_RANGE;
            Mode nextMode = Mode.from(config.string(id, "mode"));
            int nextFixed = clamp(config.integer(id, "fixedDistance"),
                    DEFAULT_FIXED_DISTANCE, 1, 32);
            int nextMax = clamp(config.integer(id, "maxDistance"),
                    DEFAULT_MAX_DISTANCE, 1, 32);
            List<Integer> nextDistances = normalize(config.intList(id, "colorDistances"));
            Map<String, List<String>> nextDirections = new LinkedHashMap<>();
            for (String pattern : PATTERN_IDS) {
                nextDirections.put(pattern, normalizeDirections(
                        config.stringList(id, "patternDirections." + pattern),
                        DEFAULT_PATTERN_DIRECTIONS.get(pattern)));
            }
            // 旧配置迁移：border 八向全开但没有 center 时补上中心，升级后即为 5×5/7×7 正方形。
            // 想保留八向星形可以显式保存一份不含 center 的方向列表。
            migrateLegacyBorder(nextDirections);
            boolean changed = mode != nextMode
                    || fixedDistance != nextFixed
                    || maxDistance != nextMax
                    || !colorDistances.equals(nextDistances)
                    || !patternDirections.equals(nextDirections);
            this.mode = nextMode;
            this.fixedDistance = nextFixed;
            this.maxDistance = nextMax;
            this.colorDistances = nextDistances;
            this.patternDirections = Map.copyOf(nextDirections);
            return changed;
        } catch (RuntimeException e) {
            Samsara.LOGGER.warn("[周目管理 samsara] claim-range.yml 读取失败，保留默认范围: {}",
                    e.getMessage());
            return false;
        }
    }

    private static int clamp(int value, int fallback, int min, int max) {
        if (value < min || value > max) {
            return fallback;
        }
        return value;
    }

    private static List<Integer> normalize(List<Integer> raw) {
        if (raw == null || raw.size() != COLOR_COUNT) {
            return DEFAULT_COLOR_DISTANCES;
        }
        List<Integer> out = new ArrayList<>(COLOR_COUNT);
        for (int value : raw) {
            out.add(Math.max(1, Math.min(value, 32)));
        }
        return List.copyOf(out);
    }

    /**
     * 旧 `claim-range.yml` 的 border 只有八向；升级后补 center，使单面全边框成为正方形。
     * 只在「恰好八向、无 center」时触发，避免影响自定义方向。
     */
    private static void migrateLegacyBorder(Map<String, List<String>> directions) {
        List<String> border = directions.get("border");
        if (border == null || border.size() != DIRECTION_ORDER.size()
                || border.contains("center")) {
            return;
        }
        for (String direction : DIRECTION_ORDER) {
            if (!border.contains(direction)) {
                return;
            }
        }
        List<String> upgraded = new ArrayList<>(border);
        upgraded.add("center");
        directions.put("border", List.copyOf(upgraded));
    }

    private static final List<String> DIRECTION_ORDER = List.of(
            "north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest");

    private static List<String> normalizeDirections(List<String> raw, List<String> fallback) {        if (raw == null || raw.isEmpty()) {
            return fallback == null ? List.of() : List.copyOf(fallback);
        }
        List<String> out = new ArrayList<>(9);
        for (String name : DIRECTION_NAMES) {
            if (raw.stream().anyMatch(item -> item != null
                    && item.trim().equalsIgnoreCase(name)) && !out.contains(name)) {
                out.add(name);
            }
        }
        return out.isEmpty() ? (fallback == null ? List.of() : List.copyOf(fallback))
                : List.copyOf(out);
    }
}
