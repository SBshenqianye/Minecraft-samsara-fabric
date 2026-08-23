package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.Samsara;
import com.shenqianye.samsara.command.ChatFormat;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.List;

/**
 * 旗帜放置监听：玩家放置旗帜时，若含方向图案 → 自动注册为圈地旗到 AreaService。
 * <p>
 * 时序要点：UseBlockCallback 在方块放置前触发，因此必须延迟 1 tick
 * 等待 BannerBlockEntity 创建完成后再读取图案并注册。
 */
public class ClaimPlacementListener {

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            // 放置位置在点击面上方（banner 放在被点击方块的顶面）
            BlockPos pos = hitResult.getBlockPos().above();
            final BlockPos regPos = pos;

            // 延迟 1 tick 注册：UseBlockCallback 在放置前触发，BannerBlockEntity 需要 1 tick 创建
            // 使用 executeIfPossible：如果已在服务端线程则立即执行，否则调度到服务端线程
            // 注意：首次放置时 block entity 可能尚未创建，因此需要在回调中再次检查
            level.getServer().executeIfPossible(() -> {
                // 第一次检查：立即尝试
                if (level.getBlockEntity(regPos) instanceof BannerBlockEntity be) {
                    registerBanner(sp, level, regPos, be);
                    return;
                }
                // 第二次检查：再延迟 1 tick（应对极端时序）
                level.getServer().executeIfPossible(() -> {
                    if (level.getBlockEntity(regPos) instanceof BannerBlockEntity be2) {
                        registerBanner(sp, level, regPos, be2);
                    }
                });
            });

            return InteractionResult.PASS;
        });
    }

    private static void registerBanner(ServerPlayer sp, Level level, BlockPos pos, BannerBlockEntity be) {
        List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
        if (patterns.isEmpty()) return;
        if (!ClaimInteractions.hasDirectionPatterns(patterns)) return;

        ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
        String facing = ClaimInteractions.getFacing(level, pos);
        ClaimNode node = new ClaimNode(chunkPos, facing, patterns);

        String result = AreaService.getInstance().addNode(node);
        sp.sendSystemMessage(ChatFormat.formatComponent(result));
    }
}
