package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import com.shenqianye.samsara.storage.StoragePaths;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.AbstractBannerBlock;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

/**
 * 非玩家方式移除圈地旗的兜底监听。
 *
 * <p>玩家手动破坏走 {@link ClaimBreakListener}（能判断归属权并阻止非主人拆除）；
 * 爆炸、活塞、{@code /setblock}、{@code /fill}、流体等路径不经过该事件，但都会落到
 * {@code Level.setBlock}。这里由 mixin 在方块变化前调用，确认「被替换掉的是已登记的
 * 圈地旗」后排队注销，保证旗帜没了以后不会留下幽灵保护。
 *
 * <p>只做判定与排队，真正的移除和几何重算由 {@link AreaService#tick()} 合并处理。
 */
public final class ClaimBannerWatch {

    private ClaimBannerWatch() {
    }

    /**
     * {@code Level.setBlock} 变化前调用。
     *
     * @param level  服务端世界
     * @param pos    方块坐标
     * @param before 变化前状态
     * @param after  变化后状态
     */
    public static void onBlockChanging(ServerLevel level, BlockPos pos,
                                       BlockState before, BlockState after) {
        if (!(before.getBlock() instanceof AbstractBannerBlock)) {
            return;
        }
        // 同类方块替换（例如换颜色/换图案）不注销；只有旗帜真的消失才处理。
        if (after.getBlock() instanceof AbstractBannerBlock) {
            return;
        }
        if (!StoragePaths.ready()) {
            return;
        }
        try {
            if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity banner)) {
                return;
            }
            List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(banner);
            if (patterns.isEmpty() || !ClaimInteractions.isClaimBanner(patterns)) {
                return;
            }
            ChunkCoord chunk = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
            ClaimNode node = AreaService.getInstance().getNode(level.dimension(), chunk);
            if (node == null || !node.hasBannerAt(pos.getX(), pos.getY(), pos.getZ())) {
                return;
            }
            AreaService.getInstance().queueBannerRemoval(level.dimension(), chunk,
                    pos.getX(), pos.getY(), pos.getZ());
            Samsara.LOGGER.info("[圈地] 旗帜被非玩家方式移除，将注销：{} {} {} {}",
                    level.dimension().toString(), pos.getX(), pos.getY(), pos.getZ());
        } catch (RuntimeException e) {
            // 方块变化路径不能因为圈地兜底而失败；记录后继续。
            Samsara.LOGGER.warn("[圈地] 检查旗帜注销失败：{}", e.getMessage());
        }
    }
}
