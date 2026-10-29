package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.track.view.ViewManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

/**
 * 回看命令（设计 5.7.6 / F-79、F-80、F-90、F-91）。
 * <pre>
 * /samsara track view &lt;玩家&gt; &lt;开始时间&gt; [选项...]
 * /samsara track view stop [玩家|all]
 * /samsara track view status [玩家]
 * /samsara track view pause|resume [玩家]
 * /samsara track view speed &lt;0.1..64&gt;
 * /samsara track view reverse
 * /samsara track list &lt;玩家&gt; [页]
 * </pre>
 * 短别名 {@code /track ...} 仅在根节点未被其他模组占用时注册（命令谦让）。
 * <p>
 * 开始时间与选项合并为 greedyString 后自解析：Brigadier 的 word()/string() 未加引号
 * 分支都不接受 {@code :}（{@code StringReader.isAllowedInUnquotedString}），
 * 因此 {@code 2026-10-28T21:20} 会被截断（2-33 的坑）。greedyString 能原样收下四种格式，
 * 再按第一个空白拆分「开始时间 + 选项」，未知键立即报错。
 */
public final class TrackViewCommand {
    private TrackViewCommand() {
    }

    /** {@code /samsara track ...}（也是 {@code /track ...} 别名树） */
    public static LiteralArgumentBuilder<CommandSourceStack> trackNode() {
        return Commands.literal("track")
                .then(viewNode())
                .then(listNode());
    }

    /** 供 {@code /samsara debug track view} 复用（调用方已要求 OWNER） */
    public static LiteralArgumentBuilder<CommandSourceStack> debugViewNode() {
        return viewNode();
    }

    public static LiteralArgumentBuilder<CommandSourceStack> listNode() {
        return Commands.literal("list")
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(SuggestionProviders.knownPlayersProvider())
                        .executes(ctx -> list(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> list(ctx,
                                        IntegerArgumentType.getInteger(ctx, "page")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> viewNode() {
        return Commands.literal("view")
                .then(Commands.literal("stop")
                        .executes(ctx -> stop(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider())
                                .executes(ctx -> stop(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("status")
                        .executes(ctx -> status(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider())
                                .executes(ctx -> status(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("pause")
                        .executes(ctx -> pause(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider())
                                .executes(ctx -> pause(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("resume")
                        .executes(ctx -> resume(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider())
                                .executes(ctx -> resume(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("speed")
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.1, 64))
                                .suggests(SuggestionProviders.speedProvider())
                                .executes(ctx -> speed(ctx,
                                        DoubleArgumentType.getDouble(ctx, "value")))))
                .then(Commands.literal("reverse")
                        .executes(ctx -> reverse(ctx)))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(SuggestionProviders.knownPlayersProvider())
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.viewGreedyProvider())
                                .executes(TrackViewCommand::startGreedy)));
    }

    private static int startGreedy(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "input").trim();
        if (raw.isEmpty()) {
            return reply(ctx.getSource(), "缺少开始时间");
        }
        int split = 0;
        while (split < raw.length() && !Character.isWhitespace(raw.charAt(split))) {
            split++;
        }
        String start = raw.substring(0, split);
        String options = split >= raw.length() ? "" : raw.substring(split).trim();
        return start(ctx, start, options);
    }

    private static int start(CommandContext<CommandSourceStack> ctx, String start, String options) {
        ServerPlayer viewer;
        try {
            viewer = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return reply(ctx.getSource(), "回看需要以玩家身份执行");
        }
        String target = StringArgumentType.getString(ctx, "player");
        boolean allowOthers = isAdmin(ctx.getSource());
        String msg = ViewManager.getInstance().start(ctx.getSource().getServer(), viewer,
                target, start, options, allowOthers);
        return reply(ctx.getSource(), msg);
    }

    private static int stop(CommandContext<CommandSourceStack> ctx, String target) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().stop(ctx.getSource().getServer(), viewer,
                target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int status(CommandContext<CommandSourceStack> ctx, String target) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().status(ctx.getSource().getServer(), viewer, target);
        return reply(ctx.getSource(), msg);
    }

    private static int pause(CommandContext<CommandSourceStack> ctx, String target) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().pause(ctx.getSource().getServer(), viewer,
                target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int resume(CommandContext<CommandSourceStack> ctx, String target) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().resume(ctx.getSource().getServer(), viewer,
                target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int list(CommandContext<CommandSourceStack> ctx, int page) {
        String target = StringArgumentType.getString(ctx, "player");
        Component msg = ViewManager.getInstance().list(ctx.getSource().getServer(), target, page);
        ctx.getSource().sendSuccess(() -> msg, false);
        return 1;
    }

    private static int speed(CommandContext<CommandSourceStack> ctx, double value) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().setSpeed(ctx.getSource().getServer(), viewer,
                null, value, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int reverse(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().toggleReverse(ctx.getSource().getServer(), viewer,
                null, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    /** 看他人 = 管理员（与既有命令的 COMMANDS_ADMIN 口径一致） */
    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN)
                || src.permissions().hasPermission(Permissions.COMMANDS_OWNER);
    }

    /** 条件注册短别名 {@code /track}：根节点已存在时让位，不抢占。 */
    public static void registerAlias(CommandDispatcher<CommandSourceStack> dispatcher) {
        CommandAliases.register(dispatcher, "track", TrackViewCommand::trackNode);
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
