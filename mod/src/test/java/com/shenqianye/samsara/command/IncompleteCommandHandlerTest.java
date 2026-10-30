package com.shenqianye.samsara.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 未完成指令：根节点识别、候选拼接、拼写近似。 */
class IncompleteCommandHandlerTest {

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("samsara")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("season")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                                .executes(ctx -> 1))
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("start")
                                .executes(ctx -> 1))));
        return dispatcher;
    }

    @Test
    void firstTokenStopsAtFirstWhitespace() {
        assertEquals("samsara", IncompleteCommandHandler.firstToken("samsara season status"));
        assertEquals("samsara", IncompleteCommandHandler.firstToken("  samsara  "));
        assertEquals("", IncompleteCommandHandler.firstToken("   "));
    }

    @Test
    void incompleteDetectionCoversUnknownChildAndMissingExecutor() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        assertTrue(IncompleteCommandHandler.isIncomplete(
                dispatcher.parse("samsara season sta", null)));
        assertTrue(IncompleteCommandHandler.isIncomplete(
                dispatcher.parse("samsara season", null)),
                "season 自己没有 executes，也算未完成");
        assertFalse(IncompleteCommandHandler.isIncomplete(
                dispatcher.parse("samsara season status", null)));
    }

    @Test
    void cellsReplaceOnlyTheCurrentToken() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        String command = "samsara season sta";
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, null);
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parse).join();

        List<com.shenqianye.samsara.command.ui.CompletionGrid.Cell> cells =
                IncompleteCommandHandler.cells(command, suggestions,
                        IncompleteCommandHandler.deepestNode(parse));

        assertEquals(2, cells.size(), "sta 同时匹配 status 与 start");
        List<String> labels = cells.stream().map(c -> c.label()).toList();
        assertTrue(labels.contains("status"));
        assertTrue(labels.contains("start"));
        com.shenqianye.samsara.command.ui.CompletionGrid.Cell status = cells.stream()
                .filter(c -> c.label().equals("status")).findFirst().orElseThrow();
        assertEquals("samsara season status ", status.command(),
                "叶子命令也补一个空格：回车时客户端 trim，照常执行");
    }

    @Test
    void listArgumentUsesCommaAndDoesNotDoubleIt() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("samsara")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("debug")
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("groups")
                                .then(com.mojang.brigadier.builder.RequiredArgumentBuilder
                                        .<CommandSourceStack, String>argument("names",
                                                com.mojang.brigadier.arguments.StringArgumentType.greedyString())
                                        .suggests((ctx, builder) ->
                                                builder.suggest("Bot").suggest("Alex,").buildFuture())))));
        String command = "samsara debug groups ";
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, null);
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parse).join();

        List<com.shenqianye.samsara.command.ui.CompletionGrid.Cell> cells =
                IncompleteCommandHandler.cells(command, suggestions,
                        IncompleteCommandHandler.deepestNode(parse));

        assertEquals(2, cells.size());
        java.util.Map<String, String> filled = new java.util.HashMap<>();
        for (var cell : cells) {
            filled.put(cell.label(), cell.command());
        }
        assertEquals("samsara debug groups Bot,", filled.get("Bot"),
                "逗号列表候选以单个逗号结尾");
        assertEquals("samsara debug groups Alex,", filled.get("Alex,"),
                "已有逗号不重复补");
    }

    @Test
    void cellsAppendSpaceForLiteralThatStillHasChildren() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        String command = "samsara seas";
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, null);
        Suggestions suggestions = dispatcher.getCompletionSuggestions(parse).join();

        List<com.shenqianye.samsara.command.ui.CompletionGrid.Cell> cells =
                IncompleteCommandHandler.cells(command, suggestions,
                        IncompleteCommandHandler.deepestNode(parse));

        assertTrue(cells.stream().anyMatch(c -> c.label().equals("season")
                && c.command().equals("samsara season ")),
                "season 还有子命令，应补尾随空格方便继续输入");
    }

    @Test
    void childModeListsChildrenEvenWhenClientTrimmedTrailingSpace() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        String command = "samsara season"; // 客户端 trim 后服务端真正收到的输入
        ParseResults<CommandSourceStack> extended = dispatcher.parse(command + " ", null);
        Suggestions suggestions = dispatcher.getCompletionSuggestions(extended).join();

        List<com.shenqianye.samsara.command.ui.CompletionGrid.Cell> cells =
                IncompleteCommandHandler.cells(command, suggestions,
                        IncompleteCommandHandler.deepestNode(extended), true);

        assertTrue(cells.stream().anyMatch(c -> c.command().equals("samsara season status ")),
                "无执行器节点应列出子命令，且填入值带一个空格");
    }

    @Test
    void expandHintDoesNotDependOnTrailingSpaceBecauseClientTrims() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("samsara")
                .then(LiteralArgumentBuilder.<CommandSourceStack>literal("season")
                        .executes(ctx -> 1)
                        .then(LiteralArgumentBuilder.<CommandSourceStack>literal("status")
                                .executes(ctx -> 1))));
        // 客户端提交前会 trim，所以这里用不带尾随空格的命令模拟服务端真正收到的输入。
        ParseResults<CommandSourceStack> parse = dispatcher.parse("samsara season", null);

        assertTrue(IncompleteCommandHandler.shouldOfferExpand(parse, null),
                "有执行器且还有子命令时应给 [展开子命令]，不能依赖尾随空格");
        assertFalse(IncompleteCommandHandler.shouldOfferExpand(
                        dispatcher.parse("samsara season status", null), null),
                "叶子命令没有子命令，不显示展开按钮");
        assertFalse(IncompleteCommandHandler.shouldOfferExpand(
                        dispatcher.parse("samsara", null), null),
                "没有执行器的节点不应显示展开按钮");
    }

    @Test
    void fuzzyFindNearbyLiteralWhenPrefixHasNoMatch() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        String command = "samsara seasn";
        ParseResults<CommandSourceStack> parse = dispatcher.parse(command, null);

        Suggestions suggestions = IncompleteCommandHandler.fuzzySuggestions(
                command, parse, IncompleteCommandHandler.deepestNode(parse));

        List<String> texts = suggestions.getList().stream().map(Suggestion::getText).toList();
        assertTrue(texts.contains("season"));
        assertEquals(command.indexOf("seasn"), suggestions.getRange().getStart());
        assertEquals(command.length(), suggestions.getRange().getEnd());
    }
}
