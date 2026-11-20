package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
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
                                                .suggests(SuggestionProviders.choices(DebugCommand::claimDirTooltip,
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
}
