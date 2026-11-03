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
 *
 * <p>下界虚空 → 下层主世界使用高空落点 {@link #voidDropY(int, int)}，
 * 并配合 {@link #isVoidDropGuardActive(long, long)} 防止落点被顶部阈值弹回上层（乒乓）。
 */
public final class WorldChainRules {
    public static final int VOID_MARGIN = 32;
    /** 主世界上行触发：超过建筑限制的格数。 */
    public static final int TOP_MARGIN = 5;
    /** 高空落点距离主世界建筑上限的格数；必须大于 2，避免落进顶部触发阈值。 */
    public static final int VOID_DROP_MARGIN = 16;

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
        // 主世界：超出建筑限制 5 格才触发上行，避免贴顶走动被误传；
        // 下界建筑上限之外不可站人，保持原阈值（> maxBuild - 1）。
        return kind == DimensionKind.NETHER ? y > maxBuild - 1 : y > maxBuild + TOP_MARGIN;
    }

    /**
     * 下界虚空 → 下层主世界的高空落点。
     *
     * <p>取 {@code minY + height - 16}（26.3 主世界 = 320 - 16 = 304），
     * 再钳制到 {@code minY + 2 .. minY + height - 2}。
     * <b>结果必须严格小于 {@code minY + height}</b>：否则 {@link #isTop} 会立即把它
     * 判定为“主世界顶部 → 上层下界”，把玩家弹回上层下界（乒乓）。
     */
    public static int voidDropY(int minY, int height) {
        int top = minY + height;
        int low = minY + 2;
        int high = Math.max(low, top - 2);
        int y = Math.max(low, Math.min(high, top - VOID_DROP_MARGIN));
        return Math.min(top - 1, y);
    }

    /**
     * 虚空高空落点守卫是否仍在生效。
     *
     * <p>守卫期内只抑制“主世界顶部 → 上层下界”，让慢降/区块加载造成的短暂 Y
     * 抖动不会把玩家弹回上层；守卫过期后玩家仍可正常搭方块上行。
     */
    public static boolean isVoidDropGuardActive(long nowMs, long guardUntilMs) {
        return guardUntilMs > nowMs;
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
