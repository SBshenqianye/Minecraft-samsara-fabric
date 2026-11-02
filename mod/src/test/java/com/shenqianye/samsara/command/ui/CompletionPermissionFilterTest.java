package com.shenqianye.samsara.command.ui;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.StringRange;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import net.minecraft.commands.CommandSourceStack;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/** Brigadier 不按 canUse 过滤补全时，literal 候选的兜底过滤。 */
class CompletionPermissionFilterTest {

    private static CommandDispatcher<CommandSourceStack> dispatcher() {
        CommandDispatcher<CommandSourceStack> dispatcher = new CommandDispatcher<>();
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("allowed"));
        dispatcher.register(LiteralArgumentBuilder.<CommandSourceStack>literal("denied")
                .requires(source -> false));
        return dispatcher;
    }

    private static Suggestions suggestions(String... values) {
        StringRange range = StringRange.at(0);
        return new Suggestions(range,
                java.util.Arrays.stream(values).map(value -> new Suggestion(range, value)).toList());
    }

    @Test
    void deniedLiteralIsRemovedButArgumentCandidatesStay() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        Suggestions filtered = CompletionPermissionFilter.filter(
                suggestions("allowed", "denied", "Alice"),
                dispatcher.getRoot(), null);

        assertEquals(List.of("allowed", "Alice"),
                filtered.getList().stream().map(Suggestion::getText).toList());
    }

    @Test
    void allUsableSuggestionsAreReturnedUnchanged() {
        CommandDispatcher<CommandSourceStack> dispatcher = dispatcher();
        Suggestions input = suggestions("allowed", "Alice");
        assertSame(input, CompletionPermissionFilter.filter(
                input, dispatcher.getRoot(), null));
    }
}
