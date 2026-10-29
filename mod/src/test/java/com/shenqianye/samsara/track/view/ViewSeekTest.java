package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** 进度跳转：绝对/相对/百分比与窗口钳制。 */
class ViewSeekTest {
    private static final long START = 1_000L;
    private static final long END = 3_000L;

    @Test
    void percentUsesCursorWindow() {
        assertEquals(2_000L, ViewSeek.target("50%", 0, START, END));
        assertEquals(START, ViewSeek.target("0%", 0, START, END));
        assertEquals(END, ViewSeek.target("100%", 0, START, END));
    }

    @Test
    void relativeMovesFromCurrentAndClamps() {
        assertEquals(2_000L, ViewSeek.target("+1s", START, START, END));
        assertEquals(START, ViewSeek.target("-30s", 2_000, START, END));
        assertEquals(END, ViewSeek.target("+30s", 2_000, START, END));
    }

    @Test
    void absoluteTimeIsClamped() {
        assertEquals(END, ViewSeek.target("9999999999999", 0, START, END));
        assertEquals(START, ViewSeek.target("0000000001", 0, START, END));
    }

    @Test
    void rejectsBadPercent() {
        assertThrows(IllegalArgumentException.class, () -> ViewSeek.target("120%", 0, START, END));
        assertThrows(IllegalArgumentException.class, () -> ViewSeek.target("abc%", 0, START, END));
    }

    @Test
    void rejectsBadDuration() {
        assertThrows(IllegalArgumentException.class, () -> ViewSeek.target("+abc", 0, START, END));
    }
}
