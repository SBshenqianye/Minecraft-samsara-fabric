package com.shenqianye.samsara.command;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.config.CommandConfig;
import com.shenqianye.samsara.permission.PermissionChecker;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 位置命令：{@code /samsara where [broadcast]}。
 * <pre>
 * /samsara where             —— 查看自己的维度、坐标（及区块、朝向）
 * /samsara where broadcast   —— 把同样的信息广播给全服
 * </pre>
 * 启用开关与输出内容由 {@code config/samsara/commands.json} 控制
 * （见 {@link CommandConfig}），改完可用 {@code /samsara debug reload} 热重载。
 * <p>
 * 广播冷却按玩家独立计时，防止刷屏；冷却秒数同样在配置里。
 */
public final class WhereCommand {
    /** 玩家 UUID → 上次广播时间（毫秒） */
    private static final Map<UUID, Long> LAST_BROADCAST = new ConcurrentHashMap<>();

    private WhereCommand() {
    }

    /** {@code /samsara where ...} 子树：由 DebugCommand 挂到 /samsara 根下 */
    public static LiteralArgumentBuilder<CommandSourceStack> whereNode() {
        return Commands.literal("where")
                .requires(src -> CommandConfig.getInstance().isWhereEnabled()
                        && PermissionChecker.check(src, "where"))
                .executes(ctx -> show(ctx, false))
                .then(Commands.literal("broadcast")
                        .requires(src -> CommandConfig.getInstance().isWhereEnabled()
                                && CommandConfig.getInstance().isWhereBroadcastEnabled()
                                && PermissionChecker.check(src, "where broadcast"))
                        .executes(ctx -> show(ctx, true)));
    }

    private static int show(CommandContext<CommandSourceStack> ctx, boolean broadcast) {
        CommandSourceStack src = ctx.getSource();
        ServerPlayer player = src.getPlayer();
        if (player == null) {
            return reply(src, "该命令需要以玩家身份执行（控制台没有位置）");
        }
        String detail = describe(player, !broadcast);

        if (!broadcast) {
            return reply(src, detail);
        }

        int cooldown = CommandConfig.getInstance().whereBroadcastCooldownSeconds();
        long now = System.currentTimeMillis();
        Long last = LAST_BROADCAST.get(player.getUUID());
        if (cooldown > 0 && last != null) {
            long remainMs = cooldown * 1000L - (now - last);
            if (remainMs > 0) {
                long remainSeconds = (remainMs + 999) / 1000;
                return reply(src, "广播冷却中，请 " + remainSeconds + " 秒后再试");
            }
        }
        LAST_BROADCAST.put(player.getUUID(), now);

        String message = player.getGameProfile().name() + " 的位置\n" + detail;
        Component component = ChatFormat.formatComponent(message);
        for (ServerPlayer target : src.getServer().getPlayerList().getPlayers()) {
            target.sendSystemMessage(component);
        }
        return 1;
    }

    /** 生成位置描述；compact=true 用于广播（单行），false 用于自己查看（多行） */
    private static String describe(ServerPlayer player, boolean compact) {
        CommandConfig config = CommandConfig.getInstance();
        ServerLevel level = player.level();
        BlockPos pos = player.blockPosition();
        StringBuilder sb = new StringBuilder();

        if (config.whereShowDimension()) {
            append(sb, dimensionName(level), compact);
        }
        if (config.whereShowCoordinates()) {
            append(sb, "坐标 " + pos.getX() + ", " + pos.getY() + ", " + pos.getZ(), compact);
        }
        if (config.whereShowChunk()) {
            append(sb, "区块 " + (pos.getX() >> 4) + ", " + (pos.getZ() >> 4), compact);
        }
        if (config.whereShowFacing()) {
            append(sb, "朝向 " + facingName(player.getDirection()), compact);
        }
        if (sb.length() == 0) {
            sb.append("（位置输出已在 commands.json 中全部关闭）");
        }
        return sb.toString();
    }

    /** 追加一段描述；多行模式用换行，单行模式用 " · " 连接 */
    private static void append(StringBuilder sb, String text, boolean compact) {
        if (sb.length() > 0) {
            sb.append(compact ? " · " : "\n");
        }
        sb.append(text);
    }

    /** 维度中文名 + 原版维度 ID，便于跨维度辨认 */
    private static String dimensionName(ServerLevel level) {
        String id = level.dimension().identifier().toString();
        if (level.dimension().equals(Level.OVERWORLD)) {
            return "主世界（" + id + "）";
        }
        if (level.dimension().equals(Level.NETHER)) {
            return "下界（" + id + "）";
        }
        if (level.dimension().equals(Level.END)) {
            return "末地（" + id + "）";
        }
        return id;
    }

    private static String facingName(Direction direction) {
        return switch (direction) {
            case NORTH -> "北";
            case SOUTH -> "南";
            case EAST -> "东";
            case WEST -> "西";
            case UP -> "上";
            case DOWN -> "下";
        };
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
