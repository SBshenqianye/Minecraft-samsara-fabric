package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.List;

/**
 * 旗帜放置监听（设计文档 §6.2.1）。
 * <p>
 * 玩家放置旗帜时，若含方向图案 → 自动注册为圈地旗到 AreaService。
 * 自然生成旗帜（结构）不会触发此事件，因此不会被误注册。
 */
public class ClaimPlacementListener {

    public static void init() {
        // 监听旗帜放置（UseBlockCallback 在放置后触发）
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos().above(); // 放置位置在点击面上方
            if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be)) {
                // 有时放置后 block entity 还没创建，尝试当前位
                pos = hitResult.getBlockPos();
                if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be2)) {
                    return InteractionResult.PASS;
                }
                return handlePlacement(sp, level, pos, be2);
            }
            return handlePlacement(sp, level, pos, be);
        });
    }

    private static InteractionResult handlePlacement(ServerPlayer sp, Level level,
                                                      BlockPos pos, BannerBlockEntity be) {
        // 延迟 1 tick 注册，确保 BannerBlockEntity 的图案已从物品 NBT 同步
        final BlockPos regPos = pos;
        level.getServer().executeIfPossible(() -> {
            if (!(level.getBlockEntity(regPos) instanceof BannerBlockEntity be2)) return;
            List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be2);
            if (patterns.isEmpty()) return;
            if (!ClaimInteractions.hasDirectionPatterns(patterns)) return;

            ChunkCoord chunkPos = new ChunkCoord(regPos.getX() >> 4, regPos.getZ() >> 4);
            String facing = ClaimInteractions.getFacing(level, regPos);
            ClaimNode node = new ClaimNode(chunkPos, facing, patterns);

            String result = AreaService.getInstance().addNode(node);
            sp.sendSystemMessage(Component.literal("[周目管理·圈地] " + result));
        });
        return InteractionResult.PASS;
    }
}
