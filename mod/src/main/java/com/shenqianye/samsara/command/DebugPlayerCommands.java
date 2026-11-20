package com.shenqianye.samsara.command;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.shenqianye.samsara.command.ui.SuggestionProviders;
import com.shenqianye.samsara.permission.PermissionRequirements;
import com.shenqianye.samsara.season.PlayerRegistry;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** `/samsara debug fakeplayers|groups|settings` 辅助命令组。 */
final class DebugPlayerCommands {

    private DebugPlayerCommands() {
    }

    static LiteralArgumentBuilder<CommandSourceStack> fakePlayersNode() {
        return Commands.literal("fakeplayers")
                .requires(PermissionRequirements.ownerAnd("debug fakeplayers"))
                .then(Commands.argument("names", StringArgumentType.greedyString())
                        .suggests(SuggestionProviders.fakePlayersProvider())
                        .executes(DebugPlayerCommands::fakePlayers));
    }

    static LiteralArgumentBuilder<CommandSourceStack> groupsNode() {
        return Commands.literal("groups")
                .requires(PermissionRequirements.ownerAnd("debug groups"))
                .executes(ctx -> DebugCommand.reply(ctx.getSource(), previewGroups(ctx, null)))
                .then(Commands.argument("names", StringArgumentType.greedyString())
                        .suggests(SuggestionProviders.playerListProvider())
                        .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                previewGroups(ctx, StringArgumentType.getString(ctx, "names")))));
    }

    static LiteralArgumentBuilder<CommandSourceStack> settingsNode() {
        return Commands.literal("settings")
                .requires(PermissionRequirements.ownerAnd("debug settings"))
                .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                        DebugCommand.manager().settingsText()))
                .then(Commands.literal("groupdist")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 10000))
                                .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                        DebugCommand.manager().setGroupMinDistance(
                                                IntegerArgumentType.getInteger(ctx, "blocks"))))))
                .then(Commands.literal("birthjitter")
                        .then(Commands.argument("blocks", IntegerArgumentType.integer(0, 1000))
                                .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                        DebugCommand.manager().setBirthpointJitter(
                                                IntegerArgumentType.getInteger(ctx, "blocks"))))))
                .then(Commands.literal("birthreset")
                        .then(Commands.argument("on", StringArgumentType.word())
                                .suggests(SuggestionProviders.boolProvider())
                                .executes(ctx -> DebugCommand.reply(ctx.getSource(),
                                        DebugCommand.manager().setResetIndividualBirthpoint(
                                                Boolean.parseBoolean(
                                                        StringArgumentType.getString(ctx, "on")))))));
    }

    /**
     * fakeplayers 三种形态 + clear：
     *  - `clear`             → 清空注入池
     *  - 纯数字 `3`          → TestPlayer1..3
     *  - 前缀+数量 `guard 3` → guard1,guard2,guard3
     *  - 名字序列 `Alice,Bob` → 按名字逐个生成
     * 注入后即进入注册玩家池（视为刚上线），供 /samsara debug groups 与 /season start 分组使用。
     */
    static int fakePlayers(CommandContext<CommandSourceStack> ctx) {
        String arg = StringArgumentType.getString(ctx, "names").trim();
        // 聊天栏补全点击会补一个逗号（列表参数），末尾逗号不代表空名字，直接忽略。
        while (arg.endsWith(",") || arg.endsWith("，")) {
            arg = arg.substring(0, arg.length() - 1).trim();
        }
        List<String> names = new ArrayList<>();
        if ("clear".equalsIgnoreCase(arg)) {
            PlayerRegistry.getInstance().clearInjected();
            return DebugCommand.reply(ctx.getSource(), "已清空注入的测试玩家（当前注入池 0 人）");
        }
        if (arg.matches("\\d+")) {
            int n = Integer.parseInt(arg);
            for (int i = 1; i <= n; i++) {
                names.add("TestPlayer" + i);
            }
        } else {
            String[] tokens = arg.split("\\s+");
            if (tokens.length == 2 && !tokens[0].isEmpty() && !tokens[0].matches("\\d+")
                    && tokens[1].matches("\\d+")) {
                String prefix = tokens[0];
                int n = Integer.parseInt(tokens[1]);
                for (int i = 1; i <= n; i++) {
                    names.add(prefix + i);
                }
            } else {
                for (String s : arg.split("[,，]")) {
                    s = s.trim();
                    if (!s.isEmpty()) {
                        names.add(s);
                    }
                }
            }
        }
        if (names.isEmpty()) {
            return DebugCommand.reply(ctx.getSource(),
                    "参数为空（clear=清空；数字=批量 TestPlayer1..N；前缀+数字=如 guard 3；逗号分隔名字）");
        }
        PlayerRegistry.getInstance().inject(names);
        StringBuilder sb = new StringBuilder("注入 " + names.size() + " 名测试玩家（名 → 离线UUID，分组数据）：\n");
        for (String name : names) {
            UUID offlineUuid = UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                    .getBytes(StandardCharsets.UTF_8));
            sb.append("  ").append(name).append(" → ").append(offlineUuid).append('\n');
        }
        sb.append("当前注入池共 ").append(PlayerRegistry.getInstance().injectedCount()).append(" 人（clear 清空）");
        return DebugCommand.reply(ctx.getSource(), sb.toString().trim());
    }

    /** 分组 dry-run：名单缺省用注册玩家；指定名单用 [] 临时构造（不影响注入池） */
    static String previewGroups(CommandContext<CommandSourceStack> ctx, String spec) {
        List<PlayerRegistry.RegPlayer> players;
        if (spec != null && !spec.isBlank()) {
            List<String> names = new ArrayList<>();
            for (String s : spec.split("[,，]")) {
                s = s.trim();
                if (!s.isEmpty()) {
                    names.add(s);
                }
            }
            List<PlayerRegistry.RegPlayer> tmp = new ArrayList<>();
            for (String n : names) {
                tmp.add(new PlayerRegistry.RegPlayer(n, PlayerRegistry.offlineUuid(n),
                        System.currentTimeMillis()));
            }
            if (tmp.isEmpty()) {
                return "名单为空";
            }
            players = tmp;
        } else {
            players = PlayerRegistry.getInstance().read(ctx.getSource().getServer());
            if (players.isEmpty()) {
                return "当前没有注册玩家（先用 /samsara debug fakeplayers 注入名单）";
            }
        }
        return DebugCommand.manager().previewGroups(ctx.getSource().getServer(), players);
    }
}

