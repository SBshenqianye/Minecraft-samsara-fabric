package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.identity.IdentityService;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.UUID;

/**
 * 身份组只读命令。
 *
 * <p>v0.20 起不再提供注册/登录/改密/绑定/迁移/踢出等管理命令；
 * 组增删改与 data 重绑全部走本地 Web 身份组页面。
 */
public final class IdentityCommand {
    private IdentityCommand() {
    }

    /** {@code /samsara identity status} 与短别名 {@code /identity}。 */
    public static LiteralArgumentBuilder<CommandSourceStack> identityNode() {
        return Commands.literal("identity")
                .then(Commands.literal("status")
                        .executes(ctx -> {
                            ServerPlayer player = player(ctx.getSource());
                            if (player == null) {
                                return reply(ctx.getSource(),
                                        IdentityService.getInstance().status(null));
                            }
                            return reply(ctx.getSource(),
                                    IdentityService.getInstance().status(player));
                        }));
    }

    /** {@code /samsara debug identity map|preview|reload|audit}（OWNER）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> debugIdentityNode() {
        return Commands.literal("identity")
                .then(Commands.literal("map")
                        .executes(ctx -> reply(ctx.getSource(), IdentityService.getInstance().map())))
                .then(Commands.literal("preview")
                        .executes(ctx -> reply(ctx.getSource(),
                                IdentityService.getInstance().preview(
                                        player(ctx.getSource()) == null ? null
                                                : player(ctx.getSource()).getUUID())))
                        .then(Commands.argument("target", StringArgumentType.word())
                                .suggests(SuggestionProviders.knownPlayersProvider())
                                .executes(ctx -> {
                                    UUID uuid = resolve(ctx.getSource(),
                                            StringArgumentType.getString(ctx, "target"));
                                    if (uuid == null) {
                                        return reply(ctx.getSource(), "找不到玩家或 UUID："
                                                + StringArgumentType.getString(ctx, "target"));
                                    }
                                    return reply(ctx.getSource(),
                                            IdentityService.getInstance().preview(uuid));
                                })))
                .then(Commands.literal("reload")
                        .executes(ctx -> reply(ctx.getSource(), IdentityService.getInstance().reload())))
                .then(Commands.literal("audit")
                        .executes(ctx -> reply(ctx.getSource(),
                                IdentityService.getInstance().auditReport())));
    }

    private static UUID resolve(CommandSourceStack source, String spec) {
        if (spec == null || spec.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(spec);
        } catch (RuntimeException ignored) {
            // 继续按玩家名解析
        }
        return com.shenqianye.samsara.track.TrackRecorder.resolveUuid(source.getServer(), spec);
    }

    private static ServerPlayer player(CommandSourceStack src) {
        return src.getEntity() instanceof ServerPlayer player ? player : null;
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
