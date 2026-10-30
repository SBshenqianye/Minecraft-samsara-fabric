package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ui.ChatUi;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.identity.IdentityConfig;
import com.shenqianye.samsara.identity.IdentityService;
import com.shenqianye.samsara.identity.IdentityStateStore;
import com.shenqianye.samsara.identity.IdentityValidator;
import com.shenqianye.samsara.output.OutputRenderer;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.LinkedHashMap;
import java.util.Map;
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
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker
                        .check(src, "identity"))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx.getSource(), player(ctx.getSource()))));
    }

    private static int status(CommandSourceStack src, ServerPlayer player) {
        IdentityService service = IdentityService.getInstance();
        if (!service.enabled()) {
            return send(src, "identity.status.disabled", Map.of());
        }
        if (service.configStore() != null && !service.configStore().valid()) {
            StringBuilder issues = new StringBuilder();
            for (IdentityValidator.Issue issue : service.configStore().issues()) {
                if (issues.length() > 0) {
                    issues.append('\n');
                }
                issues.append("  · ").append(issue.field()).append("：").append(issue.message());
            }
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("issues", issues.length() == 0 ? "\n  · 未知错误" : "\n" + issues);
            return send(src, "identity.status.invalid", values);
        }
        UUID uuid = player == null ? null : player.getUUID();
        IdentityConfig.Group group = uuid == null ? null : service.groupOf(uuid);
        if (group == null) {
            Map<String, Object> values = new LinkedHashMap<>();
            values.put("uuid", uuid == null ? "?" : uuid.toString());
            return send(src, "identity.status.ungrouped", values);
        }

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("groupName", group.name);
        values.put("dataUuid", group.data);
        values.put("premium", group.premium == null ? "（未设置）" : group.premium);
        values.put("offline", group.offline == null ? "（未设置）" : group.offline);
        values.put("noteLine", group.note == null || group.note.isBlank()
                ? "" : "\n备注: " + group.note);
        IdentityStateStore.GroupState state = service.stateStore() == null ? null
                : service.stateStore().get(group.dataUuid());
        values.put("lastWriterLine", state == null ? ""
                : "\n最后写入账号: "
                + (state.lastWriter == null ? "（尚未同步）" : state.lastWriter));
        values.put("blockedLine", state != null && state.syncBlocked
                ? "\n⚠ 同步已阻断（sync-blocked），请联系管理员" : "");
        return send(src, "identity.status", values);
    }

    private static int send(CommandSourceStack src, String key, Map<String, Object> values) {
        ChatUi.send(src, OutputRenderer.render(key, values, src, null));
        return 1;
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
