package com.shenqianye.samsara.rewind;

import java.util.ArrayList;
import java.util.List;

/**
 * rewind.json —— M2 时间倒流（档2 区块级波浪）调度表持久化。
 * <p>
 * 每个未圈地块分配一个均匀分布在 [startMs, startMs+durationMs] 内的"倒流时刻"，
 * 到点标记（marked）；物理删除只在停服窗口执行（设计文档 §10/§6.5）。
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
        /** 倒流时刻（epoch ms） */
        public long rewindAtMs;
        /** 已到点（等待停服物理删除） */
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