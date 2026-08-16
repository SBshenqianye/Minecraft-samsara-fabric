package com.shenqianye.samsara.season;

import java.util.List;
import java.util.Random;

/**
 * 新周目出生中心计算（M2，设计文档 §4 步骤 2）。
 * <p>
 * same-world：新出生中心必须与<b>每一个</b>历史出生中心点（第 1 次到上一次）的距离
 * 都 ≥ newCenterMinDistance（默认 15000，无上限），并尽量落在 newCenterMaxDistance
 * 附近，避免越滚越远。方向随机，最多尝试大量候选，若仍无满足点则采用"离所有历史
 * 中心最小距离最大"的次优点。
 * <p>
 * 纯 Java 逻辑，不依赖 MC 类型。
 */
public class SpawnCenterCalculator {
    /** 计算结果：x/z 为出生中心方块坐标，minDist 为与最近历史中心的距离 */
    public record CenterResult(int x, int z, int minDist) {
    }

    private SpawnCenterCalculator() {
    }

    /**
     * @param refX / refZ 基准点（典型取最近一次历史中心，用于限定搜索半径）
     * @param centers 全部历史出生中心（含第一次~上一次；不含本次要生成的点）
     * @param s 配置项
     * @param rnd 随机源
     */
    public static CenterResult next(int refX, int refZ, List<int[]> centers,
                                    SeasonData.Settings s, Random rnd) {
        // 若没有任何历史中心，退回世界原点附近随机
        if (centers.isEmpty()) {
            int d = s.newCenterMinDistance;
            double a = rnd.nextDouble() * 2 * Math.PI;
            return new CenterResult(refX + (int) Math.round(Math.cos(a) * d),
                    refZ + (int) Math.round(Math.sin(a) * d), Integer.MAX_VALUE);
        }
        int[] ref = pickReference(refX, refZ, centers);
        int maxDist = Math.max(s.newCenterMaxDistance, 60000);
        CenterResult best = null;
        int bestMin = -1;
        // 大量候选：半径在 [min, max] 均匀，方向随机
        for (int attempt = 0; attempt < 200000; attempt++) {
            double a = rnd.nextDouble() * 2 * Math.PI;
            int d = s.newCenterMinDistance + rnd.nextInt(maxDist - s.newCenterMinDistance + 1);
            int x = ref[0] + (int) Math.round(Math.cos(a) * d);
            int z = ref[1] + (int) Math.round(Math.sin(a) * d);
            int m = minDistance(x, z, centers);
            // 找到满足"所有历史中心 ≥ min"的点即返回
            if (m >= s.newCenterMinDistance) {
                return new CenterResult(x, z, m);
            }
            if (m > bestMin) {
                bestMin = m;
                best = new CenterResult(x, z, m);
            }
        }
        // 兜底：返回离所有历史中心最小距离最大的候选（次优）
        return best != null ? best : new CenterResult(ref[0], ref[1], bestMin);
    }

    /** 取基准点：若有与传入 ref 相同的历史中心则用它，否则用最后一个（最近） */
    private static int[] pickReference(int refX, int refZ, List<int[]> centers) {
        for (int[] c : centers) {
            if (c[0] == refX && c[1] == refZ) {
                return c;
            }
        }
        return centers.get(centers.size() - 1);
    }

    /** 与所有历史中心点的最小切比雪夫/欧氏距离（用欧氏） */
    private static int minDistance(int x, int z, List<int[]> centers) {
        int min = Integer.MAX_VALUE;
        for (int[] c : centers) {
            int dx = x - c[0];
            int dz = z - c[1];
            int d = (int) Math.round(Math.hypot(dx, dz));
            if (d < min) {
                min = d;
            }
        }
        return min;
    }
}