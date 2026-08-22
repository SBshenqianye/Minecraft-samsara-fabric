package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.shenqianye.samsara.season.SeasonManager;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.level.Level;

/**
 * 周目业务命令（M1：status/info/create/start/end；M2：respawn-groups、start 完整流程）。
 * <p>
 * <b>命令命名空间（设计文档 §17，2026-08-17 修订）</b>：全部命令以
 * {@code /samsara} 开头，正式入口 {@code /samsara season ...}（挂在 /samsara 根下，
 * 与其它模组不冲突）；不再注册 {@code /season} 别名（曾保留，用户决定去掉避免养成习惯）。
 * <p>
 * 权限：M1 用原版 COMMANDS_ADMIN（原 OP 等级 2），后续可接 fabric-permissions-api。
 * M2 起扩展：respawn-groups（不换周目重分组）；vote / key 留 M8（§15）。
 * <p>
 * 26.1 适配要点（去混淆 + Mojang 官方命名）：
 * - CommandRegistryAccess → CommandBuildContext；ServerCommandSource → CommandSourceStack
 * - CommandManager → Commands；Text → Component
 * - sendFeedback(Supplier&lt;Text&gt;, boolean) → sendSuccess(Supplier&lt;Component&gt;, boolean)
 * - hasPermissionLevel(2) → permissions().hasPermission(Permissions.COMMANDS_ADMIN)（26.1 全新 PermissionSet 权限模型）
 * - StringArgumentType 改用 brigadier 原版（MC 26.1 已移除 net.minecraft.commands.arguments.StringArgumentType 包装类）
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
        StringBuilder sb = new StringBuilder("周目管理 samsara · M2 分组出生（设计见 docs/周目系统设计文档.md）");
        MinecraftServer server = src.getServer();
        ServerLevel world = server.getLevel(Level.OVERWORLD);
        if (world != null) {
            BlockPos spawn = world.getRespawnData().pos();
            sb.append("\n主世界种子: ").append(world.getSeed())
                    .append(" · 出生点: ").append(spawn.getX()).append(", ").append(spawn.getY()).append(", ").append(spawn.getZ());
        }
        return sb.toString();
    }

    private static int reply(CommandSourceStack src, String message) {
        src.sendSuccess(() -> Component.literal("[周目管理] " + message), false);
        return 1;
    }
}
