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
import net.minecraft.world.level.block.state.properties.BlockStateProperties;

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
            // 只处理点击方块顶面（= 放置站立旗帜）
            // 点击侧面 = 放置墙挂旗帜，跳过
            if (hitResult.getDirection() != net.minecraft.core.Direction.UP) {
                return InteractionResult.PASS;
            }
            // 放置位置在点击面上方（banner 放在被点击方块的顶面）
            BlockPos pos = hitResult.getBlockPos().above();
            final BlockPos regPos = pos;

            // 延迟 1 tick 注册：UseBlockCallback 在放置前触发，BannerBlockEntity 需要 1 tick 创建
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
        // 跳过墙挂旗帜
        if (level.getBlockState(pos).hasProperty(BlockStateProperties.FACING)) {
            return;
        }
        List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
        if (patterns.isEmpty()) return;
        if (!ClaimInteractions.hasDirectionPatterns(patterns)) return;

        // ===== 方向判断（无论 debugMode 都需要） =====
        var state = level.getBlockState(pos);
        int rot16 = state.hasProperty(BlockStateProperties.ROTATION_16)
                ? state.getValue(BlockStateProperties.ROTATION_16) : -1;
        String facing = ClaimInteractions.getFacing(level, pos);
        boolean cardinal = isCardinal(facing);

        // ===== 调试信息（仅全局 debug 开启时） =====
        if (com.shenqianye.samsara.DebugToggle.isDebugEnabled()) {
            ClaimNode node = new ClaimNode(new ChunkCoord(0, 0), "north", patterns);
            Extension ext = node.getExtension();

            StringBuilder dbg = new StringBuilder();
            dbg.append("=== 旗帜放置调试 ===\n");
            dbg.append("ROTATION_16 = ").append(rot16).append("\n");
            dbg.append("getFacing() = ").append(facing).append("\n");
            dbg.append("isCardinal = ").append(cardinal).append("\n");
            dbg.append("图案: ");
            for (ClaimNode.PatternEntry pe : patterns) {
                dbg.append(pe.patternId()).append("(color=").append(pe.color()).append(") ");
            }
            dbg.append("\n原始扩展(朝北): 北=").append(ext.getUp())
               .append(" 东北=").append(ext.getUpRight())
               .append(" 东=").append(ext.getRight())
               .append(" 东南=").append(ext.getDownRight())
               .append(" 南=").append(ext.getDown())
               .append(" 西南=").append(ext.getDownLeft())
               .append(" 西=").append(ext.getLeft())
               .append(" 西北=").append(ext.getUpLeft())
               .append("（").append(ext.directionCount()).append(" 方向）\n");

            if (cardinal) {
                ClaimNode realNode = new ClaimNode(new ChunkCoord(0, 0), facing, patterns, pos.getY(),
                        sp.getUUID(), sp.getName().getString());
                Extension rotated = realNode.getExtension();
                dbg.append("旋转后(朝").append(facing).append("): 北=").append(rotated.getUp())
                   .append(" 东北=").append(rotated.getUpRight())
                   .append(" 东=").append(rotated.getRight())
                   .append(" 东南=").append(rotated.getDownRight())
                   .append(" 南=").append(rotated.getDown())
                   .append(" 西南=").append(rotated.getDownLeft())
                   .append(" 西=").append(rotated.getLeft())
                   .append(" 西北=").append(rotated.getUpLeft()).append("\n");

                List<ChunkCoord> influenced = realNode.getInfluencedCoords();
                dbg.append("影响区块: ").append(influenced.size()).append(" 个");
                if (!influenced.isEmpty()) {
                    dbg.append(" 例: ");
                    int show = Math.min(5, influenced.size());
                    for (int i = 0; i < show; i++) {
                        dbg.append(influenced.get(i));
                        if (i < show - 1) dbg.append(", ");
                    }
                    if (influenced.size() > show) dbg.append("...");
                }
            } else {
                dbg.append("→ 非正方向，静默跳过不注册");
            }

            sp.sendSystemMessage(ChatFormat.formatComponent(dbg.toString()));
        }
        // ===== 调试信息结束 =====

        if (!cardinal) {
            return;
        }

        ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
        ClaimNode regNode = new ClaimNode(chunkPos, level.dimension(), facing, patterns,
                pos.getX(), pos.getZ(), pos.getY(),
                sp.getUUID(), sp.getName().getString());

        String result = AreaService.getInstance().addNode(regNode);
        if (result != null) {
            sp.sendSystemMessage(ChatFormat.formatComponent(result));
        }
    }

    /** 判断是否为4个世界朝向（N/S/E/W） */
    private static boolean isCardinal(String facing) {
        return "north".equals(facing) || "south".equals(facing)
                || "east".equals(facing) || "west".equals(facing);
    }
}
