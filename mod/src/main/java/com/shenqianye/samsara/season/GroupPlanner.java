package com.shenqianye.samsara.season;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * 分组出生算法（M2，设计文档 §4 步骤 4）。
 * <p>
 * 纯 Java 逻辑、不依赖 MC 类型，可脱离服务端单测：
 * <ul>
 *   <li>组数 = ceil(人数 / 组上限)；每组 3~5 人（偶数分配，天然落在区间内）；</li>
 *   <li>每组出生点：围绕出生中心，极角按组序号均分 + 随机抖动，半径在
 *       spreadMin~spreadMax 之间，且组间距离 ≥ groupMinDistance（最多重试 40 次）；</li>
 *   <li>返回 {@link Plan}（含文本报告），不落盘 —— 落盘与传送由调用方（SeasonManager）负责。</li>
 * </ul>
 */
public class GroupPlanner {
    /** 单组出生点 */
    public static class GroupSpawnPoint {
        public final int x;
        public final int z;
        public final List<UUID> playerUuids;
        public final List<String> playerNames;

        GroupSpawnPoint(int x, int z, List<UUID> playerUuids, List<String> playerNames) {
            this.x = x;
            this.z = z;
            this.playerUuids = playerUuids;
            this.playerNames = playerNames;
        }
    }

    /** 完整分组计划 */
    public static class Plan {
        public final int centerX;
        public final int centerZ;
        public final int radius;
        public final List<GroupSpawnPoint> groups;
        /** 是否发生了无法满足组距的兜底放置 */
        public final boolean distanceViolated;
        /** 本次使用的组距配置（报告用，L1 修复：不再硬编码 300） */
        public final int minGroupDist;

        Plan(int centerX, int centerZ, int radius, List<GroupSpawnPoint> groups, boolean distanceViolated,
             int minGroupDist) {
            this.centerX = centerX;
            this.centerZ = centerZ;
            this.radius = radius;
            this.groups = groups;
            this.distanceViolated = distanceViolated;
            this.minGroupDist = minGroupDist;
        }

        /** 多行文本报告（设计规范 §2：每条一行、间隔号连接、块间空行） */
        public String report() {
            StringBuilder sb = new StringBuilder();
            sb.append("分组计划 · 出生中心 (").append(centerX).append(", ").append(centerZ)
                    .append(") · 半径 ").append(radius).append(" · 注册玩家 ").append(playerCount())
                    .append(" 人 → ").append(groups.size()).append(" 组\n");
            for (int i = 0; i < groups.size(); i++) {
                GroupSpawnPoint g = groups.get(i);
                sb.append("第 ").append(i + 1).append(" 组（").append(g.playerUuids.size()).append(" 人）");
                if (g.playerUuids.isEmpty()) {
                    sb.append("\n");
                    continue;
                }
                int dist = (int) Math.round(Math.hypot(g.x - centerX, g.z - centerZ));
                sb.append(" · 出生点 (").append(g.x).append(", ").append(g.z).append(") · 距中心 ")
                        .append(dist).append(" · 玩家: ");
                if (g.playerNames.size() > 12) {
                    sb.append(String.join("、", g.playerNames.subList(0, 12)))
                            .append(" 等 ").append(g.playerNames.size()).append(" 人\n");
                } else {
                    sb.append(String.join("、", g.playerNames)).append("\n");
                }
            }
            if (distanceViolated) {
                sb.append("⚠ 部分出生点未满足组距 ≥ ").append(minGroupDist).append("（随机尝试耗尽，已兜底放置）\n");
            }
            return sb.toString().trim();
        }

        private int playerCount() {
            int c = 0;
            for (GroupSpawnPoint g : groups) {
                c += g.playerUuids.size();
            }
            return c;
        }
    }

    private GroupPlanner() {
    }

    /**
     * @param centerX / centerZ 出生中心（区块所在的方块坐标）
     * @param radius  出生保护半径（用于报告）
     * @param players 注册玩家（已按最后上线排序）
     * @param s       配置项
     * @param rnd     随机源
     */
    public static Plan plan(int centerX, int centerZ, int radius, List<PlayerRegistry.RegPlayer> players,
                            SeasonData.Settings s, Random rnd) {
        int n = players.size();
        int groupCount = Math.max(1, (n + s.groupSizeMax - 1) / s.groupSizeMax);
        int base = n / groupCount;
        int rem = n % groupCount;
        // L5 修复：组数多时自动扩大散布半径，避免几十组挤在小半径里必然违反组距
        // 估算半径随组数开方增长：each 组距 ≥ groupMinDistance 需要的环带宽度
        int effectiveSpreadMax = s.spreadMaxRadius;
        if (s.groupMinDistance > 0) {
            int need = (int) Math.ceil(Math.sqrt(groupCount) * s.groupMinDistance * 0.6);
            effectiveSpreadMax = Math.max(effectiveSpreadMax, Math.min(20000, need));
        }
        List<GroupSpawnPoint> points = new ArrayList<>();
        boolean violated = false;
        int idx = 0;
        for (int i = 0; i < groupCount; i++) {
            int size = base + (i < rem ? 1 : 0);
            List<UUID> uuids = new ArrayList<>();
            List<String> names = new ArrayList<>();
            for (int j = 0; j < size; j++) {
                PlayerRegistry.RegPlayer p = players.get(idx++);
                uuids.add(p.uuid);
                names.add(p.name);
            }
            if (n == 0) {
                points.add(new GroupSpawnPoint(centerX, centerZ, uuids, names));
                continue;
            }
            int[] pos;
            if (s.groupMinDistance <= 0) {
                // 组距配置 = 0：所有组共用出生中心（设计：组间距离为 0 同样共用）
                pos = new int[]{centerX, centerZ, 0};
            } else {
                pos = placePoint(centerX, centerZ, i, groupCount, s, effectiveSpreadMax, rnd, points);
                if (pos[2] == 1) {
                    violated = true;
                }
            }
            points.add(new GroupSpawnPoint(pos[0], pos[1], uuids, names));
        }
        return new Plan(centerX, centerZ, radius, points, violated, s.groupMinDistance);
    }

    /**
     * 放置第 i 组出生点；返回 {x, z, violated}。
     * 极角 = i*(2π/g) ± 抖动(0.35 rad)，半径 = spreadMin~spreadMax 均匀；
     * 与已放置组距 ≥ groupMinDistance，最多 40 次尝试。
     */
    private static int[] placePoint(int cx, int cz, int i, int g, SeasonData.Settings s, int spreadMax,
                                    Random rnd, List<GroupSpawnPoint> placed) {
        double baseAngle = i * (2.0 * Math.PI / g);
        double spreadMin = Math.min(s.spreadMinRadius, spreadMax);
        for (int attempt = 0; attempt < 40; attempt++) {
            double angle = baseAngle + (rnd.nextDouble() * 2 - 1) * 0.7;
            double dist = spreadMin + rnd.nextDouble() * (spreadMax - spreadMin);
            int x = cx + (int) Math.round(Math.cos(angle) * dist);
            int z = cz + (int) Math.round(Math.sin(angle) * dist);
            boolean ok = true;
            for (GroupSpawnPoint p : placed) {
                if (Math.hypot(x - p.x, z - p.z) < s.groupMinDistance) {
                    ok = false;
                    break;
                }
            }
            if (ok) {
                return new int[]{x, z, 0};
            }
        }
        // 兜底：均分角 + 最小半径（可能违反组距，由调用方标注）
        return new int[]{cx + (int) Math.round(Math.cos(baseAngle) * spreadMin),
                cz + (int) Math.round(Math.sin(baseAngle) * spreadMin), 1};
    }
}