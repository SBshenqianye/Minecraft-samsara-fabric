package com.shenqianye.samsara.rewind;

import java.util.ArrayList;
import java.util.List;

/**
 * rewind.json —— M2 时间倒流调度表持久化。
 * <p>
 * 每个未圈地块分配一个均匀分布在 [startMs, startMs+durationMs] 内的「倒流时刻」，
 * 到时打标（marked = 已到倒流时刻，待退场）。物理删除在倒流完成时执行：已卸载区块
 * 后台清零，仍加载的入待删队列（卸载即删/停服兜底）。
 * 服务重启后凭本文件恢复倒流进度。
 */
public class RewindData {
    public int seasonId;
    public long startMs;
    public long durationMs;
    /** 倒流结束后的传送阶段是否已完成（防重复传送） */
    public boolean teleportPhaseDone = false;
    public int totalCount;
    public int protectedCount;
    public List<Chunk> chunks = new ArrayList<>();

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