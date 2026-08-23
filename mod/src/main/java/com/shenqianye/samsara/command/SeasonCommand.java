package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shenqianye.samsara.season.SeasonManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;

/**
 * 周目业务命令：{@code /samsara season ...}
 * <p>
 * 包含 status/info/create/start/end/skip/respawn-groups 子命令。
 * <p>
 * <b>命令命名空间</b>：全部命令以 {@code /samsara} 开头，
 * 正式入口 {@code /samsara season ...}（挂在 /samsara 根下，与其它模组不冲突）。
 * <p>
 * 权限：使用原版 COMMANDS_ADMIN（OP 等级 2），后续可接 fabric-permissions-api。
 */
public class SeasonCommand {
    private static final SeasonManager MANAGER = SeasonManager.getInstance();

    public static void register() {
        // 命令树统一由 DebugCommand 挂在 /samsara 根下（seasonNode()），这里不再单独注册 /season
    }

    /** {@code /samsara season ...} 子树：由 DebugCommand 挂到 /samsara 根下 */
    public static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> seasonNode() {
        return Commands.literal("season")
                .requires(src -> com.shenqianye.samsara.permission.PermissionChecker.check(src, "season"))
                .then(Commands.literal("status")
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.statusText(1)))
                        .then(Commands.argument("page", IntegerArgumentType.integer(1))
                                .executes(ctx -> reply(ctx.getSource(),
                                        MANAGER.statusText(IntegerArgumentType.getInteger(ctx, "page"))))))
                .then(Commands.literal("info")
                        .executes(ctx -> reply(ctx.getSource(), infoText(ctx.getSource()))))
                .then(Commands.literal("create")
                        .requires(src -> isAdmin(src))
                        .then(Commands.argument("name", StringArgumentType.string())
                                .executes(ctx -> reply(ctx.getSource(), MANAGER.create(
                                        StringArgumentType.getString(ctx, "name"), "same-world")))
                                .then(Commands.argument("mode", StringArgumentType.word())
                                        .executes(ctx -> reply(ctx.getSource(), MANAGER.create(
                                                StringArgumentType.getString(ctx, "name"),
                                                StringArgumentType.getString(ctx, "mode")))))))
                .then(Commands.literal("start")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.start(ctx.getSource().getServer()))))
                .then(Commands.literal("end")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.end())))
                .then(Commands.literal("skip")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(), MANAGER.skip())))
                .then(Commands.literal("respawn-groups")
                        .requires(src -> isAdmin(src))
                        .executes(ctx -> reply(ctx.getSource(),
                                MANAGER.respawnGroups(ctx.getSource().getServer()))));
    }

    private static boolean isAdmin(CommandSourceStack src) {
        return src.permissions().hasPermission(Permissions.COMMANDS_ADMIN);
    }

    /** /season info：模组说明 + 当前世界种子与出生点（测试便利：种子可查地图工具熟悉地形） */
    private static String infoText(CommandSourceStack src) {
        StringBuilder sb = new StringBuilder("周目管理 samsara · 分组出生（设计见 docs/周目系统设计文档.md）");
        MinecraftServer server = src.getServer();
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world != null) {
            BlockPos spawn = world.getRespawnData().pos();
            sb.append("\n主世界种子: ").append(world.getSeed())
                    .append(" · 出生点: ").append(spawn.getX()).append(", ").append(spawn.getY()).append(", ").append(spawn.getZ());
        }
        return sb.toString();
    }

    /**
     * 发送格式化回复消息到聊天栏。
     * <p>
     * 使用 {@link ChatFormat} 统一格式：首行带 {@code [周目管理] ▸ } 前缀，
     * 后续行缩进对齐，自动换行（默认 34 宽度单位）。
     *
     * @param src     命令源
     * @param message 消息文本（支持 {@code \n} 换行）
     * @return 固定返回 1（brigadier 命令执行成功）
     */
    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> ChatFormat.formatComponent(message), false);
        return 1;
    }
}
