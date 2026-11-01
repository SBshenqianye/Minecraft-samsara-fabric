package com.shenqianye.samsara.season;

/**
 * 跨世界传送的纯阈值/坐标规则（不依赖 MC 类型，便于单测）。
 *
 * <p>阈值口径：
 * <ul>
 *   <li>虚空：{@code y < minY - 32}；</li>
 *   <li>主世界顶部：{@code y > minY + height}（默认 > 320）；</li>
 *   <li>下界顶部：{@code y > minY + height - 1}（默认 > 255）。</li>
 * </ul>
 */
public final class WorldChainRules {
    public static final int VOID_MARGIN = 32;

    public enum DimensionKind {
        OVERWORLD, NETHER, END
    }

    private WorldChainRules() {
    }

    public static boolean isVoid(double y, int minY) {
        return y < minY - VOID_MARGIN;
    }

    public static boolean isTop(double y, int minY, int height, DimensionKind kind) {
        if (kind == DimensionKind.END) {
            return false;
        }
        int maxBuild = minY + height;
        return kind == DimensionKind.NETHER ? y > maxBuild - 1 : y > maxBuild;
    }

    /** 主世界方块坐标 → 下界坐标（下界 1 格 = 主世界 8 格）。 */
    public static int toNether(double overworldCoord) {
        return (int) Math.floor(overworldCoord / 8.0);
    }

    /** 下界方块坐标 → 主世界坐标。 */
    public static int toOverworld(double netherCoord) {
        return (int) Math.round(netherCoord * 8.0);
    }
}
