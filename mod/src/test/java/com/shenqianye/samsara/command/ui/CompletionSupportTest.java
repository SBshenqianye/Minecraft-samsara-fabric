package com.shenqianye.samsara.command.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 补全纯工具层：编辑距离、拼写近似、显示宽度与截断。 */
class CompletionSupportTest {

    @Test
    void editDistanceHandlesTypicalTypos() {
        assertEquals(1, CompletionSupport.editDistance("seasn", "season"));
        assertEquals(1, CompletionSupport.editDistance("trak", "track"));
        assertEquals(0, CompletionSupport.editDistance("same", "same"));
        assertEquals(3, CompletionSupport.editDistance("", "abc"));
    }

    @Test
    void fuzzySortsByDistanceAndPreservesOrderOnTie() {
        List<String> matches = CompletionSupport.fuzzy("trak",
                List.of("trace", "track", "tps"), 2);
        assertEquals(List.of("track", "trace"), matches);
    }

    @Test
    void fuzzyRejectsBlankAndFarCandidates() {
        assertTrue(CompletionSupport.fuzzy("", List.of("season"), 1).isEmpty());
        assertTrue(CompletionSupport.fuzzy("season", List.of("claim"), 1).isEmpty());
    }

    @Test
    void displayWidthTreatsCjkAsWide() {
        assertEquals(18, CompletionSupport.displayWidth("玩家"));
        assertEquals(12, CompletionSupport.displayWidth("ab"));
        assertEquals(0, CompletionSupport.displayWidth(null));
    }

    @Test
    void truncateKeepsWithinBudgetAndAddsEllipsis() {
        String text = "abcdefghijklmnopqrstuvwxyz";
        String out = CompletionSupport.truncate(text, 60);
        assertTrue(CompletionSupport.displayWidth(out) <= 60);
        assertTrue(out.endsWith("…"));
        assertEquals("short", CompletionSupport.truncate("short", 60));
    }
}
