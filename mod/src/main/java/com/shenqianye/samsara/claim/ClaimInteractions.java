package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 旗帜圈地交互（设计文档 §6.2.1 + §6.7）。
 * 右键已放置的圈地旗 → 聊天显示详情（方向+距离+保护状态）。
 */
public class ClaimInteractions {

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            BlockPos pos = hitResult.getBlockPos();
            if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be)) {
                return InteractionResult.PASS;
            }
            List<ClaimNode.PatternEntry> patterns = readPatterns(be);
            if (patterns.isEmpty()) return InteractionResult.PASS;

            ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
            String facing = getFacing(level, pos);
            ClaimNode node = new ClaimNode(chunkPos, facing, patterns);
            Extension ext = node.getExtension();
            if (!ext.hasAnyDirection()) return InteractionResult.PASS;

            List<ChunkCoord> influenced = node.getInfluencedCoords();
            boolean protected_ = AreaService.getInstance().isProtected(chunkPos.x, chunkPos.z);
            StringBuilder sb = new StringBuilder();
            sb.append("[圈地旗] ").append(chunkPos);
            sb.append(" 朝向 ").append(facing);
            sb.append(protected_ ? " ✓ 受保护" : " ✗ 未保护");
            sb.append("\n  影响区块: ").append(influenced.size()).append(" 个");
            sb.append(" · 方向: ");
            if (ext.getUp() > 0) sb.append("北").append(ext.getUp()).append(" ");
            if (ext.getDown() > 0) sb.append("南").append(ext.getDown()).append(" ");
            if (ext.getLeft() > 0) sb.append("西").append(ext.getLeft()).append(" ");
            if (ext.getRight() > 0) sb.append("东").append(ext.getRight()).append(" ");
            if (ext.getLeftUp() > 0) sb.append("西北").append(ext.getLeftUp()).append(" ");
            if (ext.getRightUp() > 0) sb.append("东北").append(ext.getRightUp()).append(" ");
            if (ext.getLeftDown() > 0) sb.append("西南").append(ext.getLeftDown()).append(" ");
            if (ext.getRightDown() > 0) sb.append("东南").append(ext.getRightDown()).append(" ");
            sp.sendSystemMessage(Component.literal("[周目管理] " + sb.toString().trim()));
            return InteractionResult.SUCCESS;
        });
    }

    /** 从 BannerBlockEntity 读取图案列表 */
    static List<ClaimNode.PatternEntry> readPatterns(BannerBlockEntity be) {
        List<ClaimNode.PatternEntry> result = new ArrayList<>();
        BannerPatternLayers layers = be.getPatterns();
        if (layers == null) return result;
        for (BannerPatternLayers.Layer layer : layers.layers()) {
            String id = layer.pattern().value().assetId().getPath();
            int color = layer.color().ordinal();
            result.add(new ClaimNode.PatternEntry(id, color));
        }
        return result;
    }

    /** 获取旗帜朝向（standing = rotation→facing，wall = block state facing） */
    static String getFacing(Level level, BlockPos pos) {
        var state = level.getBlockState(pos);
        if (state.hasProperty(BlockStateProperties.FACING)) {
            return state.getValue(BlockStateProperties.FACING).toString().toLowerCase();
        }
        if (state.hasProperty(BlockStateProperties.ROTATION_16)) {
            int rot = state.getValue(BlockStateProperties.ROTATION_16);
            return switch (rot % 16) {
                case 0 -> "south"; case 2 -> "south_west";
                case 4 -> "west"; case 6 -> "north_west";
                case 8 -> "north"; case 10 -> "north_east";
                case 12 -> "east"; case 14 -> "south_east";
                default -> "south";
            };
        }
        return "north";
    }
}
