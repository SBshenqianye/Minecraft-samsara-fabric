package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

/**
 * 旗帜拆除监听（设计文档 §6.2.1 补充）。
 * <p>
 * 玩家破坏旗帜时，若该旗帜含方向图案且已注册为圈地旗 → 自动从 AreaService 移除节点，
 * 并惰性重算白名单（下次 isProtected/getWhitelist 触发）。
 * <p>
 * 事件：{@link PlayerBlockBreakEvents#BEFORE}，在方块破坏前触发。
 * 返回 false 可取消破坏（本监听始终返回 true，不阻止破坏）。
 */
public class ClaimBreakListener {

    public static void init() {
        PlayerBlockBreakEvents.BEFORE.register((world, player, pos, state, blockEntity) -> {
            if (world.isClientSide() || !(world instanceof ServerLevel level)) {
                return true; // 不阻止
            }

            // 检查被破坏的方块是否是旗帜
            if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be)) {
                return true;
            }

            // 读取图案，判断是否为圈地旗
            java.util.List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
            if (patterns.isEmpty()) return true;
            if (!ClaimInteractions.hasDirectionPatterns(patterns)) return true;

            // 计算区块坐标（与 ClaimPlacementListener 一致）
            ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);

            // 尝试移除节点
            String result = AreaService.getInstance().removeNode(chunkPos);

            // 通知拆除者
            if (player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(Component.literal("[周目管理·圈地] " + result));
            }

            return true; // 不阻止破坏
        });
    }
}
