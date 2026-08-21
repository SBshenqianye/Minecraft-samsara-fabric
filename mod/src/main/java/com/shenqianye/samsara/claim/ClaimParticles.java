package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 手持旗帜粒子预览（设计文档 §6.7）。
 * <p>
 * 玩家主手持旗帜 → 扫描附近（32 格内）已放置的圈地旗 → 显示影响范围边界粒子。
 * 每 10 tick 刷新一次，无ray trace，基于距离查找。
 */
public class ClaimParticles {
    private static int tickCounter = 0;
    private static final double RANGE = 32.0; // 搜索范围（方块）

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            tickCounter++;
            if (tickCounter % 10 != 0) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!(player.level() instanceof ServerLevel level)) continue;

                // 找玩家附近（32 格内）的所有圈地旗
                int px = player.blockPosition().getX();
                int pz = player.blockPosition().getZ();
                int radius = (int) Math.ceil(RANGE / 16.0); // 区块半径

                for (int cx = (px >> 4) - radius; cx <= (px >> 4) + radius; cx++) {
                    for (int cz = (pz >> 4) - radius; cz <= (pz >> 4) + radius; cz++) {
                        // 检查距离（方块级）
                        int bx = cx * 16 + 8;
                        int bz = cz * 16 + 8;
                        double dist = Math.sqrt((bx - px) * (bx - px) + (bz - pz) * (bz - pz));
                        if (dist > RANGE) continue;

                        // 在该区块中查找旗帜方块实体
                        var chunk = level.getChunk(cx, cz);
                        for (var entry : chunk.getBlockEntities().entrySet()) {
                            if (!(entry.getValue() instanceof BannerBlockEntity be)) continue;
                            List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
                            if (patterns.isEmpty()) continue;
                            if (!ClaimInteractions.hasDirectionPatterns(patterns)) continue;

                            ChunkCoord bannerPos = new ChunkCoord(cx, cz);
                            String facing = ClaimInteractions.getFacing(level, entry.getKey());
                            ClaimNode node = new ClaimNode(bannerPos, facing, patterns);
                            if (!node.getExtension().hasAnyDirection()) continue;

                            List<ChunkCoord> influenced = node.getInfluencedCoords();
                            ClaimInteractions.sendBoundaryParticles(
                                    level, player, bannerPos, influenced,
                                    entry.getKey().getY() + 1.0);
                        }
                    }
                }
            }
        });
    }

}
