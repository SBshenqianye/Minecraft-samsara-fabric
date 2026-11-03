package com.shenqianye.samsara.track;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Track v2 可配置保留策略（设计文档〈轨迹保留策略〉/ F-163）。
 * <p>
 * 本类是纯逻辑，不依赖 Minecraft 与文件系统，便于单测。
 * <ul>
 *   <li>{@code adaptive}：与 v1 完全等价的 1/4/16/64/256 天档位；</li>
 *   <li>{@code keepAll}：不降采样；</li>
 *   <li>{@code curve}：按年龄段目标保留比例换算目标采样率；</li>
 *   <li>{@code maxBytesPerPlayer}：超出时先降级到 adaptive，仍超出则拒绝新归档。</li>
 * </ul>
 */
public final class TrackRetention {
    public enum Mode {
        ADAPTIVE, KEEP_ALL, CURVE;

        public static Mode parse(String value) {
            if (value == null) {
                return ADAPTIVE;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "keepall", "keep_all", "keep-all" -> KEEP_ALL;
                case "curve" -> CURVE;
                default -> ADAPTIVE;
            };
        }

        public String id() {
            return switch (this) {
                case ADAPTIVE -> "adaptive";
                case KEEP_ALL -> "keepAll";
                case CURVE -> "curve";
            };
        }
    }

    /** 曲线一档：年龄上限（天，0 = “更老”哨兵）与目标保留比例。 */
    public record CurvePoint(int ageDays, double keepRatio) {
    }

    public record Settings(Mode mode, double q, int skeletonPerDay, boolean keepAll,
                           long maxBytesPerPlayer, List<CurvePoint> curve) {
        public Settings {
            curve = curve == null ? List.of() : List.copyOf(curve);
        }

        public static Settings defaults() {
            return new Settings(Mode.ADAPTIVE, 0.9, 2000, false, 0L,
                    defaultCurve());
        }

        /** 归一化 mode：显式 keepAll=true 时优先 keepAll。 */
        public Mode effectiveMode() {
            return keepAll && mode != Mode.CURVE ? Mode.KEEP_ALL : mode;
        }
    }

    /** 与 v1 行为一致的档位：跨过阈值后把采样率降到对应档（Hz）。 */
    public static final int[] ADAPTIVE_TIER_DAYS = {1, 4, 16, 64, 256};
    public static final double[] ADAPTIVE_TIER_HZ = {2.5, 1.25, 0.625, 0.3125, 0.15625};
    public static final double FRESH_HZ = 5.0;
    public static final double SKELETON_HZ = 0.05;

    public static final List<CurvePoint> DEFAULT_CURVE = List.of(
            new CurvePoint(7, 1.0),
            new CurvePoint(30, 0.5),
            new CurvePoint(90, 0.25),
            new CurvePoint(0, 0.1));

    private TrackRetention() {
    }

    public static List<CurvePoint> defaultCurve() {
        return DEFAULT_CURVE;
    }

