package com.shenqianye.samsara.rewind;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 全局回看的设计上限：单会话 1000 包/tick、最多 4 会话。 */
class DiffViewServiceBudgetTest {
    @Test
    void packetAndSessionLimitsMatchDesign() {
        assertEquals(1000, DiffViewService.MAX_PACKETS_PER_TICK);
        assertEquals(4, DiffViewService.MAX_SESSIONS);
        assertEquals(8, DiffViewService.DEFAULT_RADIUS);
    }

    @Test
    void fourSessionsHaveLinearWorstCaseBudget() {
        long worstCase = (long) DiffViewService.MAX_PACKETS_PER_TICK * DiffViewService.MAX_SESSIONS;
        assertEquals(4000L, worstCase, "4 会话 × 1000 包/tick");
    }
}
