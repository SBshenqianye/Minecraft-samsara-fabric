package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.permission.PermissionRequirements;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

/** `/samsara debug perm` 子命令树与执行。 */
final class DebugPermissionCommands {

    private DebugPermissionCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("perm")
                .requires(PermissionRequirements.ownerAnd("debug perm"))
                .then(Commands.literal("list")
                        .requires(PermissionRequirements.ownerAnd("debug perm list"))
                        .executes(ctx -> reply(ctx.getSource(), list())))
                // 语法说明：greedyString 必须位于末尾。旧写法 greedyString("command") + word("level")
                // 的 level 分支永远解析不到，这里改为单个 greedyString 自己拆「路径 + 等级」。
                .then(Commands.literal("set")
                        .requires(PermissionRequirements.ownerAnd("debug perm set"))
                        .then(Commands.argument("input", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.permInputProvider())
                                .executes(ctx -> replyOrErr(ctx,
                                        set(ctx.getSource(),
                                                StringArgumentType.getString(ctx, "input"))))))
                .then(Commands.literal("reset")
                        .requires(PermissionRequirements.ownerAnd("debug perm reset"))
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.commandPathProvider())
                                .executes(ctx -> reply(ctx.getSource(),
                                        reset(StringArgumentType.getString(ctx, "command"))))))
                .then(Commands.literal("info")
                        .requires(PermissionRequirements.ownerAnd("debug perm info"))
                        .then(Commands.argument("command", StringArgumentType.greedyString())
                                .suggests(SuggestionProviders.commandPathProvider())
                                .executes(ctx -> reply(ctx.getSource(),
                                        info(StringArgumentType.getString(ctx, "command"))))))
                .then(Commands.literal("owner")
                        .requires(PermissionRequirements.ownerAnd("debug perm owner"))
                        .then(Commands.argument("sub", StringArgumentType.word())
                                .suggests(SuggestionProviders.choicesWithHint("add=加入 owner 名单；remove=移出",
                                        "add", "remove"))
                                .then(Commands.argument("name", StringArgumentType.word())
                                        .suggests(SuggestionProviders.knownPlayersProvider("账号"))
                                        .executes(ctx -> reply(ctx.getSource(),
                                                owner(StringArgumentType.getString(ctx, "sub"),
                                                        StringArgumentType.getString(ctx, "name")))))));
    }

    static String list() {
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        var entries = config.getAllEntries();
        StringBuilder sb = new StringBuilder("命令权限配置（" + entries.size() + " 条）：\n");
        for (var e : entries.entrySet()) {
            sb.append("  /samsara ").append(e.getKey())
              .append(" → ").append(e.getValue()).append("\n");
        }
        sb.append("\n数据文件: ").append(config.configPath());
        return sb.toString().trim();
    }

    /**
     * perm set：单个 greedyString 拆成「命令路径 + 等级」。
     * 路径本身可能含空格（如 {@code season create}），因此以最后一个空格为界拆等级。
     *
     * @return 成功/错误信息；参数不足时返回 null 让 replyOrErr 回错误
     */
    static String set(CommandSourceStack src, String input) {
        String trimmed = input == null ? "" : input.trim();
        int split = trimmed.lastIndexOf(' ');
        if (split <= 0 || split >= trimmed.length() - 1) {
            return null;
        }
        String command = trimmed.substring(0, split).trim();
        String levelStr = trimmed.substring(split + 1).trim();
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        var playerLevel = com.shenqianye.samsara.permission.PermissionChecker.getPlayerLevel(src);
        var newLevel = com.shenqianye.samsara.permission.PermissionLevel.fromString(levelStr);
        return config.setEntry(command, newLevel, playerLevel);
    }

    static String reset(String command) {
        return com.shenqianye.samsara.permission.PermissionConfig.getInstance().resetEntry(command);
    }

    static String info(String command) {
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        var level = config.getRequired(command);
        return "/samsara " + command + " → " + level + "（当前所需等级）";
    }

    static String owner(String sub, String name) {
        var config = com.shenqianye.samsara.permission.PermissionConfig.getInstance();
        return switch (sub.toLowerCase()) {
            case "add" -> config.addOwner(name);
            case "remove", "rm" -> config.removeOwner(name);
            default -> "用法: /samsara debug perm owner <add|remove> <玩家名>";
        };
    }

    private static int reply(CommandSourceStack src, String message) {
        DebugCommand.reply(src, message);
        return 1;
    }

    private static int replyOrErr(CommandContext<CommandSourceStack> ctx, String message) {
        return DebugCommand.replyOrErr(ctx, message);
    }
}
