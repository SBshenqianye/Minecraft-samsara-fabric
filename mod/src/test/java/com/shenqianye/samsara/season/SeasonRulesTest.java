package com.shenqianye.samsara.season;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 创建新周目前自动结束旧 ACTIVE 的规则。 */
class SeasonRulesTest {

    private static Season season(int id, Season.State state) {
        Season season = new Season();
        season.id = id;
        season.name = "S" + id;
        season.state = state;
        return season;
    }

    @Test
    void onlyActiveIsAutoEnded() {
        assertTrue(SeasonRules.shouldAutoEnd(season(1, Season.State.ACTIVE)));
        assertFalse(SeasonRules.shouldAutoEnd(season(2, Season.State.PENDING)));
        assertFalse(SeasonRules.shouldAutoEnd(season(3, Season.State.ENDED)));
        assertFalse(SeasonRules.shouldAutoEnd(null));
    }

    @Test
    void picksActiveSeasonToEnd() {
        Season ended = season(1, Season.State.ENDED);
        Season active = season(2, Season.State.ACTIVE);
        assertSame(active, SeasonRules.activeToEnd(List.of(ended, active)));
        assertNull(SeasonRules.activeToEnd(List.of(ended)));
    }
}
