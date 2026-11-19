package com.shenqianye.samsara.track.view;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 回看观察点的安全落脚点搜索。
 *
 * <p>从 {@code ViewManager} 抽出的纯世界查询逻辑：不持有会话状态，也不改世界。
 */
final class ViewSafeSpot {

    /** 候选水平偏移：先正后方，再左右/前方，最后 2 格环；都尽量贴近理想观察点。 */
    private static final int[][] SAFE_HORIZONTAL = {
            {0, 0}, {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1},
            {2, 0}, {-2, 0}, {0, 2}, {0, -2},
            {2, 2}, {2, -2}, {-2, 2}, {-2, -2}
    };

    private ViewSafeSpot() {
    }

    /** 在理想观察点附近找安全落脚点；返回 {x,y,z}，找不到返回 null。 */
    static double[] findSafeSpot(ServerLevel level, double x, double y, double z) {
        // 先在同一高度附近找，再逐步向上/向下扩展到 4 格
        for (int dy = 0; dy <= 4; dy++) {
            for (int[] o : SAFE_HORIZONTAL) {
                double cx = x + o[0];
                double cy = y + dy;
                double cz = z + o[1];
                if (isSafeStand(level, cx, cy, cz)) {
                    return new double[]{cx, cy, cz};
                }
            }
        }
        for (int dy = -1; dy >= -3; dy--) {
            for (int[] o : SAFE_HORIZONTAL) {
                double cx = x + o[0];
                double cy = y + dy;
                double cz = z + o[1];
                if (isSafeStand(level, cx, cy, cz)) {
                    return new double[]{cx, cy, cz};
                }
            }
        }
        // 地洞/低矮空间：尝试向上找洞口（最多 12 格）
        for (int dy = 5; dy <= 12; dy++) {
            if (isSafeStand(level, x, y + dy, z)) {
                return new double[]{x, y + dy, z};
            }
        }
        return null;
    }

    /** 脚/头两格可站、无流体/危险方块，且下方有实心地面。 */
    static boolean isSafeStand(ServerLevel level, double x, double y, double z) {
        BlockPos feet = BlockPos.containing(x, y, z);
        BlockPos head = feet.above();
        if (!isPassable(level, feet) || !isPassable(level, head)) {
            return false;
        }
        BlockPos below = feet.below();
        BlockState ground = level.getBlockState(below);
        if (ground.getCollisionShape(level, below).isEmpty()) {
            return false;
        }
        return !isHazard(ground);
    }

    static boolean isPassable(ServerLevel level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!state.getFluidState().isEmpty()) {
            return false;
        }
        if (!state.getCollisionShape(level, pos).isEmpty()) {
            return false;
        }
        return !isHazard(state);
    }

    static boolean isHazard(BlockState state) {
        return state.is(Blocks.LAVA) || state.is(Blocks.FIRE) || state.is(Blocks.SOUL_FIRE)
                || state.is(Blocks.MAGMA_BLOCK) || state.is(Blocks.CACTUS)
                || state.is(Blocks.POWDER_SNOW) || state.is(Blocks.SWEET_BERRY_BUSH)
                || state.is(Blocks.CAMPFIRE) || state.is(Blocks.SOUL_CAMPFIRE)
                || state.is(Blocks.WITHER_ROSE) || state.is(Blocks.NETHER_PORTAL)
                || state.is(Blocks.END_PORTAL);
    }
}
