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

import java.util.*;
import java.util.stream.Collectors;

/**
 * 旗帜圈地命令（玩家用）：{@code /samsara claim ...}
 * <p>
 * 仅包含正常游玩中需要使用的命令：
 * <ul>
 *   <li>{@code info [x] [z]} — 查看指定区块的圈地信息</li>
 *   <li>{@code boundary} — 切换边界持续显示开关</li>
 * </ul>
 * 管理员命令（give/list/rebuild/scan/remove/particle/delete）已移至
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
                        .executes(ctx -> reply(ctx.getSource(), toggleBoundary(ctx.getSource())))
                        .then(Commands.literal("fixed")
                                .executes(ctx -> reply(ctx.getSource(), toggleFixed(ctx.getSource()))))
                        .then(Commands.literal("dynamic")
                                .executes(ctx -> reply(ctx.getSource(), toggleDynamic(ctx.getSource())))));
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
            sb.append("\n  旗帜朝向: ").append(ClaimInteractions.facingToDisplay(node.getFacing()));
            if (node.getOwnerName() != null) sb.append(" · 主人: ").append(node.getOwnerName());
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

    private static String toggleFixed(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "需要以玩家身份执行";
        }
        boolean nowOn = ClaimParticles.toggleFixed(player.getUUID());
        return "固定边界（旗帜高度）已" + (nowOn ? "开启 ✓" : "关闭 ✗");
    }

    private static String toggleDynamic(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "需要以玩家身份执行";
        }
        boolean nowOn = ClaimParticles.toggleDynamic(player.getUUID());
        return "跟随边界（玩家高度）已" + (nowOn ? "开启 ✓" : "关闭 ✗");
    }

    // ---- 扫描工具（供 debug claim scan 调用） ----

    /**
     * 扫描并同步指定 ServerLevel 中的圈地旗帜。
     * <p>
     * 同步逻辑：
     * 1. 扫描世界中所有有效旗帜 → 未注册的自动注册
     * 2. 检查已注册节点 → 旗帜已不存在的自动移除
     *
     * @return 同步结果报告
     */
    public static String scanLevel(net.minecraft.server.level.ServerLevel level) {
        // 收集世界中所有有效旗帜的实际位置
        Set<String> worldBanners = new HashSet<>();
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            for (var entry : chunk.getBlockEntities().entrySet()) {
                if (!(entry.getValue() instanceof BannerBlockEntity be)) continue;
                List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
                if (patterns.isEmpty()) continue;
                if (!ClaimInteractions.hasDirectionPatterns(patterns)) continue;
                BlockPos pos = entry.getKey();
                worldBanners.add(pos.getX() + "," + pos.getY() + "," + pos.getZ());
            }
        });

        int[] autoRegistered = {0};
        int[] autoRemoved = {0};

        // Phase 1: 世界中的旗帜 → 未注册的自动注册
        level.getChunkSource().chunkMap.forEachBlockTickingChunk(chunk -> {
            for (var entry : chunk.getBlockEntities().entrySet()) {
                if (!(entry.getValue() instanceof BannerBlockEntity be)) continue;
                List<ClaimNode.PatternEntry> patterns = ClaimInteractions.readPatterns(be);
                if (patterns.isEmpty()) continue;
                if (!ClaimInteractions.hasDirectionPatterns(patterns)) continue;
                BlockPos pos = entry.getKey();
                ChunkCoord chunkPos = new ChunkCoord(pos.getX() >> 4, pos.getZ() >> 4);
                ClaimNode existing = AreaService.getInstance().getNode(chunkPos);
                if (existing == null) {
                    // 未注册 → 自动注册
                    String facing = ClaimInteractions.getFacing(level, pos);
                    ClaimNode newNode = new ClaimNode(chunkPos, facing, patterns,
                            pos.getX(), pos.getZ(), pos.getY(), null, null);
                    AreaService.getInstance().addNode(newNode);
                    autoRegistered[0]++;
                } else {
                    // 已注册 → 确保旗帜坐标+方向+类型在列表中
                    String facing = ClaimInteractions.getFacing(level, pos);
                    existing.addBanner(pos.getX(), pos.getY(), pos.getZ(), facing, patterns);
                }
            }
        });

        // Phase 2: 已注册节点 → 旗帜已不存在的自动移除
        // 先收集所有需要移除的操作，避免 ConcurrentModification
        List<Runnable> removals = new ArrayList<>();
        Map<ChunkCoord, ClaimNode> nodes = AreaService.getInstance().getNodes();
        for (Map.Entry<ChunkCoord, ClaimNode> e : nodes.entrySet()) {
            ClaimNode node = e.getValue();
            List<ClaimNode.BannerEntry> toRemoveBanners = new ArrayList<>();
            for (ClaimNode.BannerEntry be : node.getBanners()) {
                String key = be.blockX() + "," + be.blockY() + "," + be.blockZ();
                if (!worldBanners.contains(key)) {
                    toRemoveBanners.add(be);
                }
            }
            for (ClaimNode.BannerEntry be : toRemoveBanners) {
                removals.add(() -> {
                    node.removeBanner(be.blockX(), be.blockY(), be.blockZ());
                    autoRemoved[0]++;
                });
            }
            if (node.getBanners().size() - toRemoveBanners.size() == 0) {
                ChunkCoord chunk = e.getKey();
                removals.add(() -> AreaService.getInstance().removeNode(chunk));
            }
        }
        for (Runnable r : removals) r.run();

        // 保存变更
        if (autoRegistered[0] > 0 || autoRemoved[0] > 0) {
            AreaService.getInstance().recompute();
        }

        int totalNodes = AreaService.getInstance().getNodes().size();
        int totalChunks = AreaService.getInstance().getWhitelist().size();
        StringBuilder sb = new StringBuilder();
        sb.append("同步完成：");
        sb.append("世界中发现 ").append(worldBanners.size()).append(" 面有效旗帜\n");
        if (autoRegistered[0] > 0) sb.append("  ✓ 自动注册 ").append(autoRegistered[0]).append(" 面\n");
        if (autoRemoved[0] > 0) sb.append("  ✗ 自动清理 ").append(autoRemoved[0]).append(" 面失效坐标\n");
        sb.append("当前状态：").append(totalNodes).append(" 个区块注册 → ").append(totalChunks).append(" 个保护区块");
        return sb.toString();
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
