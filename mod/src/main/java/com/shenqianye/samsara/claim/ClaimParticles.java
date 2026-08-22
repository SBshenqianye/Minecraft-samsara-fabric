package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.ItemTags;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.List;

/**
 * 手持旗帜粒子预览（设计文档 §6.7）。
 * <p>
 * 玩家<b>主手持有旗帜</b> → 扫描附近（32 格内）已放置的圈地旗 →
 * 在<b>玩家头部上方 + 旗帜所在高度</b>两个 Y 层级显示影响范围边界粒子。
 * 每 10 tick 刷新一次，空手不显示。
 */
public class ClaimParticles {
    private static int tickCounter = 0;
    private static final double RANGE = 32.0; // 搜索范围（方块）
    /** 服务端停服时置 true，停止发送粒子包（避免停服期间发包导致客户端渲染崩溃） */
    private static volatile boolean shuttingDown = false;

    /** 由 Samsara SERVER_STOPPING 钩子调用 */
    public static void shutdown() { shuttingDown = true; }

    public static void init() {
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            if (shuttingDown) return;          // 停服期间不发粒子
            tickCounter++;
            if (tickCounter % 10 != 0) return;

            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                if (!(player.level() instanceof ServerLevel level)) continue;

                // ★ 修复1：仅主手持有旗帜时才显示粒子
                if (!player.getMainHandItem().is(ItemTags.BANNERS)) continue;

                int px = player.blockPosition().getX();
                int pz = player.blockPosition().getZ();
                int radius = (int) Math.ceil(RANGE / 16.0);

                for (int cx = (px >> 4) - radius; cx <= (px >> 4) + radius; cx++) {
                    for (int cz = (pz >> 4) - radius; cz <= (pz >> 4) + radius; cz++) {
                        int bx = cx * 16 + 8;
                        int bz = cz * 16 + 8;
                        double dist = Math.sqrt((bx - px) * (bx - px) + (bz - pz) * (bz - pz));
                        if (dist > RANGE) continue;

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

                            // ★ 修复2：玩家头部上方粒子（高于玩家，易于辨识）
                            ClaimInteractions.sendBoundaryParticles(
                                    level, player, bannerPos, influenced,
                                    player.getY() + player.getBbHeight() + 1.0);

                            // ★ 修复3：旗帜所在高度粒子（地面参考线）
                            double bannerY = entry.getKey().getY() + 1.0;
                            // 避免与玩家高度重复（相差不足 2 格时跳过）
                            if (Math.abs(bannerY - (player.getY() + player.getBbHeight() + 1.0)) > 2.0) {
                                ClaimInteractions.sendBoundaryParticles(
                                        level, player, bannerPos, influenced, bannerY);
                            }
                        }
                    }
                }
            }
        });
    }
}