    /** 默认曲线 → YAML/Web 使用的可变映射。 */
    public static List<Map<String, Object>> defaultCurveConfig() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (CurvePoint point : DEFAULT_CURVE) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("ageDays", point.ageDays());
            map.put("keepRatio", point.keepRatio());
            out.add(map);
        }
        return out;
    }

    /** v1 档位；默认 adaptive 必须与本方法等价。 */
    public static double adaptiveTargetHz(int ageDays) {
        int age = Math.max(0, ageDays);
        for (int i = 0; i < ADAPTIVE_TIER_DAYS.length; i++) {
            if (age < ADAPTIVE_TIER_DAYS[i]) {
                return i == 0 ? FRESH_HZ : ADAPTIVE_TIER_HZ[i - 1];
            }
        }
        return SKELETON_HZ;
    }

    /** 年龄段目标保留比例；{@code ageDays=0} 的最后一档表示“更老”。 */
    public static double keepRatioAt(int ageDays, List<CurvePoint> curve) {
        if (curve == null || curve.isEmpty()) {
            return 1.0;
        }
        int age = Math.max(0, ageDays);
        for (int i = 0; i < curve.size(); i++) {
            CurvePoint point = curve.get(i);
            boolean last = i == curve.size() - 1;
            if (!last && point.ageDays() <= 0) {
                continue;
            }
            if (point.ageDays() == 0 || age <= point.ageDays()) {
                return point.keepRatio();
            }
        }
        return curve.get(curve.size() - 1).keepRatio();
    }

    /** 曲线比例 → 目标采样率；0 比例仍保留骨架下限。 */
    public static double curveTargetHz(int ageDays, List<CurvePoint> curve) {
        double ratio = keepRatioAt(ageDays, curve);
        return Math.max(SKELETON_HZ, FRESH_HZ * Math.max(0.0, Math.min(1.0, ratio)));
    }

    /** 某年龄的目标采样率；keepAll 返回 -1（不降采样）。 */
    public static double targetHz(Settings settings, int ageDays) {
        if (settings == null) {
            return adaptiveTargetHz(ageDays);
        }
        return switch (settings.effectiveMode()) {
            case KEEP_ALL -> -1;
            case CURVE -> curveTargetHz(ageDays, settings.curve());
            case ADAPTIVE -> adaptiveTargetHz(ageDays);
        };
    }

    /** 超出 maxBytesPerPlayer 时先降级到 adaptive；0 = 不限制。 */
    public static Mode effectiveModeForBytes(Settings settings, long bytes) {
        Mode mode = settings == null ? Mode.ADAPTIVE : settings.effectiveMode();
        if (settings == null || settings.maxBytesPerPlayer() <= 0 || bytes <= settings.maxBytesPerPlayer()) {
            return mode;
        }
        return Mode.ADAPTIVE;
    }

    /** 已经降级到 adaptive 后仍超出上限：拒绝新归档。 */
    public static boolean shouldRefuseNewArchive(Settings settings, long bytes) {
        return settings != null && settings.maxBytesPerPlayer() > 0
                && bytes >= settings.maxBytesPerPlayer()
                && effectiveModeForBytes(settings, bytes) == Mode.ADAPTIVE;
    }

    /**
     * 校验曲线。返回空列表表示合法；否则返回可直接写日志的中文原因。
     */
    public static List<String> validateCurve(List<CurvePoint> curve) {
        List<String> errors = new ArrayList<>();
        if (curve == null || curve.isEmpty()) {
            errors.add("curve 至少需要一档");
            return errors;
        }
        int previousAge = -1;
        double previousRatio = 1.0;
        for (int i = 0; i < curve.size(); i++) {
            CurvePoint point = curve.get(i);
            boolean last = i == curve.size() - 1;
            if (point == null) {
                errors.add("第 " + (i + 1) + " 档为空");
                continue;
            }
            if (point.ageDays() < 0) {
                errors.add("第 " + (i + 1) + " 档 ageDays 不能为负");
            }
            if (last) {
                if (point.ageDays() != 0 && point.ageDays() <= previousAge) {
                    errors.add("最后一档 ageDays 必须为 0（更老）或大于前一档");
                }
            } else {
                if (point.ageDays() <= 0) {
                    errors.add("只有最后一档的 ageDays 允许为 0");
                } else if (point.ageDays() <= previousAge) {
                    errors.add("ageDays 必须严格递增（第 " + (i + 1) + " 档）");
                }
            }
            if (!Double.isFinite(point.keepRatio())
                    || point.keepRatio() < 0.0 || point.keepRatio() > 1.0) {
                errors.add("keepRatio 必须在 [0,1] 内（第 " + (i + 1) + " 档）");
            } else if (point.keepRatio() > previousRatio + 1e-9) {
                errors.add("keepRatio 必须单调不增（第 " + (i + 1) + " 档）");
            }
            previousAge = Math.max(previousAge, point.ageDays());
            previousRatio = point.keepRatio();
        }
        return errors;
    }

    /** 解析 YAML/Web 的曲线值；失败返回默认曲线。 */
    public static List<CurvePoint> parseCurve(Object raw) {
        if (!(raw instanceof List<?> list) || list.isEmpty()) {
            return defaultCurve();
        }
        List<CurvePoint> out = new ArrayList<>();
        try {
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) {
                    return defaultCurve();
                }
                Object age = map.containsKey("ageDays") ? map.get("ageDays") : map.get("agedays");
                Object ratio = map.containsKey("keepRatio") ? map.get("keepRatio") : map.get("keepratio");
                if (age == null || ratio == null) {
                    return defaultCurve();
                }
                out.add(new CurvePoint((int) Double.parseDouble(String.valueOf(age).trim()),
                        Double.parseDouble(String.valueOf(ratio).trim())));
            }
        } catch (RuntimeException e) {
            return defaultCurve();
        }
        return validateCurve(out).isEmpty() ? List.copyOf(out) : defaultCurve();
    }

    /** 容量展示：按 6B/点估算当前策略的稳态日数（仅用于日志/文档）。 */
    public static double steadyStateDays(double q) {
        if (q <= 0 || q >= 1) {
            return 0;
        }
        return 1.0 / (1.0 - q);
    }

    /** 每点字节数与默认采样率（5Hz，一天 432000 点）。 */
    public static final long BYTES_PER_SAMPLE = 6L;
    public static final long FRESH_SAMPLES_PER_DAY = 5L * 86_400L;

    public record TierEstimate(int ageDays, double keepRatio, long bytesPerDay,
                               long cumulativeBytes, boolean older) {
    }

    public record Estimate(long bytesPerSample, long freshSamplesPerDay, long freshBytesPerDay,
                           List<TierEstimate> tiers, long steadyBytes, long growthPerDay,
                           boolean unbounded, boolean keepAll, String warning) {
    }

    /**
     * Web 曲线编辑器容量预估：按 6B/点、5Hz 新鲜采样估算每档每日字节与有限档累计。
     * <p>
     * 最后一档表示“更老”的无限区间；其 keepRatio &gt; 0 时容量随时间线性增长，
     * 因此 {@code unbounded=true}，{@code steadyBytes} 只统计有限档位。
     */
    public static Estimate estimate(List<CurvePoint> curve, boolean keepAll) {
        long freshBytes = BYTES_PER_SAMPLE * FRESH_SAMPLES_PER_DAY;
        if (keepAll) {
            return new Estimate(BYTES_PER_SAMPLE, FRESH_SAMPLES_PER_DAY, freshBytes,
                    List.of(), 0, freshBytes, true, true,
                    "keepAll：不降采样，容量会随时间无限增长");
        }
        List<CurvePoint> points = curve == null || curve.isEmpty() ? defaultCurve() : curve;
        List<TierEstimate> tiers = new ArrayList<>();
        long cumulative = 0;
        int previousAge = 0;
        for (int i = 0; i < points.size(); i++) {
            CurvePoint point = points.get(i);
            if (point == null) {
                continue;
            }
            boolean last = i == points.size() - 1;
            int age = Math.max(0, point.ageDays());
            double ratio = Double.isFinite(point.keepRatio())
                    ? Math.max(0.0, Math.min(1.0, point.keepRatio())) : 0.0;
            long perDay = Math.round(freshBytes * ratio);
            if (!last) {
                int days = Math.max(0, age - previousAge);
                cumulative += perDay * days;
                tiers.add(new TierEstimate(age, ratio, perDay, cumulative, false));
                previousAge = Math.max(previousAge, age);
            } else {
                if (age > previousAge) {
                    cumulative += perDay * (age - previousAge);
                }
                tiers.add(new TierEstimate(age, ratio, perDay, cumulative, age == 0));
            }
        }
        double lastRatio = tiers.isEmpty() ? 0 : tiers.get(tiers.size() - 1).keepRatio();
        long growth = Math.round(freshBytes * Math.max(0.0, Math.min(1.0, lastRatio)));
        boolean unbounded = growth > 0;
        String warning = unbounded
                ? "最后一档 keepRatio=" + formatRatio(lastRatio)
                + "：更老数据每天约 " + humanBytes(growth) + " 增长，稳态无上限"
                : "最后一档 keepRatio=0：容量有上限";
        return new Estimate(BYTES_PER_SAMPLE, FRESH_SAMPLES_PER_DAY, freshBytes,
                List.copyOf(tiers), cumulative, growth, unbounded, false, warning);
    }

    private static String formatRatio(double ratio) {
        return Math.round(ratio * 1000.0) / 10.0 + "%";
    }

    private static String humanBytes(long bytes) {
        if (bytes >= 1_073_741_824L) {
            return String.format(Locale.ROOT, "%.2f GiB", bytes / 1_073_741_824.0);
        }
        if (bytes >= 1_048_576L) {
            return String.format(Locale.ROOT, "%.2f MiB", bytes / 1_048_576.0);
        }
        if (bytes >= 1024L) {
            return String.format(Locale.ROOT, "%.1f KiB", bytes / 1024.0);
        }
        return bytes + " B";
    }
}
