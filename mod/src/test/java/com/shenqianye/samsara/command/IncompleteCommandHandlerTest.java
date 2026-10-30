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
        assertEquals("samsara season status", status.command(),
                "前缀必须保留，且 status 没有子命令时不补尾随空格");
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
