package com.shenqianye.samsara.track.view;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 拖动模式：A/D 速率、Shift 加速、窗口钳制。 */
class ViewScrubTest {
    private static final long START = 10_000L;
    private static final long END = 20_000L;

    @Test
    void forwardAndBackAtBaseRate() {
        assertEquals(12_000L, ViewScrub.target(10_000, START, END, false, true, false, 0.5));
        assertEquals(10_000L, ViewScrub.target(10_000, START, END, true, false, false, 0.5));
    }

    @Test
    void shiftQuadruplesRate() {
        assertEquals(18_000L, ViewScrub.target(10_000, START, END, false, true, true, 0.5));
    }

    @Test
    void clampsToWindow() {
        assertEquals(END, ViewScrub.target(19_900, START, END, false, true, true, 1.0));
        assertEquals(START, ViewScrub.target(10_100, START, END, true, false, true, 1.0));
    }

    @Test
    void bothOrNeitherDoesNothing() {
        assertEquals(15_000L, ViewScrub.target(15_000, START, END, true, true, false, 0.5));
        assertEquals(15_000L, ViewScrub.target(15_000, START, END, false, false, false, 0.5));
    }
}
