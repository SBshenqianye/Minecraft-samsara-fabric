package com.shenqianye.samsara.command.ui;

import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import com.shenqianye.samsara.season.PlayerRegistry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Tab 补全框架：纯数据列表、前缀过滤、贪心参数 token 定位与替换范围。 */
class SuggestionProvidersTest {
    private static final List<String> STARTS = List.of(
            "2026-10-28T21:16:42", "2026-10-27T09:00:00");
    private static final List<String> CYCLES = List.of("cycle0", "cycle3");

    // ---- 纯数据 ----

    @Test
    void cyclesUseCyclePrefixAndKeepOrder() {
        assertEquals(List.of("cycle2", "cycle0"), SuggestionProviders.cycles(List.of(2, 0)));
        assertTrue(SuggestionProviders.cycles(List.of()).isEmpty());
    }

    @Test
    void viewOptionsCoverDocumentedFlags() {
        List<String> options = SuggestionProviders.viewOptions();
        assertTrue(options.containsAll(List.of("--speed", "--duration", "--cycle", "--follow",
                "--spectator", "--glow", "--no-glow", "--no-name-tag")));
    }

    @Test
    void durationsCoverCommonWindows() {
        List<String> durations = SuggestionProviders.durations();
        assertTrue(durations.containsAll(List.of("30s", "1m", "5m", "30m", "1h", "2h")));
    }

    @Test
    void permissionLevelsAreUserOpOwnerConfig() {
        assertEquals(List.of("user", "op", "owner", "config"), SuggestionProviders.permissionLevels());
    }

    @Test
    void seasonModes() {
        assertEquals(List.of("same-world", "new-world"), SuggestionProviders.seasonModes());
    }

    @Test
    void commandPathsFallBackToDefaultsWithoutFabric() {
        List<String> paths = SuggestionProviders.commandPaths();
        assertTrue(paths.contains("season create"), "应包含内置默认命令路径");
        assertTrue(paths.contains("debug claim"));
        assertTrue(paths.contains("perm set"));
    }

    @Test
    void mergeDeduplicatesAndPreservesOrder() {
        assertEquals(List.of("Bot", "Alice", "Bob"),
                SuggestionProviders.merge(List.of("Bot", "Alice"), List.of("Alice", "Bob")));
    }

    @Test
    void filterIsCaseInsensitive() {
        assertEquals(List.of("Season"), SuggestionProviders.filter("sea", List.of("Season", "claim")));
        assertTrue(SuggestionProviders.filter("zzz", List.of("Season")).isEmpty());
    }

    // ---- 贪心参数 token ----

    @Test
    void tokenUsesLastSeparator() {
        SuggestionProviders.TokenSuggestions token = SuggestionProviders.token(
                "Alice,Bo", ',', List.of("Bob", "Bot"));
        assertEquals(6, token.offset());
        assertEquals("Bo", token.token());
        assertEquals(List.of("Bob", "Bot"), token.candidates());
    }

    @Test
    void blankInputSuggestsStartTimes() {
        SuggestionProviders.TokenSuggestions token = SuggestionProviders.viewInput("", STARTS);
        assertEquals(0, token.offset());
        assertEquals(STARTS, token.candidates());
    }

    @Test
    void partialStartTimeIsPrefixFiltered() {
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput("2026-10-28T21", STARTS);
        assertEquals(0, token.offset());
        assertEquals(List.of("2026-10-28T21:16:42"), token.candidates());
    }

    @Test
    void afterStartTimeSuggestOptions() {
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput("2026-10-28T21:20 ", STARTS);
        assertEquals("2026-10-28T21:20 ".length(), token.offset());
        assertEquals("", token.token());
        assertEquals(SuggestionProviders.viewOptions(), token.candidates());
    }

    @Test
    void partialOptionTokenUsesTokenOffset() {
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput("2026-10-28T21:20 --sp", STARTS);
        assertEquals("2026-10-28T21:20 --sp".indexOf("--sp"), token.offset());
        assertEquals("--sp", token.token());
        assertTrue(token.candidates().contains("--speed"));
        assertTrue(token.candidates().contains("--spectator"));
        assertFalse(token.candidates().contains("--duration"));
    }

