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
     * 全局配置项（设计文档 §3.3 settings + M2 倒流时长 + M2 出生点微调）。
     */
    public static class Settings {
        public int groupSizeMin = 3;
        public int groupSizeMax = 5;
        public int groupMinDistance = 1000;
        public int spawnRadius = 1500;
        public int spreadMinRadius = 500;
        public int spreadMaxRadius = 2000;
        public int newCenterMinDistance = 15000;
        public int newCenterMaxDistance = 40000;
        /** M2 时间倒流时长，单位毫秒；&lt;=0 表示不开倒流、start 后立即传送 */
        public long rewindDurationMs = 86_400_000L;   // 默认 1 天

        /** 旧字段（已弃用）：天为单位，仅用于读取旧 season.json 后迁移到 rewindDurationMs */
        public Double rewindDurationDays;

        /** 每组内每个玩家出生点的独立抖动（方块）；0 = 同组共用组出生点 */
        public int birthpointJitter = 40;
        /** 是否把每个玩家的系统出生点重置到各自的分组出生点（0=关闭，保留玩家原出生点） */
        public boolean resetIndividualBirthpoint = true;

        /** M3 活跃度：死服判定阈值（设计文档 §5.2，可配置）——连续 N 天 0 人上线 */
        public int deadDays = 14;
        /** M3 活跃度：死服判定阈值——近 30 天活跃玩家数 ≤ 该值 */
        public int deadActiveMax = 2;
    }
}
