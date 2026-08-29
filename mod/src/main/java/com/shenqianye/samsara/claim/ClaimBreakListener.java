package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.command.ChatFormat;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.List;

/**
 * 旗帜拆除监听：玩家破坏旗帜时，若该旗帜含方向图案且已注册为圈地旗
 * → 检查归属权（主人或 OP 可拆除），自动从 AreaService 移除节点。
 * <p>
 * 事件：{@link PlayerBlockBreakEvents#BEFORE}，在方块破坏前触发。
 * 返回 false 可取消破坏（非主人且非OP时阻止破坏）。
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

            // 跳过墙挂旗帜
            if (level.getBlockState(pos).hasProperty(
                    net.minecraft.world.level.block.state.properties.BlockStateProperties.FACING)) {
                return true;
            }

            List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
            if (patterns.isEmpty()) return true;
            if (!ClaimInteractions.hasDirectionPatterns(patterns)) return true;

            ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
            ClaimNode node = AreaService.getInstance().getNode(chunkPos);

            if (node == null) {
                // 不是已注册的圈地旗，静默跳过（不显示任何消息）
                return true;
            }

            // 归属权检查：只有主人或 OP 可以拆除圈地旗
            if (player instanceof ServerPlayer sp) {
                boolean isOwner = node.getOwnerUUID() != null
                        && node.getOwnerUUID().equals(sp.getUUID());
                boolean isOp = sp.permissions().hasPermission(Permissions.COMMANDS_OWNER);
                if (!isOwner && !isOp) {
                    String ownerName = node.getOwnerName() != null ? node.getOwnerName() : "未知";
                    sp.sendSystemMessage(ChatFormat.formatComponent(
                            "这面圈地旗属于 " + ownerName + "，你没有权限拆除"));
                    return false; // 阻止破坏
                }
            }

            String result = AreaService.getInstance().removeBanner(chunkPos,
                    pos.getX(), pos.getY(), pos.getZ());

            if (result != null && player instanceof ServerPlayer sp) {
                sp.sendSystemMessage(ChatFormat.formatComponent(result));
            }

            return true;
        });
    }
}
