package com.shenqianye.samsara.rewind;

import java.util.ArrayList;
import java.util.List;

/**
 * rewind.json —— 时间倒流调度表持久化。
 * <p>
 * 2026-10-28 起所有未圈地块的「退场时刻」统一为倒流时长终点（{@code startMs + durationMs}）：
 * 运行期由 {@link DiffRestore} 的双光标逐格恢复，物理删除在终点执行——已卸载区块后台清零，
 * 仍加载的入待删队列（卸载即删/停服兜底）。服务重启后凭本文件恢复倒流进度。
 */
public class RewindData {
    public int seasonId;
    /** 本轮回流所属世界（世界栈中的世界目录名，如 world / world_s1） */
    public String worldKey;
    public long startMs;
    public long durationMs;
    /** 倒流结束后的传送阶段是否已完成（防重复传送） */
    public boolean teleportPhaseDone = false;
    public int totalCount;
    public int protectedCount;
    public List<Chunk> chunks = new ArrayList<>();

    // ---- 倒流节奏（《设计文档》§5.3；Gson 不走构造器，缺失字段必须由 normalize() 补默认值）----
    public double curveK;
    public double nearRatio;
    public int nearRadius;
    public int exitRadius;
    public long nearDwellMs;
    public long rampMs;

    /** 旧 rewind.json 可能没有节奏字段（Gson 反序列化不会执行字段默认值），统一在此补默认值 */
    public void normalize() {
        if (curveK <= 0) {
            curveK = 2.0;
        }
        if (nearRatio <= 0) {
            nearRatio = 0.4;
        }
        if (nearRadius <= 0) {
            nearRadius = 3;
        }
        if (exitRadius <= nearRadius) {
            exitRadius = nearRadius + 1;
        }
        if (nearDwellMs <= 0) {
            nearDwellMs = 10_000L;
        }
        if (rampMs <= 0) {
            rampMs = 4_000L;
        }
        if (chunks == null) {
            chunks = new ArrayList<>();
        }
    }

    /** 单区块倒流安排 */
    public static class Chunk {
        public int x;
        public int z;
        /** 倒流时刻（epoch ms）：到此刻该区块应退场 */
        public long rewindAtMs;
        /** 已到倒流时刻（待退场：等待物理删除） */
        public boolean marked;

        public Chunk() {
        }

        public Chunk(int x, int z, long rewindAtMs) {
            this.x = x;
            this.z = z;
            this.rewindAtMs = rewindAtMs;
        }
    }

    public int markedCount() {
        int c = 0;
        for (Chunk ch : chunks) {
            if (ch.marked) {
                c++;
            }
        }
        return c;
    }
}