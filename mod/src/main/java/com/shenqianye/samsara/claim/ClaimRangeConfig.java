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
 * </ul>
 * <p>
 * 配置只影响“从旗帜向外延伸多少区块”，不改变图案→方向映射，也不改变「化线为面」的算法。
 * Web 配置页用染料色块 + 实时形状预览编辑同一份配置。
 */
public final class ClaimRangeConfig {
    /** MC 染料颜色数量（0=白 … 15=黑）。 */
    public static final int COLOR_COUNT = 16;
    /** 默认保持旧行为：颜色 0~15 → 1~16 区块。 */
    public static final List<Integer> DEFAULT_COLOR_DISTANCES =
            List.of(1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16);
    public static final int DEFAULT_FIXED_DISTANCE = 4;
    public static final int DEFAULT_MAX_DISTANCE = 16;
    /** 支持单独设置距离的旗帜图案；顺序与 Extension.PATTERN_DIR 一致。 */
    public static final List<String> PATTERN_IDS = List.of(
            "border", "cross", "straight_cross",
            "stripe_top", "stripe_bottom", "stripe_left", "stripe_right",
            "square_top_left", "square_top_right", "square_bottom_left", "square_bottom_right",
            "stripe_downleft", "stripe_downright", "stripe_center", "stripe_middle");

    private static ClaimRangeConfig instance;

    private Mode mode = Mode.DYE;
    private int fixedDistance = DEFAULT_FIXED_DISTANCE;
    private List<Integer> colorDistances = DEFAULT_COLOR_DISTANCES;
    private int maxDistance = DEFAULT_MAX_DISTANCE;
    private Map<String, Integer> patternDistances = Map.of();

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
        return resolveDistance(fixed, fixedDistance, colorDistances, Map.of(), null, maxDistance, color);
    }

    /**
     * 带图案覆盖的换算：某图案配置了正数距离时优先使用它，否则回退到染料/固定距离。
     */
    public static int resolveDistance(boolean fixed, int fixedDistance,
                                      List<Integer> colorDistances,
                                      Map<String, Integer> patternDistances, String patternId,
                                      int maxDistance, int color) {
        int index = Math.floorMod(color, COLOR_COUNT);
        int override = patternId == null || patternDistances == null
                ? 0 : patternDistances.getOrDefault(patternId, 0);
        int base = override > 0
                ? override
                : fixed
                        ? fixedDistance
                        : (colorDistances != null && index < colorDistances.size()
                                ? colorDistances.get(index)
                                : index + 1);
        int cap = Math.max(1, maxDistance);
        return Math.max(1, Math.min(base, cap));
    }

    public int distanceForColor(int color) {
        return distanceForColor(color, null);
    }

    public int distanceForColor(int color, String patternId) {
        return resolveDistance(mode == Mode.FIXED, fixedDistance, colorDistances,
                patternDistances, patternId, maxDistance, color);
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

    public Map<String, Integer> patternDistances() {
        return patternDistances;
    }

    public int maxDistance() {
        return maxDistance;
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
            Map<String, Integer> nextPatterns = new LinkedHashMap<>();
            for (String pattern : PATTERN_IDS) {
                nextPatterns.put(pattern, clamp(config.integer(id, "patternDistances." + pattern),
                        0, 0, 32));
            }
            boolean changed = mode != nextMode
                    || fixedDistance != nextFixed
                    || maxDistance != nextMax
                    || !colorDistances.equals(nextDistances)
                    || !patternDistances.equals(nextPatterns);
            this.mode = nextMode;
            this.fixedDistance = nextFixed;
            this.maxDistance = nextMax;
            this.colorDistances = nextDistances;
            this.patternDistances = Map.copyOf(nextPatterns);
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
}
