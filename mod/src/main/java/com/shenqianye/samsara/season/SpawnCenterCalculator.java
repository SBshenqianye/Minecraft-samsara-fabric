package com.shenqianye.samsara.season;

import java.util.List;
import java.util.Random;

/**
 * 出生中心计算（同世界重置的「排斥点」）。
 * <p>
 * 新出生中心必须与<b>每一个</b>历史出生中心都 ≥ minSeparation（默认 15000，
 * 随历史数量小幅增长），方向随机，距离在 [minSeparation, newCenterMaxDistance] 之间；
 * 找到满足距离的候选后交给地形/保护区校验器，校验失败继续采样。
 * 若始终没有满足点，采用「离所有历史中心最小距离最大」的次优点作为兜底。
 * <p>
 * 纯 Java 逻辑，不依赖 MC 类型；地形/保护区校验通过 {@link CandidateFilter} 注入。
 */
public class SpawnCenterCalculator {
    /** 计算结果：x/z 为出生中心方块坐标，minDist 为与最近历史中心的距离。 */
    public record CenterResult(int x, int z, int minDist, boolean validated) {
        public CenterResult(int x, int z, int minDist) {
            this(x, z, minDist, false);
        }
    }

    /** 候选点校验：返回 true 表示可以落脚（地形安全、不在保护区等）。 */
    @FunctionalInterface
    public interface CandidateFilter {
        boolean test(int x, int z, int minDist);
    }

    private SpawnCenterCalculator() {
    }

    /**
     * 旧入口：不做地形校验（保留给单测/预览）。
     *
     * @param refX / refZ 基准点（典型取最近一次历史中心）
     * @param centers 全部历史出生中心（含第一次~上一次；不含本次要生成的点）
     */
    public static CenterResult next(int refX, int refZ, List<int[]> centers,
                                    SeasonData.Settings s, Random rnd) {
        return next(refX, refZ, centers, s.newCenterMinDistance, s, rnd,
                (x, z, d) -> true, 0);
    }

    /**
     * 新入口：按有效最小间距采样，并对满足距离的候选做有限次校验。
     *
     * @param minSeparation 本次要求的最小间距（通常 base + 2500 × 历史数量，封顶 100000）
     * @param filter        地形/保护区校验；返回 false 继续采样
     * @param maxChecks     最多校验多少个「距离已满足」的候选（0 = 不校验）
     */
    public static CenterResult next(int refX, int refZ, List<int[]> centers, int minSeparation,
                                    SeasonData.Settings s, Random rnd,
                                    CandidateFilter filter, int maxChecks) {
        int minSep = Math.max(0, minSeparation);
        if (centers.isEmpty()) {
            int d = Math.max(1, minSep);
            double a = rnd.nextDouble() * 2 * Math.PI;
            int x = refX + (int) Math.round(Math.cos(a) * d);
            int z = refZ + (int) Math.round(Math.sin(a) * d);
            boolean ok = maxChecks <= 0 || filter.test(x, z, Integer.MAX_VALUE);
            return new CenterResult(x, z, Integer.MAX_VALUE, ok);
        }
        int[] ref = pickReference(refX, refZ, centers);
        int maxDist = Math.max(s.newCenterMaxDistance, minSep + 10000);
        maxDist = Math.max(maxDist, minSep + 1);
        CenterResult best = null;
        int bestMin = -1;
        int checks = 0;
        // 大量候选：半径在 [min, max] 均匀，方向随机
        for (int attempt = 0; attempt < 200000; attempt++) {
            double a = rnd.nextDouble() * 2 * Math.PI;
            int d = minSep + rnd.nextInt(maxDist - minSep + 1);
            int x = ref[0] + (int) Math.round(Math.cos(a) * d);
            int z = ref[1] + (int) Math.round(Math.sin(a) * d);
            int m = minDistance(x, z, centers);
            // 找到满足「所有历史中心 ≥ min」且通过校验的点即返回
            if (m >= minSep) {
                if (maxChecks <= 0 || checks < maxChecks) {
                    if (maxChecks > 0) {
                        checks++;
                    }
                    if (filter.test(x, z, m)) {
                        return new CenterResult(x, z, m, true);
                    }
                }
            }
            if (m > bestMin) {
                bestMin = m;
                best = new CenterResult(x, z, m, false);
            }
            // 距离候选已经足够多但都不合格：不再空转 20 万次
            if (maxChecks > 0 && checks >= maxChecks && m < minSep) {
                // 仍然保留兜底最优；继续少量采样即可
                if (attempt > 20000) {
                    break;
                }
            }
        }
        return best != null ? best : new CenterResult(ref[0], ref[1], bestMin, false);
    }

    /** 有效最小间距：base + 2500 × 历史数量，封顶 100000。 */
    public static int effectiveSeparation(int base, int historyCount) {
        long grown = (long) Math.max(0, base) + 2500L * Math.max(0, historyCount);
        return (int) Math.min(100_000L, Math.max(0L, grown));
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

    /** 与所有历史中心点的最小欧氏距离 */
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
