package com.shenqianye.samsara.command.ui;

import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.MutableComponent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 聊天栏候选网格：多列排布、不拆按钮、点击只填入。 */
class CompletionGridTest {

    @Test
    void packsMultipleShortCandidatesPerLine() {
        List<CompletionGrid.Cell> cells = new ArrayList<>();
        for (String name : List.of("status", "info", "create", "start", "end", "skip")) {
            cells.add(new CompletionGrid.Cell(name, "samsara season " + name, "点击填入"));
        }

        List<MutableComponent> lines = CompletionGrid.render(cells, 300);

        assertTrue(lines.size() >= 1, "至少一行");
        assertTrue(lines.get(0).getString().contains("status")
                        && lines.get(0).getString().contains("info"),
                "短候选应横向排在同一行，而不是一行一个");
        for (MutableComponent line : lines) {
            assertTrue(CompletionSupport.displayWidth(line.getString()) <= 320,
                    "行宽应控制在聊天栏附近");
        }
    }

    @Test
    void everyCellIsSuggestCommandWithFullCommand() {
        List<MutableComponent> lines = CompletionGrid.render(List.of(
                new CompletionGrid.Cell("season", "samsara season ", "进入周目"),
                new CompletionGrid.Cell("track", "samsara track", "回看")));

        String text = lines.get(0).getString();
        assertTrue(text.contains("[season]"));
        assertTrue(text.contains("[track]"));
        ClickEvent first = lines.get(0).getSiblings().get(0).getStyle().getClickEvent();
        assertInstanceOf(ClickEvent.SuggestCommand.class, first);
        assertEquals("/samsara season ",
                ((ClickEvent.SuggestCommand) first).command(),
                "字面量节点后应保留尾随空格，方便继续输入");
    }

    @Test
    void longCandidateGoesToItsOwnLineWithoutSplitting() {
        List<CompletionGrid.Cell> cells = List.of(
                new CompletionGrid.Cell("2026-10-28T21:16:42", "samsara track view Bot 2026-10-28T21:16:42",
                        "回看"));
        List<MutableComponent> lines = CompletionGrid.render(cells, 80);
        assertEquals(1, lines.size());
        assertEquals("[2026-10-28T21:16:42]", lines.get(0).getString(),
                "单个按钮不能被拆成两行");
    }

    @Test
    void emptyInputProducesNoLines() {
        assertTrue(CompletionGrid.render(List.of()).isEmpty());
    }
}
