package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.command.ChatFormat;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.List;

/**
 * 旗帜拆除监听：玩家破坏旗帜时，若该旗帜含方向图案且已注册为圈地旗
 * → 自动从 AreaService 移除节点，并标记白名单需重算。
 * <p>
 * 事件：{@link PlayerBlockBreakEvents#BEFORE}，在方块破坏前触发。
 * 返回 false 可取消破坏（本监听始终返回 true，不阻止破坏）。
 */
public class ClaimBreakListener {

    public static void init() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(world instanceof ServerLevel level)) {
                return true;
            }

            if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be)) {
                return true;
            }

            List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
            if (patterns.isEmpty()) return true;
            if (!ClaimInteractions.hasDirectionPatterns(patterns)) return true;

            ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
            String result = AreaService.getInstance().removeNode(chunkPos);

            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(ChatFormat.formatComponent(result));
            }

            return true;
        });
    }
}
