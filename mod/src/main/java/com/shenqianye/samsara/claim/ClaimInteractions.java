package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;
import net.minecraft.world.level.block.entity.BannerPatternLayers;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 旗帜圈地交互（设计文档 §6.2.1 + §6.7）。
 * <p>
 * 空手/手持旗帜 右键已放置的圈地旗 → 聊天显示详情 + 粒子预览影响范围。
 * 自然生成旗帜（结构旗帜）无方向图案 → 自动跳过。
 */
public class ClaimInteractions {
    /** 防抖：每个玩家上次交互的 tick 时间 */
    private static final Map<UUID, Long> lastInteraction = new java.util.HashMap<>();
    private static final long DEBOUNCE_TICKS = 20; // 1 秒冷却

    public static void init() {
        UseBlockCallback.EVENT.register((player, level, hand, hitResult) -> {
            if (level.isClientSide() || !(player instanceof ServerPlayer sp)) {
                return InteractionResult.PASS;
            }
            // 防抖：同一玩家 1 秒内不重复触发
            long now = level.getGameTime();
            Long last = lastInteraction.get(sp.getUUID());
            if (last != null && now - last < DEBOUNCE_TICKS) return InteractionResult.PASS;
            lastInteraction.put(sp.getUUID(), now);

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

            // 显示详情
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

            // 立即发送粒子预览（§6.7：右键时即时反馈）
            if (level instanceof ServerLevel sl) {
                sendBoundaryParticles(sl, sp, chunkPos, influenced, pos.getY() + 1.0);
            }
            return InteractionResult.SUCCESS;
        });
    }

    /** 发送边界粒子（§6.7：沿保护区域边界线均匀放置） */
    static void sendBoundaryParticles(ServerLevel level, ServerPlayer player,
                                       ChunkCoord center, List<ChunkCoord> influenced, double y) {
        Set<ChunkCoord> area = new HashSet<>(influenced);
        area.add(center);
        // 收集所有朝外的边（每条边 = 两个端点）
        Set<String> edges = new HashSet<>();
        int[] dx = {0, 0, 1, -1};
        int[] dz = {1, -1, 0, 0};
        for (ChunkCoord c : area) {
            for (int i = 0; i < 4; i++) {
                ChunkCoord nb = new ChunkCoord(c.x + dx[i], c.z + dz[i]);
                if (!area.contains(nb)) {
                    // c 的第 i 边朝外，生成该边的两个端点
                    long x1, z1, x2, z2;
                    switch (i) {
                        case 0 -> { // south edge: z=(c.z+1)*16
                            x1 = c.x * 16L; z1 = (c.z + 1) * 16L;
                            x2 = (c.x + 1) * 16L; z2 = (c.z + 1) * 16L;
                        }
                        case 1 -> { // north edge: z=c.z*16
                            x1 = c.x * 16L; z1 = c.z * 16L;
                            x2 = (c.x + 1) * 16L; z2 = c.z * 16L;
                        }
                        case 2 -> { // east edge: x=(c.x+1)*16
                            x1 = (c.x + 1) * 16L; z1 = c.z * 16L;
                            x2 = (c.x + 1) * 16L; z2 = (c.z + 1) * 16L;
                        }
                        default -> { // west edge: x=c.x*16
                            x1 = c.x * 16L; z1 = c.z * 16L;
                            x2 = c.x * 16L; z2 = (c.z + 1) * 16L;
                        }
                    }
                    // 去重：按字典序存储
                    String key = Math.min(x1, x2) + "," + Math.min(z1, z2) + ","
                            + Math.max(x1, x2) + "," + Math.max(z1, z2);
                    edges.add(key);
                }
            }
        }
        // 沿每条边均匀放置粒子（每 4 格一个）
        for (String ek : edges) {
            String[] p = ek.split(",");
            long x1 = Long.parseLong(p[0]), z1 = Long.parseLong(p[1]);
            long x2 = Long.parseLong(p[2]), z2 = Long.parseLong(p[3]);
            long len = Math.max(Math.abs(x2 - x1), Math.abs(z2 - z1));
            if (len == 0) continue;
            long sx = Long.signum(x2 - x1);
            long sz = Long.signum(z2 - z1);
            for (long d = 0; d <= len; d += 4) {
                level.sendParticles(player, ParticleTypes.END_ROD,
                        true, false, x1 + sx * d, y, z1 + sz * d,
                        1, 0.0, 0.0, 0.0, 0.0);
            }
        }
    }

    /** 从 BannerBlockEntity 读取图案列表（public 供 ClaimCommand.scan 调用） */
    public static List<ClaimNode.PatternEntry> readPatterns(BannerBlockEntity be) {
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

    /** 判断旗帜是否有方向图案（= 可能是圈地旗） */
    public static boolean hasDirectionPatterns(List<ClaimNode.PatternEntry> patterns) {
        ClaimNode node = new ClaimNode(new ChunkCoord(0, 0), "north", patterns);
        return node.getExtension().hasAnyDirection();
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
