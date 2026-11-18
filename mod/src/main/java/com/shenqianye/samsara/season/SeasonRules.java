package com.shenqianye.samsara.season;

import java.util.List;

/**
 * 周目生命周期规则（纯逻辑，可单测）。
 *
 * <p>设计文档要求：创建新周目时自动结束上一个周目，任何时刻最多一个 ACTIVE。
 */
public final class SeasonRules {

    private SeasonRules() {
    }

    /** 该周目是否需要在创建新周目前自动结束。 */
    public static boolean shouldAutoEnd(Season season) {
        return season != null && season.state == Season.State.ACTIVE;
    }

    /** 当前列表里的 ACTIVE 周目（正常最多一个；旧数据有多个时取最后一个）。 */
    public static Season activeToEnd(List<Season> seasons) {
        if (seasons == null) {
            return null;
        }
        Season found = null;
        for (Season season : seasons) {
            if (shouldAutoEnd(season)) {
                found = season;
            }
        }
        return found;
    }
}
