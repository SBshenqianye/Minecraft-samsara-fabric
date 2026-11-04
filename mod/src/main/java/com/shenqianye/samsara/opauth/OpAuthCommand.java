package com.shenqianye.samsara.opauth;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.shenqianye.samsara.command.ChatFormat;
import com.shenqianye.samsara.permission.PermissionChecker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * OP / owner 登录命令。
 *
 * <p>玩家：{@code /samsara register <注册码> <密码> <密码>}、{@code /samsara login <密码>}。
 * 管理员：{@code /samsara opauth code|reset|set|list}，控制台/RCON 可直接用。
 * 短别名 {@code /reg}、{@code /l} 由 {@code CommandAliases} 按“谦让”规则注册。
 */
public final class OpAuthCommand {
    private OpAuthCommand() {
    }

    public static LiteralArgumentBuilder<CommandSourceStack> registerNode() {
        return registerCommand("register");
    }

    /** 短别名 {@code /reg}。 */
    public static LiteralArgumentBuilder<CommandSourceStack> registerAliasNode() {
        return registerCommand("reg");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> registerCommand(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("code", StringArgumentType.word())
                        .then(Commands.argument("password", StringArgumentType.word())
                                .then(Commands.argument("confirm", StringArgumentType.word())
                                        .executes(ctx -> {
                                            ServerPlayer player = ctx.getSource().getPlayer();
                                            if (player == null) {
                                                return fail(ctx.getSource(), "只能由玩家执行。");
                                            }
                                            OpAuthService.getInstance().register(player,
                                                    StringArgumentType.getString(ctx, "code"),
                                                    StringArgumentType.getString(ctx, "password"),
                                                    StringArgumentType.getString(ctx, "confirm"));
                                            return 1;
                                        }))));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> loginNode() {
        return loginCommand("login");
    }

    /** 短别名 {@code /l}。 */
    public static LiteralArgumentBuilder<CommandSourceStack> loginAliasNode() {
        return loginCommand("l");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> loginCommand(String literal) {
        return Commands.literal(literal)
                .then(Commands.argument("password", StringArgumentType.word())
                        .executes(ctx -> {
                            ServerPlayer player = ctx.getSource().getPlayer();
                            if (player == null) {
                                return fail(ctx.getSource(), "只能由玩家执行。");
                            }
                            OpAuthService.getInstance().login(player,
                                    StringArgumentType.getString(ctx, "password"));
                            return 1;
                        }));
    }

    public static LiteralArgumentBuilder<CommandSourceStack> adminNode() {
        return Commands.literal("opauth")
                .requires(src -> src.getEntity() == null
                        || com.shenqianye.samsara.permission.PermissionRequirements.isOwner(src))
                .then(Commands.literal("code")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    String code = OpAuthService.getInstance().generateCode(name);
                                    if (code == null) {
                                        return fail(ctx.getSource(), "op-auth 尚未初始化。");
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "注册码（" + codeMinutes() + " 分钟内有效，仅一次）："
                                                    + code), false);
                                    return 1;
                                })))
                .then(Commands.literal("reset")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .executes(ctx -> {
                                    String name = StringArgumentType.getString(ctx, "name");
                                    String code = OpAuthService.getInstance().reset(name);
                                    if (code == null) {
                                        return fail(ctx.getSource(), "op-auth 尚未初始化。");
                                    }
                                    ctx.getSource().sendSuccess(() -> Component.literal(
                                            "已清除 " + name + " 的旧密码；新注册码（"
                                                    + codeMinutes() + " 分钟内有效，仅一次）："
                                                    + code), false);
                                    return 1;
                                })))
                .then(Commands.literal("set")
                        .then(Commands.argument("name", StringArgumentType.word())
                                .then(Commands.argument("password", StringArgumentType.word())
                                        .executes(ctx -> {
                                            String name = StringArgumentType.getString(ctx, "name");
                                            String password = StringArgumentType.getString(ctx, "password");
                                            if (!OpAuthService.getInstance().setPassword(name, password)) {
                                                return fail(ctx.getSource(), "密码至少 8 位。");
                                            }
                                            ctx.getSource().sendSuccess(() -> Component.literal(
                                                    "已设置 " + name + " 的登录密码。"), false);
                                            return 1;
                                        }))))
                .then(Commands.literal("list")
                        .executes(ctx -> {
                            ctx.getSource().sendSuccess(() -> Component.literal(
                                    formatStatus(OpAuthService.getInstance().status())), false);
                            return 1;
                        }));
    }

    private static String formatStatus(java.util.Map<String, Object> status) {
        StringBuilder out = new StringBuilder("OP/owner 登录门禁：")
                .append(Boolean.TRUE.equals(status.get("enabled")) ? "启用" : "关闭")
                .append(" · 超时 ").append(status.get("timeoutSeconds")).append(" 秒")
                .append(" · 待登录 ").append(status.get("pending"));
        Object raw = status.get("entries");
        if (raw instanceof java.util.List<?> list) {
            for (Object value : list) {
                if (!(value instanceof java.util.Map<?, ?> entry)) {
                    continue;
                }
                out.append("\n· ").append(entry.get("name"))
                        .append(entry.get("hasPassword") instanceof Boolean b && b
                                ? " · 已设置密码" : " · 未注册")
                        .append(entry.get("hasCode") instanceof Boolean b && b
                                ? " · 有待用注册码" : "");
            }
        }
        return out.toString();
    }

    private static int codeMinutes() {
        return OpAuthService.getInstance().codeMinutes();
    }

    private static int fail(CommandSourceStack source, String message) {
        source.sendFailure(ChatFormat.formatComponent(message));
        return 0;
    }
}
