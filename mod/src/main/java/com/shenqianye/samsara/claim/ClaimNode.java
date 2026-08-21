package com.shenqianye.samsara.claim;

import com.shenqianye.samsara.geometry.ChunkCoord;

import java.util.ArrayList;
import java.util.List;

/**
 * 旗帜圈地节点（设计文档 §6.2.1）。
 * <p>
 * 一个节点 = 一面已注册的圈地旗，包含区块坐标、朝向、原始图案列表。
 * 通过 {@link Extension} 计算影响坐标集。
 */
public class ClaimNode {
    private final ChunkCoord location;
    private final String facing;
    private final List<PatternEntry> patterns;
    /** 旋转后的扩展（延迟计算，首次访问时构建） */
    private Extension rotatedExtension;
    /** 影响坐标集（延迟计算） */
    private List<ChunkCoord> influencedCoords;

    public ClaimNode(ChunkCoord location, String facing, List<PatternEntry> patterns) {
        this.location = location;
        this.facing = facing;
        this.patterns = new ArrayList<>(patterns);
    }

    public ChunkCoord getLocation() { return location; }
    public String getFacing() { return facing; }
    public List<PatternEntry> getPatterns() { return patterns; }

    /** 获取旋转后的扩展方向集（根据旗帜朝向旋转） */
    public Extension getExtension() {
        if (rotatedExtension == null) {
            rotatedExtension = new Extension();
            for (PatternEntry pe : patterns) {
                rotatedExtension.addDirection(pe.patternId(), pe.color());
            }
            rotatedExtension.rotate(facing);
        }
        return rotatedExtension;
    }

    /** 计算影响坐标集（从节点位置沿每个方向延伸） */
    public List<ChunkCoord> getInfluencedCoords() {
        if (influencedCoords != null) return influencedCoords;
        influencedCoords = new ArrayList<>();
        Extension ext = getExtension();
        addLine(location, 0, -ext.getUp(), influencedCoords);        // 北 (Z-)
        addLine(location, 0, ext.getDown(), influencedCoords);        // 南 (Z+)
        addLine(location, -ext.getLeft(), 0, influencedCoords);       // 西 (X-)
        addLine(location, ext.getRight(), 0, influencedCoords);       // 东 (X+)
        addLine(location, -ext.getLeftUp(), -ext.getLeftUp(), influencedCoords);   // 西北
        addLine(location, ext.getRightUp(), -ext.getRightUp(), influencedCoords);   // 东北
        addLine(location, -ext.getLeftDown(), ext.getLeftDown(), influencedCoords); // 西南
        addLine(location, ext.getRightDown(), ext.getRightDown(), influencedCoords); // 东南
        return influencedCoords;
    }

    /** 从起点沿方向延伸 N 格，生成所有中间格点 */
    private static void addLine(ChunkCoord start, int dx, int dz,
                                 List<ChunkCoord> out) {
        int dist = Math.max(Math.abs(dx), Math.abs(dz));
        if (dist == 0) return;
        int sx = dx / dist;
        int sz = dz / dist;
        for (int i = 1; i <= dist; i++) {
            out.add(new ChunkCoord(start.x + sx * i, start.z + sz * i));
        }
    }

    /** 旗帜图案记录（patternId + color） */
    public record PatternEntry(String patternId, int color) {}
}
