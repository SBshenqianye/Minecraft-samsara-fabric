package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 栈维度加载失败后的重试退避（纯逻辑）。 */
class StackLoadPolicyTest {

    @Test
    void neverFailedIsAlwaysAllowed() {
        assertTrue(StackLoadPolicy.shouldRetry(0, 0, 0));
        assertTrue(StackLoadPolicy.shouldRetry(-1, 0, 0));
        assertEquals(0L, StackLoadPolicy.nextDelayMs(0));
    }

    @Test
    void delayGrowsExponentiallyThenCaps() {
        assertEquals(5_000L, StackLoadPolicy.nextDelayMs(1));
        assertEquals(10_000L, StackLoadPolicy.nextDelayMs(2));
        assertEquals(20_000L, StackLoadPolicy.nextDelayMs(3));
        assertEquals(40_000L, StackLoadPolicy.nextDelayMs(4));
        assertEquals(60_000L, StackLoadPolicy.nextDelayMs(5));
        assertEquals(60_000L, StackLoadPolicy.nextDelayMs(50));
    }

    @Test
    void retryWaitsForBackoffWindow() {
        long last = 1_000L;
        assertFalse(StackLoadPolicy.shouldRetry(1, last, last + 4_999L));
        assertTrue(StackLoadPolicy.shouldRetry(1, last, last + 5_000L));
        // 第二次失败后窗口变成 10s
        assertFalse(StackLoadPolicy.shouldRetry(2, last, last + 9_999L));
        assertTrue(StackLoadPolicy.shouldRetry(2, last, last + 10_000L));
    }
}
