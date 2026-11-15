package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 圈地预览/显示共用的连通分量与边界边计算。
 * <p>
 * 右键预览必须和真实保护范围同源：这里固定“看到的边界 = 白名单外边界”。
 */
class ClaimPreviewTest {

    private static Set<ChunkCoord> square(int radius) {
        Set<ChunkCoord> set = new HashSet<>();
        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                set.add(new ChunkCoord(x, z));
            }
        }
        return set;
    }

    @Test
    void componentAtStaysInsideConnectedArea() {
        Set<ChunkCoord> area = square(1);
        assertEquals(9, ClaimPreview.componentAt(area, new ChunkCoord(0, 0)).size());
        assertEquals(9, ClaimPreview.componentAt(area, new ChunkCoord(-1, -1)).size());
        assertTrue(ClaimPreview.componentAt(area, new ChunkCoord(5, 5)).isEmpty());
        assertTrue(ClaimPreview.componentAt(Set.of(), new ChunkCoord(0, 0)).isEmpty());
    }

    @Test
    void componentAtSeparatesDisconnectedClaims() {
        Set<ChunkCoord> area = new HashSet<>(square(1));
        area.add(new ChunkCoord(10, 10));
        area.add(new ChunkCoord(11, 10));
        assertEquals(9, ClaimPreview.componentAt(area, new ChunkCoord(0, 0)).size());
        assertEquals(2, ClaimPreview.componentAt(area, new ChunkCoord(10, 10)).size());
    }

    @Test
    void boundaryEdgesTracePerimeterOnly() {
        // 5×5 实心方形：外边界 20 条 16 格长的边
        Set<String> edges = ClaimPreview.boundaryEdges(square(2));
        assertEquals(20, edges.size());
        // 每条边都落在包围盒四条边上（内部区块不产生边）
        assertTrue(edges.stream().allMatch((e) -> {
            String[] p = e.split(",");
            int x1 = Integer.parseInt(p[0]);
            int z1 = Integer.parseInt(p[1]);
            int x2 = Integer.parseInt(p[2]);
            int z2 = Integer.parseInt(p[3]);
            return x1 == x2 ? (x1 == -32 || x1 == 48) : (z1 == -32 || z1 == 48);
        }));
        // 西边界 x = -2*16 = -32，z 覆盖 [-32,32]
        assertTrue(edges.contains("-32,-32,-32,-16"));
        assertTrue(edges.contains("48,16,48,32"));
    }

    @Test
    void boundaryEdgesOfTwoByTwoSquare() {
        Set<ChunkCoord> area = Set.of(
                new ChunkCoord(0, 0), new ChunkCoord(1, 0),
                new ChunkCoord(0, 1), new ChunkCoord(1, 1));
        // 2×2 周长 = 8 条边（每条 16 格）
        assertEquals(8, ClaimPreview.boundaryEdges(area).size());
    }
}
