package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.shenqianye.samsara.claim.AreaService;
import com.shenqianye.samsara.claim.ClaimInteractions;
import com.shenqianye.samsara.claim.ClaimNode;
import com.shenqianye.samsara.claim.ClaimParticles;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.geometry.ChunkCoord;
import com.shenqianye.samsara.output.OutputRenderer;
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
                        .executes(ctx -> infoReply(ctx.getSource(), infoAtPlayer(ctx.getSource())))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .suggests(SuggestionProviders.chunkXProvider())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .suggests(SuggestionProviders.chunkZProvider())
                                        .executes(ctx -> {
                                            ServerPlayer p;
                                            try { p = ctx.getSource().getPlayerOrException(); }
                                            catch (Exception e) { return reply(ctx.getSource(), "需要以玩家身份执行"); }
                                            return infoReply(ctx.getSource(),
                                                    infoData(p.level().dimension(),
                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                            IntegerArgumentType.getInteger(ctx, "z")));
                                        }))))
                // claim boundary — 持续显示边界开关
                .then(Commands.literal("boundary")
                        .executes(ctx -> toggleBoundary(ctx.getSource()))
                        .then(Commands.literal("fixed")
                                .executes(ctx -> toggleFixed(ctx.getSource())))
                        .then(Commands.literal("dynamic")
                                .executes(ctx -> toggleDynamic(ctx.getSource()))));
    }

    // ---- claim info ----

    private static InfoData infoAtPlayer(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return null;
        }
        int cx = player.blockPosition().getX() >> 4;
        int cz = player.blockPosition().getZ() >> 4;
        return infoData(player.level().dimension(), cx, cz);
    }

    static String infoAt(net.minecraft.resources.ResourceKey<Level> dim, int x, int z) {
        return infoData(dim, x, z).text();
    }

    static InfoData infoData(net.minecraft.resources.ResourceKey<Level> dim, int x, int z) {
        ChunkCoord loc = new ChunkCoord(x, z);
        ClaimNode node = AreaService.getInstance().getNode(dim, loc);
        boolean protected_ = AreaService.getInstance().isProtected(dim, x, z);
        String status = protected_ ? "✓ 受保护" : "✗ 未保护";
        StringBuilder detail = new StringBuilder();
        if (node != null) {
            detail.append("\n  旗帜朝向: ").append(ClaimInteractions.facingToDisplay(node.getFacing()));
            if (node.getOwnerName() != null) detail.append(" · 主人: ").append(node.getOwnerName());
            detail.append(" · 图案:");
            for (ClaimNode.PatternEntry pe : node.getPatterns()) {
                detail.append(" ").append(pe.patternId()).append("(").append(pe.color() + 1).append("区块)");
            }
        }
        return new InfoData(x, z, dim.identifier().toString(), status, detail.toString());
    }

    /**
     * claim info 输出：只读 + 幂等，附 [查看当前区块] 点击即执行。
     * 按钮由模板插槽给出，命令内容固定。
     */
    private static int infoReply(CommandSourceStack src, InfoData data) {
        if (data == null) {
            return reply(src, "请指定坐标（info <x> <z>）");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("chunkX", data.x());
        values.put("chunkZ", data.z());
        values.put("dimension", data.dimension());
        values.put("status", data.status());
        values.put("detailLine", data.detailLine());
        ChatUi.send(src, OutputRenderer.render("claim.info", values, src, null));
        return 1;
    }

    record InfoData(int x, int z, String dimension, String status, String detailLine) {
        String text() {
            return "区块 (" + x + "," + z + ") " + dimension + " " + status + detailLine;
        }
    }

    // ---- claim boundary ----
    private static int toggleBoundary(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return reply(src, "需要以玩家身份执行");
        }
        boolean nowOn = ClaimParticles.toggleBoundary(player.getUUID());
        return boundaryReply(src, "claim.boundary", nowOn,
                " ✓（进入管理区域自动显示）", " ✗");
    }

    private static int toggleFixed(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return reply(src, "需要以玩家身份执行");
        }
        boolean nowOn = ClaimParticles.toggleFixed(player.getUUID());
        return boundaryReply(src, "claim.boundary.fixed", nowOn, " ✓", " ✗");
    }

    private static int toggleDynamic(CommandSourceStack src) {
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return reply(src, "需要以玩家身份执行");
        }
        boolean nowOn = ClaimParticles.toggleDynamic(player.getUUID());
        return boundaryReply(src, "claim.boundary.dynamic", nowOn, " ✓", " ✗");
    }

    private static int boundaryReply(CommandSourceStack src, String key, boolean on,
                                     String onDetail, String offDetail) {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("state", on ? "开启" : "关闭");
        values.put("detail", on ? onDetail : offDetail);
        ChatUi.send(src, OutputRenderer.render(key, values, src, null));
        return 1;
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
                ClaimNode existing = AreaService.getInstance().getNode(level.dimension(), chunkPos);
                if (existing == null) {
                    // 未注册 → 自动注册
                    String facing = ClaimInteractions.getFacing(level, pos);
                    ClaimNode newNode = new ClaimNode(chunkPos, level.dimension(), facing, patterns,
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
        Map<ChunkCoord, ClaimNode> nodes = AreaService.getInstance().getNodes(level.dimension());
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
                removals.add(() -> AreaService.getInstance().removeNode(level.dimension(), chunk));
            }
        }
        for (Runnable r : removals) r.run();

        // 保存变更
        if (autoRegistered[0] > 0 || autoRemoved[0] > 0) {
            AreaService.getInstance().recompute(level.dimension());
        }

        int totalNodes = AreaService.getInstance().getNodes(level.dimension()).size();
        int totalChunks = AreaService.getInstance().getWhitelist(level.dimension()).size();
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
        src.sendSuccess(() -> OutputRenderer.renderLegacy("claim.reply", message, src), false);
        return 1;
    }
}
