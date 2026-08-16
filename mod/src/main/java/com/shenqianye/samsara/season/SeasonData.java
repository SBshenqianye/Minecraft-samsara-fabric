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
    /** 分组/出生/倒流全局配置（§3.3 settings；Gson 只覆盖出现的字段，缺失字段保留 Java 默认值） */
    public Settings settings = new Settings();

    /**
     * 全局配置项（设计文档 §3.3 settings + M2 倒流时长）。
     */
    public static class Settings {
        public int groupSizeMin = 3;
        public int groupSizeMax = 5;
        public int groupMinDistance = 300;
        public int spawnRadius = 1500;
        public int spreadMinRadius = 500;
        public int spreadMaxRadius = 2000;
        public int newCenterMinDistance = 15000;
        public int newCenterMaxDistance = 40000;
        /** M2 时间倒流（档2）时长，单位天；&lt;=0 表示不开倒流、start 后立即传送 */
        public double rewindDurationDays = 1.0;
    }
}
