package com.shenqianye.samsara.geometry;

import java.util.*;

/**
 * 化线为面——纯 Java 几何库（设计文档 §6.3，格点图方案）。
 * <p>
 * 输入：节点集合 + 线段集合（均为整数区块坐标，8 方向）。
 * 输出：白名单区块坐标集 = 封闭图形（边界+内部）∪ 开放折线 ∪ 孤立点。
 * <p>
 * 无 MC 依赖，可脱离服务端独立开发与测试。
 * <p>
 * 算法步骤：
 * <ol>
 *   <li>线段光栅化 → 格点集 + 格点边集</li>
 *   <li>建图（无向图，8 邻域）</li>
 *   <li>半边缘遍历求封闭面（有界面 = 多边形）</li>
 *   <li>扫描线填充 → 内部点集</li>
 *   <li>开放折线 + 孤立点 → 并集</li>
 * </ol>
 */
public final class Geometry {
    private Geometry() {}

    // ========================================================================
    // 公共 API
    // ========================================================================

    /**
     * 化线为面：输入节点+线段，输出白名单区块坐标集。
     *
     * @param nodes 节点集合（旗帜所在区块坐标），可为 null 或空
     * @param segments 线段集合（节点与其影响坐标的连线），可为 null 或空
     * @return 白名单区块坐标集（不可变 Set）
     */
    public static Set<ChunkCoord> compute(Set<ChunkCoord> nodes, List<int[]> segments) {
        Set<ChunkCoord> allPoints = new HashSet<>();
        Map<ChunkCoord, Set<ChunkCoord>> adj = new HashMap<>();

        // 1. 光栅化线段 → 格点 + 邻接表
        if (segments != null) {
            for (int[] seg : segments) {
                ChunkCoord a = new ChunkCoord(seg[0], seg[1]);
                ChunkCoord b = new ChunkCoord(seg[2], seg[3]);
                List<ChunkCoord> path = rasterize(a, b);
                for (int i = 0; i < path.size(); i++) {
                    allPoints.add(path.get(i));
                    ChunkCoord cur = path.get(i);
                    if (i > 0) {
                        ChunkCoord prev = path.get(i - 1);
                        adj.computeIfAbsent(cur, k -> new HashSet<>()).add(prev);
                        adj.computeIfAbsent(prev, k -> new HashSet<>()).add(cur);
                    }
                }
            }
        }

        // 2. 孤立点（无任何边连接的节点）
        Set<ChunkCoord> isolated = new HashSet<>();
        if (nodes != null) {
            for (ChunkCoord n : nodes) {
                allPoints.add(n);
                if (!adj.containsKey(n) || adj.get(n).isEmpty()) {
                    isolated.add(n);
                }
            }
        }

        // 3. 求封闭面（去重：同一组点的面只保留一个）
        List<Set<ChunkCoord>> facesRaw = findFaces(allPoints, adj);
        List<Set<ChunkCoord>> faces = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Set<ChunkCoord> f : facesRaw) {
            String key = f.stream().sorted().map(c -> c.x + "," + c.z).reduce((a, b) -> a + ";" + b).orElse("");
            if (seen.add(key)) {
                faces.add(f);
            }
        }

        // 4. 边界点集（所有面上的点）
        Set<ChunkCoord> boundary = new HashSet<>();
        for (Set<ChunkCoord> face : faces) {
            boundary.addAll(face);
        }

        // 5. 扫描线填充 → 内部点集
        Set<ChunkCoord> interior = new HashSet<>();
        for (Set<ChunkCoord> face : faces) {
            interior.addAll(scanlineFill(face, adj));
        }

        // 6. 开放折线点集（不在任何面上的线段格点）
        Set<ChunkCoord> openPolyline = new HashSet<>();
        for (ChunkCoord p : allPoints) {
            if (!boundary.contains(p) && !isolated.contains(p)) {
                openPolyline.add(p);
            }
        }

