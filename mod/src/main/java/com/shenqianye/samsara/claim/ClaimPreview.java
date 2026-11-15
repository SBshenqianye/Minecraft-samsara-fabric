package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;

/**
 * 圈地预览与常驻显示共用的纯几何工具。
 * <p>
 * 右键预览曾经按“延伸到哪些区块”的射线画粒子，而真正的保护范围是
 * {@code AreaService} 计算出的白名单（会因多面旗帜接续成面而扩张）。
 * 两条路径口径不同 → 玩家看到的“米字”和实际保护的正方形对不上。
 * 这里把“保护区域边界”抽成唯一实现，预览和常驻显示都调它。
 */
public final class ClaimPreview {
    private ClaimPreview() {
    }

    /**
     * 取 {@code area} 中与 {@code start} 四邻接连通的那一块。
     * <p>
     * 白名单是所有旗帜的并集；预览只应显示当前这面旗帜所在的连通区域，
     * 不应把别人的圈地一起画出来。
     *
     * @return 连通区块集合；{@code start} 不在白名单内时返回空集
     */
    public static Set<ChunkCoord> componentAt(Set<ChunkCoord> area, ChunkCoord start) {
        if (area == null || area.isEmpty() || start == null || !area.contains(start)) {
            return Set.of();
        }
        Set<ChunkCoord> out = new HashSet<>();
        Deque<ChunkCoord> queue = new ArrayDeque<>();
        out.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            ChunkCoord cur = queue.poll();
            for (ChunkCoord nb : neighbors(cur)) {
                if (area.contains(nb) && out.add(nb)) {
                    queue.add(nb);
                }
            }
        }
        return out;
    }

    /**
     * 保护区域的外边界线段，按区块 16 格换算成方块坐标。
     * <p>
     * 每条边的 key 为 {@code "minX,minZ,maxX,maxZ"}（方块坐标），与粒子发送端共用。
     */
    public static Set<String> boundaryEdges(Set<ChunkCoord> area) {
        Set<String> edges = new HashSet<>();
        if (area == null || area.isEmpty()) {
            return edges;
        }
        int[] dx = {0, 0, 1, -1};
        int[] dz = {1, -1, 0, 0};
        for (ChunkCoord c : area) {
            for (int i = 0; i < 4; i++) {
                ChunkCoord nb = new ChunkCoord(c.x + dx[i], c.z + dz[i]);
                if (area.contains(nb)) {
                    continue;
                }
                long x1;
                long z1;
                long x2;
                long z2;
                switch (i) {
                    case 0 -> { // south edge: z=(c.z+1)*16
                        x1 = c.x * 16L;
                        z1 = (c.z + 1) * 16L;
                        x2 = (c.x + 1) * 16L;
                        z2 = (c.z + 1) * 16L;
                    }
                    case 1 -> { // north edge: z=c.z*16
                        x1 = c.x * 16L;
                        z1 = c.z * 16L;
                        x2 = (c.x + 1) * 16L;
                        z2 = c.z * 16L;
                    }
                    case 2 -> { // east edge: x=(c.x+1)*16
                        x1 = (c.x + 1) * 16L;
                        z1 = c.z * 16L;
                        x2 = (c.x + 1) * 16L;
                        z2 = (c.z + 1) * 16L;
                    }
                    default -> { // west edge: x=c.x*16
                        x1 = c.x * 16L;
                        z1 = c.z * 16L;
                        x2 = c.x * 16L;
                        z2 = (c.z + 1) * 16L;
                    }
                }
                edges.add(Math.min(x1, x2) + "," + Math.min(z1, z2) + ","
                        + Math.max(x1, x2) + "," + Math.max(z1, z2));
            }
        }
        return edges;
    }

    private static Set<ChunkCoord> neighbors(ChunkCoord c) {
        return Set.of(
                new ChunkCoord(c.x + 1, c.z),
                new ChunkCoord(c.x - 1, c.z),
                new ChunkCoord(c.x, c.z + 1),
                new ChunkCoord(c.x, c.z - 1));
    }
}
