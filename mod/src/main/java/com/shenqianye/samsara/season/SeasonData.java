package com.shenqianye.samsara.season;

import java.util.ArrayList;
import java.util.List;

/**
 * season.json 根结构（设计文档 §3.3）。
 */
public class SeasonData {
    public int schemaVersion = 1;
    public int currentSeasonId = 0;
    /** 世界栈：栈底→栈顶，栈顶 = 当前世界（M7 起使用，§14）。 */
    public List<String> worldStack = new ArrayList<>();
    public List<Season> seasons = new ArrayList<>();
}
