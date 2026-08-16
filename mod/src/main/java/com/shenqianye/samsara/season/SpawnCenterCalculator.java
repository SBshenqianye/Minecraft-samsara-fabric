package com.shenqianye.samsara.season;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * 新周目出生中心计算（M2，设计文档 §4 步骤 2）。
 * <p>
 * same-world：距旧中心 newCenterMinDistance~newCenterMaxDistance 格、随机方向，
 * 并避开已用方向（与任一历史方向角距 ≥ 60°，最多尝试 120 次）。
 * new-world：直接用世界出生点（由调用方决定，不调用本类）。
 * <p>
 * 纯 Java 逻辑，不依赖 MC 类型。
 */
public class SpawnCenterCalculator {
    /** 计算结果：x/z 为出生中心方块坐标，distance 为与旧中心的距离 */
    public record CenterResult(int x, int z, int distance) {
    }

    private SpawnCenterCalculator() {
    }

    /**
     * @param oldX / oldZ 旧出生中心
     * @param usedCenters 已用过的中心点列表（用于避开已用方向；会跳过与旧中心相同的点）
     * @param s 配置项
     * @param rnd 随机源
     */
    public static CenterResult next(int oldX, int oldZ, List<int[]> usedCenters,
                                    SeasonData.Settings s, Random rnd) {
        List<Double> usedAngles = new ArrayList<>();
        for (int[] c : usedCenters) {
            if (c[0] == oldX && c[1] == oldZ) {
                continue;
            }
            usedAngles.add(Math.atan2(c[1] - oldZ, c[0] - oldX));
        }
        double angle = rnd.nextDouble() * 2 * Math.PI;
        boolean ok = false;
        for (int attempt = 0; attempt < 120 && !ok; attempt++) {
            angle = rnd.nextDouble() * 2 * Math.PI;
            ok = true;
            for (double a : usedAngles) {
                if (Math.abs(normAngle(angle - a)) < Math.toRadians(60)) {
                    ok = false;
                    break;
                }
            }
        }
        int distance = s.newCenterMinDistance
                + rnd.nextInt(s.newCenterMaxDistance - s.newCenterMinDistance + 1);
        int x = oldX + (int) Math.round(Math.cos(angle) * distance);
        int z = oldZ + (int) Math.round(Math.sin(angle) * distance);
        return new CenterResult(x, z, distance);
    }

    /** 角度归一化到 [-π, π) */
    private static double normAngle(double a) {
        while (a >= Math.PI) {
            a -= 2 * Math.PI;
        }
        while (a < -Math.PI) {
            a += 2 * Math.PI;
        }
        return a;
    }
}