        // 7. 并集
        Set<ChunkCoord> result = new HashSet<>();
        result.addAll(boundary);
        result.addAll(interior);
        result.addAll(openPolyline);
        result.addAll(isolated);
        return Collections.unmodifiableSet(result);
    }

    /**
     * 把「按角度顺序排列的延伸末端」依次连成闭合环，返回环上的线段集合。
     * <p>
     * <b>为什么需要它</b>：单面旗帜的 N 条延伸射线是**从同一个节点出发的星形图（无环）**，
     * 半边缘遍历找不到任何有界面，因此永远填不出内部。把末端按角度顺序连成环后，
     * 图形闭合，才能被 {@link #findFaces} 识别为面并交给 {@link #scanlineFill} 填充。
     * <p>
     * 形状由末端分布决定（设延伸距离同为 d）：
     * <ul>
     *   <li>仅 4 正方向（`straight_cross`）→ 末端为四边中点 → **菱形**（|dx|+|dz| ≤ d）</li>
     *   <li>仅 4 斜方向（`cross`）→ 末端为四角 → **正方形**（边长 2d）</li>
     *   <li>8 方向（`border`）→ 末端 = 四角 + 四边中点 → 仍是**正方形**（中点落在边上，把菱形"撑平"）</li>
     * </ul>
     * <b>少于 3 个末端不成环</b>：1 个末端是孤立方向；2 个末端只能连成一条线（与射线重合），
     * 围不出面积——两种情况都按开放折线处理，返回空列表。
     *
     * @param endpointsInAngularOrder 按角度顺序（顺时针或逆时针均可，但必须连贯）排列的末端格点
     * @return 环线段集合，每条为 {@code {x1,z1,x2,z2}}；末端少于 3 个时返回空列表
     */
    public static List<int[]> ringSegments(List<ChunkCoord> endpointsInAngularOrder) {
        List<int[]> segs = new ArrayList<>();
        if (endpointsInAngularOrder == null || endpointsInAngularOrder.size() < 3) {
            return segs;
        }
        int n = endpointsInAngularOrder.size();
        for (int i = 0; i < n; i++) {
            ChunkCoord a = endpointsInAngularOrder.get(i);
            ChunkCoord b = endpointsInAngularOrder.get((i + 1) % n);
            segs.add(new int[]{a.x, a.z, b.x, b.z});
        }
        return segs;
    }

    // ========================================================================
    // 线段光栅化
    // ========================================================================

    /**
     * Bresenham 光栅化：两点间路径上所有整数格点（含端点）。
     * 方向限 8 邻域（正交+对角），步长 = gcd(|dx|,|dz|)。
     */
    static List<ChunkCoord> rasterize(ChunkCoord a, ChunkCoord b) {
        int dx = b.x - a.x;
        int dz = b.z - a.z;
        if (dx == 0 && dz == 0) {
            return List.of(a);
        }
        int g = gcd(Math.abs(dx), Math.abs(dz));
        int sx = dx / g;
        int sz = dz / g;
        List<ChunkCoord> path = new ArrayList<>(g + 1);
        for (int i = 0; i <= g; i++) {
            path.add(new ChunkCoord(a.x + sx * i, a.z + sz * i));
        }
        return path;
    }

    private static int gcd(int a, int b) {
        while (b != 0) { int t = b; b = a % b; a = t; }
        return a;
    }

    // ========================================================================
    // 封闭面遍历（半边缘 / angle-sorted next-edge）
    // ========================================================================

    /**
     * 对每个顶点，按角度排序其邻居（逆时针从 East 开始）。
     * 对每条有向边 (u→v)，"下一条" = v 处 u 的逆时针后继。
     * 沿 next 走回起点 → 一个面（有界 = 封闭图形）。
     * <p>
     * 稀疏图处理：不要求每个顶点都有全部 8 邻居，
     * 而是在 v 的实际邻居中找"逆时针角度差最小"的那个。
     */
    static List<Set<ChunkCoord>> findFaces(Set<ChunkCoord> allPoints,
                                            Map<ChunkCoord, Set<ChunkCoord>> adj) {
        // 跟踪已遍历的有向边
        Set<String> visited = new HashSet<>();
        List<Set<ChunkCoord>> faces = new ArrayList<>();

        for (ChunkCoord u : allPoints) {
            Set<ChunkCoord> neighbors = adj.getOrDefault(u, Set.of());
            for (ChunkCoord v : neighbors) {
                String edgeKey = u.x + "," + u.z + "->" + v.x + "," + v.z;
                if (visited.contains(edgeKey)) continue;

                // 沿 next 走一圈
                Set<ChunkCoord> face = new LinkedHashSet<>();
                ChunkCoord start = u;
                ChunkCoord cur = u;
                ChunkCoord nxt = v;
                boolean bounded = true;
                int maxSteps = allPoints.size() * 4;
                int steps = 0;

                while (steps < maxSteps) {
                    String k = cur.x + "," + cur.z + "->" + nxt.x + "," + nxt.z;
                    visited.add(k);
                    face.add(cur);

                    if (nxt.equals(start)) {
                        break; // 封闭
                    }

                    // 在 nxt 处找 cur 的逆时针后继
                    Set<ChunkCoord> nxtNeighbors = adj.getOrDefault(nxt, Set.of());
                    int dirRev = nxt.directionIndexTo(cur);
                    ChunkCoord nextNode = null;
                    int bestAngle = 9; // 大于 8 的初始值

                    for (ChunkCoord nb : nxtNeighbors) {
                        if (nb.equals(cur)) continue;
                        int dirNb = nxt.directionIndexTo(nb);
                        int angleDiff = (dirNb - dirRev + 8) % 8;
                        if (angleDiff > 0 && angleDiff < bestAngle) {
                            bestAngle = angleDiff;
                            nextNode = nb;
                        }
                    }

                    if (nextNode == null) {
                        bounded = false;
                        break;
                    }

                    cur = nxt;
                    nxt = nextNode;
                    steps++;
                }

                if (bounded && face.size() >= 3) {
                    faces.add(face);
                }
            }
        }
        return faces;
    }

    // ========================================================================
    // 扫描线填充（半开区间约定）
    // ========================================================================

    /**
     * 对一个封闭面（边界格点集），用扫描线填充内部点。
     * 采用半开区间约定规避顶点/共线歧义。
     * 使用真实邻接关系（非 8-neighbor 全连接）避免假对角边。
     */
    static Set<ChunkCoord> scanlineFill(Set<ChunkCoord> boundary,
                                         Map<ChunkCoord, Set<ChunkCoord>> adj) {
        if (boundary.size() < 3) return Set.of();

        Set<ChunkCoord> interior = new HashSet<>();

        // 用真实邻接关系构建边集（只取边界内存在的边）
        Set<String> edgeSet = new HashSet<>();
        for (ChunkCoord p : boundary) {
            Set<ChunkCoord> neighbors = adj.getOrDefault(p, Set.of());
            for (ChunkCoord nb : neighbors) {
                if (boundary.contains(nb)) {
                    String key = p.compareTo(nb) < 0
                            ? p.x + "," + p.z + "," + nb.x + "," + nb.z
                            : nb.x + "," + nb.z + "," + p.x + "," + p.z;
                    edgeSet.add(key);
                }
            }
        }

        // 找包围盒
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (ChunkCoord p : boundary) {
            minZ = Math.min(minZ, p.z);
            maxZ = Math.max(maxZ, p.z);
        }

        // 对每行扫描
        for (int z = minZ + 1; z < maxZ; z++) {
            // 求该行与所有边界边的交点（半开区间：只计下端点）
            List<Integer> intersections = new ArrayList<>();
            for (String ek : edgeSet) {
                String[] parts = ek.split(",");
                int x1 = Integer.parseInt(parts[0]);
                int z1 = Integer.parseInt(parts[1]);
                int x2 = Integer.parseInt(parts[2]);
                int z2 = Integer.parseInt(parts[3]);

                // 边跨越 z 行？（半开区间）
                if ((z1 < z && z <= z2) || (z2 < z && z <= z1)) {
                    double t = (double) (z - z1) / (z2 - z1);
                    int ix = (int) Math.round(x1 + t * (x2 - x1));
                    intersections.add(ix);
                }
            }

            // 排序后成对填充
            Collections.sort(intersections);
            for (int i = 0; i + 1 < intersections.size(); i += 2) {
                int xFrom = intersections.get(i);
                int xTo = intersections.get(i + 1);
                for (int x = xFrom; x <= xTo; x++) {
                    ChunkCoord p = new ChunkCoord(x, z);
                    if (!boundary.contains(p)) {
                        interior.add(p);
                    }
                }
            }
        }
        return interior;
    }
}