    @Test
    void afterCycleKeySuggestCycles() {
        // 尾随空格：在末尾插入整个 cycleN
        SuggestionProviders.TokenSuggestions trailing =
                SuggestionProviders.viewInput("2026-10-28T21:20 --cycle ", STARTS, CYCLES);
        assertEquals("2026-10-28T21:20 --cycle ".length(), trailing.offset());
        assertTrue(trailing.candidates().contains("cycle0"));

        // 已输入前缀：只替换 cycle1 这一段，不吞掉开始时间
        SuggestionProviders.TokenSuggestions partial =
                SuggestionProviders.viewInput("2026-10-28T21:20 --cycle cy", STARTS, CYCLES);
        assertEquals("2026-10-28T21:20 --cycle cy".lastIndexOf("cy"), partial.offset());
        assertEquals("cy", partial.token());
        assertTrue(partial.candidates().contains("cycle3"));
    }

    @Test
    void afterDurationKeySuggestDurations() {
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput("2026-10-28T21:20 --duration 3", STARTS);
        assertEquals("2026-10-28T21:20 --duration 3".indexOf("3"), token.offset());
        assertTrue(token.candidates().contains("30s"));
        assertTrue(token.candidates().contains("30m"));
    }

    @Test
    void afterSpeedKeySuggestSpeedValues() {
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput("2026-10-28T21:20 --speed ", STARTS);
        assertTrue(token.candidates().contains("4"));
        assertTrue(token.candidates().contains("0.25"));
    }

    @Test
    void afterFollowKeySuggestOnOff() {
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput("2026-10-28T21:20 --follow ", STARTS);
        assertEquals(List.of("on", "off"), token.candidates());
    }

    @Test
    void inlineCycleValueOnlyReplacesValue() {
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput("2026-10-28T21:20 --cycle=cy", STARTS, CYCLES);
        assertEquals("2026-10-28T21:20 --cycle=".length(), token.offset());
        assertEquals("cy", token.token());
        assertTrue(token.candidates().contains("cycle0"));
    }

    @Test
    void suggestionsRangeCoversOnlyCurrentToken() {
        String input = "2026-10-28T21:20 --cy";
        SuggestionsBuilder builder = new SuggestionsBuilder(input, 0);
        SuggestionProviders.TokenSuggestions token =
                SuggestionProviders.viewInput(builder.getRemaining(), STARTS);

        Suggestions suggestions = SuggestionProviders.offerToken(builder, token, null).join();
        assertEquals(input.indexOf("--cy"), suggestions.getRange().getStart(),
                "替换范围必须从当前 token 开始，不能吃掉开始时间");
        assertEquals(input.length(), suggestions.getRange().getEnd());
        assertFalse(suggestions.getList().isEmpty());
    }

    @Test
    void suggestionTooltipsAreProvidedForOptions() {
        assertNotNull(SuggestionProviders.viewTooltip("--speed"));
        assertNotNull(SuggestionProviders.viewTooltip("--duration"));
        assertNotNull(SuggestionProviders.viewTooltip("cycle3"));
        assertNotNull(SuggestionProviders.viewTooltip("30m"));
        assertNotNull(SuggestionProviders.viewTooltip("2026-10-28T21:16:42"));
    }

    @Test
    void playerTooltipKeepsHintAndAppendsUuid() {
        PlayerRegistry registry = PlayerRegistry.getInstance();
        registry.inject(List.of("Alice"));
        try {
            UUID id = PlayerRegistry.offlineUuid("Alice");
            assertEquals(id, SuggestionProviders.playerUuid(null, "alice"));
            String tip = SuggestionProviders.playerTooltip(null, "Alice");
            assertTrue(tip.contains("Alice"), "hover 必须保留玩家名");
            assertTrue(tip.contains(id.toString()), "hover 必须补上 UUID");
            String roleTip = SuggestionProviders.playerTooltip(null, "Alice", "监控目标");
            assertTrue(roleTip.contains("监控目标"), "hover 必须写清玩家角色");
            assertTrue(roleTip.contains("Alice"));
        } finally {
            registry.clearInjected();
        }
    }
}
