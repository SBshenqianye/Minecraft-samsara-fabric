package com.shenqianye.samsara.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.permission.PermissionChecker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.server.level.ServerPlayer;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * TPA 传送请求（《需求文档》F-53~F-59 / 《设计文档》〈TPA 传送〉）。
 * <pre>
 * /samsara tpa &lt;玩家&gt;      请求传送到对方
 * /samsara tpahere &lt;玩家&gt;  请求对方传送到你这里
 * /samsara tpaccept        同意待处理请求
 * /samsara tpdeny          拒绝待处理请求
 * /samsara tpcancel        取消自己发出的请求
 * </pre>
 * 规则：
 * <ul>
 *   <li>请求默认 60 秒过期（{@code commands.json} 的 {@code tpa.requestTimeoutSeconds}）；</li>
 *   <li>每个玩家同时只有 1 个待处理请求，新请求覆盖旧请求（F-59）；</li>
 *   <li>默认只允许同一维度内传送（{@code tpa.crossDimension=true} 可放开）；</li>
 *   <li>请求只存在内存里，服务器重启即清空（有效期本来就短，无需持久化）。</li>
 * </ul>
 * 短别名 {@code /tpa}、{@code /tpahere}、{@code /tpaccept}、{@code /tpyes}、
 * {@code /tpdeny}、{@code /tpno}、{@code /tpcancel} 由 {@code DebugCommand} 按〈命令谦让〉
 * 条件注册：根节点已被其他模组占用时自动让位。
 */
public final class TpaCommand {
    /** 目标玩家 UUID → 收到的请求（每个目标最多 1 个） */
    private static final Map<UUID, Request> INCOMING = new ConcurrentHashMap<>();
    /** 发起者 UUID → 目标 UUID（每个发起者最多 1 个） */
    private static final Map<UUID, UUID> OUTGOING = new ConcurrentHashMap<>();

    private record Request(UUID sender, UUID target, boolean here, long createdAt) {
    }

    private TpaCommand() {
    }

    // ---- 命令节点 ----

    /** {@code /samsara tpa <player>} */
    public static LiteralArgumentBuilder<CommandSourceStack> tpaNode() {
        return requestNode("tpa", false);
    }

    /** {@code /samsara tpahere <player>} */
    public static LiteralArgumentBuilder<CommandSourceStack> tpaHereNode() {
        return requestNode("tpahere", true);
    }

    /** {@code /samsara tpaccept}（也用于短别名 {@code /tpyes}） */
    public static LiteralArgumentBuilder<CommandSourceStack> acceptNode() {
        return acceptNode("tpaccept");
    }

    public static LiteralArgumentBuilder<CommandSourceStack> acceptNode(String literal) {
        return actionNode(literal, TpaCommand::accept);
    }

    /** {@code /samsara tpdeny}（也用于短别名 {@code /tpno}） */
    public static LiteralArgumentBuilder<CommandSourceStack> denyNode() {
        return denyNode("tpdeny");
    }

    public static LiteralArgumentBuilder<CommandSourceStack> denyNode(String literal) {
        return actionNode(literal, TpaCommand::deny);
    }

    /** {@code /samsara tpcancel} */
    public static LiteralArgumentBuilder<CommandSourceStack> cancelNode() {
        return actionNode("tpcancel", TpaCommand::cancel);
    }

    private static LiteralArgumentBuilder<CommandSourceStack> requestNode(String literal, boolean here) {
        return Commands.literal(literal)
                .requires(src -> CommandConfig.getInstance().isTpaEnabled()
                        && PermissionChecker.check(src, literal))
                .then(Commands.argument("player", StringArgumentType.word())
                        .executes(ctx -> request(ctx, here)));
    }

    private static LiteralArgumentBuilder<CommandSourceStack> actionNode(String literal,
                                                                        Command<CommandSourceStack> action) {
        return Commands.literal(literal)
                .requires(src -> CommandConfig.getInstance().isTpaEnabled()
                        && PermissionChecker.check(src, literal))
                .executes(action);
    }

    // ---- 请求生命周期 ----

    private static int request(CommandContext<CommandSourceStack> ctx, boolean here) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer sender = src.getPlayer();
        if (sender == null) {
            return reply(src, "该命令需要以玩家身份执行");
        }
        String targetName = StringArgumentType.getString(ctx, "player");
        ServerPlayer target = src.getServer().getPlayerList().getPlayerByName(targetName);
        if (target == null) {
            return reply(src, "玩家 " + targetName + " 不在线");
        }
        if (target.getUUID().equals(sender.getUUID())) {
            return reply(src, "不能向自己发送传送请求");
        }
        if (!CommandConfig.getInstance().tpaCrossDimension()
                && !sender.level().dimension().equals(target.level().dimension())) {
            return reply(src, "双方不在同一维度，无法传送（可在 commands.json 的 tpa.crossDimension 放开）");
        }

        // F-59：新请求覆盖旧请求（发起者的旧请求 + 目标收到的旧请求都清掉）
        clearOutgoing(sender.getUUID());
        clearIncoming(target.getUUID());

