package com.shenqianye.samsara.command.ui;

import net.minecraft.nbt.CompoundTag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 候选翻页：切片、页码钳制、首页/末页按钮、节流、payload 往返。 */
class CompletionPagingTest {

    @Test
    void slicesAndReportsButtons() {
        List<Integer> all = List.of(1, 2, 3, 4, 5, 6, 7);
        CompletionPaging.Page<Integer> first = CompletionPaging.slice(all, 1, 3);
        assertEquals(List.of(1, 2, 3), first.items());
        assertEquals(3, first.pageCount());
        assertEquals(7, first.total());
        assertFalse(first.hasPrev(), "首页不显示上一页");
        assertTrue(first.hasNext());

        CompletionPaging.Page<Integer> last = CompletionPaging.slice(all, 3, 3);
        assertEquals(List.of(7), last.items());
        assertTrue(last.hasPrev());
        assertFalse(last.hasNext(), "末页不显示下一页");
    }

    @Test
    void clampsPageNumber() {
        List<Integer> all = List.of(1, 2, 3, 4, 5);
        assertEquals(1, CompletionPaging.slice(all, -9, 2).page());
        assertEquals(3, CompletionPaging.slice(all, 99, 2).page());
        assertEquals(1, CompletionPaging.clamp(0, 3));
        assertEquals(3, CompletionPaging.clamp(7, 3));
    }

    @Test
    void capsTotalCandidatesAndPages() {
        List<Integer> huge = java.util.stream.IntStream.range(0, 999).boxed().toList();
        CompletionPaging.Page<Integer> page = CompletionPaging.slice(huge, 1, 40);
        assertEquals(CompletionPaging.MAX_TOTAL, page.total());
        assertEquals(CompletionPaging.MAX_PAGES, page.pageCount());
        assertEquals(40, page.items().size(), "单页仍受 maxItems 限制");
    }

    @Test
    void emptyListStillHasOnePage() {
        CompletionPaging.Page<String> page = CompletionPaging.slice(List.of(), 1, 20);
        assertTrue(page.items().isEmpty());
        assertEquals(1, page.pageCount());
        assertEquals(0, page.total());
    }

    @Test
    void throttleAllowsFirstClickAndThenWaits() {
        assertTrue(CompletionPaging.allowAt(0L, 1_000L), "第一次翻页不节流");
        assertFalse(CompletionPaging.allowAt(1_000L, 1_100L));
        assertTrue(CompletionPaging.allowAt(1_000L, 1_250L));
    }

    @Test
    void pageLabelUsesNOfMAndTotal() {
        assertEquals("第 2/3 页 · 共 21 项", CompletionPaging.label(2, 3, 21));
    }

    @Test
    void payloadRoundTripsAndRejectsInvalid() {
        CompoundTag tag = CompletionAction.payload("samsara season\n", 3, "abc");
        CompletionAction.Request request = CompletionAction.parse(tag);
        assertEquals("samsara season", request.input(), "换行必须被清掉");
        assertEquals(3, request.page());
        assertEquals("abc", request.nonce());

        assertNull(CompletionAction.parse(new CompoundTag()), "缺 input 的 payload 不处理");
        assertNull(CompletionAction.parse(null));
    }
}
