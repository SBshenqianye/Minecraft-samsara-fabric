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
        /** 时间倒流时长，单位毫秒；&lt;=0 表示不开倒流、start 后立即传送 */
        public long rewindDurationMs = 93_600_000L;   // 默认 26 小时（比一天多 2h，使周目相位逐季漂移）

        // ---- 倒流节奏（双光标与分档，设计文档 §5.3）----
        /** 权威光标曲线 p(t)=1-(1-t)^k 的 k；越大开场越快、尾段越平 */
        public double rewindCurveK = 2.0;
        /** 近档呈现光标 B 的速率 = A 速率 × r（r=1 即与权威光标同速） */
        public double rewindNearRatio = 0.4;
        /** 近档半径（区块）：区块中心到最近玩家的水平距离 ≤ 此值进入近档 */
        public int rewindNearRadius = 3;
        /** 出近档半径（区块）：> 此值离开近档（3~4 为保持带，防横跳） */
        public int rewindExitRadius = 4;
        /** 最短驻留（毫秒）：进入近档后至少停留这么久才允许出档 */
        public long rewindNearDwellMs = 10_000L;
        /** 出档斜坡（毫秒）：离开近档后 B 追平 A 的时间 */
        public long rewindRampMs = 4_000L;

        // ---- 玩家轨迹（设计文档 §5.4/§5.5）----
        /** 轨迹采样率（Hz，2~20） */
        public double trackSampleHz = 5.0;
        /** 轨迹保留：每天至少保留的点数（骨架下限） */
        public int trackSkeletonPerDay = 2000;

        /** 旧字段（已弃用）：天为单位，仅用于读取旧 season.json 后迁移到 rewindDurationMs */
        public Double rewindDurationDays;

        /** 每组内每个玩家出生点的独立抖动（方块）；0 = 同组共用组出生点 */
        public int birthpointJitter = 40;
        /** 是否把每个玩家的系统出生点重置到各自的分组出生点（0=关闭，保留玩家原出生点） */
        public boolean resetIndividualBirthpoint = true;

        /**
         * 补齐旧 season.json 缺失的新字段（Gson 不执行字段默认值，缺失的 double/int 会是 0）。
         * 只补"<=0 无意义"的字段；rewindDurationMs 的 0 是合法值（关闭倒流），不能动。
         */
        public void normalize() {
            if (rewindCurveK <= 0) {
                rewindCurveK = 2.0;
            }
            if (rewindNearRatio <= 0) {
                rewindNearRatio = 0.4;
            }
            if (rewindNearRadius <= 0) {
                rewindNearRadius = 3;
            }
            if (rewindExitRadius <= rewindNearRadius) {
                rewindExitRadius = rewindNearRadius + 1;
            }
            if (rewindNearDwellMs <= 0) {
                rewindNearDwellMs = 10_000L;
            }
            if (rewindRampMs <= 0) {
                rewindRampMs = 4_000L;
            }
            if (trackSampleHz < 2 || trackSampleHz > 20) {
                trackSampleHz = 5.0;
            }
            if (trackSkeletonPerDay < 100) {
                trackSkeletonPerDay = 2000;
            }
        }
    }
}