        long now = System.currentTimeMillis();
        Request request = new Request(sender.getUUID(), target.getUUID(), here, now);
        INCOMING.put(target.getUUID(), request);
        OUTGOING.put(sender.getUUID(), target.getUUID());

        String timeout = timeoutText();
        if (here) {
            reply(src, "已请求 " + target.getName().getString()
                    + " 传送到你这里（" + timeout + "内有效）");
            target.sendSystemMessage(ChatFormat.formatComponent(
                    sender.getName().getString() + " 请求你传送到他那里。\n"
                            + timeout + "内输入 /tpaccept 同意，/tpdeny 拒绝"));
        } else {
            reply(src, "已向 " + target.getName().getString()
                    + " 发送传送请求（" + timeout + "内有效）");
            target.sendSystemMessage(ChatFormat.formatComponent(
                    sender.getName().getString() + " 请求传送到你这里。\n"
                            + timeout + "内输入 /tpaccept 同意，/tpdeny 拒绝"));
        }
        return 1;
    }

    private static int accept(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer accepter = src.getPlayer();
        if (accepter == null) {
            return reply(src, "该命令需要以玩家身份执行");
        }
        Request request = takeValid(accepter.getUUID());
        if (request == null) {
            return reply(src, "没有待处理的传送请求（可能已过期）");
        }
        ServerPlayer sender = src.getServer().getPlayerList().getPlayer(request.sender());
        if (sender == null) {
            return reply(src, "对方已离线，传送请求作废");
        }
        if (!CommandConfig.getInstance().tpaCrossDimension()
                && !sender.level().dimension().equals(accepter.level().dimension())) {
            return reply(src, "双方不在同一维度，传送请求作废");
        }

        // tpa：发起者 → 接受者；tpahere：接受者 → 发起者
        ServerPlayer mover = request.here() ? accepter : sender;
        ServerPlayer destination = request.here() ? sender : accepter;
        mover.teleportTo(destination.level(),
                destination.getX(), destination.getY(), destination.getZ(),
                Set.of(), destination.getYRot(), destination.getXRot(), false);

        reply(src, "已同意 " + sender.getName().getString() + " 的传送请求");
        sender.sendSystemMessage(ChatFormat.formatComponent(
                accepter.getName().getString() + " 同意了你的传送请求"));
        return 1;
    }

    private static int deny(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer denier = src.getPlayer();
        if (denier == null) {
            return reply(src, "该命令需要以玩家身份执行");
        }
        Request request = takeValid(denier.getUUID());
        if (request == null) {
            return reply(src, "没有待处理的传送请求（可能已过期）");
        }
        ServerPlayer sender = src.getServer().getPlayerList().getPlayer(request.sender());
        reply(src, "已拒绝 " + (sender != null ? sender.getName().getString() : "对方") + " 的传送请求");
        if (sender != null) {
            sender.sendSystemMessage(ChatFormat.formatComponent(
                    denier.getName().getString() + " 拒绝了你的传送请求"));
        }
        return 1;
    }

    private static int cancel(CommandContext<CommandSourceStack> ctx) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer sender = src.getPlayer();
        if (sender == null) {
            return reply(src, "该命令需要以玩家身份执行");
        }
        UUID targetId = OUTGOING.remove(sender.getUUID());
        if (targetId == null) {
            return reply(src, "你没有待处理的传送请求");
        }
        Request request = INCOMING.get(targetId);
        if (request != null && request.sender().equals(sender.getUUID())) {
            INCOMING.remove(targetId, request);
        }
        ServerPlayer target = src.getServer().getPlayerList().getPlayer(targetId);
        if (target != null) {
            target.sendSystemMessage(ChatFormat.formatComponent(
                    sender.getName().getString() + " 取消了传送请求"));
        }
        return reply(src, "已取消传送请求");
    }

    /** 取出并清理目标玩家的待处理请求；过期请求按无请求处理 */
    private static Request takeValid(UUID target) {
        Request request = INCOMING.get(target);
        if (request == null) {
            return null;
        }
        INCOMING.remove(target, request);
        OUTGOING.remove(request.sender(), request.target());
        long timeoutMs = CommandConfig.getInstance().tpaRequestTimeoutSeconds() * 1000L;
        if (System.currentTimeMillis() - request.createdAt() > timeoutMs) {
            return null;
        }
        return request;
    }

    private static void clearIncoming(UUID target) {
        Request old = INCOMING.remove(target);
        if (old != null) {
            OUTGOING.remove(old.sender(), old.target());
        }
    }

    private static void clearOutgoing(UUID sender) {
        UUID target = OUTGOING.remove(sender);
        if (target == null) {
            return;
        }
        Request old = INCOMING.get(target);
        if (old != null && old.sender().equals(sender)) {
            INCOMING.remove(target, old);
        }
    }

    private static String timeoutText() {
        return CommandConfig.getInstance().tpaRequestTimeoutSeconds() + " 秒";
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
