package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import java.util.List;
import com.shenqianye.samsara.permission.PermissionRequirements;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import static com.shenqianye.samsara.command.DebugCommand.*;

/** `/samsara debug claim` 子命令树。 */
final class DebugClaimCommands {
    private DebugClaimCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("claim")
                                .requires(PermissionRequirements.ownerAnd("debug claim"))
                                // debug claim give <dir> <dist>
                                .then(Commands.literal("give")
                                        .requires(PermissionRequirements.ownerAnd("debug claim give"))
                                        .then(Commands.argument("dir", StringArgumentType.word())
                                                .suggests(SuggestionProviders.choices(DebugClaimCommands::claimDirTooltip,
                                                        "f", "b", "l", "r",
                                                        "fl", "fr", "bl", "br",
                                                        "dl", "dr", "c", "m",
                                                        "sc", "x", "a"))
                                                .then(Commands.argument("dist", IntegerArgumentType.integer(1, 16))
                                                        .executes(ctx -> reply(ctx.getSource(), debugGiveBanner(
                                                                ctx.getSource(),
                                                                StringArgumentType.getString(ctx, "dir"),
                                                                IntegerArgumentType.getInteger(ctx, "dist")))))))
                                // debug claim flags：一次性给全套测试旗（白底橙图案，含图标图案）
                                .then(Commands.literal("flags")
                                        .requires(PermissionRequirements.ownerAnd("debug claim flags"))
                                        .executes(ctx -> reply(ctx.getSource(), debugGiveFlags(ctx.getSource()))))
                                // debug claim list
                                .then(Commands.literal("list")
                                        .requires(PermissionRequirements.ownerAnd("debug claim list"))
                                        .executes(ctx -> reply(ctx.getSource(), debugListNodes())))
                                // debug claim rebuild
                                .then(Commands.literal("rebuild")
                                        .requires(PermissionRequirements.ownerAnd("debug claim rebuild"))
                                        .executes(ctx -> reply(ctx.getSource(), debugRebuild())))
                                // debug claim scan
                                .then(Commands.literal("scan")
                                        .requires(PermissionRequirements.ownerAnd("debug claim scan"))
                                        .executes(ctx -> reply(ctx.getSource(), debugScan(ctx.getSource()))))
                                // debug claim remove <x> <z>
                                .then(Commands.literal("remove")
                                        .requires(PermissionRequirements.ownerAnd("debug claim remove"))
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .suggests(SuggestionProviders.chunkXProvider())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .suggests(SuggestionProviders.chunkZProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugRemoveNode(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z")))))))
                                // debug claim info <x> <z>
                                .then(Commands.literal("info")
                                        .requires(PermissionRequirements.ownerAnd("debug claim info"))
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .suggests(SuggestionProviders.chunkXProvider())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .suggests(SuggestionProviders.chunkZProvider())
                                                        .executes(ctx -> {
                                                            net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dim = net.minecraft.world.level.Level.OVERWORLD;
                                                            try { dim = ctx.getSource().getPlayerOrException().level().dimension(); }
                                                            catch (Exception ignored) {}
                                                            return reply(ctx.getSource(),
                                                                    ClaimCommand.infoAt(dim,
                                                                            IntegerArgumentType.getInteger(ctx, "x"),
                                                                            IntegerArgumentType.getInteger(ctx, "z")));
                                                        }))))
                                // debug claim particle ...
                                .then(Commands.literal("particle")
                                        .requires(PermissionRequirements.ownerAnd("debug claim particle"))
                                        .then(Commands.literal("day")
                                                .requires(PermissionRequirements.ownerAnd("debug claim particle day"))
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("day", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("night")
                                                .requires(PermissionRequirements.ownerAnd("debug claim particle night"))
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("night", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("font")
                                                .requires(PermissionRequirements.ownerAnd("debug claim particle font"))
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("font", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("beam")
                                                .requires(PermissionRequirements.ownerAnd("debug claim particle beam"))
                                                .then(Commands.argument("name", StringArgumentType.word())
                                                        .suggests((ctx, builder) -> {
                                                            for (String name : com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticleNames()) {
                                                                builder.suggest(name);
                                                            }
                                                            return builder.buildFuture();
                                                        })
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetParticle("beam", StringArgumentType.getString(ctx, "name"))))))
                                        .then(Commands.literal("list")
                                                .requires(PermissionRequirements.ownerAnd("debug claim particle list"))
                                                .executes(ctx -> reply(ctx.getSource(), debugListParticles())))
                                        .then(Commands.literal("shared")
                                                .requires(PermissionRequirements.ownerAnd("debug claim particle shared"))
                                                .then(Commands.argument("on_off", StringArgumentType.word())
                                                        .suggests(SuggestionProviders.boolProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetSharedDisplay(StringArgumentType.getString(ctx, "on_off"))))))
                                        .then(Commands.literal("global")
                                                .requires(PermissionRequirements.ownerAnd("debug claim particle global"))
                                                .then(Commands.argument("on_off", StringArgumentType.word())
                                                        .suggests(SuggestionProviders.boolProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugSetGlobalDisplay(StringArgumentType.getString(ctx, "on_off")))))))
                                // debug claim delete dryrun|backup|execute|report
                                // dryrun: 列出将被删除的区块（不实际操作）
                                // backup: 备份受影响区域的 .mca 文件到 config/samsara/backups/
                                // execute: 报告待删除区块数（服务端运行时不直接删除，需停服手动操作）
                                // report: 生成删除报告（白名单数、待删数、示例坐标）
                                .then(Commands.literal("delete")
                                        .requires(PermissionRequirements.ownerAnd("debug claim delete"))
                                        .then(Commands.literal("dryrun")
                                                .requires(PermissionRequirements.ownerAnd("debug claim delete dryrun"))
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteDryRun(ctx.getSource()))))
                                        .then(Commands.literal("backup")
                                                .requires(PermissionRequirements.ownerAnd("debug claim delete backup"))
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteBackup(ctx.getSource()))))
                                        .then(Commands.literal("execute")
                                                .requires(PermissionRequirements.ownerAnd("debug claim delete execute"))
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteExecute(ctx.getSource()))))
                                        .then(Commands.literal("report")
                                                .requires(PermissionRequirements.ownerAnd("debug claim delete report"))
                                                .executes(ctx -> reply(ctx.getSource(), debugDeleteReport(ctx.getSource())))))
                                // debug claim owner <x> <z> — 查看指定区块的旗帜归属
                                .then(Commands.literal("owner")
                                        .requires(PermissionRequirements.ownerAnd("debug claim owner"))
                                        .then(Commands.argument("x", IntegerArgumentType.integer())
                                                .suggests(SuggestionProviders.chunkXProvider())
                                                .then(Commands.argument("z", IntegerArgumentType.integer())
                                                        .suggests(SuggestionProviders.chunkZProvider())
                                                        .executes(ctx -> reply(ctx.getSource(),
                                                                debugClaimOwner(
                                                                        IntegerArgumentType.getInteger(ctx, "x"),
                                                                        IntegerArgumentType.getInteger(ctx, "z"))))))); 
    }
    // ---- debug claim 命令实现 ----

    /**
     * debug give：给予玩家圈地旗帜。
     * <p>
     * 方向字母按**图案在旗帜上的位置**命名（观看者视角，与 MC 图案 id 同一视角）：
     * <pre>
     *   f 前   → stripe_top          b 后   → stripe_bottom
     *   l 左   → stripe_left         r 右   → stripe_right
     *   fl 前左 → square_top_left     fr 前右 → square_top_right
     *   bl 后左 → square_bottom_left  br 后右 → square_bottom_right
     *   dl 斜左下 → stripe_downleft  dr 斜右下 → stripe_downright
     *   c 竖条 → stripe_center        m 横条 → stripe_middle
     *   sc 四正 → straight_cross      x 四斜 → cross        a 全部8方向 → border
     * </pre>
     * 形状提示（延伸距离同为 d）：`a`/`x` 围成**正方形**、`sc` 围成**菱形**、
     * 单条条纹只有**线**；放好旗帜后右键可看到实际世界方向与"是否围成面"。
     */
    static String debugGiveBanner(CommandSourceStack src, String dir, int dist) {
        String key = dir.toLowerCase();
        String patternId = switch (key) {
            case "f"  -> "stripe_top";
            case "b"  -> "stripe_bottom";
            case "l"  -> "stripe_left";
            case "r"  -> "stripe_right";
            case "fl" -> "square_top_left";
            case "fr" -> "square_top_right";
            case "bl" -> "square_bottom_left";
            case "br" -> "square_bottom_right";
            case "dl" -> "stripe_downleft";
            case "dr" -> "stripe_downright";
            case "c"  -> "stripe_center";
            case "m"  -> "stripe_middle";
            case "sc" -> "straight_cross";
            case "x"  -> "cross";
            case "a"  -> "border";
            default -> null;
        };
        if (patternId == null) {
            return "方向无效。可用：f/b/l/r（单条纹）· fl/fr/bl/br（角）· dl/dr（斜条纹）· "
                    + "c/m（竖条/横条）· sc（四正）· x（四斜）· a（全部8方向）";
        }
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "需要以玩家身份执行";
        }

        int colorValue = dist - 1;
        net.minecraft.world.item.DyeColor dye = net.minecraft.world.item.DyeColor.byId(colorValue);

        var bp = new net.minecraft.world.level.block.entity.BannerPattern(
                net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", patternId),
                "block.minecraft.banner." + patternId);
        var layer = new net.minecraft.world.level.block.entity.BannerPatternLayers.Layer(
                net.minecraft.core.Holder.direct(bp), dye);
        var layers = new net.minecraft.world.level.block.entity.BannerPatternLayers(List.of(layer));

        net.minecraft.world.item.ItemStack banner = new net.minecraft.world.item.ItemStack(
                net.minecraft.world.item.Items.BANNER.white());
        banner.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, layers);
        banner.setCount(1);
        player.getInventory().add(banner);

        return "已给予圈地旗：" + dir + " " + dist + " 区块";
    }

    /**
     * {@code /samsara debug claim flags}：给执行者一套测试旗。
     * 白底 + 橙色方向图案 + 橙色图标图案（skull），全部满足注册条件，方便一次测完所有方向。
     */
    static String debugGiveFlags(CommandSourceStack src) {
        net.minecraft.server.level.ServerPlayer player;
        try {
            player = src.getPlayerOrException();
        } catch (Exception e) {
            return "需要以玩家身份执行";
        }
        java.util.List<String> patterns = java.util.List.of(
                "stripe_top", "stripe_bottom", "stripe_left", "stripe_right",
                "square_top_left", "square_top_right", "square_bottom_left", "square_bottom_right",
                "stripe_downleft", "stripe_downright", "stripe_center", "stripe_middle",
                "cross", "straight_cross", "border");
        int given = 0;
        for (String patternId : patterns) {
            var dir = new net.minecraft.world.level.block.entity.BannerPattern(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", patternId),
                    "block.minecraft.banner." + patternId);
            var icon = new net.minecraft.world.level.block.entity.BannerPattern(
                    net.minecraft.resources.Identifier.fromNamespaceAndPath("minecraft", "skull"),
                    "block.minecraft.banner.skull");
            var layers = new net.minecraft.world.level.block.entity.BannerPatternLayers(java.util.List.of(
                    new net.minecraft.world.level.block.entity.BannerPatternLayers.Layer(
                            net.minecraft.core.Holder.direct(icon), net.minecraft.world.item.DyeColor.WHITE),
                    new net.minecraft.world.level.block.entity.BannerPatternLayers.Layer(
                            net.minecraft.core.Holder.direct(dir), net.minecraft.world.item.DyeColor.ORANGE)));
            net.minecraft.world.item.ItemStack banner = new net.minecraft.world.item.ItemStack(
                    net.minecraft.world.item.Items.BANNER.white());
            banner.set(net.minecraft.core.component.DataComponents.BANNER_PATTERNS, layers);
            banner.set(net.minecraft.core.component.DataComponents.CUSTOM_NAME,
                    net.minecraft.network.chat.Component.literal("测试旗 " + patternId)
                            .withStyle(net.minecraft.ChatFormatting.GOLD));
            player.getInventory().add(banner);
            given++;
        }
        return "已给予 " + given + " 面测试旗（白底 + 橙色方向图案 + 图标图案），可直接放置测试。";
    }

    /**
     * debug give 的方向补全说明。
     * <p>
     * 方向字母按**图案在旗帜上的位置**命名（观看者视角，与 MC 图案 id 同一视角）。
     */
    static String claimDirTooltip(String dir) {
        return switch (dir) {
            case "f" -> "前：stripe_top（单条纹·线）";
            case "b" -> "后：stripe_bottom（单条纹·线）";
            case "l" -> "左：stripe_left（单条纹·线）";
            case "r" -> "右：stripe_right（单条纹·线）";
            case "fl" -> "前左：square_top_left（角）";
            case "fr" -> "前右：square_top_right（角）";
            case "bl" -> "后左：square_bottom_left（角）";
            case "br" -> "后右：square_bottom_right（角）";
            case "dl" -> "斜左下：stripe_downleft";
            case "dr" -> "斜右下：stripe_downright";
            case "c" -> "竖条：stripe_center";
            case "m" -> "横条：stripe_middle";
            case "sc" -> "四正：straight_cross（实心菱形）";
            case "x" -> "四斜：cross（实心正方形）";
            case "a" -> "全部 8 方向：border（实心正方形）";
            default -> "方向字母";
        };
    }

    /** debug list：列出所有已注册的圈地旗帜 */
    static String debugListNodes() {
        var allNodes = com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes();
        if (allNodes.isEmpty()) {
            return "当前无圈地旗帜";
        }
        int totalNodes = allNodes.values().stream().mapToInt(java.util.Map::size).sum();
        int totalChunks = 0;
        for (var dimEntry : allNodes.keySet()) {
            totalChunks += com.shenqianye.samsara.claim.AreaService.getInstance()
                    .getWhitelist(dimEntry).size();
        }
        StringBuilder sb = new StringBuilder("圈地旗帜（" + totalNodes + " 面）· 保护区块 " + totalChunks + " 个：\n");
        for (var dimEntry : allNodes.entrySet()) {
            sb.append("  [").append(dimEntry.getKey().toString()).append("]\n");
            for (var e : dimEntry.getValue().entrySet()) {
                com.shenqianye.samsara.geometry.ChunkCoord loc = e.getKey();
                com.shenqianye.samsara.claim.ClaimNode n = e.getValue();
                sb.append("  ").append(loc).append(" 朝向").append(com.shenqianye.samsara.claim.ClaimInteractions.facingToDisplay(n.getFacing()))
                        .append(" 图案").append(n.getPatterns().size()).append(" 个");
                if (n.getOwnerName() != null) sb.append(" 主人:").append(n.getOwnerName());
                sb.append("\n");
            }
        }
        sb.append("数据文件: ").append(com.shenqianye.samsara.claim.AreaService.getInstance().configPath());
        return sb.toString().trim();
    }

    /** debug rebuild：强制重算白名单 */
    static String debugRebuild() {
        // 重建所有维度
        for (var dimEntry : com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes().keySet()) {
            com.shenqianye.samsara.claim.AreaService.getInstance().recompute(dimEntry);
        }
        int totalChunks = 0;
        int totalNodes = 0;
        for (var dimEntry : com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes().keySet()) {
            totalChunks += com.shenqianye.samsara.claim.AreaService.getInstance()
                    .getWhitelist(dimEntry).size();
            totalNodes += com.shenqianye.samsara.claim.AreaService.getInstance()
                    .getNodes(dimEntry).size();
        }
        return "白名单已重算：" + totalNodes
                + " 面旗帜 → " + totalChunks + " 个保护区块";
    }

    /** debug scan：扫描世界中已放置的圈地旗帜 */
    static String debugScan(CommandSourceStack src) {
        var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
        if (level == null) return "主世界不可用";
        return com.shenqianye.samsara.command.ClaimCommand.scanLevel(level);
    }

    /** debug remove：移除指定位置的圈地旗帜（默认 overworld） */
    static String debugRemoveNode(int x, int z) {
        return com.shenqianye.samsara.claim.AreaService.getInstance().removeNode(
                net.minecraft.world.level.Level.OVERWORLD,
                new com.shenqianye.samsara.geometry.ChunkCoord(x, z));
    }

    // ---- debug claim delete（生命周期说明） ----
    // 生命周期：dryrun（预览）→ backup（备份）→ execute（入队 + 立即删除未加载区块）→ report（报告）
    // execute 会真正删除区块（不可逆），因此带二次确认；删除动作在后台线程执行备份 + 清零。

    /** debug claim delete dryrun：预览将被删除的区块（不实际操作） */
    static String debugDeleteDryRun(CommandSourceStack src) {
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.dryRun(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteDryRun 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** debug claim delete backup：备份受影响区域的 .mca 文件到 config/samsara/backups/ */
    static String debugDeleteBackup(CommandSourceStack src) {
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.backup(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteBackup 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /**
     * debug claim delete execute：把全部非白名单区块加入待删队列，并立即删除其中<b>未加载</b>的区块
     * （备份 + 清零，不可逆）——因此需二次确认（《设计规范》〈破坏性操作：二次确认〉/ 需求 `F-63`）。
     * <p>
     * <b>修正历史注释</b>：旧注释写作"execute（停服后手动删 .mca）/ 服务端运行时不会直接删除文件"，
     * 与实际实现不符——{@code SafeDeletion.execute()} 会入队 {@code PendingDeleteQueue} 并
     * <b>立即删除所有未加载区块</b>，无需停服手动操作。
     */
    static String debugDeleteExecute(CommandSourceStack src) {
        if (!ConfirmGuard.confirm("claim.delete.execute")) {
            return "⚠ 危险操作确认：删除全部未圈地（非白名单）区块\n"
                    + "· 立即删除【未加载】区块（备份 + 清零，不可逆）；仍加载的区块入待删队列、卸载即删\n"
                    + "· 备份只作人工恢复用，不能自动回滚\n"
                    + "· 建议先执行 dryrun 预览、backup 备份\n"
                    + "再次输入 /samsara debug claim delete execute（" + ConfirmGuard.ttlSeconds()
                    + " 秒内）才会真正执行。";
        }
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.execute(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteExecute 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }


    /** debug claim delete report：生成删除报告（白名单数、待删数、示例坐标） */
    static String debugDeleteReport(CommandSourceStack src) {
        try {
            var level = src.getServer().getLevel(net.minecraft.world.level.Level.OVERWORLD);
            if (level == null) return "主世界不可用";
            return com.shenqianye.samsara.claim.SafeDeletion.report(level);
        } catch (Exception e) {
            com.shenqianye.samsara.Samsara.LOGGER.error("[samsara] debugDeleteReport 异常", e);
            return "执行出错: " + e.getClass().getSimpleName() + ": " + e.getMessage();
        }
    }

    /** debug claim owner <x> <z>：查看指定区块的旗帜归属 */
    static String debugClaimOwner(int x, int z) {
        // 搜索所有维度
        com.shenqianye.samsara.claim.ClaimNode node = null;
        net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> foundDim = null;
        for (var dimEntry : com.shenqianye.samsara.claim.AreaService.getInstance().getAllNodes().entrySet()) {
            var n = dimEntry.getValue().get(new com.shenqianye.samsara.geometry.ChunkCoord(x, z));
            if (n != null) { node = n; foundDim = dimEntry.getKey(); break; }
        }
        if (node == null) {
            return "区块 (" + x + "," + z + ") 无注册圈地旗";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("区块 (").append(x).append(",").append(z).append(") 圈地旗归属：\n");
        sb.append("  朝向: ").append(com.shenqianye.samsara.claim.ClaimInteractions.facingToDisplay(node.getFacing())).append("\n");
        if (node.getOwnerUUID() != null) {
            sb.append("  主人 UUID: ").append(node.getOwnerUUID()).append("\n");
            sb.append("  主人名称: ").append(node.getOwnerName() != null ? node.getOwnerName() : "未知").append("\n");
        } else {
            sb.append("  主人: 无（命令创建或旧版数据）\n");
        }
        sb.append("  图案: ");
        for (var pe : node.getPatterns()) {
            sb.append(pe.patternId()).append("(色").append(pe.color()).append(") ");
        }
        return sb.toString().trim();
    }

    /** debug particle：设置粒子类型 */
    static String debugSetParticle(String type, String name) {
        var config = com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance();
        if (!com.shenqianye.samsara.claim.ClaimParticleConfig.isValidParticle(name)) {
            return "无效粒子类型: " + name + "\n可用: " + com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticles();
        }
        return switch (type) {
            case "day" -> { config.setDayParticle(name); yield "昼间边界粒子已设为: " + name; }
            case "night" -> { config.setNightParticle(name); yield "夜间边界粒子已设为: " + name; }
            case "font" -> { config.setFontParticle(name); yield "字体粒子已设为: " + name; }
            case "beam" -> { config.setBeamParticle(name); yield "光束粒子已设为: " + name; }
            default -> "未知类型: " + type;
        };
    }

    /** debug particle list：显示当前粒子配置 */
    static String debugListParticles() {
        var config = com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance();
        return "当前粒子配置：\n  昼间边界: " + config.getDayParticleName()
                + "\n  夜间边界: " + config.getNightParticleName()
                + "\n  字体: " + config.getFontParticleName()
                + "\n  光束: " + config.getBeamParticleName()
                + "\n  共享显示: " + (config.isSharedDisplay() ? "ON（所有人可见）" : "OFF（仅持有者可见）")
                + "\n  全图显示: " + (config.isGlobalDisplay() ? "ON（不在管理区域内也显示）" : "OFF（仅管理区域内显示）")
                + "\n可用粒子: " + com.shenqianye.samsara.claim.ClaimParticleConfig.getAvailableParticles();
    }

    /** debug particle shared：设置共享显示 */
    static String debugSetSharedDisplay(String onOff) {
        boolean shared = onOff.equalsIgnoreCase("on") || onOff.equalsIgnoreCase("true");
        com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance().setSharedDisplay(shared);
        return "共享显示已设为: " + (shared ? "ON（所有人可见 TextDisplay）" : "OFF（仅手持旗帜者可见粒子）");
    }

    /** debug particle global：设置全图显示 */
    static String debugSetGlobalDisplay(String onOff) {
        boolean global = onOff.equalsIgnoreCase("on") || onOff.equalsIgnoreCase("true");
        com.shenqianye.samsara.claim.ClaimParticleConfig.getInstance().setGlobalDisplay(global);
        return "全图显示已设为: " + (global ? "ON（不在管理区域内也显示边界）" : "OFF（仅管理区域内显示）");
    }

    /**
     * {@code /samsara}：可点击指令列表。
     * <p>
     * 只读命令用 {@link ChatUi#run}（点击即执行），破坏性/管理员/需要参数的命令用
     * {@link ChatUi#suggest}（点击只填入）。管理员按钮再按 {@code PermissionChecker} 决定是否显示，
     * 避免普通玩家看到点了也会被拒绝的按钮。
     */
}

