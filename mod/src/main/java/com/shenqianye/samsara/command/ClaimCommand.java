package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.shenqianye.samsara.claim.AreaService;
import com.shenqianye.samsara.claim.ClaimInteractions;
import com.shenqianye.samsara.claim.ClaimNode;
import com.shenqianye.samsara.claim.ClaimParticles;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BannerBlockEntity;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旗帜圈地命令（玩家用）：{@code /samsara claim ...}
 * <p>
 * 仅包含正常游玩中需要使用的命令：
 * <ul>
 *   <li>{@code info [x] [z]} — 查看指定区块的圈地信息</li>
 *   <li>{@code boundary} — 切换边界持续显示开关</li>
 * </ul>
 * 管理员命令（give/list/rebuild/scan/remove/particle）已移至
 * {@code /samsara debug claim ...}。
 */
public class ClaimCommand {

    /** /samsara claim ... 子树（玩家命令） */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> claimNode() {
        return Commands.literal("claim")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker.check(src, "claim"))
                // claim info [x] [z]
                .then(Commands.literal("info")
                        .executes(ctx -> reply(ctx.getSource(), infoAtPlayer(ctx.getSource())))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                infoAt(IntegerArgumentType.getInteger(ctx, "x"),
                                                      IntegerArgumentType.getInteger(ctx, "z")))))))
                // claim boundary — 持续显示边界开关
                .then(Commands.literal("boundary")
                        .executes(ctx -> reply(ctx.getSource(), toggleBoundary(ctx.getSource()))));
    }

    // ---- claim info ----

    private static String infoAtPlayer(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "请指定坐标（info <x> <z>）";
        }
        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        return infoAt(cx, cz);
    }

    static String infoAt(int x, int z) {
        ChunkCoord loc = new ChunkCoord(x, z);
        ClaimNode node = AreaService.getInstance().getNode(loc);
        boolean protected_ = AreaService.getInstance().isProtected(x, z);
        StringBuilder sb = new StringBuilder();
        sb.append("区块 (").append(x).append(",").append(z).append(") ");
        sb.append(protected_ ? "✓ 受保护" : "✗ 未保护");
        if (node != null) {
            sb.append("\n  旗帜朝向: ").append(node.getFacing());
            sb.append(" · 图案:");
            for (ClaimNode.PatternEntry pe : node.getPatterns()) {
                sb.append(" ").append(pe.patternId()).append("(").append(pe.color() + 1).append("区块)");
            }
        }
        return sb.toString();
    }

    // ---- claim boundary ----

    private static String toggleBoundary(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "需要以玩家身份执行";
        }
        boolean nowOn = ClaimParticles.toggleBoundary(player.getUUID());
        return "边界持续显示已" + (nowOn ? "开启 ✓（进入管理区域自动显示）" : "关闭 ✗");
    }

    // ---- 扫描工具（供 debug claim scan 调用） ----

    /** 扫描指定 ServerLevel 中的圈地旗帜（只扫描已加载区块） */
    public static String scanLevel(net.minecraft.server.level.ServerLevel level) {
        int[] found = {0};
        int[] registered = {0};
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            for (var entry : chunk.getBlockEntities().entrySet()) {
                if (!(entry.getValue() instanceof BannerBlockEntity be)) continue;
                List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
                if (patterns.isEmpty()) continue;
                if (!ClaimInteractions.hasDirectionPatterns(patterns)) continue;
                found[0]++;
                BlockPos pos = entry.getKey();
                ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
                if (AreaService.getInstance().getNode(chunkPos) != null) {
                    registered[0]++;
                }
            }
        });
        return "扫描完成：已加载区块中发现 " + found[0] + " 面圈地旗"
                + "（其中 " + registered[0] + " 面已注册）";
    }

    // ---- 工具 ----

    /**
     * 发送格式化回复消息到聊天栏。
     *
     * @param src     命令源
     * @param message 消息文本
     * @return 固定返回 1
     */
    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
