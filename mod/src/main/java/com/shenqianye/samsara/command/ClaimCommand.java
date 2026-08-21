package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shenqianye.samsara.claim.AreaService;
import com.shenqianye.samsara.claim.ClaimNode;
import com.shenqianye.samsara.geometry.ChunkCoord;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.entity.BannerPatternLayers;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 旗帜圈地命令（设计文档 §17.3 M4b）：{@code /samsara claim ...}
 * <p>
 * 给予旗帜 / 列表 / 详情 / 重建白名单 / 扫描 / 预览 / 移除 / 导出。
 */
public class ClaimCommand {

    // ---- 染料颜色序号 → NBT Color 值映射（反向：距离 → color） ----
    private static final String[] COLOR_NAMES = {
        "white","orange","magenta","light_blue","yellow","lime",
        "pink","gray","light_gray","cyan","purple","blue",
        "brown","green","red","black"
    };

    /** /samsara claim ... 子树 */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> claimNode() {
        return Commands.literal("claim")
                // claim give <dir> <dist>
                .then(Commands.literal("give")
                        .requires(src -> isAdmin(src))
                        .then(Commands.argument("dir", StringArgumentType.word())
                                .then(Commands.argument("dist", IntegerArgumentType.integer(1, 16))
                                        .executes(ctx -> reply(ctx.getSource(), giveBanner(
                                                ctx.getSource(),
                                                StringArgumentType.getString(ctx, "dir"),
                                                IntegerArgumentType.getInteger(ctx, "dist")))))))
                // claim list
                .then(Commands.literal("list")
                        .executes(ctx -> reply(ctx.getSource(), listNodes())))
                // claim info [pos]
                .then(Commands.literal("info")
                        .executes(ctx -> reply(ctx.getSource(), infoAtPlayer(ctx.getSource())))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                infoAt(IntegerArgumentType.getInteger(ctx, "x"),
                                                      IntegerArgumentType.getInteger(ctx, "z")))))))
                // claim rebuild
                .then(Commands.literal("rebuild")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), rebuild())))
                // claim scan
                .then(Commands.literal("scan")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), scan())))
                // claim preview
                .then(Commands.literal("preview")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), preview())))
                // claim remove <x> <z>
                .then(Commands.literal("remove")
                        .requires(src -> isAdmin(src))
                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                        .executes(ctx -> reply(ctx.getSource(),
                                                removeNode(IntegerArgumentType.getInteger(ctx, "x"),
                                                          IntegerArgumentType.getInteger(ctx, "z")))))))
                // claim export
                .then(Commands.literal("export")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), export())));
    }

    // ---- claim give ----

    private static String giveBanner(CommandSourceStack src, String dir, int dist) {
        String patternId = switch (dir.toLowerCase()) {
            case "n"  -> "stripe_top"; case "s"  -> "stripe_bottom";
            case "w"  -> "stripe_left"; case "e"  -> "stripe_right";
            case "nw" -> "square_top_left"; case "sw" -> "square_bottom_left";
            case "ne" -> "square_top_right"; case "se" -> "square_bottom_right";
            case "all"-> "border";
            default -> null;
        };
        if (patternId == null) {
            return "方向无效（n/ne/e/se/s/sw/w/nw/all）";
        }
        String dirName = switch (dir.toLowerCase()) {
            case "n" -> "北"; case "s" -> "南"; case "w" -> "西"; case "e" -> "东";
            case "nw" -> "西北"; case "sw" -> "西南"; case "ne" -> "东北"; case "se" -> "东南";
            case "all" -> "全方向"; default -> dir;
        };
        ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "需要以玩家身份执行";
        }

        // MC 26.2 API：Items.BANNER.white() + DataComponents.BANNER_PATTERNS
        int colorValue = dist - 1; // dist 1~16 → color ordinal 0~15
        DyeColor dye = DyeColor.byId(colorValue);

        // 创建 BannerPattern 并用 Holder.direct 包装
        var bp = new net.minecraft.world.level.block.entity.BannerPattern(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", patternId),
                "block.minecraft.banner." + patternId);
        var layer = new BannerPatternLayers.Layer(net.minecraft.core.Holder.direct(bp), dye);
        var layers = new BannerPatternLayers(List.of(layer));

        ItemStack banner = new ItemStack(Items.BANNER.white());
        banner.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, layers);
        banner.setCount(1);
        player.getInventory().add(banner);

        return "已给予圈地旗：" + dirName + " " + dist + " 区块";
    }

    // ---- claim list ----

    private static String listNodes() {
        Map<ChunkCoord, ClaimNode> nodes = AreaService.getInstance().getNodes();
        if (nodes.isEmpty()) {
            return "当前无圈地旗帜。用 /samsara claim give <方向> <距离> 获取";
        }
        Set<ChunkCoord> wl = AreaService.getInstance().getWhitelist();
        StringBuilder sb = new StringBuilder("圈地旗帜（" + nodes.size() + " 面）· 保护区块 " + wl.size() + " 个：\n");
        for (Map.Entry<ChunkCoord, ClaimNode> e : nodes.entrySet()) {
            ChunkCoord loc = e.getKey();
            ClaimNode n = e.getValue();
            sb.append("  ").append(loc).append(" 朝向").append(n.getFacing())
                    .append(" 图案").append(n.getPatterns().size()).append(" 个\n");
        }
        sb.append("数据文件: ").append(AreaService.getInstance().configPath());
        return sb.toString().trim();
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

    private static String infoAt(int x, int z) {
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

    // ---- claim rebuild ----

    private static String rebuild() {
        AreaService.getInstance().recompute();
        Set<ChunkCoord> wl = AreaService.getInstance().getWhitelist();
        return "白名单已重算：" + AreaService.getInstance().getNodes().size() + " 面旗帜 → "
                + wl.size() + " 个保护区块";
    }

    // ---- claim scan ----

    private static String scan() {
        // TODO: 扫描世界中的所有旗帜方块（需要 ServerLevel）
        return "扫描功能待实现（需 ServerLevel 访问）";
    }

    // ---- claim preview ----

    private static String preview() {
        Set<ChunkCoord> wl = AreaService.getInstance().getWhitelist();
        if (wl.isEmpty()) return "无保护区块";
        // 找边界
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkCoord c : wl) {
            minX = Math.min(minX, c.x); maxX = Math.max(maxX, c.x);
            minZ = Math.min(minZ, c.z); maxZ = Math.max(maxZ, c.z);
        }
        return "保护区域：(" + minX + "," + minZ + ")~(" + maxX + "," + maxZ + ") "
                + wl.size() + " 区块 · " + (maxX - minX + 1) + "×" + (maxZ - minZ + 1);
    }

    // ---- claim remove ----

    private static String removeNode(int x, int z) {
        return AreaService.getInstance().removeNode(new ChunkCoord(x, z));
    }

    // ---- claim export ----

    private static String export() {
        Map<ChunkCoord, ClaimNode> nodes = AreaService.getInstance().getNodes();
        Set<ChunkCoord> wl = AreaService.getInstance().getWhitelist();
        StringBuilder sb = new StringBuilder("圈地导出（JSON 格式）：\n");
        sb.append("  旗帜: ").append(nodes.size()).append(" 面\n");
        sb.append("  保护区块: ").append(wl.size()).append(" 个\n");
        sb.append("  数据文件: ").append(AreaService.getInstance().configPath());
        return sb.toString();
    }

    // ---- 工具 ----

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> Component.literal("[周目管理] " + message), false);
        return 1;
    }
}
