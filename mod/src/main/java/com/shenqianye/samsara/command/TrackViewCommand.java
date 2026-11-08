package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.output.OutputRenderer;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.track.view.CameraService;
import com.shenqianye.samsara.track.view.ViewManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;

import java.util.ArrayList;
import java.util.List;

/**
 * 回看命令（设计 5.7.6 / F-79、F-80、F-90、F-91）。
 * <pre>
 * /samsara track view &lt;玩家&gt; &lt;开始时间&gt; [选项...]
 * /samsara track view stop [玩家|all]
 * /samsara track view status [玩家]
 * /samsara track view pause|resume [玩家]
 * /samsara track view speed &lt;0.1..64&gt;
 * /samsara track view reverse
 * /samsara track view ui [接收者|broadcast]
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
                .requires(PermissionRequirements.path("track"))
                .then(viewNode("track"))
                .then(listNode("track"))
                .then(TrackClipCommand.clipNode());
    }

    /** {@code /samsara camera ...}：同时驱动轨迹回看与 Diff 回看。 */
    public static LiteralArgumentBuilder<CommandSourceStack> cameraNode() {
        String path = "camera";
        return Commands.literal("camera")
                .requires(PermissionRequirements.path(path))
                .then(Commands.literal("start")
                        .requires(PermissionRequirements.path(path + " start"))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.viewGreedyProvider())
                                .executes(TrackViewCommand::cameraStart)))
                .then(Commands.literal("stop")
                        .requires(PermissionRequirements.path(path + " stop"))
                        .executes(ctx -> cameraStop(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> cameraStop(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("status")
                        .requires(PermissionRequirements.path(path + " status"))
                        .executes(ctx -> cameraStatus(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> cameraStatus(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("pause")
                        .requires(PermissionRequirements.path(path + " pause"))
                        .executes(ctx -> cameraPause(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> cameraPause(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("resume")
                        .requires(PermissionRequirements.path(path + " resume"))
                        .executes(ctx -> cameraResume(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> cameraResume(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("seek")
                        .requires(PermissionRequirements.path(path + " seek"))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.seekProvider())
                                .executes(TrackViewCommand::cameraSeek)))
                .then(Commands.literal("speed")
                        .requires(PermissionRequirements.path(path + " speed"))
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.1, 64))
                                .suggests(SuggestionProviders.speedProvider())
                                .executes(ctx -> cameraSpeed(ctx,
                                        DoubleArgumentType.getDouble(ctx, "value")))));
    }

    private static int cameraStart(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer viewer;
        try {
            viewer = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return reply(ctx.getSource(), "镜头回看需要以玩家身份执行");
        }
        CameraService.StartRequest request;
        try {
            request = CameraService.parse(StringArgumentType.getString(ctx, "input"));
        } catch (IllegalArgumentException e) {
            return reply(ctx.getSource(), "参数错误：" + e.getMessage());
        }
        String msg = CameraService.getInstance().start(ctx.getSource().getServer(), viewer,
                request, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int cameraStop(CommandContext<CommandSourceStack> ctx, String target) {
        String msg = CameraService.getInstance().stop(ctx.getSource().getServer(),
                ctx.getSource().getPlayer(), target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int cameraStatus(CommandContext<CommandSourceStack> ctx, String target) {
        String msg = CameraService.getInstance().status(ctx.getSource().getServer(),
                ctx.getSource().getPlayer(), target);
        return reply(ctx.getSource(), msg);
    }

    private static int cameraPause(CommandContext<CommandSourceStack> ctx, String target) {
        String msg = CameraService.getInstance().pause(ctx.getSource().getServer(),
                ctx.getSource().getPlayer(), target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int cameraResume(CommandContext<CommandSourceStack> ctx, String target) {
        String msg = CameraService.getInstance().resume(ctx.getSource().getServer(),
                ctx.getSource().getPlayer(), target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int cameraSeek(CommandContext<CommandSourceStack> ctx) {
        String spec = StringArgumentType.getString(ctx, "input").trim();
        if (spec.isEmpty()) {
            return reply(ctx.getSource(), "缺少跳转目标（示例：+30s、-10m、50%、2026-10-28T21:20）");
        }
        String msg = CameraService.getInstance().seek(ctx.getSource().getServer(),
                ctx.getSource().getPlayer(), null, spec, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int cameraSpeed(CommandContext<CommandSourceStack> ctx, double value) {
        String msg = CameraService.getInstance().speed(ctx.getSource().getServer(),
                ctx.getSource().getPlayer(), null, value, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    /** 供 {@code /samsara debug track view} 复用（调用方已要求 OWNER）。 */
    public static LiteralArgumentBuilder<CommandSourceStack> debugViewNode() {
        return viewNode("debug track");
    }

    /** 供 {@code /samsara debug track list} 复用。 */
    public static LiteralArgumentBuilder<CommandSourceStack> debugListNode() {
        return listNode("debug track");
    }

    public static LiteralArgumentBuilder<CommandSourceStack> listNode() {
        return listNode("track");
    }

    private static LiteralArgumentBuilder<CommandSourceStack> listNode(String prefix) {
        String path = prefix + " list";
        return Commands.literal("list")
                .requires(PermissionRequirements.path(path))
                .then(Commands.argument("player", StringArgumentType.word())
                        .suggests(SuggestionProviders.knownPlayersProvider("监控目标"))
                        .executes(ctx -> list(ctx, 1))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> list(ctx,
                                        IntegerArgumentType.getInteger(ctx, "page")))));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> viewNode(String prefix) {
        String path = prefix + " view";
        return Commands.literal("view")
                .requires(PermissionRequirements.path(path))
                .then(Commands.literal("stop")
                        .requires(PermissionRequirements.path(path + " stop"))
                        .executes(ctx -> stop(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> stop(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("status")
                        .requires(PermissionRequirements.path(path + " status"))
                        .executes(ctx -> status(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> status(ctx, StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("pause")
                        .requires(PermissionRequirements.path(path + " pause"))
                        .executes(ctx -> pause(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> pause(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("resume")
                        .requires(PermissionRequirements.path(path + " resume"))
                        .executes(ctx -> resume(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> resume(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("speed")
                        .requires(PermissionRequirements.path(path + " speed"))
                        .then(Commands.argument("value", DoubleArgumentType.doubleArg(0.1, 64))
                                .suggests(SuggestionProviders.speedProvider())
                                .executes(ctx -> speed(ctx,
                                        DoubleArgumentType.getDouble(ctx, "value")))))
                .then(Commands.literal("reverse")
                        .requires(PermissionRequirements.path(path + " reverse"))
                        .executes(ctx -> reverse(ctx)))
                .then(Commands.literal("align")
                        .requires(PermissionRequirements.path(path + " align"))
                        .executes(ctx -> align(ctx, null))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> align(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("ui")
                        .requires(PermissionRequirements.path(path + " ui"))
                        .executes(ctx -> ui(ctx, null))
                        .then(Commands.literal("broadcast")
                                .requires(PermissionRequirements.path(path + " ui broadcast"))
                                .executes(TrackViewCommand::uiBroadcast))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("接收者（观看者）"))
                                .executes(ctx -> ui(ctx,
                                        StringArgumentType.getString(ctx, "player")))))
                .then(Commands.literal("seek")
                        .requires(PermissionRequirements.path(path + " seek"))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.seekProvider())
                                .executes(TrackViewCommand::seek)))
                .then(Commands.literal("scrub")
                        .requires(PermissionRequirements.path(path + " scrub"))
                        .executes(ctx -> scrub(ctx, ""))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.scrubProvider())
                                .executes(ctx -> scrub(ctx,
                                        StringArgumentType.getString(ctx, "input")))))
                .then(Commands.literal("aim")
                        .requires(PermissionRequirements.path(path + " aim"))
                        .executes(ctx -> aim(ctx, ""))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.aimProvider())
                                .executes(ctx -> aim(ctx,
                                        StringArgumentType.getString(ctx, "input")))))
                .then(Commands.literal("drop")
                        .requires(PermissionRequirements.path(path + " drop"))
                        .executes(ctx -> drop(ctx, null))
                        .then(Commands.literal("on")
                                .executes(ctx -> drop(ctx, true)))
                        .then(Commands.literal("off")
                                .executes(ctx -> drop(ctx, false))))
                .then(Commands.literal("inspect")
                        .requires(PermissionRequirements.path(path + " inspect"))
                        .executes(ctx -> inspect(ctx, null, 1))
                        .then(Commands.argument("player", StringArgumentType.word())
                                .suggests(SuggestionProviders.playersProvider("观看者"))
                                .executes(ctx -> inspect(ctx,
                                        StringArgumentType.getString(ctx, "player"), 1))
                                .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                        .executes(ctx -> inspect(ctx,
                                                StringArgumentType.getString(ctx, "player"),
                                                IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("clip")
                        .requires(PermissionRequirements.path(path + " clip"))
                        .then(Commands.argument("id", StringArgumentType.word())
                                .suggests(SuggestionProviders.clipIdProvider())
                                .executes(TrackViewCommand::startClip)))
                .then(Commands.argument("input", StringArgumentType.greedyString())
                        .suggests(SuggestionProviders.viewGreedyProvider())
                        .executes(TrackViewCommand::startGreedyAll));
    }

    /** 新语法：view [目标列表|all] <开始时间> [选项...]，第一个参数整体自解析。 */
    private static int startGreedyAll(CommandContext<CommandSourceStack> ctx) {
        String raw = StringArgumentType.getString(ctx, "input").trim();
        if (raw.isEmpty()) {
            return reply(ctx.getSource(), "缺少开始时间或目标");
        }
        String[] tokens = raw.split("\\s+");
        List<String> targets = new ArrayList<>();
        boolean all = false;
        int index;
        String start;
        String first = tokens[0];
        if ("all".equalsIgnoreCase(first)) {
            all = true;
            if (tokens.length < 2) {
                return reply(ctx.getSource(), "all 后需要开始时间");
            }
            start = tokens[1];
            index = 2;
        } else if (isTime(first)) {
            all = true;
            start = first;
            index = 1;
        } else {
            for (String part : first.split(",")) {
                if (!part.isBlank()) {
                    targets.add(part.trim());
                }
            }
            if (targets.isEmpty()) {
                return reply(ctx.getSource(), "缺少回看目标");
            }
            if (tokens.length < 2) {
                return reply(ctx.getSource(), "目标后需要开始时间");
            }
            start = tokens[1];
            index = 2;
        }
        StringBuilder options = new StringBuilder();
        for (int i = index; i < tokens.length; i++) {
            if (!options.isEmpty()) {
                options.append(' ');
            }
            options.append(tokens[i]);
        }
        return start(ctx, targets, all, start, options.toString());
    }

    private static boolean isTime(String token) {
        try {
            com.shenqianye.samsara.track.view.ViewTime.parseAbsolute(token);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private static int startClip(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer viewer;
        try {
            viewer = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return reply(ctx.getSource(), "回看需要以玩家身份执行");
        }
        String msg = ViewManager.getInstance().startFromClip(ctx.getSource().getServer(), viewer,
                StringArgumentType.getString(ctx, "id"), isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int start(CommandContext<CommandSourceStack> ctx, List<String> targets,
                             boolean all, String start, String options) {
        ServerPlayer viewer;
        try {
            viewer = ctx.getSource().getPlayerOrException();
        } catch (Exception e) {
            return reply(ctx.getSource(), "回看需要以玩家身份执行");
        }
        boolean allowOthers = isAdmin(ctx.getSource());
        String msg = ViewManager.getInstance().start(ctx.getSource().getServer(), viewer,
                targets, all, start, options, allowOthers);
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

    private static int align(CommandContext<CommandSourceStack> ctx, String target) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().align(ctx.getSource().getServer(), viewer,
                target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    /**
     * 重新发送聊天栏控制条（按钮被聊天记录刷掉后找回）。
     *
     * @param target 接收者（观看者）的名字；null = 发给自己（控制台默认广播）
     */
    private static int ui(CommandContext<CommandSourceStack> ctx, String target) {
        ServerPlayer actor = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().showControls(ctx.getSource().getServer(), actor,
                target, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    /** 把控制条广播给所有观看者（管理员/控制台）。 */
    private static int uiBroadcast(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer actor = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().broadcastControls(ctx.getSource().getServer(), actor,
                isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int seek(CommandContext<CommandSourceStack> ctx) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String raw = StringArgumentType.getString(ctx, "input").trim();
        if (raw.isEmpty()) {
            return reply(ctx.getSource(), "缺少跳转目标（示例：+30s、-10m、50%、2026-10-28T21:20）");
        }
        String target = null;
        String spec = raw;
        int split = 0;
        while (split < raw.length() && !Character.isWhitespace(raw.charAt(split))) {
            split++;
        }
        if (split < raw.length()) {
            String first = raw.substring(0, split);
            String rest = raw.substring(split).trim();
            if (!rest.isEmpty() && ViewManager.getInstance()
                    .hasViewerSession(ctx.getSource().getServer(), first)) {
                target = first;
                spec = rest;
            }
        }
        String msg = ViewManager.getInstance().seek(ctx.getSource().getServer(), viewer,
                target, spec, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int aim(CommandContext<CommandSourceStack> ctx, String raw) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        Boolean on = null;
        String target = null;
        for (String token : raw.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.equalsIgnoreCase("on") || token.equalsIgnoreCase("true")) {
                on = true;
            } else if (token.equalsIgnoreCase("off") || token.equalsIgnoreCase("false")) {
                on = false;
            } else {
                target = token;
            }
        }
        String msg = ViewManager.getInstance().setAim(ctx.getSource().getServer(), viewer,
                target, on, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int drop(CommandContext<CommandSourceStack> ctx, Boolean on) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().setEquipmentDrop(ctx.getSource().getServer(), viewer,
                null, on, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int inspect(CommandContext<CommandSourceStack> ctx, String target, int page) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        String msg = ViewManager.getInstance().inspect(ctx.getSource().getServer(), viewer,
                target, page, isAdmin(ctx.getSource()));
        return reply(ctx.getSource(), msg);
    }

    private static int scrub(CommandContext<CommandSourceStack> ctx, String raw) {
        ServerPlayer viewer = ctx.getSource().getPlayer();
        Boolean on = null;
        String target = null;
        for (String token : raw.trim().split("\\s+")) {
            if (token.isBlank()) {
                continue;
            }
            if (token.equalsIgnoreCase("on") || token.equalsIgnoreCase("true")) {
                on = true;
            } else if (token.equalsIgnoreCase("off") || token.equalsIgnoreCase("false")) {
                on = false;
            } else {
                target = token;
            }
        }
        String msg = ViewManager.getInstance().setScrub(ctx.getSource().getServer(), viewer,
                target, on, isAdmin(ctx.getSource()));
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
        src.sendSuccess(() -> OutputRenderer.renderLegacy("track.reply", message, src), false);
        return 1;
    }
}
