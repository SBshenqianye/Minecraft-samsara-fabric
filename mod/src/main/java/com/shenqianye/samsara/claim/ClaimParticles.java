package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手持旗帜粒子预览（设计文档 §6.7）。
 * 玩家看向已放置的圈地旗 → 每 10 tick 发送 END_ROD 粒子标记影响范围边界。
 * 简化版：不检测手持物品，直接检测看向的方块是否为旗帜。
 */
public class ClaimParticles {
    private static int tickCounter = 0;

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 10 != 0) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                // Ray trace 看向的方块（8 格范围）
                var hit = player.pick(8.0, 0.0F, false);
                if (!(hit instanceof net.minecraft.world.phys.BlockHitResult blockHit)) continue;

                if (!(player.level() instanceof ServerLevel level)) continue;
                var pos = blockHit.getBlockPos();
                if (!(level.getBlockEntity(pos) instanceof BannerBlockEntity be)) continue;

                List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
                if (patterns.isEmpty()) continue;

                ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
                String facing = ClaimInteractions.getFacing(level, pos);
                ClaimNode node = new ClaimNode(chunkPos, facing, patterns);
                Extension ext = node.getExtension();
                if (!ext.hasAnyDirection()) continue;

                // 计算影响区域 → 边界区块 → 角点粒子
                List<ChunkCoord> influenced = node.getInfluencedCoords();
                Set<ChunkCoord> area = new HashSet<>(influenced);
                area.add(chunkPos);
                Set<ChunkCoord> boundary = findBoundary(area);

                for (ChunkCoord bc : boundary) {
                    double x = bc.x * 16 + 8;
                    double z = bc.z * 16 + 8;
                    double y = pos.getY() + 1.0;
                    level.sendParticles(player, ParticleTypes.END_ROD,
                            true, false, x, y, z, 2, 0.5, 0.5, 0.5, 0.01);
                }
            }
        });
    }

    /** 找边界区块：有邻居不在区域内的区块 */
    private static Set<ChunkCoord> findBoundary(Set<ChunkCoord> area) {
        Set<ChunkCoord> boundary = new HashSet<>();
        int[] dx = {0, 0, 1, -1};
        int[] dz = {1, -1, 0, 0};
        for (ChunkCoord c : area) {
            for (int i = 0; i < 4; i++) {
                ChunkCoord nb = new ChunkCoord(c.x + dx[i], c.z + dz[i]);
                if (!area.contains(nb)) {
                    boundary.add(c);
                    break;
                }
            }
        }
        return boundary;
    }
}
