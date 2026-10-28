package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 回放按开始时刻反查周目（不依赖 MC/FabricLoader） */
class SeasonCycleTest {
    private static Season season(int id, Long start, Long end) {
        Season s = new Season();
        s.id = id;
        s.startTime = start;
        s.endTime = end;
        return s;
    }

    @Test
    void resolvesCycleByStartAndEndTime() {
        List<Season> seasons = List.of(
                season(1, 1_000L, 5_000L),
                season(2, 10_000L, 20_000L),
                season(3, 30_000L, null));

        assertEquals(0, SeasonManager.cycleIdAt(seasons, 999L), "早于所有周目 → cycle0");
        assertEquals(1, SeasonManager.cycleIdAt(seasons, 1_000L));
        assertEquals(1, SeasonManager.cycleIdAt(seasons, 5_000L));
        assertEquals(1, SeasonManager.cycleIdAt(seasons, 7_000L), "周目间的空隙取此前最近的周目");
        assertEquals(2, SeasonManager.cycleIdAt(seasons, 15_000L));
        assertEquals(2, SeasonManager.cycleIdAt(seasons, 25_000L), "未结束周目之前仍归上一个");
        assertEquals(3, SeasonManager.cycleIdAt(seasons, 30_000L));
        assertEquals(3, SeasonManager.cycleIdAt(seasons, 99_999L), "未结束周目上界为 +∞");
    }

    @Test
    void emptyOrUnstartedSeasonsUseCycleZero() {
        assertEquals(0, SeasonManager.cycleIdAt(List.of(), 123L));
        assertEquals(0, SeasonManager.cycleIdAt(List.of(season(4, null, null)), 123L));
    }

    @Test
    void overlappingWindowsTakeLatestStart() {
        List<Season> seasons = List.of(
                season(1, 0L, 100_000L),
                season(2, 50_000L, null));
        assertEquals(1, SeasonManager.cycleIdAt(seasons, 40_000L));
        assertEquals(2, SeasonManager.cycleIdAt(seasons, 60_000L), "重叠时取开始最晚的周目");
    }
}
