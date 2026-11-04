package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 单面旗帜的「射线形状」——圈地几何的唯一事实来源。
 *
 * <h3>层级（2026-11-02 用户裁决）</h3>
 * <ol>
 *   <li>每个图案只产生一组方向 + 距离 → 若干条从旗帜出发的射线；</li>
 *   <li>同一面旗帜叠加多个图案 = 这些射线的并集，<b>仍然只是线</b>；</li>
 *   <li>单面旗帜永远不生成末端环、不单独调用化线为面；</li>
 *   <li>所有旗帜的所有射线汇总后，才交给 {@code Geometry.compute} 做全局
 *       rasterize → findFaces → scanlineFill；</li>
 *   <li>因此只有多面旗帜的线互相接续/交叉围出的闭合面才会被填充。</li>
 * </ol>
 *
 * <p>本类只负责第 1~2 步：给定图案、距离、朝向，返回从旗帜中心出发的射线格。
 * 不依赖 Minecraft；Web 端的射线预览逻辑必须与这里的默认方向表保持同源。</p>
 *
 * <p>图案方向仍可由 {@code claim-range.yml} 的 {@code patternDirections.<pattern>} 覆盖；
 * 未配置或配置为空时回退到 {@link #DEFAULT_RAYS}。朝向只做 90° 旋转
 * （北→东→南→西），不引入 8 向朝向。</p>
 */
public final class ClaimShape {
    private ClaimShape() {}

    /** 8 方向固定顺序：北→东北→东→东南→南→西南→西→西北。 */
    public static final List<String> DIRECTION_ORDER = List.of(
            "north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest");

    /**
     * 「中心 / 闭合」方向：不产生射线，而是把同一图案层的射线末端按角度顺序连成环。
     * <p>
     * 8 向 + 中心 = 正方形（border 用 d=2 就是 5×5）；4 正向 + 中心 = 菱形；
     * 4 斜向 + 中心 = 旋转 45° 的正方形。中心方向至少要配合 3 个射线方向才生效。
     */
    public static final String CENTER = "center";

    /** 「边框」语义：选中后按图案颜色距离画正方形边框（不填充内部）。 */
    public static final String BORDER = "border";

    /** 3×3 单位方向全集（8 向 + 边框），用于配置校验与 Web 罗盘。 */
    public static final List<String> ALL_DIRECTIONS = List.of(
            "north", "northeast", "east", "southeast",
            "south", "southwest", "west", "northwest", CENTER);

    /** 方向名 → 单位向量（x 向东，z 向南）。 */
    private static final Map<String, int[]> VECTORS;

    static {
        Map<String, int[]> vectors = new LinkedHashMap<>();
        vectors.put("north", new int[]{0, -1});
        vectors.put("northeast", new int[]{1, -1});
        vectors.put("east", new int[]{1, 0});
        vectors.put("southeast", new int[]{1, 1});
        vectors.put("south", new int[]{0, 1});
        vectors.put("southwest", new int[]{-1, 1});
        vectors.put("west", new int[]{-1, 0});
        vectors.put("northwest", new int[]{-1, -1});
        VECTORS = Map.copyOf(vectors);
    }

    /**
     * 每个图案默认产生的射线方向（facing=north）。
     * 与 {@link ClaimRangeConfig#DEFAULT_PATTERN_DIRECTIONS} 一致；
     * 这里单独保留一份，保证纯几何类不依赖配置/服务端初始化。
     */
    public static final Map<String, List<String>> DEFAULT_RAYS;

    static {
        Map<String, List<String>> defaults = new LinkedHashMap<>();
        // border = 只画正方形边框：颜色距离 = 边框半径，内部不填充。
        defaults.put("border", List.of(CENTER));
        defaults.put("cross", List.of("northeast", "southeast", "southwest", "northwest"));
        defaults.put("straight_cross", List.of("north", "east", "south", "west"));
        defaults.put("stripe_top", List.of("north"));
        defaults.put("stripe_bottom", List.of("south"));
        defaults.put("stripe_left", List.of("west"));
        defaults.put("stripe_right", List.of("east"));
        defaults.put("square_top_left", List.of("northwest"));
        defaults.put("square_top_right", List.of("northeast"));
        defaults.put("square_bottom_left", List.of("southwest"));
        defaults.put("square_bottom_right", List.of("southeast"));
        defaults.put("stripe_downleft", List.of("northeast", "southwest"));
        defaults.put("stripe_downright", List.of("northwest", "southeast"));
        defaults.put("stripe_center", List.of("north", "south"));
        defaults.put("stripe_middle", List.of("east", "west"));
        DEFAULT_RAYS = Map.copyOf(defaults);
    }

    /** 默认射线方向（只读）。 */
    public static List<String> defaultDirections(String patternId) {
        return DEFAULT_RAYS.getOrDefault(patternId, List.of());
    }

    /** 朝向 → 顺时针 90° 步数（北=0、东=1、南=2、西=3）。 */
    public static int facingSteps(String facing) {
        return switch (facing == null ? "north" : facing) {
            case "east" -> 1;
            case "south" -> 2;
            case "west" -> 3;
            default -> 0;
        };
    }

    /** 把方向名旋转到指定朝向（只做 90° 的整数倍）。 */
    public static String rotateDirection(String direction, String facing) {
        int index = DIRECTION_ORDER.indexOf(direction);
        if (index < 0) return direction;
        int rotated = (index + facingSteps(facing) * 2) % 8;
        return DIRECTION_ORDER.get(rotated);
    }

    /**
     * 单面旗帜的射线线段，坐标相对旗帜中心。
     *
     * @param patternId 图案 ID
     * @param distance  该图案层颜色对应的距离（区块）
     * @param facing    旗帜朝向（north/east/south/west）
     * @return 每条为 {@code {0,0,dx*distance,dz*distance}}；距离 <= 0 时为空
     */
    public static List<int[]> segments(String patternId, int distance, String facing) {
        return segments(patternId, distance, facing, defaultDirections(patternId));
    }

    /**
     * 单面旗帜的射线线段，允许用 {@code patternDirections} 覆盖默认方向。
     * 空列表会回退到默认方向，避免配置误清空导致整面旗帜失效。
     */
    public static List<int[]> segments(String patternId, int distance, String facing,
                                       List<String> directions) {
        List<int[]> out = new ArrayList<>();
        out.addAll(raySegments(patternId, distance, facing, directions));
        out.addAll(borderSegments(patternId, distance, facing, directions));
        return out;
    }

    /** 只返回八向射线（不包含边框），供全局“化线为面”使用。 */
    public static List<int[]> raySegments(String patternId, int distance, String facing,
                                          List<String> directions) {
        List<int[]> out = new ArrayList<>();
        int d = Math.max(0, distance);
        if (d == 0) return out;
        for (String direction : effectiveDirections(patternId, directions)) {
            if (containsBorder(List.of(direction))) continue;
            String rotated = rotateDirection(direction, facing);
            int[] vector = VECTORS.get(rotated);
            if (vector == null) continue;
            out.add(new int[]{0, 0, vector[0] * d, vector[1] * d});
        }
        return out;
    }

    /**
     * 边框线段：方向含 {@code center}/{@code border} 时，按颜色距离 d 画正方形周长。
     * 返回 4 条边，不填充内部；d=2 即 5×5 的边框。
     */
    public static List<int[]> borderSegments(String patternId, int distance, String facing,
                                             List<String> directions) {
        List<int[]> out = new ArrayList<>();
        int d = Math.max(0, distance);
        if (d == 0 || !containsBorder(effectiveDirections(patternId, directions))) return out;
        out.add(new int[]{-d, -d, d, -d});
        out.add(new int[]{d, -d, d, d});
        out.add(new int[]{d, d, -d, d});
        out.add(new int[]{-d, d, -d, -d});
        return out;
    }

    /** 方向列表是否包含“边框/中心/闭合”。 */
    public static boolean containsBorder(List<String> directions) {
        if (directions == null) return false;
        return directions.stream().anyMatch(item -> item != null
                && (item.trim().equalsIgnoreCase(CENTER)
                        || item.trim().equalsIgnoreCase(BORDER)));
    }

    /** 兼容旧调用名。 */
    public static boolean containsCenter(List<String> directions) {
        return containsBorder(directions);
    }

    /**
     * 单面旗帜的射线格（含中心）。这里不做任何填充：单旗永远是线。
     */
    public static Set<ChunkCoord> cells(String patternId, int distance, String facing) {
        return cells(patternId, distance, facing, defaultDirections(patternId));
    }

    /** 单面旗帜的射线格（含中心），允许覆盖方向。 */
    public static Set<ChunkCoord> cells(String patternId, int distance, String facing,
                                        List<String> directions) {
        Set<ChunkCoord> out = new LinkedHashSet<>();
        List<int[]> rays = raySegments(patternId, distance, facing, directions);
        // 旗帜所在区块始终属于保护范围；边框只额外贡献周长。
        out.add(new ChunkCoord(0, 0));
        for (int[] segment : rays) {
            rasterize(segment, out);
        }
        for (int[] segment : borderSegments(patternId, distance, facing, directions)) {
            rasterize(segment, out);
        }
        return out;
    }

    /** 把一条整数线段上的所有格点加入集合（含起终点）。 */
    public static void rasterize(int[] segment, Set<ChunkCoord> out) {
        int x1 = segment[0];
        int z1 = segment[1];
        int x2 = segment[2];
        int z2 = segment[3];
        int dx = x2 - x1;
        int dz = z2 - z1;
        int steps = gcd(Math.abs(dx), Math.abs(dz));
        if (steps == 0) {
            out.add(new ChunkCoord(x1, z1));
            return;
        }
        int sx = dx / steps;
        int sz = dz / steps;
        for (int i = 0; i <= steps; i++) {
            out.add(new ChunkCoord(x1 + sx * i, z1 + sz * i));
        }
    }

    /** 单面旗帜的射线格列表（含中心），供粒子/调试使用。 */
    public static List<ChunkCoord> cellList(String patternId, int distance, String facing,
                                            List<String> directions) {
        return new ArrayList<>(cells(patternId, distance, facing, directions));
    }

    private static List<String> effectiveDirections(String patternId, List<String> directions) {
        List<String> normalized = normalize(directions);
        if (!normalized.isEmpty()) return normalized;
        return defaultDirections(patternId);
    }

    /** 按 8 方向固定顺序 + 中心去重、过滤未知名称。 */
    public static List<String> normalize(List<String> directions) {
        if (directions == null || directions.isEmpty()) return List.of();
        List<String> out = new ArrayList<>(9);
        for (String name : ALL_DIRECTIONS) {
            for (String raw : directions) {
                if (raw == null) continue;
                String value = raw.trim();
                if (BORDER.equalsIgnoreCase(value)) value = CENTER;
                if (value.equalsIgnoreCase(name) && !out.contains(name)) {
                    out.add(name);
                    break;
                }
            }
        }
        return out;
    }

    private static int gcd(int a, int b) {
        while (b != 0) {
            int t = b;
            b = a % b;
            a = t;
        }
        return a;
    }
}
